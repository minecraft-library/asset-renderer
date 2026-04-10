package dev.sbs.renderer.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.model.Texture;
import dev.sbs.renderer.model.TexturePack;
import dev.sbs.renderer.model.asset.AnimationData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scans a texture pack directory and produces a {@link TexturePack} entity plus a list of
 * {@link Texture} rows that catalogue every {@code .png} file under {@code assets/minecraft/textures}.
 * <p>
 * Texture sizes are read from the PNG header via {@link ImageIO} and any adjacent
 * {@code .png.mcmeta} sidecar is parsed eagerly so the resulting {@link Texture#getAnimation()}
 * field already carries the frame list when the caller queries it.
 */
@UtilityClass
public class TexturePackReader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull Reflection<Texture> TEXTURE_REFLECTION = new Reflection<>(Texture.class);
    private static final @NotNull FieldAccessor<String> TEXTURE_ID = TEXTURE_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> TEXTURE_PACK_ID = TEXTURE_REFLECTION.getField("packId");
    private static final @NotNull FieldAccessor<String> TEXTURE_RELATIVE_PATH = TEXTURE_REFLECTION.getField("relativePath");
    private static final @NotNull FieldAccessor<Integer> TEXTURE_WIDTH = TEXTURE_REFLECTION.getField("width");
    private static final @NotNull FieldAccessor<Integer> TEXTURE_HEIGHT = TEXTURE_REFLECTION.getField("height");
    private static final @NotNull FieldAccessor<Optional<AnimationData>> TEXTURE_ANIMATION = TEXTURE_REFLECTION.getField("animation");

    private static final @NotNull Reflection<TexturePack> PACK_REFLECTION = new Reflection<>(TexturePack.class);
    private static final @NotNull FieldAccessor<String> PACK_ID = PACK_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> PACK_NAMESPACE = PACK_REFLECTION.getField("namespace");
    private static final @NotNull FieldAccessor<String> PACK_DESCRIPTION = PACK_REFLECTION.getField("description");
    private static final @NotNull FieldAccessor<String> PACK_ROOT_PATH = PACK_REFLECTION.getField("rootPath");
    private static final @NotNull FieldAccessor<Integer> PACK_PRIORITY = PACK_REFLECTION.getField("priority");

    private static final @NotNull Reflection<AnimationData> ANIMATION_REFLECTION = new Reflection<>(AnimationData.class);
    private static final @NotNull FieldAccessor<Integer> ANIMATION_FRAMETIME = ANIMATION_REFLECTION.getField("frametime");
    private static final @NotNull FieldAccessor<Boolean> ANIMATION_INTERPOLATE = ANIMATION_REFLECTION.getField("interpolate");
    private static final @NotNull FieldAccessor<Integer> ANIMATION_WIDTH = ANIMATION_REFLECTION.getField("width");
    private static final @NotNull FieldAccessor<Integer> ANIMATION_HEIGHT = ANIMATION_REFLECTION.getField("height");
    private static final @NotNull FieldAccessor<ConcurrentList<AnimationData.FrameEntry>> ANIMATION_FRAMES = ANIMATION_REFLECTION.getField("frames");

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

        TexturePack pack = new TexturePack();
        PACK_ID.set(pack, packId);
        PACK_NAMESPACE.set(pack, "minecraft");
        PACK_DESCRIPTION.set(pack, description);
        PACK_ROOT_PATH.set(pack, packRoot.toString());
        PACK_PRIORITY.set(pack, priority);
        return pack;
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
        Path texturesDir = packRoot.resolve("assets/minecraft/textures");
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
        String id = "minecraft:" + withoutExtension;

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

        Texture texture = new Texture();
        TEXTURE_ID.set(texture, id);
        TEXTURE_PACK_ID.set(texture, packId);
        TEXTURE_RELATIVE_PATH.set(texture, relative);
        TEXTURE_WIDTH.set(texture, width);
        TEXTURE_HEIGHT.set(texture, height);

        Optional<AnimationData> animation = parseMcMeta(mcmetaSibling(file));
        if (animation.isPresent())
            TEXTURE_ANIMATION.set(texture, animation);
        return texture;
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

        JsonObject animJson = root.getAsJsonObject("animation");
        AnimationData animation = new AnimationData();
        if (animJson.has("frametime"))
            ANIMATION_FRAMETIME.set(animation, animJson.get("frametime").getAsInt());
        if (animJson.has("interpolate"))
            ANIMATION_INTERPOLATE.set(animation, animJson.get("interpolate").getAsBoolean());
        if (animJson.has("width"))
            ANIMATION_WIDTH.set(animation, animJson.get("width").getAsInt());
        if (animJson.has("height"))
            ANIMATION_HEIGHT.set(animation, animJson.get("height").getAsInt());
        if (animJson.has("frames"))
            ANIMATION_FRAMES.set(animation, parseFrames(animJson.getAsJsonArray("frames")));

        return Optional.of(animation);
    }

    /**
     * Normalises the vanilla {@code frames} array into a list of {@link AnimationData.FrameEntry}
     * records. Bare-integer entries become frames with the default ({@code -1}) duration marker
     * which {@link dev.sbs.renderer.draw.AnimationKit AnimationKit} resolves against the
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
