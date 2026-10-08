package lib.minecraft.renderer.request;

import dev.simplified.collection.Concurrent;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.DataComponents;
import lib.minecraft.renderer.vanilla.DecodedComponent;
import lib.minecraft.renderer.vanilla.SunAngle;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The immutable evaluation context that walks an item-definition tree - a fixed set of
 * neutral GUI defaults plus the handful of caller
 * overrides an icon renderer can honestly supply (trim material, clock time, compass angle, the
 * stack's components), mirroring how {@code EntityOptions} carries
 * {@code state}/{@code collarColor}/{@code age}.
 *
 * <p>{@link #resolve(ItemModelTree)} walks a tree to the branch that renders, and every dispatch
 * property a vanilla tree branches on resolves through one of three accessors -
 * {@link #conditionValue(ItemModelNode.Condition)} (booleans), {@link #selectValue(ItemModelNode.Select)}
 * (case keys), {@link #rangeValue(String, int)} (numeric thresholds). A property this context has no
 * value for is <b>unevaluable</b>: the walk takes the {@code on_false} / no-case-match /
 * {@code fallback} branch, which is the Catharsis degradation contract. Property ids are read
 * namespace-exact, as vanilla parses an identifier: a bare or {@code minecraft:} id names vanilla's
 * property, and an id in any other namespace - a mod's - is unevaluable even where its path spells one
 * of vanilla's. The default {@link #gui()} context leaves every caller override neutral, so it
 * resolves each vanilla tree to its fallback branch - except where a property has one honest answer
 * whatever the caller ({@code display_context} is the display the render draws, the GUI for
 * {@link #gui()}, and {@link #DIMENSION_OVERWORLD the dimension} always the overworld), which is
 * answered rather than degraded. {@link #timeDispatchSteps(ItemModelNode)} follows the same branches
 * to the time table a derived item animation counts its frames from.
 *
 * <p>The component tests read {@link #components}, the stack's 26.1 component patch. A
 * {@code minecraft:component} condition applies its decoded
 * {@link ItemModelNode.ComponentPredicate predicate}, {@code has_component} asks whether the stack holds
 * the component, and a component select reduces the stack's value to the key its cases were decoded to,
 * through the {@link DecodedComponent} its node carries. With no map supplied, every test reads a stack
 * with no components: {@code has_component} is false, a {@code custom_data} test compares against an
 * empty compound - so a {@code {}} test passes - and a component select takes its fallback. Of the components
 * an item holds by default one is known here, {@code minecraft:item_model}, which every 26.1 item holds
 * as its own id, so a test of it reads {@link #itemId} wherever the patch neither sets nor removes it.
 * Every other default is unknown, so for those only what the patch writes counts.
 *
 * <p>An item render takes the patch, and the item id, from the caller's {@link ItemContext} stack
 * wherever the context it walks at carries none of its own, so one stack reaches the walk whether or
 * not the caller also supplies a context; {@link #withComponents(CompoundTag)} and
 * {@link #withItemId(String)} set them explicitly, and those win.
 *
 * @param displayContext the {@code minecraft:display_context} case key; {@code "gui"} for an icon, {@code "thirdperson_righthand"} for a held render
 * @param usingItem the {@code minecraft:using_item} flag; {@code false} renders bow unpulled (today's output)
 * @param broken the {@code minecraft:broken} flag; {@code false}
 * @param trimMaterial the {@code minecraft:trim_material} case key, qualified to {@code minecraft:} when bare, or empty to take the fallback (today's output)
 * @param time the {@code minecraft:time} range input; {@code 0} selects clock frame 0
 * @param compassAngle the {@code minecraft:compass} range input; {@code 0} selects the neutral compass frame
 * @param customModelData an explicit {@code custom_model_data} float override that wins over the component tree, or empty to read it from {@link #components}
 * @param components the stack's component patch (an nbt-factory {@code CompoundTag} keyed by qualified component id, e.g. {@code minecraft:custom_data}, a removed component keyed {@code !minecraft:<id>}), or empty when the caller supplies no stack, which every component test reads as a stack with no components
 * @param itemId the stack's item id, qualified to {@code minecraft:} when bare - the {@code minecraft:item_model} the item holds by default - or empty when the caller supplies no stack, or one naming no item
 */
@Parity(claim = "asset-layer")
public record ItemModelContext(
    @NotNull String displayContext,
    boolean usingItem,
    boolean broken,
    @NotNull Optional<String> trimMaterial,
    float time,
    float compassAngle,
    @NotNull Optional<Float> customModelData,
    @NotNull Optional<CompoundTag> components,
    @NotNull Optional<String> itemId
) {

    /** The component every 26.1 item holds by default as its own id. */
    private static final @NotNull String ITEM_MODEL = "minecraft:item_model";

    /** The GUI display-context key every icon renders at. */
    public static final @NotNull String DISPLAY_CONTEXT_GUI = "gui";

    /** The third-person right-hand display-context key a held render resolves at. */
    public static final @NotNull String DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND = "thirdperson_righthand";

    /**
     * The dimension every icon renders as if held in - the {@code minecraft:context_dimension} case key.
     * <p>
     * An icon renderer has no world to read a dimension from, but leaving the property unevaluable is
     * not the neutral choice it looks like: a tree branching on it would take its <b>fallback</b>, and
     * vanilla writes that branch for the dimensions an item misbehaves in rather than as a degradation
     * path. The clock is the case in point - outside the overworld its needle spins at random, so its
     * fallback dispatches on a random source while only the overworld case reads the daytime this
     * renderer computes. Pinning the overworld renders the item as it is normally seen and keeps the
     * branch matched to the input; a caller wanting the off-world look would need a dimension override,
     * which nothing asks for.
     */
    public static final @NotNull String DIMENSION_OVERWORLD = "minecraft:overworld";

    /**
     * The neutral GUI context, held as one instance. Every component is immutable here - the one
     * component whose type is not, {@link #components}, is empty on this context - so a shared
     * instance carries no state a caller could reach. Nothing compares a context by identity either
     * (the fast path at {@link #isNeutral()} and the render memo both go through the record's equals),
     * so sharing changes no answer.
     */
    private static final @NotNull ItemModelContext GUI = new ItemModelContext(DISPLAY_CONTEXT_GUI,
        false, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.empty(), Optional.empty());

    /** Qualifies {@link #trimMaterial} and {@link #itemId} to {@code minecraft:} where bare, once, as the context is built. */
    public ItemModelContext {
        trimMaterial = trimMaterial.map(material -> ResourceId.parse(material).id());
        itemId = itemId.map(id -> ResourceId.parse(id).id());
    }

    /**
     * Constructs a context that reads no item id. An item render gives it the id of the stack it draws,
     * and {@link #withItemId(String)} gives it one explicitly.
     *
     * @param displayContext the {@code minecraft:display_context} case key
     * @param usingItem the {@code minecraft:using_item} flag
     * @param broken the {@code minecraft:broken} flag
     * @param trimMaterial the {@code minecraft:trim_material} case key, or empty to take the fallback
     * @param time the {@code minecraft:time} range input
     * @param compassAngle the {@code minecraft:compass} range input
     * @param customModelData an explicit {@code custom_model_data} float override, or empty to read it from the component patch
     * @param components the stack's component patch, or empty when the caller supplies no stack
     */
    public ItemModelContext(
        @NotNull String displayContext, boolean usingItem, boolean broken, @NotNull Optional<String> trimMaterial,
        float time, float compassAngle, @NotNull Optional<Float> customModelData, @NotNull Optional<CompoundTag> components
    ) {
        this(displayContext, usingItem, broken, trimMaterial, time, compassAngle, customModelData, components, Optional.empty());
    }

    /**
     * The neutral GUI context: {@code display_context = gui} and every caller override left at its
     * default so each vanilla tree resolves to its fallback branch.
     *
     * @return the neutral GUI evaluation context
     */
    public static @NotNull ItemModelContext gui() {
        return GUI;
    }

    /**
     * Whether this context is the neutral {@link #gui()} default - the fast path where the render may
     * reuse the pipeline-baked item rather than draw what the walk lands on.
     *
     * @return whether every field equals the neutral GUI default
     */
    public boolean isNeutral() {
        return this.equals(gui());
    }

    /**
     * Returns this context viewed at an animation tick - a copy whose {@code minecraft:time} input is
     * the {@link SunAngle sun angle} that many ticks after noon, with every other override carried
     * over untouched. This is what makes a time-driven tree (the clock's) resolve to a different face
     * on each frame of an animated bake.
     *
     * <p>Tick {@code 0} samples noon, where the angle is exactly {@code 0} - so the neutral
     * {@link #gui()} context is returned unchanged there and keeps its fast path, and frame {@code 0}
     * of any animated render depicts the same instant as a still one. Ticks advance world time from
     * that anchor, wrapping every {@link SunAngle#TICKS_PER_DAY day}.
     *
     * <p>Any caller-supplied {@link #time} is replaced: an animated render drives the day from its own
     * schedule, so a manual time override only applies to a still. The
     * {@link #compassAngle} is deliberately left alone - a compass needle is a bearing from its holder
     * to a target, not a function of the clock, so no passage of time moves it.
     *
     * @param tick the animation tick, counted in game ticks from noon
     * @return this context with its time input resolved at the tick
     */
    public @NotNull ItemModelContext atTick(int tick) {
        return new ItemModelContext(this.displayContext, this.usingItem, this.broken, this.trimMaterial,
            SunAngle.at(SunAngle.NOON_TICK + (long) tick), this.compassAngle, this.customModelData, this.components, this.itemId);
    }

    /**
     * Returns this context viewed at a display context - a copy whose {@code minecraft:display_context}
     * input is the given key, with every other input carried over untouched. The neutral
     * {@link #gui()} context viewed at {@link #DISPLAY_CONTEXT_GUI} equals itself and keeps its fast
     * path.
     *
     * @param displayContext the {@code minecraft:display_context} case key to resolve at
     * @return this context at that display context
     */
    public @NotNull ItemModelContext withDisplayContext(@NotNull String displayContext) {
        return new ItemModelContext(displayContext, this.usingItem, this.broken, this.trimMaterial,
            this.time, this.compassAngle, this.customModelData, this.components, this.itemId);
    }

    /**
     * Returns this context reading a stack's component patch - a copy whose {@link #components} is a
     * deep copy of the given compound, with every other input carried over untouched.
     * <p>
     * The copy is what keeps a render's answer the caller's at the call: a render memoises the item it
     * resolves per context, keyed on the context's value, so a compound the caller went on mutating
     * would move that key mid-render. Copying also materialises a lazily decoded tree once, here,
     * rather than on the first lookup that hashes it.
     *
     * @param components the stack's component patch, keyed by qualified component id
     * @return this context reading that patch
     */
    public @NotNull ItemModelContext withComponents(@NotNull CompoundTag components) {
        return new ItemModelContext(this.displayContext, this.usingItem, this.broken, this.trimMaterial,
            this.time, this.compassAngle, this.customModelData, Optional.of(copy(components)), this.itemId);
    }

    /**
     * Returns this context reading a stack of an item - a copy whose {@link #itemId} is the given id,
     * qualified to {@code minecraft:} when bare, with every other input carried over untouched.
     *
     * @param itemId the stack's item id
     * @return this context reading that item
     */
    public @NotNull ItemModelContext withItemId(@NotNull String itemId) {
        return new ItemModelContext(this.displayContext, this.usingItem, this.broken, this.trimMaterial,
            this.time, this.compassAngle, this.customModelData, this.components, Optional.of(itemId));
    }

    /**
     * Returns this context reading no stack - a copy whose {@link #components} and {@link #itemId} are
     * empty, so that it reads neither the patch nor the default the item id stands for, with every
     * other input carried over untouched, or this context itself where it reads neither already. A
     * walk whose branch no component test chose answers the same here as at the context carrying the
     * stack, which is how a render proceeds at it.
     *
     * @return this context without its component patch and item id
     */
    public @NotNull ItemModelContext withoutComponents() {
        if (this.components.isEmpty() && this.itemId.isEmpty()) return this;
        return new ItemModelContext(this.displayContext, this.usingItem, this.broken, this.trimMaterial,
            this.time, this.compassAngle, this.customModelData, Optional.empty(), Optional.empty());
    }

    /**
     * Resolves a {@code condition} node's boolean property from the property id alone. Only the
     * properties an icon can honestly evaluate without further inputs are wired ({@code using_item},
     * {@code broken}); {@code has_component} and {@code component} need the operands their node carries
     * (see {@link #conditionValue(ItemModelNode.Condition)}), and every other live gameplay flag
     * ({@code damaged}, {@code fishing_rod/cast}, ...) reads {@code false}, so the walker takes the
     * {@code on_false} branch.
     *
     * @param property the node's {@code property} id, bare or {@code minecraft:}-qualified for one of vanilla's
     * @return the boolean value, {@code false} when unevaluable
     */
    public boolean conditionValue(@NotNull String property) {
        return switch (path(property)) {
            case "using_item" -> this.usingItem;
            case "broken" -> this.broken;
            default -> false;
        };
    }

    /**
     * Resolves a {@code condition} node against everything it carries. A {@code minecraft:component}
     * condition applies its decoded {@linkplain ItemModelNode.Condition#predicate() predicate} to the
     * stack's components, {@link #itemId the item's} {@code minecraft:item_model} among them;
     * {@code has_component} asks {@link #hasComponent(String, boolean)} about the component it names,
     * with its {@code ignore_default}; every other property delegates to
     * {@link #conditionValue(String)}.
     *
     * @param condition the condition node
     * @return the boolean value, {@code false} when unevaluable
     */
    public boolean conditionValue(@NotNull ItemModelNode.Condition condition) {
        if (condition.predicate().isPresent()) {
            ItemModelNode.ComponentPredicate predicate = condition.predicate().get();
            return predicate.matches(this.held(predicate.id()));
        }

        return path(condition.property()).equals("has_component")
            ? this.hasComponent(condition.component(), condition.ignoreDefault())
            : this.conditionValue(condition.property());
    }

    /**
     * Whether the stack holds the named component - the {@code minecraft:has_component} evaluation
     * without {@code ignore_default}. A component the patch removes reads absent, and so does every
     * component when no map is supplied, taking the {@code on_false} branch - bar
     * {@code minecraft:item_model}, which the item holds by default.
     *
     * @param component the component id, qualified to {@code minecraft:} when bare
     * @return whether the component is present
     */
    public boolean hasComponent(@NotNull String component) {
        return this.hasComponent(component, false);
    }

    /**
     * Whether the stack holds the named component, as {@code minecraft:has_component} asks it. With
     * {@code ignoreDefault} off the stack must hold the component and the patch must not remove it - the
     * patch setting it, or the item holding it by default, which is known only for
     * {@code minecraft:item_model}, the {@link #itemId item's} own id; any other component the item holds
     * only by default reads absent. With it on, a patch that names the component at all answers true, a
     * removal included, and a default answers nothing, which is how vanilla reads the flag.
     *
     * @param component the component id, qualified to {@code minecraft:} when bare
     * @param ignoreDefault whether the condition sets {@code ignore_default}
     * @return whether the component is present
     */
    public boolean hasComponent(@NotNull String component, boolean ignoreDefault) {
        String id = ResourceId.parse(component).id();
        String removal = DataComponents.REMOVED + id;
        if (ignoreDefault) return this.components.filter(map -> map.containsKey(id) || map.containsKey(removal)).isPresent();
        return this.held(id).filter(map -> map.containsKey(id) && !map.containsKey(removal)).isPresent();
    }

    /**
     * Resolves a {@code select} node's case key from the property id alone. {@code display_context}
     * (this context's own key), {@code trim_material} (the caller override, absent by default, qualified
     * as the identifier it is) and {@code context_dimension} (always
     * {@link #DIMENSION_OVERWORLD the overworld}) are wired; {@code component} needs the component its
     * node names (see {@link #selectValue(ItemModelNode.Select)}), and every other property is
     * unevaluable and returns empty so the walker takes the no-case-match fallback.
     *
     * @param property the node's {@code property} id, bare or {@code minecraft:}-qualified for one of vanilla's
     * @return the case key to match, or empty when unevaluable
     */
    public @NotNull Optional<String> selectValue(@NotNull String property) {
        return switch (path(property)) {
            case "display_context" -> Optional.of(this.displayContext);
            case "trim_material" -> this.trimMaterial;
            case "context_dimension" -> Optional.of(DIMENSION_OVERWORLD);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves a {@code select} node's case key against everything it carries. A
     * {@code minecraft:component} select reduces the stack's value of the component it names to the key
     * its cases were decoded to, through the {@linkplain ItemModelNode.Select#decoded() decoded component}
     * it carries - for {@code minecraft:item_model} the {@link #itemId item's} own id where the patch
     * neither sets nor removes one - and is unevaluable for a component this renderer does not decode or
     * one the stack does not hold; every other property delegates to {@link #selectValue(String)}.
     *
     * @param select the select node
     * @return the case key to match, or empty when unevaluable
     */
    public @NotNull Optional<String> selectValue(@NotNull ItemModelNode.Select select) {
        if (!path(select.property()).equals("component")) return this.selectValue(select.property());
        return select.decoded().flatMap(component -> component.key(this.held(component.id())));
    }

    /**
     * Resolves a {@code range_dispatch} node's numeric input at {@code custom_model_data} index
     * {@code 0} - the shorthand for nodes that carry no index ({@code time}, {@code compass}).
     *
     * @param property the node's {@code property} id, bare or {@code minecraft:}-qualified for one of vanilla's
     * @return the numeric dispatch input
     */
    public float rangeValue(@NotNull String property) {
        return rangeValue(property, 0);
    }

    /**
     * Resolves a {@code range_dispatch} node's numeric input at a component index. {@code time} and
     * {@code compass} read their caller overrides ({@code 0} by default); {@code custom_model_data}
     * reads the explicit {@link #customModelData} override, else the {@code floats[index]} entry of the
     * item's {@code minecraft:custom_model_data} component, else {@code 0}; every other property reads
     * {@code 0} (the neutral use-duration / charge / cast input).
     *
     * @param property the node's {@code property} id, bare or {@code minecraft:}-qualified for one of vanilla's
     * @param index the {@code custom_model_data} float-list index the node selects ({@code 0} for the others)
     * @return the numeric dispatch input
     */
    public float rangeValue(@NotNull String property, int index) {
        return switch (path(property)) {
            case "time" -> this.time;
            case "compass" -> this.compassAngle;
            case "custom_model_data" -> customModelDataFloat(index);
            default -> 0f;
        };
    }

    /**
     * Resolves an item-definition tree against this context - walks its {@linkplain ItemModelTree#root()
     * root node} with {@link #resolve(ItemModelNode)}.
     *
     * @param tree the parsed item-definition tree
     * @return the resolved branch, {@linkplain ItemModelNode.Resolution#isEmpty() empty} when the branch renders nothing
     */
    public @NotNull ItemModelNode.Resolution resolve(@NotNull ItemModelTree tree) {
        return resolve(tree.root());
    }

    /**
     * Resolves a dispatch node against this context, walking the branch that renders to its leaves.
     * One structural pass: a {@code condition} takes the branch
     * {@link #conditionValue(ItemModelNode.Condition)} selects (unknown &rarr; {@code on_false}); a
     * {@code select} takes the case holding the key {@link #selectValue(ItemModelNode.Select)} answers
     * (no match or unevaluable property &rarr; {@code fallback}), which is the first such case and the
     * only one, a definition that repeats a case value failing to parse; a {@code range_dispatch} takes
     * the highest threshold {@code <=} the scaled value (none &rarr; {@code fallback}); a
     * {@code composite} walks every child and answers the
     * {@linkplain ItemModelNode.Resolution#layers() layers} each draws, in order, the resolution marked
     * {@linkplain ItemModelNode.Resolution#composed() composed}; a {@code model} / {@code special} is a
     * leaf; a {@code bundle} and an {@code empty} node render nothing; and an absent fallback, like the
     * root of a refused definition, is vanilla's missing item model,
     * {@link ItemModelNode.Resolution#MISSING}.
     *
     * <p>The neutral {@link #gui()} context resolves every vanilla tree to its fallback branch, giving the
     * derived model id and tint list - bar the properties that have one honest answer for an icon
     * whatever the caller ({@code display_context}, {@code context_dimension}), which select their
     * matching case.
     *
     * @param node the dispatch node to walk
     * @return the resolved branch, {@linkplain ItemModelNode.Resolution#isEmpty() empty} when the branch renders nothing
     */
    public @NotNull ItemModelNode.Resolution resolve(@NotNull ItemModelNode node) {
        return switch (node) {
            case ItemModelNode.Model model -> new ItemModelNode.Resolution(Optional.of(model.model()), model.tints(), Optional.empty(), false, false);
            case ItemModelNode.Condition condition -> resolve(this.branch(condition));
            case ItemModelNode.Select select -> resolve(this.branch(select));
            case ItemModelNode.RangeDispatch range -> resolve(this.branch(range));
            case ItemModelNode.Composite composite -> resolveComposite(composite);
            case ItemModelNode.Special special -> new ItemModelNode.Resolution(Optional.empty(), Concurrent.newUnmodifiableList(), Optional.of(special), false, false);
            case ItemModelNode.Bundle ignored -> ItemModelNode.Resolution.NOTHING;
            case ItemModelNode.Empty ignored -> ItemModelNode.Resolution.NOTHING;
            case ItemModelNode.Absent ignored -> ItemModelNode.Resolution.MISSING;
        };
    }

    /**
     * Returns how many distinct steps the {@code minecraft:time} dispatch on the branch this context
     * walks resolves over a day, or empty when that branch holds none - the frame count an item
     * animation derives, taken from the table its frames are drawn from.
     * <p>
     * The search follows the branch {@link #resolve(ItemModelNode) the walk} takes: the case the stack,
     * the display context and the {@linkplain #DIMENSION_OVERWORLD overworld} pin select, the
     * {@code on_true} or {@code on_false} a condition answers, and the entry a range dispatch's input
     * reaches. It stops at the first dispatch whose table
     * {@linkplain ItemModelNode.RangeDispatch#timeSteps() steps through a day}; a time table too short to
     * sweep is followed like any other range dispatch. A {@code composite} is searched through every
     * child rather than only the first that draws, because vanilla draws them all, and the first table
     * among its children answers.
     *
     * @param node the dispatch node to search
     * @return the number of steps a day resolves through, or empty when the walked branch holds no time table
     */
    public @NotNull OptionalInt timeDispatchSteps(@NotNull ItemModelNode node) {
        return switch (node) {
            case ItemModelNode.RangeDispatch range -> {
                OptionalInt steps = range.timeSteps();
                yield steps.isPresent() ? steps : this.timeDispatchSteps(this.branch(range));
            }
            case ItemModelNode.Condition condition -> this.timeDispatchSteps(this.branch(condition));
            case ItemModelNode.Select select -> this.timeDispatchSteps(this.branch(select));
            case ItemModelNode.Composite composite -> composite.models()
                .stream()
                .map(this::timeDispatchSteps)
                .filter(OptionalInt::isPresent)
                .findFirst()
                .orElseGet(OptionalInt::empty);
            case ItemModelNode.Model ignored -> OptionalInt.empty();
            case ItemModelNode.Special ignored -> OptionalInt.empty();
            case ItemModelNode.Bundle ignored -> OptionalInt.empty();
            case ItemModelNode.Empty ignored -> OptionalInt.empty();
            case ItemModelNode.Absent ignored -> OptionalInt.empty();
        };
    }

    /** The branch a {@code condition} walk takes: {@code on_true} where {@link #conditionValue(ItemModelNode.Condition)} holds, else {@code on_false}. */
    private @NotNull ItemModelNode branch(@NotNull ItemModelNode.Condition condition) {
        return this.conditionValue(condition) ? condition.onTrue() : condition.onFalse();
    }

    /** The branch a {@code select} walk takes: the case holding the key {@link #selectValue(ItemModelNode.Select)} answers, else the fallback. */
    private @NotNull ItemModelNode branch(@NotNull ItemModelNode.Select select) {
        Optional<String> key = this.selectValue(select);
        if (key.isPresent()) {
            for (ItemModelNode.Select.Case option : select.cases())
                if (option.when().contains(key.get())) return option.model();
        }
        return select.fallback();
    }

    /** The branch a {@code range_dispatch} walk takes: the entry of the highest threshold at or below the scaled input, else the fallback. */
    private @NotNull ItemModelNode branch(@NotNull ItemModelNode.RangeDispatch range) {
        float scaled = range.scale() * this.rangeValue(range.property(), range.index());
        ItemModelNode.RangeDispatch.Entry best = null;
        for (ItemModelNode.RangeDispatch.Entry entry : range.entries())
            if (entry.threshold() <= scaled && (best == null || entry.threshold() > best.threshold())) best = entry;
        return best != null ? best.model() : range.fallback();
    }

    /** The branch a {@code composite} walk takes: every child walked, and the layers each draws joined in order into one resolution. */
    private @NotNull ItemModelNode.Resolution resolveComposite(@NotNull ItemModelNode.Composite composite) {
        return ItemModelNode.Resolution.composite(composite.models()
            .stream()
            .map(child -> this.resolve(child))
            .toList());
    }

    /** The {@code custom_model_data} float at an index: the explicit override, else the component's {@code floats[index]}, else {@code 0}. */
    private float customModelDataFloat(int index) {
        if (this.customModelData.isPresent()) return this.customModelData.get();
        if (index < 0) return 0f;
        Optional<CompoundTag> customModelData = component("minecraft:custom_model_data")
            .filter(CompoundTag.class::isInstance)
            .map(CompoundTag.class::cast);
        if (customModelData.isEmpty()) return 0f;
        ListTag<?> floats = customModelData.get().getListTag("floats");
        if (floats == null || index >= floats.size()) return 0f;
        return floats.get(index) instanceof FloatTag value ? value.floatValue() : 0f;
    }

    /** The stack's value of a component, qualified to {@code minecraft:} when bare, or empty when no map is supplied or the patch does not hold it. */
    private @NotNull Optional<Tag<?>> component(@NotNull String id) {
        return this.components.map(map -> map.get(ResourceId.parse(id).id()));
    }

    /** The stack's components as a test of one qualified id reads them: the patch, copied with the {@link #itemId item's} own id as its {@code minecraft:item_model} where that is the component tested and the patch neither sets nor removes it. */
    private @NotNull Optional<CompoundTag> held(@NotNull String id) {
        if (!id.equals(ITEM_MODEL) || this.itemId.isEmpty()) return this.components;
        if (this.components.filter(map -> map.containsKey(id) || map.containsKey(DataComponents.REMOVED + id)).isPresent())
            return this.components;

        CompoundTag held = new CompoundTag(this.components.map(CompoundTag::size).orElse(0) + 1);
        this.components.ifPresent(held::putAll);
        held.put(id, new StringTag(this.itemId.get()));
        return Optional.of(held);
    }

    /** A deep copy of a compound, each entry copied by {@link #copy(Tag)}. */
    private static @NotNull CompoundTag copy(@NotNull CompoundTag compound) {
        CompoundTag copy = new CompoundTag(compound.size());
        for (Map.Entry<String, Tag<?>> entry : compound.entrySet())
            copy.put(entry.getKey(), copy(entry.getValue()));
        return copy;
    }

    /** A deep copy of one tag, a list keeping its element type even when it holds none. */
    private static @NotNull Tag<?> copy(@NotNull Tag<?> tag) {
        return switch (tag) {
            case CompoundTag compound -> copy(compound);
            case ListTag<?> list -> {
                ListTag<Tag<?>> copy = new ListTag<>(list.getListType(), list.size());
                for (Tag<?> element : list)
                    copy.add(copy(element));
                yield copy;
            }
            default -> tag.clone();
        };
    }

    /** A property id's path under the vanilla namespace, or {@code ""} - which names no property - for one in any other namespace. */
    private static @NotNull String path(@NotNull String property) {
        return ResourceId.vanillaPath(property).orElse("");
    }

}
