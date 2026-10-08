package lib.minecraft.renderer.asset.item;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.DataComponents;
import lib.minecraft.renderer.vanilla.DecodedComponent;
import lib.minecraft.renderer.vanilla.SpecialModels;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * One node of a parsed {@code items/*.json} dispatch tree. The sealed hierarchy
 * mirrors the 26.1 node vocabulary: a {@link Model} leaf, the {@link Condition} / {@link Select} /
 * {@link RangeDispatch} dispatch nodes, {@link Composite} concatenation, a {@link Special}
 * hardcoded-render leaf, the {@link Bundle} selected-item slot marker, the {@link Empty} node that
 * renders nothing, and the {@link Absent} sentinel the parser substitutes for a fallback a select or
 * range dispatch does not declare, which also roots a definition the loader refused.
 *
 * <p>Nodes are immutable records built once at pipeline time from the item definition JSON and walked
 * by {@link ItemModelContext#resolve(ItemModelNode)}. No child is ever {@code null}: a branch vanilla
 * requires fails the parse when it is missing, and a fallback it leaves optional is
 * {@link Absent#INSTANCE} - so resolution never dereferences a missing case.
 *
 * <p>Two operands are types of their own: the {@link ComponentPredicate} a {@code minecraft:component}
 * condition tests, and the {@link SpecialTransform} a special leaf carries.
 */
public sealed interface ItemModelNode
    permits ItemModelNode.Model, ItemModelNode.Condition, ItemModelNode.Select,
    ItemModelNode.RangeDispatch, ItemModelNode.Composite, ItemModelNode.Special,
    ItemModelNode.Bundle, ItemModelNode.Empty, ItemModelNode.Absent {

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
     * {@link #onFalse}. Two properties carry operands: {@code has_component} names the component it
     * tests in {@link #component} and may set {@link #ignoreDefault}, and {@code component} carries
     * the test it applies, decoded, in {@link #predicate}. Both are evaluated against the render-time
     * component map by {@link ItemModelContext#conditionValue(Condition)}.
     *
     * @param property the dispatch property id, as the definition writes it
     * @param component the component a {@code has_component} condition tests, or empty string when absent
     * @param ignoreDefault whether a {@code has_component} condition asks if the stack's own patch names the component, a removal included, rather than whether the stack holds it
     * @param predicate the decoded test of a {@code minecraft:component} condition, empty for every other property
     * @param onTrue the branch when the property is true
     * @param onFalse the branch when the property is false or unevaluable
     */
    record Condition(
        @NotNull String property, @NotNull String component, boolean ignoreDefault,
        @NotNull Optional<ComponentPredicate> predicate,
        @NotNull ItemModelNode onTrue, @NotNull ItemModelNode onFalse
    ) implements ItemModelNode {

        /**
         * Constructs a condition that carries no component test and reads a {@code has_component}
         * target with the item's defaults counted - the shape every property but {@code component}
         * takes.
         *
         * @param property the dispatch property id, as the definition writes it
         * @param component the component a {@code has_component} condition tests, or empty string when absent
         * @param onTrue the branch when the property is true
         * @param onFalse the branch when the property is false or unevaluable
         */
        public Condition(
            @NotNull String property, @NotNull String component,
            @NotNull ItemModelNode onTrue, @NotNull ItemModelNode onFalse
        ) {
            this(property, component, false, Optional.empty(), onTrue, onFalse);
        }

    }

    /**
     * A {@code minecraft:select} node - a case key selecting a matching {@link Case}, else
     * {@link #fallback}. {@link #blockStateProperty} names the block-state property a
     * {@code block_state} select keys on (unevaluable in an icon context, so it takes the fallback),
     * {@link #component} the component a {@code minecraft:component} select reads its key from, and
     * {@link #decoded} that component decoded, which reduces the stack's value to the key its cases hold.
     *
     * @param property the dispatch property id, as the definition writes it
     * @param blockStateProperty the block-state property for a {@code block_state} select, or empty string
     * @param component the component a {@code minecraft:component} select keys on, as written, or empty string for every other property
     * @param decoded the component a {@code minecraft:component} select keys on, decoded, or empty for every other property and for a component this renderer does not decode
     * @param cases the ordered cases, no value repeated across or within them
     * @param fallback the branch when no case matches or the property is unevaluable, {@link Absent#INSTANCE} when the definition declares none
     */
    record Select(
        @NotNull String property, @NotNull String blockStateProperty, @NotNull String component,
        @NotNull Optional<DecodedComponent> decoded, @NotNull ConcurrentList<Case> cases, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code select} case - the model to use when the context's case key is one of
         * {@link #when}.
         *
         * <p>Each key is the case value's canonical spelling: an identifier-keyed property's value
         * qualified to {@code minecraft:} when bare, a value of a {@link DecodedComponent} reduced to
         * its key, and every other value as written. On a vanilla property or a decoded component,
         * two keys are equal exactly when vanilla finds the two values equal; a mod's property and a
         * component this renderer does not decode compare their values as written.
         *
         * @param when the case keys this branch matches (a single value or a list of them in the JSON)
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
     * @param fallback the branch when no threshold is satisfied, {@link Absent#INSTANCE} when the definition declares none
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

        /**
         * Returns how many distinct steps this dispatch resolves over a day, where it dispatches on
         * {@code minecraft:time}. This is what lets a caller ask for an item to be animated without
         * knowing that a clock happens to ship sixty-four faces.
         * <p>
         * The step count is one less than the threshold table's size: the final entry exists to wrap the
         * table's far end back onto its first model, so it repeats a step rather than adding one. A table
         * of fewer than two steps sweeps nothing and answers empty.
         *
         * @return the number of steps a day resolves through, or empty for a dispatch on another property and a table too short to sweep
         */
        public @NotNull OptionalInt timeSteps() {
            int steps = this.entries.size() - 1;
            boolean time = ResourceId.vanillaPath(this.property).filter("time"::equals).isPresent();
            return time && steps > 1 ? OptionalInt.of(steps) : OptionalInt.empty();
        }

    }

    /**
     * A {@code minecraft:composite} node - all children evaluated and their output concatenated, each
     * drawn over the ones before it. The walk answers what every child draws, in order, as the layers
     * of one {@link Resolution}, through {@link Resolution#composite(List)}.
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
     * The {@code minecraft:empty} node, which renders nothing and has no fallback. A node whose type
     * sits in a namespace other than vanilla's - a mod's node type this renderer cannot read - parses
     * to it as well.
     */
    record Empty() implements ItemModelNode {

        /** The shared empty-node instance. */
        public static final @NotNull Empty INSTANCE = new Empty();

    }

    /**
     * The absent-fallback sentinel - the {@code fallback} of a {@code select} or {@code range_dispatch}
     * that declares none, and the root of a definition the loader refused. It is a node of its own
     * rather than {@link Empty} because vanilla answers the two differently: an explicit
     * {@code minecraft:empty} draws nothing, where an absent fallback bakes as the missing item model,
     * and so does a definition that fails to load. The walk resolves it to
     * {@link Resolution#MISSING}.
     */
    record Absent() implements ItemModelNode {

        /** The shared absent-fallback instance. */
        public static final @NotNull Absent INSTANCE = new Absent();

    }

    /**
     * The resolved branch: the primary model leaf id (if the branch is a plain model), the per-layer
     * tints from that branch, and the special leaf (if the branch is a hardcoded-render kind). At most
     * one of {@link #modelId()} / {@link #special()} is present. With both empty the branch is either
     * vanilla's missing item model, where {@link #missing} is set, or a branch that renders nothing.
     *
     * <p>A {@code composite} draws every child that draws anything, one over another in order, as
     * vanilla's does. The first layer it draws is this resolution's own leaf, which is what the item
     * index and the block-item projection read, and the rest are its {@link #later} layers;
     * {@link #layers()} answers all of them.
     *
     * @param modelId the resolved plain-model id, or empty for a special, missing or nothing branch
     * @param tints the per-layer tints from the resolved model branch, empty when untinted
     * @param special the resolved special leaf, or empty for a plain-model, missing or nothing branch
     * @param composed whether the walk reached this branch through a {@code composite}, whose other children draw beside it
     * @param missing whether the branch is vanilla's missing item model - a {@code select} or {@code range_dispatch} that declares no fallback, or a definition the loader refused
     * @param later the layers a {@code composite} draws over this one, in paint order, each one leaf reached through the composite and holding no later layers of its own; empty where no composite reached the branch or none of its other children draws
     */
    record Resolution(
        @NotNull Optional<String> modelId,
        @NotNull ConcurrentList<LayerTint> tints,
        @NotNull Optional<Special> special,
        boolean composed,
        boolean missing,
        @NotNull ConcurrentList<Resolution> later
    ) {

        /** The empty resolution - a branch that renders nothing. */
        public static final @NotNull Resolution NOTHING =
            new Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.empty(), false, false);

        /** The missing item model - the branch an absent fallback and a refused definition resolve to. */
        public static final @NotNull Resolution MISSING =
            new Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.empty(), false, true);

        /**
         * Constructs a resolution that draws one leaf and no later layers.
         *
         * @param modelId the resolved plain-model id, or empty for a special, missing or nothing branch
         * @param tints the per-layer tints from the resolved model branch, empty when untinted
         * @param special the resolved special leaf, or empty for a plain-model, missing or nothing branch
         * @param composed whether the walk reached this branch through a {@code composite}
         * @param missing whether the branch is vanilla's missing item model
         */
        public Resolution(
            @NotNull Optional<String> modelId, @NotNull ConcurrentList<LayerTint> tints,
            @NotNull Optional<Special> special, boolean composed, boolean missing
        ) {
            this(modelId, tints, special, composed, missing, Concurrent.newUnmodifiableList());
        }

        /**
         * Composes what a {@code composite}'s children resolve to into the one branch that draws them
         * all: every layer of every child that draws, in the composite's order, each marked
         * {@link #composed}, the first as the resolution's own leaf and the rest as its {@link #later}
         * layers. A child that is itself a composite gives up its layers in place, and where no child
         * draws, the composite renders nothing.
         *
         * @param children what each child resolves to, in the composite's order
         * @return the composed resolution
         */
        public static @NotNull Resolution composite(@NotNull List<Resolution> children) {
            List<Resolution> layers = children.stream()
                .flatMap(child -> child.layers().stream())
                .map(Resolution::throughComposite)
                .toList();
            if (layers.isEmpty()) return NOTHING.throughComposite();

            Resolution first = layers.getFirst();
            return new Resolution(first.modelId, first.tints, first.special, true, first.missing,
                Concurrent.newUnmodifiableList(layers.subList(1, layers.size())));
        }

        /**
         * Whether this resolution renders nothing - neither a model, a special leaf nor the missing item
         * model.
         *
         * @return whether the model and special leaves are absent and the branch is not the missing item model
         */
        public boolean isEmpty() {
            return this.modelId.isEmpty() && this.special.isEmpty() && !this.missing;
        }

        /**
         * Returns every layer this branch draws, in paint order: its own leaf, then each of its
         * {@link #later} layers. A branch that renders nothing draws no layer.
         *
         * @return the layers, each one leaf holding no later layers of its own
         */
        public @NotNull ConcurrentList<Resolution> layers() {
            if (this.isEmpty()) return Concurrent.newUnmodifiableList();
            if (this.later.isEmpty()) return Concurrent.newUnmodifiableList(this);

            return Stream.concat(
                    Stream.of(new Resolution(this.modelId, this.tints, this.special, this.composed, this.missing)),
                    this.later.stream())
                .collect(Concurrent.toUnmodifiableList());
        }

        /**
         * Returns this resolution as reached through a {@code composite} - the same branch, marked
         * {@link #composed}.
         *
         * @return this resolution with {@link #composed} set
         */
        public @NotNull Resolution throughComposite() {
            return this.composed ? this : new Resolution(this.modelId, this.tints, this.special, true, this.missing, this.later);
        }

        /**
         * Returns the block model this branch draws on its own - its model leaf, where that names a
         * block model and no {@code composite} reached it. A composite draws every child beside the
         * one this resolution holds, so a {@link #composed} branch is never one block model. It is the
         * test an item's neutral walk is put to for the item to take a block model as its inventory
         * icon.
         *
         * @return the block model id, or empty for a composed, special or empty branch and for a leaf naming a model outside {@code block/}
         */
        public @NotNull Optional<String> blockModel() {
            if (this.composed) return Optional.empty();
            return this.modelId.filter(VanillaPaths::isBlockModelRef);
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

    /**
     * The decoded test of a {@code minecraft:component} condition - vanilla's data component predicate,
     * named by the node's {@code predicate} id and decoded from its {@code value} once, at load, so a
     * walk down a long ladder of them compares and never parses.
     *
     * <p>{@code minecraft:custom_data} is {@link CustomData}, the fourteen other predicate types vanilla
     * registers are {@link Unevaluated}, and an id naming a data component rather than a predicate type
     * is the {@link Present} form, a mod's id among them.
     *
     * <p>Each test reads the stack's component map, keyed by qualified component id, in the 26.1 patch
     * form, where a component the stack removes is written as {@value DataComponents#REMOVED} before its
     * id.
     */
    sealed interface ComponentPredicate
        permits ComponentPredicate.CustomData, ComponentPredicate.Present, ComponentPredicate.Unevaluated {

        /**
         * The qualified id the condition names this test by.
         *
         * @return the predicate id, qualified to {@code minecraft:} when bare
         */
        @NotNull String id();

        /**
         * Whether a stack's components pass this test.
         *
         * @param components the stack's component map keyed by qualified component id, or empty when the caller supplies no stack
         * @return whether the stack passes
         */
        boolean matches(@NotNull Optional<CompoundTag> components);

        /**
         * The {@code minecraft:custom_data} test - vanilla's {@code NbtUtils.compareNbt(expected, actual,
         * true)} over the stack's custom data.
         *
         * <p>The expected compound keeps vanilla's typing, so a JSON {@code {"x":1}} and an SNBT
         * {@code "{x:1}"} are two different tests: the JSON {@code 1} converts to a byte, the narrowest
         * tag that holds it, where the unsuffixed SNBT {@code 1} is an int.
         *
         * <p>Matching is a partial compare, in vanilla's order. The same tag, or no expected tag, passes,
         * and no actual tag fails. Two tags of different ids fail, the id deciding rather than the class,
         * so a borrowed tree meets a decoded one. A compound passes when every expected key passes
         * against the actual tag of that name, extra actual keys ignored. A list passes when every
         * expected element passes against some actual element, in any order and one actual element
         * serving any number of them, except that an empty expected list passes only an empty actual one.
         * Every other tag, the array tags included, passes on value equality.
         *
         * <p>A stack with no custom data is tested as an empty compound, as vanilla reads one, so
         * {@code {}} passes every stack and no stack at all. Custom data held as a string tag is read as
         * SNBT, and any other tag passes nothing.
         *
         * @param expected the decoded compound the stack's custom data is compared against
         */
        record CustomData(@NotNull CompoundTag expected) implements ComponentPredicate {

            /** {@inheritDoc} */
            @Override
            public @NotNull String id() {
                return DataComponents.CUSTOM_DATA;
            }

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                Optional<Tag<?>> held = components.map(map -> map.get(DataComponents.CUSTOM_DATA));
                if (held.isEmpty()) return compare(this.expected, CompoundTag.EMPTY);
                return switch (held.get()) {
                    case CompoundTag actual -> compare(this.expected, actual);
                    case StringTag snbt -> parsed(snbt.getValue()).filter(actual -> compare(this.expected, actual)).isPresent();
                    default -> false;
                };
            }

            /** Parses an SNBT compound, or empty when the string is not one. */
            private static @NotNull Optional<CompoundTag> parsed(@NotNull String snbt) {
                try {
                    return Optional.of(NbtFactory.fromSnbt(snbt));
                } catch (RuntimeException unreadable) {
                    return Optional.empty();
                }
            }

            /** Vanilla's {@code NbtUtils.compareNbt} with {@code partial} set, gated on the tag id rather than the class. */
            private static boolean compare(@Nullable Tag<?> expected, @Nullable Tag<?> actual) {
                if (expected == actual || expected == null) return true;
                if (actual == null || expected.getId() != actual.getId()) return false;

                if (expected instanceof CompoundTag wanted) {
                    CompoundTag held = (CompoundTag) actual;
                    if (held.size() < wanted.size()) return false;
                    for (Map.Entry<String, Tag<?>> entry : wanted.entrySet())
                        if (!compare(entry.getValue(), held.get(entry.getKey()))) return false;
                    return true;
                }

                if (expected instanceof ListTag<?> wanted) {
                    ListTag<?> held = (ListTag<?>) actual;
                    if (wanted.isEmpty()) return held.isEmpty();
                    if (held.size() < wanted.size()) return false;
                    for (Tag<?> element : wanted)
                        if (held.stream().noneMatch(candidate -> compare(element, candidate))) return false;
                    return true;
                }

                return expected.equals(actual);
            }

        }

        /**
         * The any-component presence form - a {@code predicate} naming a data component rather than a
         * predicate type, which passes when the stack holds that component and does not remove it. Its
         * {@code value} is an object whose members are not read. It reads the components it is handed,
         * which the walk fills with the one default it knows, {@code minecraft:item_model}: any other
         * component the item holds by default, which vanilla counts, is unknown here.
         *
         * @param id the qualified id of the component the stack must hold
         */
        record Present(@NotNull String id) implements ComponentPredicate {

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                return components.filter(map -> map.containsKey(this.id) && !map.containsKey(DataComponents.REMOVED + this.id)).isPresent();
            }

        }

        /**
         * One of the fourteen registered predicate types besides {@code custom_data} - each reads item
         * state or registry contents this renderer does not model, so it passes nothing and the walk
         * takes {@code on_false}. Its {@code value} is not decoded.
         *
         * @param id the qualified predicate type id
         */
        record Unevaluated(@NotNull String id) implements ComponentPredicate {

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                return false;
            }

        }

    }

}
