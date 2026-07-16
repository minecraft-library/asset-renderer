package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.block.BlockDefaultsReader;
import lib.minecraft.renderer.pipeline.load.block.BlockItemsReader;
import lib.minecraft.renderer.pipeline.load.block.BlockRendererOverrides;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A loader that reads blockstate JSON files from every pack's {@code assets/<namespace>/blockstates/}
 * subtree and produces both variant-based and multipart-based blockstate data.
 * <p>
 * Minecraft defines two blockstate formats. The {@code "variants"} format maps block property
 * combinations (e.g. {@code "facing=north,half=bottom"}) to a single {@link Block.Variant}
 * model reference. The {@code "multipart"} format assembles multiple conditional model parts
 * into a composite {@link Block.Multipart} block, where each part applies when its
 * {@code "when"} condition matches the block's properties. Both formats are parsed into their
 * respective data structures and returned together as a {@link LoadResult}.
 * <p>
 * <b>Weighted arrays - first entry (normative, both sites).</b> When a {@code "variants"} value or a
 * multipart part's {@code "apply"} value is an array (a weighted-random set), only the FIRST entry is
 * used. This is a parity requirement, not a simplification: the vanilla-reference harness forces
 * {@code FirstVariantRandomSource.nextInt -> 0}, so every ground-truth icon renders the first array
 * entry. Both array sites therefore share one deterministic rule (see {@link #parseVariants} and
 * {@link #parseMultipart}).
 * <p>
 * The merge runs over the {@link PackStack} effective file set: packs ascending, each pack's
 * {@code filter.block} erasing matching lower-pack ids before its own subtrees merge in, and a
 * higher pack's blockstate fully replacing (variants&harr;multipart included) any lower pack's for
 * the same id - matching the vanilla client's per-file topmost-pack-wins semantics. A vanilla-only
 * stack scans exactly {@code assets/minecraft/blockstates}.
 *
 * @see Block.Variant
 * @see Block.Multipart
 * @see ModelResolver
 */
@UtilityClass
public class BlockStateLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads blockstate JSON files across the whole pack stack. Packs are visited ascending; before
     * each pack's rows merge in, its {@code filter.block} patterns erase matching accumulated ids
     * from both the variant and multipart maps, then every {@code (root x namespace)} blockstate
     * subtree it owns is scanned. Per block id, a higher pack's blockstate fully replaces any lower
     * pack's variants or multipart - matching Minecraft, which loads exactly one blockstate file per
     * id from the topmost matching pack.
     * <p>
     * Each parsed {@link Block.Variant} carries its resolved {@link ModelData}, baked in from
     * {@code blockModels} at parse time so a variant reaches its geometry through its owning
     * {@link Block} rather than a context-level model registry. The bundled
     * {@code block_defaults.json} snapshot is read here too, supplying each block's canonical
     * default-state key.
     *
     * @param stack the resolved pack stack
     * @param blockModels the parsed model set, keyed by full model id
     * @return the parsed blockstate data
     */
    public static @NotNull LoadResult load(@NotNull PackStack stack, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        HashMap<String, ConcurrentMap<String, Block.Variant>> variants = new HashMap<>();
        HashMap<String, Block.Multipart> multiparts = new HashMap<>();

        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> {
                variants.keySet().removeIf(id -> section.hides(ResourceId.parse(id)));
                multiparts.keySet().removeIf(id -> section.hides(ResourceId.parse(id)));
            });
            PackContainer container = pack.container();

            for (PackRoot root : pack.roots())
                for (String namespace : pack.namespaces()) {
                    String blockstatesPrefix = root.prefix() + VanillaSourcePaths.assetSubdir(namespace, VanillaSourcePaths.BLOCKSTATES_SUBDIR);
                    mergeDir(container, blockstatesPrefix, namespace, blockModels, variants, multiparts);
                }
        }

        return new LoadResult(
            Concurrent.adoptMap(variants).toUnmodifiable(),
            Concurrent.adoptMap(multiparts).toUnmodifiable(),
            loadDefaultStateKeys(stack),
            loadItemBlockIds()
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
     * {@code ToolingBlockDefaults}) into a map of block id to canonical default-state key, overlaying
     * the pack {@code renderer/block_defaults.json} override channel - the only way
     * any pack can change an ASM-derived default state. A vanilla-only stack ships no override, so the
     * result is byte-identical to the classpath snapshot.
     *
     * @param stack the resolved pack stack whose {@code renderer/block_defaults.json} override is consulted
     * @return block id to default-state key (e.g. {@code "facing=north,lit=false"})
     * @throws PipelineException if the resource is missing or cannot be parsed, or a pack override file
     *     fails format-2 envelope validation
     */
    private static @NotNull ConcurrentMap<String, String> loadDefaultStateKeys(@NotNull PackStack stack) {
        Diagnostics diagnostics = Diagnostics.root("blockDefaults", Diagnostics.Output.CONSOLE, null);
        return BlockDefaultsReader.load(diagnostics, BlockRendererOverrides.gather(stack, diagnostics));
    }

    /**
     * Reads the bundled {@code block_items.json} snapshot (generated by {@code ToolingBlockItems}) into
     * a map of secondary block id to the standing block id whose inventory item it shares. It carries
     * no pack override channel - the block-to-item registration is a fixed vanilla structural fact.
     *
     * @return secondary block id to standing block id (e.g. {@code minecraft:white_wall_banner ->
     *     minecraft:white_banner}); blocks that own their own item are absent
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    private static @NotNull ConcurrentMap<String, String> loadItemBlockIds() {
        Diagnostics diagnostics = Diagnostics.root("blockItems", Diagnostics.Output.CONSOLE, null);
        return BlockItemsReader.load(diagnostics);
    }

    /**
     * Scans one {@code (root x namespace)} blockstate directory and merges its parsed entries into
     * the running per-id variant / multipart maps. A higher root's variant entry deletes any earlier
     * multipart entry for the same id (and vice versa) - this preserves "exactly one blockstate file
     * wins" even when packs convert a block from variants to multipart or back. No-op when the
     * directory is absent (a pack simply lacks that namespace's blockstates).
     */
    private static void mergeDir(
        @NotNull PackContainer container,
        @NotNull String blockstatesPrefix,
        @NotNull String namespace,
        @NotNull ConcurrentMap<String, ModelData> blockModels,
        @NotNull HashMap<String, ConcurrentMap<String, Block.Variant>> variants,
        @NotNull HashMap<String, Block.Multipart> multiparts
    ) {
        // Two-phase walk: serial path enumeration, then parallel JSON parse per file. Per-file
        // work is CPU-bound (Gson parse of a small blockstate JSON) plus a tiny byte read; the
        // parallel stream scales it across cores. Each file produces at most one Parsed record;
        // the partition into variants/multiparts happens in a sequential pass over the gathered
        // list so neither map pays per-element write-lock cost. Blockstate files are flat under
        // blockstates/ - direct children only, matching the old one-level list.
        List<String> files = container.entries(blockstatesPrefix)
            .filter(p -> p.endsWith(".json"))
            .filter(p -> p.indexOf('/', blockstatesPrefix.length() + 1) < 0)
            .toList();

        List<Parsed> parsedAll = files.parallelStream()
            .map(file -> parseBlockstateFile(container, file, blockstatesPrefix, namespace, blockModels))
            .flatMap(Optional::stream)
            .toList();

        for (Parsed p : parsedAll) {
            if (p.variants != null) {
                variants.put(p.blockId, p.variants);
                multiparts.remove(p.blockId);
            } else if (p.multipart != null) {
                multiparts.put(p.blockId, p.multipart);
                variants.remove(p.blockId);
            } else {
                // A valid but non-renderable higher-pack file still shadows the lower pack's entry -
                // vanilla presents only the topmost file, so its presence must erase the lower rows.
                variants.remove(p.blockId);
                multiparts.remove(p.blockId);
            }
        }
    }

    /**
     * Parses one blockstate JSON file into a {@link Parsed} record. The block id is the file name
     * with its {@code .json} suffix stripped and the {@code <namespace>:} prefix prepended (the
     * owning namespace, so a pack's {@code assets/<ns>/blockstates} keys are namespace-qualified).
     * A {@code "variants"} object yields the variant branch, a {@code "multipart"} array the
     * multipart branch, each only when non-empty. A file that parses to valid JSON but yields no
     * renderable definition (neither key, or empty variants/multipart) returns a <em>shadowing</em>
     * {@link Parsed} carrying neither branch, so a higher pack's presence still erases the lower
     * pack's entry (vanilla topmost-file-wins). Only a null root or a read / parse failure resolves
     * to empty - falling back to a lower pack's copy, the deliberate malformed-file behaviour.
     *
     * @param file the blockstate JSON file
     * @param namespace the owning namespace, prepended to the derived block id
     * @param blockModels the parsed model set used to bake each variant's resolved {@link ModelData}
     * @return the parsed variant / multipart / shadow record, or empty on a null or malformed file
     */
    private static @NotNull Optional<Parsed> parseBlockstateFile(@NotNull PackContainer container, @NotNull String entry, @NotNull String blockstatesPrefix, @NotNull String namespace, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        String fileName = entry.substring(blockstatesPrefix.length() + 1);
        String blockName = fileName.substring(0, fileName.length() - 5);
        String blockId = VanillaSourcePaths.namespacePrefix(namespace) + blockName;

        try {
            JsonObject root = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return Optional.empty();

            if (root.has("variants")) {
                ConcurrentMap<String, Block.Variant> parsed = parseVariants(root.getAsJsonObject("variants"), blockModels);
                if (!parsed.isEmpty()) return Optional.of(new Parsed(blockId, parsed, null));
            } else if (root.has("multipart")) {
                Block.Multipart parsed = parseMultipart(root.getAsJsonArray("multipart"), blockModels);
                if (!parsed.parts().isEmpty()) return Optional.of(new Parsed(blockId, null, parsed));
            }
            // Valid JSON, nothing renderable: shadow the lower pack's entry (whole-file replace).
            return Optional.of(new Parsed(blockId, null, null));
        } catch (JsonSyntaxException ex) {
            // Malformed: fall back to a lower pack's copy (no shadow).
            return Optional.empty();
        }
    }

    /**
     * One parsed blockstate file, carrying at most one of {@code variants} or {@code multipart} so
     * the merge pass can route it to the matching per-id map. Both {@code null} is a shadow record:
     * a valid but non-renderable file that must still erase the lower pack's rows for its block id.
     *
     * @param blockId the namespaced block id derived from the file name
     * @param variants the parsed variant map, or {@code null} when the file is multipart or a shadow
     * @param multipart the parsed multipart block, or {@code null} when the file is variant-based or a shadow
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
     * an {@code "apply"} value - a single variant object or a weighted-random array of them. When the
     * {@code "apply"} value is an array, the FIRST entry is taken, the same normative first-entry rule
     * {@link #parseVariants} applies to weighted {@code "variants"} arrays (harness
     * {@code FirstVariantRandomSource -> 0} parity). Non-object elements and entries with no
     * {@code "apply"} or an empty {@code "apply"} array are skipped.
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
        return new Block.Variant(modelId, x, y, uvlock, new Block.ElementGeometry(resolveVariantModel(modelId, blockModels)));
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

        /**
         * Secondary block id to the standing block id whose inventory item it shares, from the bundled
         * {@code block_items.json} snapshot. Blocks that own their own item are absent.
         */
        private final @NotNull ConcurrentMap<String, String> itemBlockIds;

    }

}
