package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.json.JsonException;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * entry. Both array sites therefore share one deterministic rule ({@link #firstApply}).
 * <p>
 * The merge runs over the {@link PackStack} effective file set: packs ascending, each pack's
 * {@code filter.block} erasing matching lower-pack ids before its own subtrees merge in, and a
 * higher pack's blockstate fully replacing (variants&harr;multipart included) any lower pack's for
 * the same id - matching the vanilla client's per-file topmost-pack-wins semantics. A vanilla-only
 * stack scans exactly {@code assets/minecraft/blockstates}.
 *
 * @see Block.Variant
 * @see Block.Multipart
 */
@UtilityClass
public class BlockStateLoader {

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
     * {@link Block} rather than a context-level model registry.
     *
     * @param stack the resolved pack stack
     * @param blockModels the parsed model set, keyed by full model id
     * @return the parsed blockstate data
     */
    public static @NotNull BlockStates load(@NotNull PackStack stack, @NotNull ConcurrentMap<String, ModelData> blockModels) {
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

        return new BlockStates(
            Concurrent.adoptMap(variants).toUnmodifiable(),
            Concurrent.adoptMap(multiparts).toUnmodifiable()
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
            switch (p) {
                case Parsed.Variants v -> {
                    variants.put(v.blockId(), v.variants());
                    multiparts.remove(v.blockId());
                }
                case Parsed.Multipart m -> {
                    multiparts.put(m.blockId(), m.multipart());
                    variants.remove(m.blockId());
                }
                case Parsed.Shadow s -> {
                    // A valid but non-renderable higher-pack file still shadows the lower pack's entry -
                    // vanilla presents only the topmost file, so its presence must erase the lower rows.
                    variants.remove(s.blockId());
                    multiparts.remove(s.blockId());
                }
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
            JsonNode root = JsonNode.parse(container.bytes(entry).orElseThrow());
            if (!root.isObject()) return Optional.empty();

            if (root.has("variants")) {
                ConcurrentMap<String, Block.Variant> parsed = parseVariants(root.get("variants"), blockModels);
                if (!parsed.isEmpty()) return Optional.of(new Parsed.Variants(blockId, parsed));
            } else if (root.has("multipart")) {
                Block.Multipart parsed = parseMultipart(root.get("multipart"), blockModels);
                if (!parsed.parts().isEmpty()) return Optional.of(new Parsed.Multipart(blockId, parsed));
            }
            // Valid JSON, nothing renderable: shadow the lower pack's entry (whole-file replace).
            return Optional.of(new Parsed.Shadow(blockId));
        } catch (JsonException ex) {
            // Malformed: fall back to a lower pack's copy (no shadow).
            return Optional.empty();
        }
    }

    /**
     * One parsed blockstate file, routed by the merge pass to the matching per-id map. A file is
     * exactly one of three states, each named rather than encoded in nullable fields: a {@link Variants}
     * or {@link Multipart} definition, or a {@link Shadow} - a valid but non-renderable file that must
     * still erase the lower pack's rows for its block id (vanilla topmost-file-wins).
     */
    private sealed interface Parsed permits Parsed.Variants, Parsed.Multipart, Parsed.Shadow {

        /**
         * The namespaced block id derived from the file name.
         *
         * @return the block id
         */
        @NotNull String blockId();

        /**
         * A variant-form file: a {@code variantKey -> }{@link Block.Variant} map.
         *
         * @param blockId the namespaced block id
         * @param variants the parsed variant map
         */
        record Variants(@NotNull String blockId, @NotNull ConcurrentMap<String, Block.Variant> variants) implements Parsed {}

        /**
         * A multipart-form file: a composite conditional block.
         *
         * @param blockId the namespaced block id
         * @param multipart the parsed multipart block
         */
        record Multipart(@NotNull String blockId, @NotNull Block.Multipart multipart) implements Parsed {}

        /**
         * A valid but non-renderable file (no renderable definition), retained so a higher pack's
         * presence still erases the lower pack's rows for its block id.
         *
         * @param blockId the namespaced block id
         */
        record Shadow(@NotNull String blockId) implements Parsed {}
    }

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
    private static @NotNull ConcurrentMap<String, Block.Variant> parseVariants(@NotNull JsonNode variants, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        HashMap<String, Block.Variant> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : variants.members())
            firstApply(entry.getValue()).ifPresent(obj -> result.put(entry.getKey(), parseApply(obj, blockModels)));
        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * The applicable variant object for a {@code "variants"} value or a multipart part's {@code "apply"}
     * value - the shared weighted-array first-entry rule (harness {@code FirstVariantRandomSource -> 0}
     * parity). An array yields its first element, a bare object yields itself, and anything else (a
     * non-object, an empty array, or a non-object first element) yields empty so the caller skips it.
     *
     * @param value the {@code "variants"} value or {@code "apply"} value
     * @return the applicable object node, or empty when there is none
     */
    private static @NotNull Optional<JsonNode> firstApply(@NotNull JsonNode value) {
        JsonNode candidate = value.isArray() ? value.at(0) : value;
        return candidate != null && candidate.isObject() ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * Parses a {@code "multipart"} array into a {@link Block.Multipart}. Each element carries an
     * optional {@code "when"} condition (parsed into a {@link Block.Multipart.When} by
     * {@link #parseWhen}, {@link Block.Multipart.When.Always} when absent) and an {@code "apply"}
     * value - a single variant object or a weighted-random array of them. When the
     * {@code "apply"} value is an array, the FIRST entry is taken, the same normative first-entry rule
     * {@link #firstApply} applies to weighted {@code "variants"} arrays (harness
     * {@code FirstVariantRandomSource -> 0} parity). Non-object elements and entries with no
     * {@code "apply"} or an empty {@code "apply"} array are skipped.
     *
     * @param parts the blockstate {@code "multipart"} array
     * @param blockModels the parsed model set used to bake each part's resolved {@link ModelData}
     * @return the assembled multipart block
     */
    private static @NotNull Block.Multipart parseMultipart(@NotNull JsonNode parts, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        ArrayList<Block.Multipart.Part> result = new ArrayList<>();

        for (JsonNode element : parts.elements()) {
            if (!element.isObject()) continue;

            Block.Multipart.When when = element.has("when")
                ? parseWhen(element.get("when"))
                : new Block.Multipart.When.Always();

            // "apply" can be a single object or an array (weighted random); take the first
            JsonNode applyValue = element.get("apply");
            if (applyValue == null) continue;
            Optional<JsonNode> applyObj = firstApply(applyValue);
            if (applyObj.isEmpty()) continue;

            result.add(new Block.Multipart.Part(when, parseApply(applyObj.get(), blockModels)));
        }

        return new Block.Multipart(Concurrent.adoptList(result).toUnmodifiable());
    }

    /**
     * Parses one multipart {@code "when"} condition into a {@link Block.Multipart.When}. An
     * {@code "AND"} array becomes {@link Block.Multipart.When.All}, an {@code "OR"} array a
     * {@link Block.Multipart.When.Any} (each recursively parsed), and any other object a
     * {@link Block.Multipart.When.Match} whose values are {@code |}-split into their allowed sets.
     * {@code AND} is checked first and {@code OR} second, matching the render-time precedence: a
     * {@code when} carrying {@code AND} ignores any sibling {@code OR} or plain property.
     *
     * @param when the {@code "when"} JSON
     * @return the parsed condition
     */
    private static @NotNull Block.Multipart.When parseWhen(@NotNull JsonNode when) {
        if (when.has("AND")) return new Block.Multipart.When.All(parseWhenTerms(when.get("AND")));
        if (when.has("OR")) return new Block.Multipart.When.Any(parseWhenTerms(when.get("OR")));

        LinkedHashMap<String, List<String>> required = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : when.members())
            required.put(entry.getKey(), List.of(entry.getValue().stringValue().orElse("").split("\\|")));
        return new Block.Multipart.When.Match(required);
    }

    /**
     * Parses the sub-condition array of an {@code "AND"} / {@code "OR"} form into an ordered list of
     * {@link Block.Multipart.When}, recursing through {@link #parseWhen}. Non-object elements are
     * skipped.
     *
     * @param terms the {@code "AND"} or {@code "OR"} array
     * @return the parsed sub-conditions, in author order
     */
    private static @NotNull List<Block.Multipart.When> parseWhenTerms(@NotNull JsonNode terms) {
        ArrayList<Block.Multipart.When> parsed = new ArrayList<>();
        for (JsonNode term : terms.elements())
            if (term.isObject()) parsed.add(parseWhen(term));
        return parsed;
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
    private static @NotNull Block.Variant parseApply(@NotNull JsonNode obj, @NotNull ConcurrentMap<String, ModelData> blockModels) {
        String modelId = obj.getString("model", "");
        int x = obj.getInt("x", 0);
        int y = obj.getInt("y", 0);
        boolean uvlock = obj.getBool("uvlock", false);
        return new Block.Variant(modelId, x, y, uvlock, new Block.ElementGeometry(resolveVariantModel(modelId, blockModels)));
    }

    /**
     * The parsed blockstate data: the variant-form and multipart-form definitions across the stack. A
     * block appears in at most one of the two maps.
     *
     * @param variants block id to its {@code variantKey -> }{@link Block.Variant} map (variant-form files)
     * @param multiparts block id to its composite {@link Block.Multipart} (multipart-form files)
     */
    public record BlockStates(
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants,
        @NotNull ConcurrentMap<String, Block.Multipart> multiparts
    ) {}

}
