package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A loader that reads blockstate JSON files from {@code assets/minecraft/blockstates/} and
 * produces both variant-based and multipart-based blockstate data.
 * <p>
 * Minecraft defines two blockstate formats. The {@code "variants"} format maps block property
 * combinations (e.g. {@code "facing=north,half=bottom"}) to a single {@link Block.Variant}
 * model reference. The {@code "multipart"} format assembles multiple conditional model parts
 * into a composite {@link Block.Multipart} block, where each part applies when its
 * {@code "when"} condition matches the block's properties. Both formats are parsed into their
 * respective data structures and returned together as a {@link LoadResult}.
 * <p>
 * When a variant or multipart {@code "apply"} value is an array (weighted random selection),
 * only the first entry is used - the renderer does not support random model selection.
 *
 * @see Block.Variant
 * @see Block.Multipart
 * @see ModelResolver
 */
@UtilityClass
public class BlockStateLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull String DEFAULTS_RESOURCE_PATH = "/lib/minecraft/renderer/block_defaults.json";

    /**
     * Loads all blockstate JSON files and returns both variant and multipart data.
     *
     * @param packRoot the pack root directory
     * @param blockModels the parsed model set, keyed by full model id, used to bake each variant's
     *     resolved {@link ModelData}
     * @return the parsed blockstate data
     */
    public static @NotNull LoadResult load(@NotNull Path packRoot, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        return load(Concurrent.newList(packRoot), blockModels);
    }

    /**
     * Loads blockstate JSON files from every asset root in priority order (lowest first, highest
     * last). Per block id, a higher root's blockstate fully replaces any lower root's variants
     * or multipart - matches Minecraft, which loads exactly one blockstate file per id from the
     * topmost matching pack.
     * <p>
     * Each parsed {@link Block.Variant} carries its resolved {@link ModelData}, baked in from
     * {@code blockModels} at parse time so a variant reaches its geometry through its owning
     * {@link Block} rather than a context-level model registry. The bundled
     * {@code block_defaults.json} snapshot is read here too, supplying each block's canonical
     * default-state key.
     *
     * @param assetRoots the ordered asset roots
     * @param blockModels the parsed model set, keyed by full model id
     * @return the parsed blockstate data
     */
    public static @NotNull LoadResult load(@NotNull ConcurrentList<Path> assetRoots, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        HashMap<String, ConcurrentMap<String, Block.Variant>> variants = new HashMap<>();
        HashMap<String, Block.Multipart> multiparts = new HashMap<>();
        for (Path root : assetRoots)
            mergeRoot(root, blockModels, variants, multiparts);
        return new LoadResult(
            Concurrent.adoptMap(variants).toUnmodifiable(),
            Concurrent.adoptMap(multiparts).toUnmodifiable(),
            loadDefaultStateKeys()
        );
    }

    /**
     * Resolves a variant's {@code modelId} against the full model set, returning the parsed
     * {@link ModelData} or an element-less placeholder when the id is blank or unresolved.
     * This is the single point where the 1:1 block-to-model link is established.
     *
     * @param modelId the full namespaced model id (e.g. {@code "minecraft:block/furnace"})
     * @param blockModels the full parsed-model set keyed by model id
     * @return the resolved model, or an element-less {@link ModelData} when absent
     */
    private static @NotNull ModelData resolveVariantModel(@NotNull String modelId, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        if (modelId.isEmpty()) return new ModelData();
        ModelData model = blockModels.get(modelId);
        return model != null ? model : new ModelData();
    }

    /**
     * Reads the bundled {@code block_defaults.json} snapshot (generated by
     * {@code ToolingBlockDefaults}) into a map of block id to canonical default-state key.
     *
     * @return block id to default-state key (e.g. {@code "facing=north,lit=false"})
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    private static @NotNull ConcurrentMap<String, String> loadDefaultStateKeys() {
        HashMap<String, String> defaults = new HashMap<>();
        try (InputStream stream = BlockStateLoader.class.getResourceAsStream(DEFAULTS_RESOURCE_PATH)) {
            if (stream == null)
                throw new PipelineException("Vanilla block-defaults resource '%s' not found on the classpath", DEFAULTS_RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("blocks"))
                throw new PipelineException("Vanilla block-defaults resource '%s' has no 'blocks' object", DEFAULTS_RESOURCE_PATH);

            JsonObject blocks = root.getAsJsonObject("blocks");
            for (String blockId : blocks.keySet())
                defaults.put(blockId, blocks.get(blockId).getAsString());
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load vanilla block-defaults resource '%s'", DEFAULTS_RESOURCE_PATH);
        }
        return Concurrent.adoptMap(defaults).toUnmodifiable();
    }

    /**
     * Scans one asset root and merges its parsed blockstate entries into the running per-id
     * variant / multipart maps. A higher root's variant entry deletes any earlier multipart entry
     * for the same id (and vice versa) - this preserves "exactly one blockstate file wins" even
     * when packs convert a block from variants to multipart or back.
     */
    private static void mergeRoot(
        @NotNull Path packRoot,
        @NotNull ConcurrentMap<String, ModelData> blockModels,
        @NotNull HashMap<String, ConcurrentMap<String, Block.Variant>> variants,
        @NotNull HashMap<String, Block.Multipart> multiparts
    ) {
        Path blockstatesDir = packRoot.resolve(VanillaSourcePaths.BLOCKSTATES_DIR);
        if (!Files.isDirectory(blockstatesDir)) return;

        // Two-phase walk: serial path enumeration, then parallel JSON parse per file. Per-file
        // work is CPU-bound (Gson parse of a small blockstate JSON) plus a tiny disk read; the
        // parallel stream scales it across cores. Each file produces at most one Parsed record;
        // the partition into variants/multiparts happens in a sequential pass over the gathered
        // list so neither map pays per-element write-lock cost.
        List<Path> files;
        try (Stream<Path> stream = Files.list(blockstatesDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toList();
        } catch (IOException ex) {
            return;
        }

        List<Parsed> parsedAll = files.parallelStream()
            .map(file -> parseBlockstateFile(file, blockModels))
            .filter(Objects::nonNull)
            .toList();

        for (Parsed p : parsedAll) {
            if (p.variants != null) {
                variants.put(p.blockId, p.variants);
                multiparts.remove(p.blockId);
            } else if (p.multipart != null) {
                multiparts.put(p.blockId, p.multipart);
                variants.remove(p.blockId);
            }
        }
    }

    /**
     * Parses one blockstate JSON file into a {@link Parsed} record. The block id is the file name
     * with its {@code .json} suffix stripped and the {@link VanillaSourcePaths#MINECRAFT_NAMESPACE}
     * prefix prepended. A {@code "variants"} object yields the variant branch, a {@code "multipart"}
     * array the multipart branch; each is returned only when non-empty. Files that are neither, that
     * parse to a blank object, or that fail to read / parse resolve to {@code null} and are dropped
     * by the caller.
     *
     * @param file the blockstate JSON file
     * @param blockModels the parsed model set used to bake each variant's resolved {@link ModelData}
     * @return the parsed variant or multipart data, or {@code null} when the file yields neither
     */
    private static @Nullable Parsed parseBlockstateFile(@NotNull Path file, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        String fileName = file.getFileName().toString();
        String blockName = fileName.substring(0, fileName.length() - 5);
        String blockId = VanillaSourcePaths.MINECRAFT_NAMESPACE + blockName;

        try {
            String json = Files.readString(file);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return null;

            if (root.has("variants")) {
                ConcurrentMap<String, Block.Variant> parsed = parseVariants(root.getAsJsonObject("variants"), blockModels);
                return parsed.isEmpty() ? null : new Parsed(blockId, parsed, null);
            } else if (root.has("multipart")) {
                Block.Multipart parsed = parseMultipart(root.getAsJsonArray("multipart"), blockModels);
                return parsed.parts().isEmpty() ? null : new Parsed(blockId, null, parsed);
            }
        } catch (IOException | JsonSyntaxException ex) {
            // Skip malformed blockstate files
        }
        return null;
    }

    /**
     * One parsed blockstate file, carrying exactly one of {@code variants} or {@code multipart}
     * (the other is {@code null}) so the merge pass can route it to the matching per-id map.
     *
     * @param blockId the namespaced block id derived from the file name
     * @param variants the parsed variant map, or {@code null} when the file is multipart
     * @param multipart the parsed multipart block, or {@code null} when the file is variant-based
     */
    private record Parsed(@NotNull String blockId, @Nullable ConcurrentMap<String, Block.Variant> variants, @Nullable Block.Multipart multipart) {}

    /**
     * Parses a {@code "variants"} object into a {@code variantKey -> }{@link Block.Variant} map.
     * Each value is a single variant object or a weighted-random array of them; the array's first
     * entry is taken (random selection is unsupported). Values that are neither object nor array are
     * skipped.
     *
     * @param variants the blockstate {@code "variants"} object
     * @param blockModels the parsed model set used to bake each variant's resolved {@link ModelData}
     * @return the variant map keyed by property-combination string, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, Block.Variant> parseVariants(@NotNull JsonObject variants, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        HashMap<String, Block.Variant> result = new HashMap<>();

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

            result.put(entry.getKey(), parseApply(variantObj, blockModels));
        }

        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * Parses a {@code "multipart"} array into a {@link Block.Multipart}. Each element carries an
     * optional {@code "when"} condition object (retained verbatim for runtime property matching) and
     * an {@code "apply"} value - a single variant object or a weighted-random array of them, the
     * first entry taken. Non-object elements and entries with no {@code "apply"} or an empty
     * {@code "apply"} array are skipped.
     *
     * @param parts the blockstate {@code "multipart"} array
     * @param blockModels the parsed model set used to bake each part's resolved {@link ModelData}
     * @return the assembled multipart block
     */
    private static @NotNull Block.Multipart parseMultipart(@NotNull JsonArray parts, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        ArrayList<Block.Multipart.Part> result = new ArrayList<>();

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

            result.add(new Block.Multipart.Part(when, parseApply(applyObj, blockModels)));
        }

        return new Block.Multipart(Concurrent.adoptList(result).toUnmodifiable());
    }

    /**
     * Parses a single {@code "apply"} object (shared by the variant and multipart branches) into a
     * {@link Block.Variant}. Reads the {@code "model"} id (defaulting to blank when absent), the
     * {@code "x"} / {@code "y"} rotation in degrees (default {@code 0}), and the {@code "uvlock"}
     * flag (default {@code false}), then bakes in the resolved {@link ModelData} via
     * {@link #resolveVariantModel}.
     *
     * @param obj the {@code "apply"} JSON object
     * @param blockModels the parsed model set used to resolve the model id to {@link ModelData}
     * @return the parsed variant with its geometry baked in
     */
    private static @NotNull Block.Variant parseApply(@NotNull JsonObject obj, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        String modelId = obj.has("model") ? obj.get("model").getAsString() : "";
        int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
        int y = obj.has("y") ? obj.get("y").getAsInt() : 0;
        boolean uvlock = obj.has("uvlock") && obj.get("uvlock").getAsBoolean();
        return new Block.Variant(modelId, resolveVariantModel(modelId, blockModels), x, y, uvlock);
    }

    /**
     * The result of loading all blockstate files, containing both variant-based and
     * multipart-based definitions.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class LoadResult {

        /**
         * Block id to its {@code variantKey -> }{@link Block.Variant} map, from every
         * {@code "variants"}-format blockstate file. A block appears in at most one of this and
         * {@link #multiparts}.
         */
        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants;

        /**
         * Block id to its composite {@link Block.Multipart}, from every {@code "multipart"}-format
         * blockstate file. A block appears in at most one of this and {@link #variants}.
         */
        private final @NotNull ConcurrentMap<String, Block.Multipart> multiparts;

        /**
         * Block id to canonical default-state key (e.g.
         * {@code "facing=north,half=lower,hinge=left,open=false,powered=false"}), from the bundled
         * {@code block_defaults.json} snapshot. Empty-property blocks are absent.
         */
        private final @NotNull ConcurrentMap<String, String> defaultStateKeys;

    }

}
