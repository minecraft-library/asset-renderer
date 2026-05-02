package lib.minecraft.renderer.pipeline.resolver;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.FormatSpec;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Parses {@code pack.mcmeta} and produces a {@link TexturePack} whose
 * {@link TexturePack#getAssetRoots()} carries the base pack root plus every overlay subtree whose
 * declared {@code formats} range matches the renderer's target pack format. Overlays are appended
 * in declaration order so loaders apply them with later-wins semantics.
 *
 * @see TexturePack
 * @see FormatSpec
 */
@UtilityClass
public class PackResolver {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Resolves a pack root against the renderer's target pack format.
     *
     * @param packRoot the extracted pack root containing {@code pack.mcmeta}
     * @param packId the pack identifier
     * @param priority the pack priority across packs
     * @param targetPackFormat the renderer's target pack format
     * @return the resolved pack with base + matching overlays
     */
    public static @NotNull TexturePack resolve(
        @NotNull Path packRoot,
        @NotNull String packId,
        int priority,
        int targetPackFormat
    ) {
        if (!Files.isDirectory(packRoot))
            throw new PipelineException("Pack root '%s' does not exist or is not a directory", packRoot);

        Path mcmeta = packRoot.resolve("pack.mcmeta");
        if (!Files.isRegularFile(mcmeta))
            return new TexturePack(packId, "minecraft", "", Concurrent.newList(packRoot), priority);

        JsonObject root;
        try {
            root = GSON.fromJson(Files.readString(mcmeta), JsonObject.class);
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to parse pack.mcmeta in '%s'", packRoot);
        }
        if (root == null)
            return new TexturePack(packId, "minecraft", "", Concurrent.newList(packRoot), priority);

        String description = readDescription(root);
        ArrayList<Path> assetRoots = new ArrayList<>();
        assetRoots.add(packRoot);

        if (root.has("overlays") && root.get("overlays").isJsonObject()) {
            JsonObject overlays = root.getAsJsonObject("overlays");
            if (overlays.has("entries") && overlays.get("entries").isJsonArray()) {
                JsonArray entries = overlays.getAsJsonArray("entries");
                for (JsonElement entry : entries) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject entryObj = entry.getAsJsonObject();
                    if (!entryObj.has("directory") || !entryObj.has("formats")) continue;
                    String directory = entryObj.get("directory").getAsString();
                    FormatSpec spec = parseFormats(entryObj.get("formats"), packId);
                    if (!spec.matches(targetPackFormat)) continue;
                    Path overlayRoot = packRoot.resolve(directory);
                    if (Files.isDirectory(overlayRoot)) assetRoots.add(overlayRoot);
                }
            }
        }

        return new TexturePack(packId, "minecraft", description, Concurrent.adoptList(assetRoots).toUnmodifiable(), priority);
    }

    /**
     * Reads the pack description as a plain string, defaulting to empty when the value is a
     * structured text component the loader does not interpret.
     */
    private static @NotNull String readDescription(@NotNull JsonObject root) {
        if (!root.has("pack") || !root.get("pack").isJsonObject()) return "";
        JsonObject pack = root.getAsJsonObject("pack");
        if (!pack.has("description")) return "";
        JsonElement desc = pack.get("description");
        try {
            return desc.isJsonPrimitive() ? desc.getAsString() : "";
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return "";
        }
    }

    /**
     * Parses a single {@code formats} entry. Accepts the bare-integer, length-2-array, and
     * {@code {min_inclusive, max_inclusive}} object encodings; rejects malformed entries with a
     * pack-id-bearing exception so bad packs fail loudly rather than silently dropping their
     * overlays.
     */
    private static @NotNull FormatSpec parseFormats(@NotNull JsonElement formats, @NotNull String packId) {
        if (formats.isJsonPrimitive() && formats.getAsJsonPrimitive().isNumber())
            return new FormatSpec.IntSpec(formats.getAsInt());

        if (formats.isJsonArray()) {
            JsonArray arr = formats.getAsJsonArray();
            if (arr.size() != 2)
                throw new PipelineException("Pack '%s' has overlay 'formats' array of size %d (expected 2)", packId, arr.size());
            return new FormatSpec.RangeSpec(arr.get(0).getAsInt(), arr.get(1).getAsInt());
        }

        if (formats.isJsonObject()) {
            JsonObject obj = formats.getAsJsonObject();
            if (!obj.has("min_inclusive") || !obj.has("max_inclusive"))
                throw new PipelineException("Pack '%s' overlay 'formats' object missing min_inclusive/max_inclusive", packId);
            return new FormatSpec.RangeSpec(obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt());
        }

        throw new PipelineException("Pack '%s' has unrecognised overlay 'formats' encoding", packId);
    }

}
