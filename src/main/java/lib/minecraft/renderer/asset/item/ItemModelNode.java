package lib.minecraft.renderer.asset.item;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Quaternionf;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.SpecialModels;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * One node of a parsed {@code items/*.json} dispatch tree. The sealed hierarchy
 * mirrors the 26.1 node vocabulary: a {@link Model} leaf, the {@link Condition} / {@link Select} /
 * {@link RangeDispatch} dispatch nodes, {@link Composite} concatenation, a {@link Special}
 * hardcoded-render leaf, the {@link Bundle} selected-item slot marker, and an {@link Empty} sentinel
 * the parser substitutes for an absent branch or an unknown node type (renders nothing, no fallback).
 *
 * <p>Nodes are immutable records built once at pipeline time from the item definition JSON and walked
 * by {@link ItemModelContext#resolve(ItemModelNode)}. Absent branches are never {@code null} -
 * {@link Empty#INSTANCE} stands in - so resolution never dereferences a missing case.
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
    record Model(@NotNull String model, @NotNull ConcurrentList<LayerTint> tints) implements ItemModelNode {}

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
        @NotNull ConcurrentList<Case> cases, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code select} case - the model to use when the context's case key is one of
         * {@link #when}.
         *
         * @param when the case keys this branch matches (a single string or an array in the JSON)
         * @param model the branch model
         */
        public record Case(@NotNull ConcurrentList<String> when, @NotNull ItemModelNode model) {}

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
        @NotNull ConcurrentList<Entry> entries, @NotNull ItemModelNode fallback
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
    record Composite(@NotNull ConcurrentList<ItemModelNode> models) implements ItemModelNode {}

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
        @NotNull ConcurrentMap<String, String> fields, @NotNull SpecialTransform transform
    ) implements ItemModelNode {

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
            return SpecialModels.isRenderable(kind);
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
     * Returns how many distinct steps this tree's {@code minecraft:time} dispatch resolves over a day,
     * or empty when no branch of it dispatches on world time. This is what lets a caller ask for an
     * item to be animated without knowing that a clock happens to ship sixty-four faces.
     * <p>
     * The step count is one less than the threshold table's size: the final entry exists to wrap the
     * table's far end back onto its first model, so it repeats a step rather than adding one. Unlike
     * {@link ItemModelContext#resolve(ItemModelNode) the context's walk}, this searches <b>every</b>
     * branch rather than the one a context selects - a time dispatch can sit behind a {@code select}
     * whose property no offline render can evaluate, which is exactly where the vanilla clock keeps its
     * own.
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
        @NotNull ConcurrentList<LayerTint> tints,
        @NotNull Optional<Special> special
    ) {

        /** The empty resolution - a branch that renders nothing. */
        public static final @NotNull Resolution NOTHING =
            new Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.empty());

        /**
         * Whether this resolution renders nothing (neither a model nor a special leaf).
         *
         * @return whether both the model and special leaves are absent
         */
        public boolean isEmpty() {
            return this.modelId.isEmpty() && this.special.isEmpty();
        }

    }

    /**
     * A {@code special}-node {@code transformation} - vanilla's {@code com.mojang.math.Transformation}
     * decomposition ({@code items/player_head.json} et al.), a {@code T . Rleft . S . Rright} pose
     * carried in model space. Identity when the node declares no transformation.
     *
     * <p>It is parsed and held rather than applied, and that is deliberate: this renderer already poses
     * every subject the shipped non-identity transformations cover through a second, independent channel
     * - the {@code inventory} node on {@code block_models.json}, which reaches the render as a block
     * entity's presentation transform. The two carry the same pose in different units ({@code [0, 1]}
     * block units here against the {@code [0, 16]} authoring frame there), so applying this one as well
     * would pose every bed, banner, shulker box and head twice. Unifying the channels is a real change
     * with a real gate - the block sum - not a hookup.
     *
     * <p>The quaternions arrive as raw {@code [x, y, z, w]} components, so none of the Tait-Bryan
     * factory ordering in RENDERER-RULES.md 'JOML factories' applies to them.
     *
     * @param leftRotation the left rotation quaternion, {@code [x, y, z, w]}
     * @param rightRotation the right rotation quaternion, {@code [x, y, z, w]}
     * @param scale the per-axis scale, {@code [x, y, z]}
     * @param translation the translation, {@code [x, y, z]}
     */
    @EqualsAndHashCode
    record SpecialTransform(
        float @NotNull [] leftRotation,
        float @NotNull [] rightRotation,
        float @NotNull [] scale,
        float @NotNull [] translation
    ) {

        /** The identity transform - no rotation, unit scale, no translation. */
        public static final @NotNull SpecialTransform IDENTITY = new SpecialTransform(
            new float[]{ 0f, 0f, 0f, 1f },
            new float[]{ 0f, 0f, 0f, 1f },
            new float[]{ 1f, 1f, 1f },
            new float[]{ 0f, 0f, 0f }
        );

        /**
         * Whether this transform is the identity. Not the common case in shipped data: of the 123
         * {@code special} nodes vanilla 26.1 ships, 73 declare a non-identity transformation and the
         * other 50 declare none at all.
         *
         * @return whether every component equals {@link #IDENTITY}
         */
        public boolean isIdentity() {
            return this.equals(IDENTITY);
        }

        /**
         * Composes the {@code T . Rleft . S . Rright} decomposition into one model-space matrix. The
         * chain makes vanilla's {@code Transformation} compose calls in the same order - translate,
         * rotate by the left quaternion, scale, rotate by the right quaternion - and each fluent
         * {@link Matrix4f} op post-multiplies as JOML's in-place op does, so the result applies to a
         * column vector as {@code M * v}: the right rotation reaches a vertex first and the translation
         * last. Composes the decomposition for a caller that applies one; nothing on the render path
         * does, for the reason on the class doc.
         *
         * @return the composed model-space pre-transform matrix
         */
        public @NotNull Matrix4f toMatrix() {
            return Matrix4f.IDENTITY
                .translate(this.translation[0], this.translation[1], this.translation[2])
                .rotate(quaternion(this.leftRotation))
                .scale(this.scale[0], this.scale[1], this.scale[2])
                .rotate(quaternion(this.rightRotation));
        }

        /** Builds a {@link Quaternionf} from a {@code [x, y, z, w]} component array. */
        private static @NotNull Quaternionf quaternion(float @NotNull [] q) {
            return new Quaternionf(q[0], q[1], q[2], q[3]);
        }

    }

}
