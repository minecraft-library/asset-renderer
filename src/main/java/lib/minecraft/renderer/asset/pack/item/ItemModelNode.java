package lib.minecraft.renderer.asset.pack.item;

import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.option.ItemModelContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One node of a parsed {@code items/*.json} dispatch tree. The sealed hierarchy
 * mirrors the 26.1 node vocabulary: a {@link Model} leaf, the {@link Condition} / {@link Select} /
 * {@link RangeDispatch} dispatch nodes, {@link Composite} concatenation, a {@link Special}
 * hardcoded-render leaf, the {@link Bundle} selected-item slot marker, and an {@link Empty} sentinel
 * the parser substitutes for an absent branch or an unknown node type (renders nothing, no fallback).
 *
 * <p>Nodes are immutable records built once at pipeline time from the item definition JSON and evaluated
 * against an {@link ItemModelContext} by {@link #resolve(ItemModelContext)}. Absent branches are never
 * {@code null} - {@link Empty#INSTANCE} stands in - so resolution never dereferences a missing case.
 */
public sealed interface ItemModelNode
    permits ItemModelNode.Model, ItemModelNode.Condition, ItemModelNode.Select,
    ItemModelNode.RangeDispatch, ItemModelNode.Composite, ItemModelNode.Special,
    ItemModelNode.Bundle, ItemModelNode.Empty {

    /**
     * A {@code minecraft:model} leaf - a resolved model reference plus the per-layer tints declared
     * on this branch (tints ride the branch actually rendered).
     *
     * @param model the namespaced model id (e.g. {@code minecraft:item/bow})
     * @param tints the per-layer tint rules, index {@code N} applying to {@code layerN}; empty when untinted
     */
    record Model(@NotNull String model, @NotNull List<LayerTint> tints) implements ItemModelNode {}

    /**
     * A {@code minecraft:condition} node - a boolean property selecting {@link #onTrue} or
     * {@link #onFalse}. The optional {@link #component} names the NBT component a
     * {@code has_component} condition tests, evaluated against the render-time component map via
     * {@link ItemModelContext#hasComponent}.
     *
     * @param property the dispatch property id
     * @param component the tested component id, or empty string when absent
     * @param onTrue the branch when the property is true
     * @param onFalse the branch when the property is false or unevaluable
     */
    record Condition(
        @NotNull String property, @NotNull String component,
        @NotNull ItemModelNode onTrue, @NotNull ItemModelNode onFalse
    ) implements ItemModelNode {}

    /**
     * A {@code minecraft:select} node - a case key selecting a matching {@link Case}, else
     * {@link #fallback}. {@link #blockStateProperty} names the block-state property a
     * {@code block_state} select keys on (unevaluable in an icon context, so it takes the fallback).
     *
     * @param property the dispatch property id
     * @param blockStateProperty the block-state property for a {@code block_state} select, or empty string
     * @param cases the ordered cases
     * @param fallback the branch when no case matches or the property is unevaluable
     */
    record Select(
        @NotNull String property, @NotNull String blockStateProperty,
        @NotNull List<Case> cases, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code select} case - the model to use when the context's case key is one of
         * {@link #when}.
         *
         * @param when the case keys this branch matches (a single string or an array in the JSON)
         * @param model the branch model
         */
        public record Case(@NotNull List<String> when, @NotNull ItemModelNode model) {}

    }

    /**
     * A {@code minecraft:range_dispatch} node - a numeric property (scaled by {@link #scale}) picking
     * the highest {@link Entry} whose {@code threshold} is {@code <=} the scaled value, else
     * {@link #fallback}.
     *
     * @param property the dispatch property id
     * @param scale the multiplier applied to the property value before threshold comparison
     * @param target the {@code compass} target (spawn / lodestone), or empty string when absent
     * @param index the {@code custom_model_data} float-list index this dispatch reads ({@code 0} for other properties)
     * @param entries the threshold entries (any order; the walker picks the highest satisfied)
     * @param fallback the branch when no threshold is satisfied
     */
    record RangeDispatch(
        @NotNull String property, float scale, @NotNull String target, int index,
        @NotNull List<Entry> entries, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code range_dispatch} entry - the model to use when the scaled property value reaches
         * {@link #threshold}.
         *
         * @param threshold the lower bound (inclusive) on the scaled property value
         * @param model the branch model
         */
        public record Entry(float threshold, @NotNull ItemModelNode model) {}

    }

    /**
     * A {@code minecraft:composite} node - all children evaluated and their output concatenated.
     * Primary-leaf resolution takes the first child that yields a
     * model or special leaf.
     *
     * @param models the child nodes, in paint order
     */
    record Composite(@NotNull List<ItemModelNode> models) implements ItemModelNode {}

    /**
     * A {@code minecraft:special} leaf - a hardcoded render kind ({@code bed}, {@code shield},
     * {@code player_head}, {@code copper_golem_statue}, ...) that maps onto an existing render path,
     * carrying the {@code base} item model, the kind's inline fields, and the
     * {@link SpecialTransform}. Unknown kinds are diagnosed and dropped by {@link #resolveOrDrop()} -
     * the no-fallback contract for special nodes.
     *
     * <p>Every 26.1 vanilla special kind maps onto an existing dispatcher: {@code bed} / {@code chest}
     * / {@code shulker_box} / {@code banner} / {@code conduit} / {@code decorated_pot} render through
     * the block-entity bone geometry; {@code shield} / {@code head} / {@code player_head} through the
     * hardcoded {@code ItemRenderer} paths; {@code copper_golem_statue} / {@code trident} through their
     * special renderers.
     *
     * @param kind the special kind (the inner {@code model.type}, e.g. {@code minecraft:bed})
     * @param base the base item model id (e.g. {@code minecraft:item/white_bed})
     * @param fields the kind's inline string fields (e.g. {@code part}/{@code texture} for bed)
     * @param transform the special-node transformation, {@link SpecialTransform#IDENTITY} when absent
     */
    record Special(
        @NotNull String kind, @NotNull String base,
        @NotNull Map<String, String> fields, @NotNull SpecialTransform transform
    ) implements ItemModelNode {

        /** The special kinds vanilla 26.1 ships, each mapped onto an existing render path. */
        private static final @NotNull Set<String> KNOWN = Set.of(
            "bed", "chest", "shulker_box", "banner", "conduit", "decorated_pot",
            "shield", "head", "player_head", "copper_golem_statue", "trident");

        /**
         * Whether this leaf's kind maps onto an existing render path.
         *
         * @return whether this renderer knows how to dispatch the kind
         */
        public boolean isRenderable() {
            return isRenderable(this.kind);
        }

        /**
         * Whether the given special kind maps onto an existing render path.
         *
         * @param kind the special-node kind (the inner {@code model.type}, with or without the
         *     {@code minecraft:} prefix)
         * @return whether this renderer knows how to dispatch the kind
         */
        public static boolean isRenderable(@NotNull String kind) {
            return KNOWN.contains(strip(kind));
        }

        /**
         * Returns this leaf when its kind is renderable, else logs a pack-facing diagnostic and drops
         * it (empty) - the no-fallback contract for special nodes.
         *
         * @return this leaf when renderable, or empty (dropped with a diagnostic) for an unknown kind
         */
        public @NotNull Optional<Special> resolveOrDrop() {
            if (isRenderable()) return Optional.of(this);
            System.err.printf("Dropping item special-node of unknown kind '%s' (base '%s')%n", this.kind, this.base);
            return Optional.empty();
        }

        /** Strips a leading {@code minecraft:} namespace so kind matching accepts both id forms. */
        private static @NotNull String strip(@NotNull String kind) {
            int colon = kind.indexOf(':');
            return colon < 0 ? kind : kind.substring(colon + 1);
        }

    }

    /**
     * A {@code minecraft:bundle/selected_item} slot marker - the bundle-contents placeholder that
     * renders nothing under a neutral (no selected item) context. Rung-3 fills the slot.
     */
    record Bundle() implements ItemModelNode {}

    /**
     * The empty sentinel - an absent branch or an unknown node type. Renders nothing; there is no
     * fallback.
     */
    record Empty() implements ItemModelNode {

        /** The shared empty-node instance. */
        public static final @NotNull Empty INSTANCE = new Empty();

    }

    /**
     * Resolves this node against a context, walking the single branch that renders to its leaf. One
     * structural pass: a {@code condition} takes the branch its boolean property selects (unknown &rarr;
     * {@code on_false}); a {@code select} takes the first case whose key matches the context (no match
     * or unevaluable property &rarr; {@code fallback}); a {@code range_dispatch} takes the highest
     * threshold {@code <=} the scaled value (none &rarr; {@code fallback}); a {@code composite} takes
     * its first non-empty child; a {@code model} / {@code special} is a leaf; a {@code bundle} /
     * {@code empty} renders nothing.
     *
     * <p>The neutral {@link ItemModelContext#gui()} context resolves every vanilla tree to its fallback
     * branch, giving the derived model id and tint list.
     *
     * @param context the evaluation context
     * @return the resolved branch, {@link Resolution#NOTHING} when the branch renders nothing
     */
    default @NotNull Resolution resolve(@NotNull ItemModelContext context) {
        return switch (this) {
            case Model model -> new Resolution(Optional.of(model.model()), model.tints(), Optional.empty());
            case Condition condition ->
                (context.conditionValue(condition.property(), condition.component()) ? condition.onTrue() : condition.onFalse()).resolve(context);
            case Select select -> resolveSelect(select, context);
            case RangeDispatch range -> resolveRange(range, context);
            case Composite composite -> resolveComposite(composite, context);
            case Special special -> new Resolution(Optional.empty(), List.of(), Optional.of(special));
            case Bundle ignored -> Resolution.NOTHING;
            case Empty ignored -> Resolution.NOTHING;
        };
    }

    private static @NotNull Resolution resolveSelect(@NotNull Select select, @NotNull ItemModelContext context) {
        Optional<String> key = context.selectValue(select.property());
        if (key.isPresent()) {
            for (Select.Case option : select.cases())
                if (option.when().contains(key.get())) return option.model().resolve(context);
        }
        return select.fallback().resolve(context);
    }

    private static @NotNull Resolution resolveRange(@NotNull RangeDispatch range, @NotNull ItemModelContext context) {
        float scaled = range.scale() * context.rangeValue(range.property(), range.index());
        RangeDispatch.Entry best = null;
        for (RangeDispatch.Entry entry : range.entries())
            if (entry.threshold() <= scaled && (best == null || entry.threshold() > best.threshold())) best = entry;
        return best != null ? best.model().resolve(context) : range.fallback().resolve(context);
    }

    private static @NotNull Resolution resolveComposite(@NotNull Composite composite, @NotNull ItemModelContext context) {
        for (ItemModelNode child : composite.models()) {
            Resolution resolution = child.resolve(context);
            if (!resolution.isEmpty()) return resolution;
        }
        return Resolution.NOTHING;
    }

    /**
     * Returns how many distinct steps this tree's {@code minecraft:time} dispatch resolves over a day,
     * or empty when no branch of it dispatches on world time. This is what lets a caller ask for an
     * item to be animated without knowing that a clock happens to ship sixty-four faces.
     * <p>
     * The step count is one less than the threshold table's size: the final entry exists to wrap the
     * table's far end back onto its first model, so it repeats a step rather than adding one. Unlike
     * {@link #resolve}, this searches <b>every</b> branch rather than the one a context selects - a
     * time dispatch can sit behind a {@code select} whose property no offline render can evaluate,
     * which is exactly where the vanilla clock keeps its own.
     *
     * @return the number of steps a day resolves through, or empty when nothing dispatches on time
     */
    default @NotNull OptionalInt timeDispatchSteps() {
        return switch (this) {
            case RangeDispatch range -> {
                int steps = range.entries().size() - 1;
                if (isTimeProperty(range.property()) && steps > 1) yield OptionalInt.of(steps);
                yield firstTimeDispatch(Stream.concat(
                    range.entries().stream().map(RangeDispatch.Entry::model), Stream.of(range.fallback())));
            }
            case Condition condition -> firstTimeDispatch(Stream.of(condition.onTrue(), condition.onFalse()));
            case Select select -> firstTimeDispatch(Stream.concat(
                select.cases().stream().map(Select.Case::model), Stream.of(select.fallback())));
            case Composite composite -> firstTimeDispatch(composite.models().stream());
            case Model ignored -> OptionalInt.empty();
            case Special ignored -> OptionalInt.empty();
            case Bundle ignored -> OptionalInt.empty();
            case Empty ignored -> OptionalInt.empty();
        };
    }

    /** The first time-dispatch step count among a stream of branches, or empty when none carries one. */
    private static @NotNull OptionalInt firstTimeDispatch(@NotNull Stream<ItemModelNode> branches) {
        return branches.map(ItemModelNode::timeDispatchSteps)
            .filter(OptionalInt::isPresent)
            .findFirst()
            .orElseGet(OptionalInt::empty);
    }

    /** Whether a dispatch property is {@code minecraft:time}, accepting the unqualified id too. */
    private static boolean isTimeProperty(@NotNull String property) {
        int colon = property.indexOf(':');
        return (colon < 0 ? property : property.substring(colon + 1)).equals("time");
    }

    /**
     * The resolved branch: the primary model leaf id (if the branch is a plain model), the per-layer
     * tints from that branch, and the special leaf (if the branch is a hardcoded-render kind). At most
     * one of {@link #modelId()} / {@link #special()} is present; both empty means the branch renders
     * nothing.
     *
     * @param modelId the resolved plain-model id, or empty for a special / nothing branch
     * @param tints the per-layer tints from the resolved model branch, empty when untinted
     * @param special the resolved special leaf, or empty for a plain-model / nothing branch
     */
    record Resolution(
        @NotNull Optional<String> modelId,
        @NotNull List<LayerTint> tints,
        @NotNull Optional<Special> special
    ) {

        /** The empty resolution - a branch that renders nothing. */
        public static final @NotNull Resolution NOTHING = new Resolution(Optional.empty(), List.of(), Optional.empty());

        /**
         * Whether this resolution renders nothing (neither a model nor a special leaf).
         *
         * @return whether both the model and special leaves are absent
         */
        public boolean isEmpty() {
            return this.modelId.isEmpty() && this.special.isEmpty();
        }

    }

}
