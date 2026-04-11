package dev.sbs.renderer.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sbs.renderer.model.asset.BlockStateMultipart;
import dev.sbs.renderer.model.asset.BlockStateVariant;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Parses blockstate JSON files from {@code assets/minecraft/blockstates/} and produces both
 * variant-based and multipart-based blockstate data.
 * <p>
 * The {@code "variants"} format maps property combinations to single model references. The
 * {@code "multipart"} format assembles multiple conditional model parts into a composite block.
 * Both formats are parsed into their respective data structures and returned as a
 * {@link LoadResult}.
 */
@UtilityClass
public class BlockStateLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads all blockstate JSON files and returns both variant and multipart data.
     *
     * @param packRoot the pack root directory
     * @return the parsed blockstate data
     */
    public static @NotNull LoadResult load(@NotNull Path packRoot) {
        Path blockstatesDir = packRoot.resolve("assets/minecraft/blockstates");
        ConcurrentMap<String, ConcurrentMap<String, BlockStateVariant>> variants = Concurrent.newMap();
        ConcurrentMap<String, BlockStateMultipart> multiparts = Concurrent.newMap();

        if (!Files.isDirectory(blockstatesDir)) return new LoadResult(variants, multiparts);

        try (Stream<Path> files = Files.list(blockstatesDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                String fileName = file.getFileName().toString();
                String blockName = fileName.substring(0, fileName.length() - 5);
                String blockId = "minecraft:" + blockName;

                try {
                    String json = Files.readString(file);
                    JsonObject root = GSON.fromJson(json, JsonObject.class);
                    if (root == null) return;

                    if (root.has("variants")) {
                        ConcurrentMap<String, BlockStateVariant> parsed = parseVariants(root.getAsJsonObject("variants"));
                        if (!parsed.isEmpty())
                            variants.put(blockId, parsed);
                    } else if (root.has("multipart")) {
                        BlockStateMultipart parsed = parseMultipart(root.getAsJsonArray("multipart"));
                        if (!parsed.parts().isEmpty())
                            multiparts.put(blockId, parsed);
                    }
                } catch (IOException | com.google.gson.JsonSyntaxException ex) {
                    // Skip malformed blockstate files
                }
            });
        } catch (IOException ex) {
            // Directory scan failure is non-fatal
        }

        return new LoadResult(variants, multiparts);
    }

    private static @NotNull ConcurrentMap<String, BlockStateVariant> parseVariants(@NotNull JsonObject variants) {
        ConcurrentMap<String, BlockStateVariant> result = Concurrent.newMap();

        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            JsonElement value = entry.getValue();

            // Variants can be a single object or an array (weighted random); take the first
            JsonObject variantObj;
            if (value.isJsonArray()) {
                if (value.getAsJsonArray().isEmpty()) continue;
                variantObj = value.getAsJsonArray().get(0).getAsJsonObject();
            } else if (value.isJsonObject()) {
                variantObj = value.getAsJsonObject();
            } else {
                continue;
            }

            result.put(entry.getKey(), parseApply(variantObj));
        }

        return result;
    }

    private static @NotNull BlockStateMultipart parseMultipart(@NotNull JsonArray parts) {
        ConcurrentList<BlockStateMultipart.Part> result = Concurrent.newList();

        for (JsonElement element : parts) {
            if (!element.isJsonObject()) continue;
            JsonObject partObj = element.getAsJsonObject();

            JsonObject when = partObj.has("when") ? partObj.getAsJsonObject("when") : null;

            // "apply" can be a single object or an array (weighted random); take the first
            JsonElement applyElement = partObj.get("apply");
            if (applyElement == null) continue;

            JsonObject applyObj;
            if (applyElement.isJsonArray()) {
                JsonArray arr = applyElement.getAsJsonArray();
                if (arr.isEmpty()) continue;
                applyObj = arr.get(0).getAsJsonObject();
            } else if (applyElement.isJsonObject()) {
                applyObj = applyElement.getAsJsonObject();
            } else {
                continue;
            }

            result.add(new BlockStateMultipart.Part(when, parseApply(applyObj)));
        }

        return new BlockStateMultipart(result);
    }

    private static @NotNull BlockStateVariant parseApply(@NotNull JsonObject obj) {
        String modelId = obj.has("model") ? obj.get("model").getAsString() : "";
        int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
        int y = obj.has("y") ? obj.get("y").getAsInt() : 0;
        boolean uvlock = obj.has("uvlock") && obj.get("uvlock").getAsBoolean();
        return new BlockStateVariant(modelId, x, y, uvlock);
    }

    /**
     * The result of loading all blockstate files, containing both variant-based and
     * multipart-based definitions.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class LoadResult {

        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, BlockStateVariant>> variants;
        private final @NotNull ConcurrentMap<String, BlockStateMultipart> multiparts;

    }

}
