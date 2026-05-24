package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.asset.pack.AnimationData;
import lib.minecraft.renderer.kit.AnimationKit;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A loader that scans a texture pack directory and produces a {@link TexturePack} entity plus a
 * list of {@link Texture} rows cataloguing every {@code .png} file under
 * {@code assets/minecraft/textures}.
 * <p>
 * Texture sizes are read from the PNG header via {@link ImageIO} and any adjacent
 * {@code .png.mcmeta} sidecar is parsed eagerly so the resulting {@link Texture#getAnimation()}
 * field already carries the frame list when the caller queries it. The sidecar format is
 * vanilla's heterogeneous frames array - a mix of bare integers ({@code [0, 1, 2]}) and
 * explicit frame objects ({@code [{"index":0,"time":5}]}) - which is normalised into
 * {@link AnimationData.FrameEntry} records during the walk.
 *
 * @see TexturePack
 * @see Texture
 * @see AnimationData
 */
@UtilityClass
public class TexturePackLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Scans every pack's asset roots in priority order (lowest priority first, highest last) and
     * returns the merged texture index. Later roots override earlier ones for any colliding
     * texture id, which is how within-pack overlays and across-pack priority both produce the
     * right rendering. Each {@link Texture} carries the id of the pack whose root registered the
     * winning row.
     *
     * @param packs the packs in ascending-priority order
     * @return the merged texture index, wrapped unmodifiable
     */
    public static @NotNull ConcurrentMap<String, Texture> scanTextures(@NotNull ConcurrentList<TexturePack> packs) {
        HashMap<String, Texture> merged = new HashMap<>();
        for (TexturePack pack : packs)
            for (Path root : pack.getAssetRoots())
                merged.putAll(scanRoot(root, pack.getId()));
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Scans one asset root and returns the texture index keyed by namespaced texture id. Each
     * resulting {@link Texture} carries the supplied {@code packId} so the renderer can look up
     * the right pack root at PNG-read time. Returns an empty map when the textures subtree is
     * absent.
     */
    private static @NotNull ConcurrentMap<String, Texture> scanRoot(@NotNull Path packRoot, @NotNull String packId) {
        Path texturesDir = packRoot.resolve(VanillaSourcePaths.TEXTURES_DIR);
        if (!Files.isDirectory(texturesDir)) return Concurrent.newMap();

        // Two-phase walk: materialise the PNG path list serially (Files.walk spliterators do not
        // split well for parallel work), then parallelise the per-file decode. buildTexture is
        // I/O-bound - ImageIO.read + mcmeta parse dominate wall-clock time - so parallelStream
        // over the FJP common pool gives near-linear scaling on cold loads. Concurrent.toMap()
        // accumulates into a thread-confined HashMap per shard and adopts the merged result at
        // finish, so the build phase pays zero ConcurrentMap writeLock acquisitions.
        List<Path> pngFiles;
        try (Stream<Path> stream = Files.walk(texturesDir)) {
            pngFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".png"))
                .toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan texture directory '%s'", texturesDir);
        }

        return pngFiles.parallelStream()
            .map(p -> buildTexture(p, texturesDir, packId))
            .collect(Concurrent.toMap(Texture::getId, Function.identity()));
    }

    private static @NotNull Texture buildTexture(@NotNull Path file, @NotNull Path texturesRoot, @NotNull String packId) {
        String relative = texturesRoot.relativize(file).toString().replace('\\', '/');
        String withoutExtension = relative.endsWith(".png") ? relative.substring(0, relative.length() - 4) : relative;
        String id = VanillaSourcePaths.MINECRAFT_NAMESPACE + withoutExtension;

        int width = 0;
        int height = 0;
        try {
            var image = ImageIO.read(file.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read texture '%s'", file);
        }

        Optional<AnimationData> animation = parseMcMeta(mcmetaSibling(file));
        return new Texture(id, packId, relative, width, height, animation);
    }

    /**
     * Returns the path of the {@code .png.mcmeta} sidecar next to a given PNG file. The sidecar
     * does not need to exist - {@link #parseMcMeta(Path)} handles the missing case gracefully.
     */
    private static @NotNull Path mcmetaSibling(@NotNull Path pngFile) {
        return pngFile.resolveSibling(pngFile.getFileName().toString() + ".mcmeta");
    }

    /**
     * Parses a {@code .png.mcmeta} sidecar and extracts the {@code animation} block into an
     * {@link AnimationData} instance. The frames array is walked manually because vanilla emits
     * a heterogeneous list of bare integers ({@code [0, 1, 2]}) and explicit frame objects
     * ({@code [{"index":0,"time":5}]}) - Gson cannot deserialize both forms into the same
     * {@link AnimationData.FrameEntry} record without a custom type adapter.
     *
     * @param mcmetaFile the sidecar path; need not exist
     * @return the parsed animation block, or empty when the sidecar is missing or has no
     *     {@code animation} object
     */
    private static @NotNull Optional<AnimationData> parseMcMeta(@NotNull Path mcmetaFile) {
        if (!Files.isRegularFile(mcmetaFile)) return Optional.empty();

        JsonObject root;
        try {
            root = GSON.fromJson(Files.readString(mcmetaFile), JsonObject.class);
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to parse mcmeta '%s'", mcmetaFile);
        }
        if (root == null || !root.has("animation")) return Optional.empty();

        JsonObject a = root.getAsJsonObject("animation");
        int frametime = a.has("frametime") ? a.get("frametime").getAsInt() : 1;
        boolean interpolate = a.has("interpolate") && a.get("interpolate").getAsBoolean();
        ConcurrentList<AnimationData.FrameEntry> frames = a.has("frames") ? parseFrames(a.getAsJsonArray("frames")) : Concurrent.newList();
        int width = a.has("width") ? a.get("width").getAsInt() : -1;
        int height = a.has("height") ? a.get("height").getAsInt() : -1;

        return Optional.of(new AnimationData(frametime, interpolate, frames, width, height));
    }

    /**
     * Normalises the vanilla {@code frames} array into a list of {@link AnimationData.FrameEntry}
     * records. Bare-integer entries become frames with the default ({@code -1}) duration marker
     * which {@link AnimationKit AnimationKit} resolves against the
     * animation-level {@code frametime}; explicit objects are read directly.
     */
    private static @NotNull ConcurrentList<AnimationData.FrameEntry> parseFrames(@NotNull JsonArray elements) {
        java.util.ArrayList<AnimationData.FrameEntry> frames = new java.util.ArrayList<>(elements.size());
        for (JsonElement element : elements) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
                frames.add(new AnimationData.FrameEntry(element.getAsInt(), -1));
            else if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                int index = entry.has("index") ? entry.get("index").getAsInt() : 0;
                int time = entry.has("time") ? entry.get("time").getAsInt() : -1;
                frames.add(new AnimationData.FrameEntry(index, time));
            }
        }
        return Concurrent.adoptList(frames);
    }

}
