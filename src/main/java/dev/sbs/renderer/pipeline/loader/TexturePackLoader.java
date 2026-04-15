package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.asset.pack.Texture;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.sbs.renderer.pipeline.VanillaPaths;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
     * Loads the vanilla texture pack from a previously-extracted client jar root.
     *
     * @param packRoot the root directory containing {@code assets/minecraft/textures}
     * @return the pack entity
     */
    public static @NotNull TexturePack loadVanilla(@NotNull Path packRoot) {
        return load(packRoot, "vanilla", "Vanilla Minecraft client", 0);
    }

    /**
     * Loads a pack from a directory. Zip archives are expected to be extracted by the caller
     * before this method is invoked.
     *
     * @param packRoot the pack root directory
     * @param packId the unique pack identifier
     * @param description the pack description
     * @param priority the pack priority; higher values win when texture ids collide
     * @return the pack entity
     */
    public static @NotNull TexturePack load(
        @NotNull Path packRoot,
        @NotNull String packId,
        @NotNull String description,
        int priority
    ) {
        if (!Files.isDirectory(packRoot))
            throw new AssetPipelineException("Pack root '%s' does not exist or is not a directory", packRoot);

        return new TexturePack(packId, "minecraft", description, packRoot.toString(), priority);
    }

    /**
     * Scans the {@code assets/minecraft/textures} tree for PNG files and returns metadata rows
     * suitable for persisting as {@link Texture} entities. Adjacent {@code .png.mcmeta} sidecars
     * are parsed during the walk and attached to the texture's {@code animation} field.
     *
     * @param packRoot the pack root directory
     * @param packId the owning pack identifier
     * @return the texture metadata list
     */
    public static @NotNull ConcurrentList<Texture> scanTextures(@NotNull Path packRoot, @NotNull String packId) {
        ConcurrentList<Texture> textures = Concurrent.newList();
        Path texturesDir = packRoot.resolve(VanillaPaths.TEXTURES_DIR);
        if (!Files.isDirectory(texturesDir)) return textures;

        try (Stream<Path> stream = Files.walk(texturesDir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".png"))
                .forEach(p -> textures.add(buildTexture(p, texturesDir, packId)));
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to scan texture directory '%s'", texturesDir);
        }
        return textures;
    }

    private static @NotNull Texture buildTexture(@NotNull Path file, @NotNull Path texturesRoot, @NotNull String packId) {
        String relative = texturesRoot.relativize(file).toString().replace('\\', '/');
        String withoutExtension = relative.endsWith(".png") ? relative.substring(0, relative.length() - 4) : relative;
        String id = VanillaPaths.MINECRAFT_NAMESPACE + withoutExtension;

        int width = 0;
        int height = 0;
        try {
            var image = ImageIO.read(file.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to read texture '%s'", file);
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
            throw new AssetPipelineException(ex, "Failed to parse mcmeta '%s'", mcmetaFile);
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
     * which {@link dev.sbs.renderer.kit.AnimationKit AnimationKit} resolves against the
     * animation-level {@code frametime}; explicit objects are read directly.
     */
    private static @NotNull ConcurrentList<AnimationData.FrameEntry> parseFrames(@NotNull JsonArray elements) {
        ConcurrentList<AnimationData.FrameEntry> frames = Concurrent.newList();
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
        return frames;
    }

}
