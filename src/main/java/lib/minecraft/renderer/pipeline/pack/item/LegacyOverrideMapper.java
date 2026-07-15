package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.pipeline.pack.FormatRange;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Tolerate-and-maps a pre-format-46 {@code models/item/*.json} {@code overrides} array onto the 26.1
 * items-tree node vocabulary. Legacy {@code overrides} are vanilla-dead (0 files in
 * 26.1) but ubiquitous in pre-1.21.4 CIT packs; today Gson silently discards the array. This mapper
 * synthesises the same mechanical translation Mojang shipped in 1.21.4, so a legacy pack's overrides
 * flow through the {@link ItemModelWalker} like a native items file - legacy packs get the same
 * caller-driven dispatch for free once the caller supplies the dispatch value.
 *
 * <p>Gated on the pack's own declared {@link FormatRange} max strictly below {@value #LEGACY_MAX_MAJOR}
 * (a plain format comparison, not a capability check): only a legacy pack maps. Predicate order maps to
 * threshold order - vanilla overrides match the LAST satisfied predicate, which the walker reproduces
 * by picking the highest threshold {@code <=} the value. Any predicate outside {@link #GATES} /
 * {@link #RANGES} is a per-file diagnostic naming the pack + predicate, and that override entry is
 * skipped (never silent-dropped) while the rest of the file still maps.
 */
@UtilityClass
public class LegacyOverrideMapper {

    /**
     * The pack format that removed {@code models/item} {@code overrides} in favour of the items tree
     * (1.21.4). A pack whose declared {@link FormatRange} max is strictly below this major is legacy.
     */
    private static final int LEGACY_MAX_MAJOR = 46;

    /** The format-46.0 threshold; a declared max strictly below it marks a legacy pack. */
    private static final FormatRange.@NotNull FormatVersion LEGACY_THRESHOLD = new FormatRange.FormatVersion(LEGACY_MAX_MAJOR, 0);

    /**
     * Boolean gate predicates: the legacy predicate name to the items-tree {@code condition} property
     * it maps onto. A gate value of {@code 0} reads false, any other value true; the walker takes the
     * gate's {@code on_true} / {@code on_false} branch.
     */
    private static final @NotNull Map<String, String> GATES = Map.of(
        "pulling", "using_item",
        "blocking", "using_item",
        "damaged", "damaged",
        "cast", "fishing_rod/cast"
    );

    /**
     * Numeric range predicates: the legacy predicate name to the items-tree {@code range_dispatch}
     * property plus the scale that converts the legacy predicate value's domain to the property's. The
     * {@code pull} scale {@code 0.05} converts {@code use_duration} ticks to the legacy {@code 0..1}
     * pull fraction (matching Mojang's 1.21.4 {@code bow.json}); every other axis carries the legacy
     * threshold verbatim (scale {@code 1}).
     */
    private static final @NotNull Map<String, RangeAxis> RANGES = Map.of(
        "custom_model_data", new RangeAxis("custom_model_data", 1f),
        "damage", new RangeAxis("damage", 1f),
        "pull", new RangeAxis("use_duration", 0.05f),
        "time", new RangeAxis("time", 1f),
        "angle", new RangeAxis("compass", 1f)
    );

    /**
     * Whether a pack's own declared format range marks it as a legacy (pre-format-46) pack whose
     * {@code overrides} arrays should be tolerated and mapped.
     *
     * @param pack the resource pack
     * @return {@code true} when the pack declares a {@link FormatRange} max strictly below format 46
     */
    public static boolean isLegacyPack(@NotNull ResourcePack pack) {
        return pack.meta().pack()
            .map(section -> section.formats().max().compareTo(LEGACY_THRESHOLD) < 0)
            .orElse(false);
    }

    /**
     * Maps one item model's {@code overrides} array onto an items-tree root node.
     *
     * <p>The {@code fallback} is the branch the tree resolves to when no override matches - the
     * caller supplies the item's EXISTING tree root (its native items-tree, or a plain
     * {@code Model(<ns>:item/<name>)} when none), so the neutral / default render is unchanged and the
     * native tree's tints and structure survive under the override. Only a matching
     * caller value (e.g. {@code custom_model_data}) selects an override frame.
     *
     * @param itemId the owning item id (e.g. {@code minecraft:diamond_sword}), for diagnostics and ref
     *     namespace normalisation
     * @param overrides the raw {@code overrides} array
     * @param packId the owning pack id, for diagnostics
     * @param fallback the node the synthesised tree falls back to when no override matches
     * @return the synthesised root node, or empty when no override entry mapped (the item renders from
     *     its fallback as before)
     */
    public static @NotNull Optional<ItemModelNode> map(
        @NotNull String itemId, @NotNull JsonArray overrides, @NotNull PackId packId, @NotNull ItemModelNode fallback
    ) {
        String namespace = itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft";

        // Group mapped overrides by their gate signature, preserving file order within each group and
        // first-appearance order of groups. A file is almost always one group (all custom_model_data,
        // or all pulling/pull); multiple groups fold safely below.
        LinkedHashMap<TreeMap<String, Boolean>, List<MappedOverride>> groups = new LinkedHashMap<>();
        for (JsonElement element : overrides) {
            if (!element.isJsonObject()) continue;
            MappedOverride mapped = parseOverride(element.getAsJsonObject(), itemId, namespace, packId);
            if (mapped == null) continue;
            groups.computeIfAbsent(mapped.gates(), k -> new ArrayList<>()).add(mapped);
        }
        if (groups.isEmpty()) return Optional.empty();

        // Fold each group over the running fallback: a later group wraps the accumulator so it is
        // checked first, reproducing vanilla last-satisfied-wins. Single-group files (the norm) resolve
        // to a flat range_dispatch (ungated) or one condition wrapping a range_dispatch (gated) -
        // byte-matching Mojang's 1.21.4 structure.
        ItemModelNode result = fallback;
        for (Map.Entry<TreeMap<String, Boolean>, List<MappedOverride>> group : groups.entrySet()) {
            ItemModelNode inner = buildGroupNode(group.getValue(), result);
            result = group.getKey().isEmpty() ? inner : wrapGates(group.getKey(), inner, result);
        }
        return Optional.of(result);
    }

    /**
     * Parses one override's {@code predicate} map into gate flags plus an optional range constraint.
     * Returns {@code null} (skipping the entry) when it carries an unmapped predicate, more than one
     * range axis, a non-numeric predicate value, or no {@code model} - diagnosing each so nothing is
     * silently dropped.
     */
    private static @Nullable MappedOverride parseOverride(
        @NotNull JsonObject override, @NotNull String itemId, @NotNull String namespace, @NotNull PackId packId
    ) {
        if (!override.has("model") || !override.get("model").isJsonPrimitive()) {
            System.err.printf("Pack '%s' item '%s': skipping legacy override with no model%n", packId, itemId);
            return null;
        }
        JsonObject predicate = override.has("predicate") && override.get("predicate").isJsonObject()
            ? override.getAsJsonObject("predicate") : new JsonObject();

        TreeMap<String, Boolean> gates = new TreeMap<>();
        RangeConstraint range = null;
        for (Map.Entry<String, JsonElement> entry : predicate.entrySet()) {
            String key = entry.getKey();
            Float value = numeric(entry.getValue());
            if (value == null) {
                System.err.printf("Pack '%s' item '%s': skipping legacy override with non-numeric predicate '%s'%n", packId, itemId, key);
                return null;
            }
            if (GATES.containsKey(key)) {
                gates.put(GATES.get(key), value != 0f);
            } else if (RANGES.containsKey(key)) {
                if (range != null) {
                    System.err.printf("Pack '%s' item '%s': skipping legacy override with multiple range predicates%n", packId, itemId);
                    return null;
                }
                RangeAxis axis = RANGES.get(key);
                range = new RangeConstraint(axis.property(), axis.scale(), value);
            } else {
                System.err.printf("Pack '%s' item '%s': skipping legacy override with unmapped predicate '%s'%n", packId, itemId, key);
                return null;
            }
        }

        String ref = normalizeRef(override.get("model").getAsString(), namespace);
        return new MappedOverride(gates, range, ref);
    }

    /**
     * Builds one gate-group's inner node against a fallback: a {@code range_dispatch} over the group's
     * range axis (entries deduped by threshold, last-declared winning, so equal thresholds honour
     * last-satisfied), or - when the group carries no range predicate - the last override's model
     * (last-match-wins). The range fallback is a gate-only override's model when present (bow's
     * {@code {pulling:1}} case), else the running fallback.
     *
     * <p>Assumes a homogeneous range axis within a gate group - the group's first ranged override
     * fixes the property + scale, the real-world invariant for every legacy file (a file mixes neither
     * {@code custom_model_data} with {@code time} nor two range axes under one gate). A pathological
     * mixed-axis group folds all thresholds onto the first axis; it never crashes.
     */
    private static @NotNull ItemModelNode buildGroupNode(@NotNull List<MappedOverride> group, @NotNull ItemModelNode fallback) {
        List<MappedOverride> ranged = group.stream().filter(o -> o.range() != null).toList();
        List<MappedOverride> plain = group.stream().filter(o -> o.range() == null).toList();

        if (ranged.isEmpty())
            return new ItemModelNode.Model(group.getLast().modelRef(), List.of());

        RangeConstraint first = ranged.getFirst().range();
        LinkedHashMap<Float, String> byThreshold = new LinkedHashMap<>();
        for (MappedOverride o : ranged)
            byThreshold.put(o.range().threshold(), o.modelRef());

        List<ItemModelNode.RangeDispatch.Entry> entries = new ArrayList<>();
        byThreshold.forEach((threshold, ref) ->
            entries.add(new ItemModelNode.RangeDispatch.Entry(threshold, new ItemModelNode.Model(ref, List.of()))));

        ItemModelNode rangeFallback = plain.isEmpty()
            ? fallback
            : new ItemModelNode.Model(plain.getLast().modelRef(), List.of());
        return new ItemModelNode.RangeDispatch(first.property(), first.scale(), "", List.copyOf(entries), rangeFallback);
    }

    /**
     * Wraps a node in one {@code condition} per gate flag (all must hold - the predicate map is a
     * conjunction), routing the mismatching branch to {@code onFalse}.
     */
    private static @NotNull ItemModelNode wrapGates(
        @NotNull TreeMap<String, Boolean> gates, @NotNull ItemModelNode onTrue, @NotNull ItemModelNode onFalse
    ) {
        ItemModelNode result = onTrue;
        for (Map.Entry<String, Boolean> gate : gates.entrySet())
            result = gate.getValue()
                ? new ItemModelNode.Condition(gate.getKey(), "", result, onFalse)
                : new ItemModelNode.Condition(gate.getKey(), "", onFalse, result);
        return result;
    }

    /** Reads a predicate value as a float, or {@code null} when it is not a JSON number. */
    private static @Nullable Float numeric(@NotNull JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsFloat();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Namespaces a bare legacy model ref to the item's owning namespace ({@code item/foo -> minecraft:item/foo}). */
    private static @NotNull String normalizeRef(@NotNull String ref, @NotNull String namespace) {
        return ref.contains(":") ? ref : namespace + ":" + ref;
    }

    /** A legacy predicate mapped to a range axis: the items-tree property plus its value-domain scale. */
    private record RangeAxis(@NotNull String property, float scale) {}

    /** A concrete range constraint from one override: the axis property, its scale, and the threshold. */
    private record RangeConstraint(@NotNull String property, float scale, float threshold) {}

    /**
     * One override reduced to its mapped form.
     *
     * @param gates the gate flags (condition property to required boolean), the group signature
     * @param range the range constraint, or {@code null} when the override has no numeric predicate
     * @param modelRef the namespaced model ref this override selects
     */
    private record MappedOverride(@NotNull TreeMap<String, Boolean> gates, @Nullable RangeConstraint range, @NotNull String modelRef) {}

}
