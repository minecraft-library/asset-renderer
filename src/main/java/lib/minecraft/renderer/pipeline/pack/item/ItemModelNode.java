package lib.minecraft.renderer.pipeline.pack.item;

import lib.minecraft.renderer.asset.Item.LayerTint;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * One node of a parsed {@code items/*.json} dispatch tree (05-models.md §3). The sealed hierarchy
 * mirrors the 26.1 node vocabulary: a {@link Model} leaf, the {@link Condition} / {@link Select} /
 * {@link RangeDispatch} dispatch nodes, {@link Composite} concatenation, a {@link Special}
 * hardcoded-render leaf, the {@link Bundle} selected-item slot marker, and an {@link Empty} sentinel
 * the parser substitutes for an absent branch or an unknown node type (renders nothing, no fallback).
 *
 * <p>Nodes are immutable records built once at pipeline time by {@link ItemModelParser} and evaluated
 * against an {@link ItemModelContext} by {@link ItemModelWalker}. Absent branches are never
 * {@code null} - {@link Empty#INSTANCE} stands in - so the walker never dereferences a missing case.
 */
public sealed interface ItemModelNode
    permits ItemModelNode.Model, ItemModelNode.Condition, ItemModelNode.Select,
    ItemModelNode.RangeDispatch, ItemModelNode.Composite, ItemModelNode.Special,
    ItemModelNode.Bundle, ItemModelNode.Empty {

    /**
     * A {@code minecraft:model} leaf - a resolved model reference plus the per-layer tints declared
     * on this branch (05-models.md §3.3: tints ride the branch actually rendered).
     *
     * @param model the namespaced model id (e.g. {@code minecraft:item/bow})
     * @param tints the per-layer tint rules, index {@code N} applying to {@code layerN}; empty when untinted
     */
    record Model(@NotNull String model, @NotNull List<LayerTint> tints) implements ItemModelNode {}

    /**
     * A {@code minecraft:condition} node - a boolean property selecting {@link #onTrue} or
     * {@link #onFalse}. The optional {@link #component} names the NBT component a
     * {@code has_component} condition tests (rung-3, parse-and-hold).
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
        record Case(@NotNull List<String> when, @NotNull ItemModelNode model) {}

    }

    /**
     * A {@code minecraft:range_dispatch} node - a numeric property (scaled by {@link #scale}) picking
     * the highest {@link Entry} whose {@code threshold} is {@code <=} the scaled value, else
     * {@link #fallback}.
     *
     * @param property the dispatch property id
     * @param scale the multiplier applied to the property value before threshold comparison
     * @param target the {@code compass} target (spawn / lodestone), or empty string when absent
     * @param entries the threshold entries (any order; the walker picks the highest satisfied)
     * @param fallback the branch when no threshold is satisfied
     */
    record RangeDispatch(
        @NotNull String property, float scale, @NotNull String target,
        @NotNull List<Entry> entries, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code range_dispatch} entry - the model to use when the scaled property value reaches
         * {@link #threshold}.
         *
         * @param threshold the lower bound (inclusive) on the scaled property value
         * @param model the branch model
         */
        record Entry(float threshold, @NotNull ItemModelNode model) {}

    }

    /**
     * A {@code minecraft:composite} node - all children evaluated and their output concatenated
     * (05-models.md §3.3). The walker's primary-leaf resolution takes the first child that yields a
     * model or special leaf.
     *
     * @param models the child nodes, in paint order
     */
    record Composite(@NotNull List<ItemModelNode> models) implements ItemModelNode {}

    /**
     * A {@code minecraft:special} leaf - a hardcoded render kind ({@code bed}, {@code shield},
     * {@code player_head}, {@code copper_golem_statue}, ...) that maps onto an existing render path
     * (05-models.md §3.4), carrying the {@code base} item model, the kind's inline fields, and the
     * {@link SpecialTransform}. Unknown kinds are diagnosed and dropped by the walker.
     *
     * @param kind the special kind (the inner {@code model.type}, e.g. {@code minecraft:bed})
     * @param base the base item model id (e.g. {@code minecraft:item/white_bed})
     * @param fields the kind's inline string fields (e.g. {@code part}/{@code texture} for bed)
     * @param transform the special-node transformation, {@link SpecialTransform#IDENTITY} when absent
     */
    record Special(
        @NotNull String kind, @NotNull String base,
        @NotNull Map<String, String> fields, @NotNull SpecialTransform transform
    ) implements ItemModelNode {}

    /**
     * A {@code minecraft:bundle/selected_item} slot marker - the bundle-contents placeholder that
     * renders nothing under a neutral (no selected item) context. Rung-3 fills the slot.
     */
    record Bundle() implements ItemModelNode {}

    /**
     * The empty sentinel - an absent branch or an unknown node type. Renders nothing; there is no
     * fallback (spine §4.5 no-fallback invariant).
     */
    record Empty() implements ItemModelNode {

        /** The shared empty-node instance. */
        public static final @NotNull Empty INSTANCE = new Empty();

    }

}
