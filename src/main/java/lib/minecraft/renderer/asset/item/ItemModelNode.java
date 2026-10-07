package lib.minecraft.renderer.asset.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.ByteArrayTag;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.DoubleTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntArrayTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.LongArrayTag;
import lib.minecraft.nbt.tag.LongTag;
import lib.minecraft.nbt.tag.NumericalTag;
import lib.minecraft.nbt.tag.ShortTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.SpecialModels;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
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
 * <p>The component tests a definition carries are decoded once, at load, onto the nodes that hold
 * them: a {@code minecraft:component} condition's {@link ComponentPredicate}, and a component select's
 * case values as the canonical keys {@link SelectComponent} reduces them to.
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
     * {@code block_state} select keys on (unevaluable in an icon context, so it takes the fallback), and
     * {@link #component} the component a {@code minecraft:component} select reads its key from.
     *
     * @param property the dispatch property id, as the definition writes it
     * @param blockStateProperty the block-state property for a {@code block_state} select, or empty string
     * @param component the component a {@code minecraft:component} select keys on, as written, or empty string for every other property
     * @param cases the ordered cases, no value repeated across or within them
     * @param fallback the branch when no case matches or the property is unevaluable, {@link Absent#INSTANCE} when the definition declares none
     */
    record Select(
        @NotNull String property, @NotNull String blockStateProperty, @NotNull String component,
        @NotNull ConcurrentList<Case> cases, @NotNull ItemModelNode fallback
    ) implements ItemModelNode {

        /**
         * One {@code select} case - the model to use when the context's case key is one of
         * {@link #when}.
         *
         * <p>Each key is the case value's canonical spelling: an identifier-keyed property's value
         * qualified to {@code minecraft:} when bare, a component {@link SelectComponent} models reduced
         * to its key, and every other value as written. On a vanilla property or a modelled component,
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
            case Absent ignored -> OptionalInt.empty();
        };
    }

    /**
     * Reads a vocabulary id - a node type, a dispatch property - the way vanilla parses an identifier,
     * and answers its path when the namespace is vanilla's. An id with no colon, or with nothing before
     * its first colon, is in the {@code minecraft} namespace, as vanilla's parse puts it there; any other
     * namespace is a mod's, and its ids name nothing in vanilla's vocabulary even when the path matches
     * one, so {@code hplus:using_item} is not {@code using_item}.
     *
     * @param id the id as a definition writes it
     * @return the id's path under {@code minecraft:}, or empty when the id names another namespace
     */
    static @NotNull Optional<String> vanillaPath(@NotNull String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || id.substring(0, colon).equals("minecraft")) return Optional.of(id.substring(colon + 1));
        return Optional.empty();
    }

    /**
     * Qualifies an id the way vanilla parses an identifier: one with no namespace, or an empty one, is
     * in {@code minecraft:}, and any other is returned as written.
     *
     * @param id the id as a definition or a component map writes it
     * @return the fully qualified id
     */
    static @NotNull String qualify(@NotNull String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return "minecraft:" + id;
        return colon == 0 ? "minecraft" + id : id;
    }

    /**
     * Reads a data component id the way vanilla's registry codec reads one, refusing an id in the
     * vanilla namespace that names no component vanilla 26.1 registers. An id in any other namespace is
     * a mod's, which this renderer cannot check, so it passes and the walk reads it from the stack as
     * written.
     *
     * @param id the component id as a definition writes it
     * @return the id, as written
     * @throws JsonParseException if the id is bare or {@code minecraft:} and names no registered component, which drops the whole definition
     */
    static @NotNull String componentId(@NotNull String id) {
        if (vanillaPath(id).filter(path -> !ComponentPredicate.Present.COMPONENTS.contains(path)).isPresent())
            throw new JsonParseException(String.format("Unknown data component '%s'", id));
        return id;
    }

    /** The first time-dispatch step count among a stream of branches, or empty when none carries one. */
    private static @NotNull OptionalInt firstTimeDispatch(@NotNull Stream<ItemModelNode> branches) {
        return branches.map(ItemModelNode::timeDispatchSteps)
            .filter(OptionalInt::isPresent)
            .findFirst()
            .orElseGet(OptionalInt::empty);
    }

    /** Whether a dispatch property is {@code minecraft:time}, read namespace-exact by {@link #vanillaPath(String)}. */
    private static boolean isTimeProperty(@NotNull String property) {
        return vanillaPath(property).filter("time"::equals).isPresent();
    }

    /** Whether a compound is vanilla's list-element wrapper - one entry, keyed by the empty string. */
    private static boolean isWrapper(@NotNull CompoundTag compound) {
        return compound.size() == 1 && compound.containsKey("");
    }

    /**
     * The resolved branch: the primary model leaf id (if the branch is a plain model), the per-layer
     * tints from that branch, and the special leaf (if the branch is a hardcoded-render kind). At most
     * one of {@link #modelId()} / {@link #special()} is present. With both empty the branch is either
     * vanilla's missing item model, where {@link #missing} is set, or a branch that renders nothing.
     *
     * @param modelId the resolved plain-model id, or empty for a special, missing or nothing branch
     * @param tints the per-layer tints from the resolved model branch, empty when untinted
     * @param special the resolved special leaf, or empty for a plain-model, missing or nothing branch
     * @param composed whether the walk reached this branch through a {@code composite}, whose other children draw beside it in vanilla
     * @param missing whether the branch is vanilla's missing item model - a {@code select} or {@code range_dispatch} that declares no fallback, or a definition the loader refused
     */
    record Resolution(
        @NotNull Optional<String> modelId,
        @NotNull ConcurrentList<LayerTint> tints,
        @NotNull Optional<Special> special,
        boolean composed,
        boolean missing
    ) {

        /** The empty resolution - a branch that renders nothing. */
        public static final @NotNull Resolution NOTHING =
            new Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.empty(), false, false);

        /** The missing item model - the branch an absent fallback and a refused definition resolve to. */
        public static final @NotNull Resolution MISSING =
            new Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.empty(), false, true);

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
         * Returns this resolution as reached through a {@code composite} - the same branch, marked
         * {@link #composed}.
         *
         * @return this resolution with {@link #composed} set
         */
        public @NotNull Resolution throughComposite() {
            return this.composed ? this : new Resolution(this.modelId, this.tints, this.special, true, this.missing);
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
     * <p>The id is tried as one of vanilla's fifteen predicate types first and as a data component id
     * second, which is vanilla's order: {@code minecraft:custom_data} is {@link CustomData}, the fourteen
     * other registered types are {@link Unevaluated}, and a component id is the {@link Present} form. An
     * id in the vanilla namespace that is neither refuses the definition, as vanilla's codec does; one
     * in any other namespace is a mod's, which this renderer cannot check, and is read as the presence
     * form.
     *
     * <p>Each test reads the stack's component map, keyed by qualified component id, in the 26.1 patch
     * form, where a component the stack removes is written as {@value #REMOVED} before its id.
     */
    sealed interface ComponentPredicate
        permits ComponentPredicate.CustomData, ComponentPredicate.Present, ComponentPredicate.Unevaluated {

        /** The prefix a 26.1 component patch writes before the id of a component the stack removes. */
        @NotNull String REMOVED = "!";

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
         * Decodes a component condition's {@code predicate} and {@code value} members as vanilla's codec
         * decodes them.
         *
         * @param predicate the {@code predicate} id as written
         * @param value the {@code value} member, or {@code null} when the condition has none
         * @return the decoded test
         * @throws JsonParseException if the value is absent or does not decode, or the id is in the vanilla namespace and names neither a predicate type nor a registered component, which drops the whole definition
         */
        static @NotNull ComponentPredicate of(@NotNull String predicate, @Nullable JsonElement value) {
            String id = qualify(predicate);
            if (value == null || value.isJsonNull())
                throw new JsonParseException(String.format("Component predicate '%s' has no value", id));
            if (id.equals(CustomData.ID)) return new CustomData(CustomData.decode(value));
            if (Unevaluated.TYPES.contains(id)) return new Unevaluated(id);
            String component = componentId(id);
            if (!value.isJsonObject())
                throw new JsonParseException(String.format("Component predicate '%s' tests presence and takes an object value, not '%s'", component, value));
            return new Present(component);
        }

        /**
         * The {@code minecraft:custom_data} test - vanilla's {@code NbtUtils.compareNbt(expected, actual,
         * true)} over the stack's custom data.
         *
         * <p>Decoding keeps vanilla's typing, so a JSON {@code {"x":1}} and an SNBT {@code "{x:1}"} are
         * two different tests. The string is SNBT, where an unsuffixed {@code 1} is an int. The object
         * converts as DFU's {@code JsonOps.convertTo} converts one: a string to a string tag, a boolean to
         * a byte, an integral number to the narrowest of byte, short, int and long that holds it - so
         * {@code 1} is a byte - any other number to a float when that is exact and a double otherwise, an
         * array to a plain list and an object to a compound. A list whose elements differ in type is
         * written as vanilla's binary form writes one, each element that is not already a compound
         * wrapped as the one entry of a compound keyed by the empty string. A JSON {@code null}, which
         * vanilla cannot convert, fails the definition.
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

            /** The predicate id, which is also the id of the component the test reads. */
            private static final @NotNull String ID = "minecraft:custom_data";

            /** {@inheritDoc} */
            @Override
            public @NotNull String id() {
                return ID;
            }

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                Optional<Tag<?>> held = components.map(map -> map.get(ID));
                if (held.isEmpty()) return compare(this.expected, CompoundTag.EMPTY);
                return switch (held.get()) {
                    case CompoundTag actual -> compare(this.expected, actual);
                    case StringTag snbt -> parsed(snbt.getValue()).filter(actual -> compare(this.expected, actual)).isPresent();
                    default -> false;
                };
            }

            /** Decodes a custom data {@code value}: an SNBT string first, then a JSON object, the order vanilla's lenient codec tries them in. */
            private static @NotNull CompoundTag decode(@NotNull JsonElement value) {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    return parsed(value.getAsString()).orElseThrow(() -> new JsonParseException(
                        String.format("Custom data '%s' is not an SNBT compound", value.getAsString())));
                }
                if (value.isJsonObject()) return (CompoundTag) convert(value);
                throw new JsonParseException(String.format("Custom data is an SNBT string or an object, not '%s'", value));
            }

            /** Parses an SNBT compound, or empty when the string is not one. */
            private static @NotNull Optional<CompoundTag> parsed(@NotNull String snbt) {
                try {
                    return Optional.of(NbtFactory.fromSnbt(snbt));
                } catch (RuntimeException unreadable) {
                    return Optional.empty();
                }
            }

            /** Converts one JSON value to the tag DFU's {@code JsonOps.convertTo} builds for it. */
            private static @NotNull Tag<?> convert(@NotNull JsonElement json) {
                if (json.isJsonObject()) {
                    CompoundTag compound = new CompoundTag();
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet())
                        compound.put(entry.getKey(), convert(entry.getValue()));
                    return compound;
                }
                if (json.isJsonArray()) return list(json.getAsJsonArray());
                if (json.isJsonNull()) throw new JsonParseException("Custom data holds a null, which converts to no tag");
                JsonPrimitive primitive = json.getAsJsonPrimitive();
                if (primitive.isString()) return new StringTag(primitive.getAsString());
                if (primitive.isBoolean()) return new ByteTag((byte) (primitive.getAsBoolean() ? 1 : 0));
                BigDecimal value = primitive.getAsBigDecimal();
                try {
                    long integral = value.longValueExact();
                    if ((byte) integral == integral) return new ByteTag((byte) integral);
                    if ((short) integral == integral) return new ShortTag((short) integral);
                    if ((int) integral == integral) return new IntTag((int) integral);
                    return new LongTag(integral);
                } catch (ArithmeticException fractional) {
                    double real = value.doubleValue();
                    return (float) real == real ? new FloatTag((float) real) : new DoubleTag(real);
                }
            }

            /** Converts a JSON array to a plain list, wrapping the elements as vanilla's binary form does when their types differ. */
            private static @NotNull ListTag<Tag<?>> list(@NotNull JsonArray array) {
                ConcurrentList<Tag<?>> elements = array.asList()
                    .stream()
                    .map(CustomData::convert)
                    .collect(Concurrent.toUnmodifiableList());
                boolean mixed = elements.stream().map(Tag::getId).distinct().count() > 1;
                ListTag<Tag<?>> list = new ListTag<>(elements.size());
                elements.forEach(element -> list.add(mixed ? wrapped(element) : element));
                return list;
            }

            /** One element of a mixed list: a compound that is not itself a wrapper as it is, anything else wrapped under the empty key. */
            private static @NotNull Tag<?> wrapped(@NotNull Tag<?> element) {
                if (element instanceof CompoundTag compound && !isWrapper(compound)) return compound;
                CompoundTag wrapper = new CompoundTag();
                wrapper.put("", element);
                return wrapper;
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

            /**
             * The data components vanilla 26.1 registers, by path under {@code minecraft:} - the
             * vanilla-namespace ids a presence test, a {@code has_component} condition and a component
             * select may name.
             */
            private static final @NotNull ConcurrentSet<String> COMPONENTS = Concurrent.newUnmodifiableSet(
                "custom_data", "max_stack_size", "max_damage", "damage", "unbreakable", "use_effects", "custom_name",
                "minimum_attack_charge", "damage_type", "item_name", "item_model", "lore", "rarity", "enchantments",
                "can_place_on", "can_break", "attribute_modifiers", "custom_model_data", "tooltip_display",
                "repair_cost", "creative_slot_lock", "enchantment_glint_override", "intangible_projectile", "food",
                "consumable", "use_remainder", "use_cooldown", "damage_resistant", "tool", "weapon", "attack_range",
                "enchantable", "equippable", "repairable", "glider", "tooltip_style", "death_protection",
                "blocks_attacks", "piercing_weapon", "kinetic_weapon", "swing_animation", "additional_trade_cost",
                "stored_enchantments", "dye", "dyed_color", "map_color", "map_id", "map_decorations",
                "map_post_processing", "charged_projectiles", "bundle_contents", "potion_contents",
                "potion_duration_scale", "suspicious_stew_effects", "writable_book_content", "written_book_content",
                "trim", "debug_stick_state", "entity_data", "bucket_entity_data", "block_entity_data", "instrument",
                "provides_trim_material", "ominous_bottle_amplifier", "jukebox_playable", "provides_banner_patterns",
                "recipes", "lodestone_tracker", "firework_explosion", "fireworks", "profile", "note_block_sound",
                "banner_patterns", "base_color", "pot_decorations", "container", "block_state", "bees", "lock",
                "container_loot", "break_sound", "villager/variant", "wolf/variant", "wolf/sound_variant",
                "wolf/collar", "fox/variant", "salmon/size", "parrot/variant", "tropical_fish/pattern",
                "tropical_fish/base_color", "tropical_fish/pattern_color", "mooshroom/variant", "rabbit/variant",
                "pig/variant", "pig/sound_variant", "cow/variant", "cow/sound_variant", "chicken/variant",
                "chicken/sound_variant", "zombie_nautilus/variant", "frog/variant", "horse/variant",
                "painting/variant", "llama/variant", "axolotl/variant", "cat/variant", "cat/sound_variant",
                "cat/collar", "sheep/color", "shulker/color");

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                return components.filter(map -> map.containsKey(this.id) && !map.containsKey(REMOVED + this.id)).isPresent();
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

            /** The predicate types vanilla 26.1 registers besides {@code minecraft:custom_data}. */
            private static final @NotNull ConcurrentSet<String> TYPES = Concurrent.newUnmodifiableSet(
                "minecraft:damage", "minecraft:enchantments", "minecraft:stored_enchantments",
                "minecraft:potion_contents", "minecraft:container", "minecraft:bundle_contents",
                "minecraft:firework_explosion", "minecraft:fireworks", "minecraft:writable_book_content",
                "minecraft:written_book_content", "minecraft:attribute_modifiers", "minecraft:trim",
                "minecraft:jukebox_playable", "minecraft:villager/variant");

            /** {@inheritDoc} */
            @Override
            public boolean matches(@NotNull Optional<CompoundTag> components) {
                return false;
            }

        }

    }

    /**
     * The components a {@code minecraft:component} select keys on that this renderer decodes. Vanilla
     * matches a stack's value against the case values by the decoded values' equality, and here both
     * sides reduce to one canonical key - a case value at load, through {@link #cases(JsonElement)}, and
     * the stack's value at the walk, through {@link #key(Optional)} - so two values are equal exactly
     * when their keys are.
     *
     * <ul>
     *   <li><b>{@link #DYED_COLOR}</b> - an RGB integer, keyed by its decimal spelling.</li>
     *   <li><b>{@link #CUSTOM_NAME}</b> - a text component, compared on its contents, then its style,
     *       then its siblings in order.</li>
     *   <li><b>{@link #LORE}</b> - a list of text components, compared line by line.</li>
     *   <li><b>{@link #ITEM_MODEL}</b> - an identifier, keyed qualified to {@code minecraft:}. A stack
     *       whose patch neither sets nor removes it holds its item's own id, as every 26.1 item does
     *       by default, which the walk fills in before the key is read.</li>
     * </ul>
     *
     * <p>A text component reads the same way from a case value's JSON and from a stack's 26.1 NBT. A
     * string is a plain literal with no style; a list is its first element with the rest appended as
     * siblings; an object picks its contents by vanilla's order - {@code text}, then
     * {@code translate}, {@code keybind}, {@code score}, {@code selector}, {@code nbt} and the
     * {@code object} forms, or the kind its {@code type} names - and carries its siblings in
     * {@code extra}. A literal compares on its text, and every other contents kind on its members. The
     * five style flags are three-state, so {@code italic:false} is not an absent italic. A colour
     * compares by its spelling, a named colour by name and a hex one by its six-digit value, because
     * vanilla's select map hashes the name and so misses where a named and a hex spelling of one colour
     * meet. Shadow colour, click and hover events, insertion and font compare on their members. On the
     * NBT side a flag is a numeric tag, and a list element wrapped as the one entry of a compound keyed
     * by the empty string - vanilla's binary form of a list whose elements differ in type - is read as
     * the element it wraps.
     *
     * <p>Any other component's select is unevaluable here and takes its fallback.
     */
    enum SelectComponent {

        /** {@code minecraft:dyed_color} - an integer, or three floats folded to one as vanilla's {@code ARGB.colorFromFloat} folds them. */
        DYED_COLOR("minecraft:dyed_color"),

        /** {@code minecraft:custom_name} - a text component. */
        CUSTOM_NAME("minecraft:custom_name"),

        /** {@code minecraft:lore} - a list of at most {@code 256} text components. */
        LORE("minecraft:lore"),

        /** {@code minecraft:item_model} - the identifier of the item definition a stack draws. */
        ITEM_MODEL("minecraft:item_model");

        /** The text style members a canonical style writes after the colour and the flags, in vanilla's field order. */
        private static final @NotNull ConcurrentList<String> STYLE_MEMBERS =
            Concurrent.newUnmodifiableList("click_event", "hover_event", "insertion", "font");

        /** The three-state style flags, in vanilla's field order. */
        private static final @NotNull ConcurrentList<String> FLAGS =
            Concurrent.newUnmodifiableList("bold", "italic", "underlined", "strikethrough", "obfuscated");

        /** The named text colours vanilla parses, its chat formatting colours. */
        private static final @NotNull ConcurrentSet<String> NAMED_COLOURS = Concurrent.newUnmodifiableSet(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

        /** The text contents kinds a {@code type} member may name. */
        private static final @NotNull ConcurrentSet<String> CONTENT_KINDS = Concurrent.newUnmodifiableSet(
            "text", "translatable", "keybind", "score", "selector", "nbt", "object");

        /** The most lines a lore holds. */
        private static final int MAX_LORE_LINES = 256;

        /** The qualified component id. */
        private final @NotNull String id;

        SelectComponent(@NotNull String id) {
            this.id = id;
        }

        /**
         * Finds the modelled component a select names.
         *
         * @param componentId the select's {@code component} id, qualified to {@code minecraft:} when bare
         * @return the modelled component, or empty when this renderer does not decode it
         */
        public static @NotNull Optional<SelectComponent> of(@NotNull String componentId) {
            String id = qualify(componentId);
            return Arrays.stream(values()).filter(component -> component.id.equals(id)).findFirst();
        }

        /**
         * Decodes a case's {@code when} into the keys it matches, as vanilla's
         * {@code nonEmptyList(compactListCodec(...))} reads it: an array is tried as a list of values
         * first, and read as one value only when one of its elements does not decode. So
         * {@code [1.0, 0.0, 0.0]} is three dyed colours and {@code [[1.0, 0.0, 0.0]]} one, and a lore
         * {@code [{...}, {...}]} is one lore of two lines.
         *
         * @param when the case's {@code when} member
         * @return the keys, one per value
         * @throws JsonParseException if the list is empty or a value does not decode, which drops the whole definition
         */
        public @NotNull ConcurrentList<String> cases(@NotNull JsonElement when) {
            if (!when.isJsonArray()) return Concurrent.newUnmodifiableList(this.caseKey(when));
            JsonArray values = when.getAsJsonArray();
            if (values.isEmpty()) throw new JsonParseException(String.format("Select on '%s' has an empty 'when' list", this.id));
            try {
                return values.asList().stream().map(this::caseKey).collect(Concurrent.toUnmodifiableList());
            } catch (JsonParseException notAList) {
                return Concurrent.newUnmodifiableList(this.caseKey(values));
            }
        }

        /**
         * Reduces a stack's value of this component to the key its case values are compared with.
         *
         * @param components the stack's component map keyed by qualified component id, or empty when the caller supplies no stack
         * @return the key, or empty when the stack does not hold the component or its value does not decode
         */
        public @NotNull Optional<String> key(@NotNull Optional<CompoundTag> components) {
            return components.map(map -> map.get(this.id)).flatMap(this::stackKey);
        }

        /**
         * Reads the {@code minecraft:dyed_color} a stack's patch sets, decoded as vanilla's
         * {@code RGB_COLOR_CODEC} decodes it - the colour a {@code minecraft:dye} tint source reads
         * before its default.
         *
         * @param components the stack's component map keyed by qualified component id, or empty when the caller supplies no stack
         * @return the colour, or empty when the patch does not set the component or its value does not decode
         */
        public static @NotNull OptionalInt dyedColor(@NotNull Optional<CompoundTag> components) {
            Optional<Tag<?>> value = components.map(map -> map.get(DYED_COLOR.id));
            if (value.isEmpty()) return OptionalInt.empty();

            try {
                return OptionalInt.of(rgb(json(value.get())));
            } catch (JsonParseException unreadable) {
                return OptionalInt.empty();
            }
        }

        /** Decodes one case value, from JSON. */
        private @NotNull String caseKey(@NotNull JsonElement value) {
            return switch (this) {
                case DYED_COLOR -> Integer.toString(rgb(value));
                case CUSTOM_NAME -> text(value, false).toString();
                case LORE -> lore(value, false).toString();
                case ITEM_MODEL -> identifier(value);
            };
        }

        /** Decodes the stack's value, from 26.1 NBT, or empty when it does not decode. */
        private @NotNull Optional<String> stackKey(@NotNull Tag<?> value) {
            JsonElement json = json(value);
            try {
                return Optional.of(switch (this) {
                    case DYED_COLOR -> Integer.toString(rgb(json));
                    case CUSTOM_NAME -> text(json, true).toString();
                    case LORE -> lore(json, true).toString();
                    case ITEM_MODEL -> identifier(json);
                });
            } catch (JsonParseException unreadable) {
                return Optional.empty();
            }
        }

        /** Decodes an identifier as vanilla's {@code Identifier.CODEC} reads one: a string, qualified to {@code minecraft:} when bare. */
        private static @NotNull String identifier(@NotNull JsonElement value) {
            if (!isString(value)) throw new JsonParseException(String.format("An identifier is a string, not '%s'", value));
            return qualify(value.getAsString());
        }

        /** Decodes a dyed colour as vanilla's {@code RGB_COLOR_CODEC} does: any number's int value, else three floats. */
        private static int rgb(@NotNull JsonElement value) {
            if (isNumber(value)) return value.getAsNumber().intValue();
            if (value.isJsonArray() && value.getAsJsonArray().size() == 3
                && value.getAsJsonArray().asList().stream().allMatch(SelectComponent::isNumber)) {
                JsonArray channels = value.getAsJsonArray();
                return 0xFF << 24
                    | (channel(channels.get(0).getAsFloat()) & 0xFF) << 16
                    | (channel(channels.get(1).getAsFloat()) & 0xFF) << 8
                    | channel(channels.get(2).getAsFloat()) & 0xFF;
            }
            throw new JsonParseException(String.format("A dyed colour is a number or three floats, not '%s'", value));
        }

        /** One float channel as vanilla's {@code ARGB.as8BitChannel} scales it - {@code Mth.floor(value * 255)}. */
        private static int channel(float value) {
            float scaled = value * 255f;
            int truncated = (int) scaled;
            return scaled < truncated ? truncated - 1 : truncated;
        }

        /** Decodes a lore - a list of at most {@value #MAX_LORE_LINES} text components - to its canonical form. */
        private static @NotNull JsonArray lore(@NotNull JsonElement value, boolean nbt) {
            if (!value.isJsonArray()) throw new JsonParseException(String.format("A lore is a list of lines, not '%s'", value));
            JsonArray lines = value.getAsJsonArray();
            if (lines.size() > MAX_LORE_LINES)
                throw new JsonParseException(String.format("A lore holds at most %d lines, not %d", MAX_LORE_LINES, lines.size()));
            JsonArray canonical = new JsonArray();
            lines.forEach(line -> canonical.add(text(line, nbt)));
            return canonical;
        }

        /**
         * Decodes a text component to its canonical form, {@code [contents, style, siblings]}: the
         * contents a literal's text or another kind's members, the style an object of the members it
         * sets in a fixed order, and the siblings the canonical forms of the components it appends.
         */
        private static @NotNull JsonArray text(@NotNull JsonElement value, boolean nbt) {
            if (isString(value)) return canonicalText(new JsonPrimitive(value.getAsString()), new JsonObject(), new JsonArray());
            if (value.isJsonArray()) {
                JsonArray list = value.getAsJsonArray();
                if (list.isEmpty()) throw new JsonParseException("A text component list is empty");
                JsonArray first = text(list.get(0), nbt);
                JsonArray siblings = first.get(2).getAsJsonArray();
                for (int index = 1; index < list.size(); index++)
                    siblings.add(text(list.get(index), nbt));
                return first;
            }
            if (!value.isJsonObject()) throw new JsonParseException(String.format("A text component is a string, a list or an object, not '%s'", value));

            JsonObject component = value.getAsJsonObject();
            JsonArray siblings = new JsonArray();
            Optional<JsonElement> extra = member(component, "extra");
            if (extra.isPresent()) {
                if (!extra.get().isJsonArray() || extra.get().getAsJsonArray().isEmpty())
                    throw new JsonParseException(String.format("A text component's extra is a non-empty list, not '%s'", extra.get()));
                extra.get().getAsJsonArray().forEach(sibling -> siblings.add(text(sibling, nbt)));
            }
            return canonicalText(contents(component), style(component, nbt), siblings);
        }

        /** Assembles one canonical text component. */
        private static @NotNull JsonArray canonicalText(@NotNull JsonElement contents, @NotNull JsonObject style, @NotNull JsonArray siblings) {
            JsonArray canonical = new JsonArray();
            canonical.add(contents);
            canonical.add(style);
            canonical.add(siblings);
            return canonical;
        }

        /** Decodes an object component's contents: a literal's text as a string, any other kind as its kind and canonical members. */
        private static @NotNull JsonElement contents(@NotNull JsonObject component) {
            Optional<JsonElement> type = member(component, "type");
            String kind;
            if (type.isPresent()) {
                if (!isString(type.get()) || !CONTENT_KINDS.contains(type.get().getAsString()))
                    throw new JsonParseException(String.format("Unknown text component type '%s'", type.get()));
                kind = type.get().getAsString();
            } else {
                kind = inferredKind(component).orElseThrow(() -> new JsonParseException(
                    String.format("A text component names no contents: '%s'", component)));
            }

            if (kind.equals("text")) {
                return member(component, "text").filter(SelectComponent::isString)
                    .map(text -> (JsonElement) new JsonPrimitive(text.getAsString()))
                    .orElseThrow(() -> new JsonParseException(String.format("A text component has no string 'text': '%s'", component)));
            }
            JsonObject members = new JsonObject();
            component.entrySet()
                .stream()
                .filter(entry -> !entry.getKey().equals("type") && !entry.getKey().equals("extra"))
                .filter(entry -> !entry.getKey().equals("color") && !entry.getKey().equals("shadow_color"))
                .filter(entry -> !FLAGS.contains(entry.getKey()) && !STYLE_MEMBERS.contains(entry.getKey()))
                .forEach(entry -> members.add(entry.getKey(), entry.getValue()));
            JsonObject contents = new JsonObject();
            contents.addProperty("kind", kind);
            contents.add("members", canonical(members));
            return contents;
        }

        /** The contents kind vanilla's fuzzy match picks for an object with no {@code type}: the first whose member it carries. */
        private static @NotNull Optional<String> inferredKind(@NotNull JsonObject component) {
            if (member(component, "text").filter(SelectComponent::isString).isPresent()) return Optional.of("text");
            if (member(component, "translate").isPresent()) return Optional.of("translatable");
            if (member(component, "keybind").isPresent()) return Optional.of("keybind");
            if (member(component, "score").isPresent()) return Optional.of("score");
            if (member(component, "selector").isPresent()) return Optional.of("selector");
            if (member(component, "nbt").isPresent()) return Optional.of("nbt");
            if (Stream.of("object", "sprite", "player").anyMatch(key -> member(component, key).isPresent())) return Optional.of("object");
            return Optional.empty();
        }

        /** Decodes an object component's style: the members it sets, in vanilla's field order, each in canonical form. */
        private static @NotNull JsonObject style(@NotNull JsonObject component, boolean nbt) {
            JsonObject style = new JsonObject();
            member(component, "color").ifPresent(color -> style.addProperty("color", colour(color)));
            member(component, "shadow_color").ifPresent(shadow -> style.add("shadow_color", canonical(shadow)));
            for (String name : FLAGS)
                member(component, name).ifPresent(value -> style.addProperty(name, flag(value, nbt)));
            for (String key : STYLE_MEMBERS)
                member(component, key).ifPresent(value -> style.add(key, canonical(value)));
            return style;
        }

        /** Decodes a text colour to its spelling: a named colour by name, a hex one as {@code #RRGGBB}. */
        private static @NotNull String colour(@NotNull JsonElement value) {
            if (!isString(value)) throw new JsonParseException(String.format("A text colour is a string, not '%s'", value));
            String colour = value.getAsString();
            if (!colour.startsWith("#")) {
                if (NAMED_COLOURS.contains(colour)) return colour;
                throw new JsonParseException(String.format("Unknown text colour '%s'", colour));
            }
            try {
                int rgb = Integer.parseInt(colour.substring(1), 16);
                if (rgb >= 0 && rgb <= 0xFFFFFF) return String.format("#%06X", rgb);
            } catch (NumberFormatException ignored) {
                // Falls through to the refusal below, as an out-of-range value does.
            }
            throw new JsonParseException(String.format("Invalid text colour '%s'", colour));
        }

        /** Decodes a style flag: a JSON boolean, or on the NBT side any numeric tag, non-zero meaning set. */
        private static boolean flag(@NotNull JsonElement value, boolean nbt) {
            if (nbt && isNumber(value)) return value.getAsNumber().byteValue() != 0;
            if (!nbt && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            throw new JsonParseException(String.format("A text style flag is a boolean, not '%s'", value));
        }

        /**
         * Reduces a member this renderer compares without decoding to one spelling shared by JSON and
         * NBT: objects with their keys sorted, a number by its value and a boolean as {@code 1} or
         * {@code 0}, as both decode to the same number wherever vanilla reads one.
         */
        private static @NotNull JsonElement canonical(@NotNull JsonElement value) {
            if (value.isJsonObject()) {
                JsonObject canonical = new JsonObject();
                value.getAsJsonObject().entrySet()
                    .stream()
                    .filter(entry -> !entry.getValue().isJsonNull())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.add(entry.getKey(), canonical(entry.getValue())));
                return canonical;
            }
            if (value.isJsonArray()) {
                JsonArray canonical = new JsonArray();
                value.getAsJsonArray().forEach(element -> canonical.add(canonical(element)));
                return canonical;
            }
            if (value.isJsonNull() || isString(value)) return value;
            if (value.getAsJsonPrimitive().isBoolean()) return new JsonPrimitive(value.getAsBoolean() ? BigDecimal.ONE : BigDecimal.ZERO);
            try {
                return new JsonPrimitive(new BigDecimal(value.getAsNumber().toString()).stripTrailingZeros());
            } catch (NumberFormatException notFinite) {
                return new JsonPrimitive(value.getAsNumber().toString());
            }
        }

        /**
         * Views a 26.1 NBT value as JSON, so one decoder reads both sides: a string tag as a string, a
         * numeric tag as its number, a compound as an object, a list or array tag as an array, and a
         * list element vanilla wrapped under the empty key as the element it wraps.
         */
        private static @NotNull JsonElement json(@NotNull Tag<?> tag) {
            return switch (tag) {
                case StringTag string -> new JsonPrimitive(string.getValue());
                case NumericalTag<?> number -> new JsonPrimitive(number.getValue());
                case CompoundTag compound -> {
                    JsonObject object = new JsonObject();
                    for (Map.Entry<String, Tag<?>> entry : compound.entrySet())
                        object.add(entry.getKey(), json(entry.getValue()));
                    yield object;
                }
                case ListTag<?> list -> {
                    JsonArray array = new JsonArray();
                    for (Tag<?> element : list)
                        array.add(json(element instanceof CompoundTag wrapper && isWrapper(wrapper) ? wrapper.get("") : element));
                    yield array;
                }
                case ByteArrayTag bytes -> {
                    JsonArray array = new JsonArray();
                    for (byte value : bytes.getValue()) array.add(value);
                    yield array;
                }
                case IntArrayTag ints -> {
                    JsonArray array = new JsonArray();
                    for (int value : ints.getValue()) array.add(value);
                    yield array;
                }
                case LongArrayTag longs -> {
                    JsonArray array = new JsonArray();
                    for (long value : longs.getValue()) array.add(value);
                    yield array;
                }
                default -> JsonNull.INSTANCE;
            };
        }

        /** A member of an object, absent when missing or {@code null}, as vanilla's map read treats a null. */
        private static @NotNull Optional<JsonElement> member(@NotNull JsonObject object, @NotNull String key) {
            return Optional.ofNullable(object.get(key)).filter(value -> !value.isJsonNull());
        }

        /** Whether a value is a string. */
        private static boolean isString(@NotNull JsonElement value) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
        }

        /** Whether a value is a number. */
        private static boolean isNumber(@NotNull JsonElement value) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
        }

    }

}
