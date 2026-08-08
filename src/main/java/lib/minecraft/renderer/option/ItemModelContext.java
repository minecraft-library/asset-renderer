package lib.minecraft.renderer.option;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.ListTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The immutable evaluation context an item-definition tree is resolved against - a fixed set of
 * neutral GUI defaults plus the handful of caller
 * overrides an icon renderer can honestly supply (trim material, dye colour, clock time, compass
 * angle), mirroring how {@code EntityOptions} carries {@code state}/{@code collarColor}/{@code age}.
 *
 * <p>Every dispatch property a vanilla tree branches on resolves through one of three accessors -
 * {@link #conditionValue(String)} (booleans), {@link #selectValue(String)} (case keys),
 * {@link #rangeValue(String)} (numeric thresholds). A property this context has no value for is
 * <b>unevaluable</b>: the walker takes the {@code on_false} / no-case-match / {@code fallback}
 * branch, which is the Catharsis degradation contract. The default {@link #gui()} context leaves
 * every caller override neutral, so it resolves each vanilla tree to its fallback branch - except
 * where a property has one honest answer for an icon regardless of the caller ({@code display_context}
 * is always the GUI, {@link #DIMENSION_OVERWORLD the dimension} always the overworld), which is
 * answered rather than degraded.
 *
 * @param displayContext the {@code minecraft:display_context} case key; {@code "gui"} for icons
 * @param usingItem the {@code minecraft:using_item} flag; {@code false} renders bow unpulled (today's output)
 * @param broken the {@code minecraft:broken} flag; {@code false}
 * @param trimMaterial the {@code minecraft:trim_material} case key, or {@code null} to take the fallback (today's output)
 * @param dyeColor the dye colour override, or {@code null} so tint sources use their declared defaults
 * @param time the {@code minecraft:time} range input; {@code 0} selects clock frame 0
 * @param compassAngle the {@code minecraft:compass} range input; {@code 0} selects the neutral compass frame
 * @param customModelData an explicit {@code custom_model_data} float override that wins over the component tree, or {@code null} to read it from {@link #components}
 * @param components the render-time item component map (an nbt-factory {@code CompoundTag} keyed by component id, e.g. {@code minecraft:custom_model_data}); {@code null} when the caller supplies no stack, leaving {@code has_component} and {@code custom_model_data} unevaluable
 */
public record ItemModelContext(
    @NotNull String displayContext,
    boolean usingItem,
    boolean broken,
    @Nullable String trimMaterial,
    @Nullable Integer dyeColor,
    float time,
    float compassAngle,
    @Nullable Float customModelData,
    @Nullable CompoundTag components
) {

    /** The GUI display-context key every icon renders at. */
    public static final @NotNull String DISPLAY_CONTEXT_GUI = "gui";

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
     * component whose type is not, {@link #components}, is {@code null} on this context - so a
     * shared instance carries no state a caller could reach. Nothing compares a context by identity
     * either (the fast path at {@link #isNeutral()} and the render memo both go through the record's
     * equals), so sharing changes no answer.
     */
    private static final @NotNull ItemModelContext GUI =
        new ItemModelContext(DISPLAY_CONTEXT_GUI, false, false, null, null, 0f, 0f, null, null);

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
     * reuse the pipeline-baked item without re-walking the tree.
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
            this.dyeColor, SunAngle.at(SunAngle.NOON_TICK + (long) tick), this.compassAngle,
            this.customModelData, this.components);
    }

    /**
     * Resolves a {@code condition} node's boolean property from the property id alone. Only the
     * properties an icon can honestly evaluate without further inputs are wired ({@code using_item},
     * {@code broken}); {@code has_component} needs the tested component id (see
     * {@link #conditionValue(String, String)}), and every other live gameplay flag ({@code damaged},
     * {@code fishing_rod/cast}, ...) reads {@code false}, so the walker takes the {@code on_false} branch.
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @return the boolean value, {@code false} when unevaluable
     */
    public boolean conditionValue(@NotNull String property) {
        return switch (strip(property)) {
            case "using_item" -> this.usingItem;
            case "broken" -> this.broken;
            default -> false;
        };
    }

    /**
     * Resolves a {@code condition} node against both its property and, for {@code has_component}, the
     * component id it tests. {@code has_component} consults the {@link #components} map; every other
     * property delegates to {@link #conditionValue(String)}.
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @param component the {@code has_component} target component id, ignored for other properties
     * @return the boolean value, {@code false} when unevaluable
     */
    public boolean conditionValue(@NotNull String property, @NotNull String component) {
        return strip(property).equals("has_component") ? hasComponent(component) : conditionValue(property);
    }

    /**
     * Whether the render-time {@link #components} map carries the named component - the
     * {@code minecraft:has_component} evaluation. Unevaluable (no component map supplied) reads
     * {@code false}, taking the {@code on_false} branch.
     *
     * @param component the component id, with or without the {@code minecraft:} prefix
     * @return whether the component is present
     */
    public boolean hasComponent(@NotNull String component) {
        return this.components != null && this.components.containsKey(normalizeComponentId(component));
    }

    /**
     * Resolves a {@code select} node's case key. {@code display_context} (always {@code gui}),
     * {@code trim_material} (the caller override, absent by default) and {@code context_dimension}
     * (always {@link #DIMENSION_OVERWORLD the overworld}) are wired; every other property is
     * unevaluable and returns empty so the walker takes the no-case-match fallback.
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @return the case key to match, or empty when unevaluable
     */
    public @NotNull Optional<String> selectValue(@NotNull String property) {
        return switch (strip(property)) {
            case "display_context" -> Optional.of(this.displayContext);
            case "trim_material" -> Optional.ofNullable(this.trimMaterial);
            case "context_dimension" -> Optional.of(DIMENSION_OVERWORLD);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves a {@code range_dispatch} node's numeric input at {@code custom_model_data} index
     * {@code 0} - the shorthand for nodes that carry no index ({@code time}, {@code compass}).
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
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
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @param index the {@code custom_model_data} float-list index the node selects ({@code 0} for the others)
     * @return the numeric dispatch input
     */
    public float rangeValue(@NotNull String property, int index) {
        return switch (strip(property)) {
            case "time" -> this.time;
            case "compass" -> this.compassAngle;
            case "custom_model_data" -> customModelDataFloat(index);
            default -> 0f;
        };
    }

    /** The {@code custom_model_data} float at an index: the explicit override, else the component's {@code floats[index]}, else {@code 0}. */
    private float customModelDataFloat(int index) {
        if (this.customModelData != null) return this.customModelData;
        if (index < 0) return 0f;
        Optional<CompoundTag> customModelData = component("minecraft:custom_model_data");
        if (customModelData.isEmpty()) return 0f;
        ListTag<?> floats = customModelData.get().getListTag("floats");
        if (floats == null || index >= floats.size()) return 0f;
        return floats.get(index) instanceof FloatTag value ? value.floatValue() : 0f;
    }

    /** The component sub-compound under a (normalized) id, or empty when no map is supplied or the entry is not a compound. */
    private @NotNull Optional<CompoundTag> component(@NotNull String id) {
        if (this.components == null) return Optional.empty();
        return this.components.get(normalizeComponentId(id)) instanceof CompoundTag tag ? Optional.of(tag) : Optional.empty();
    }

    /** Prepends the implicit {@code minecraft:} namespace to an unqualified component id. */
    private static @NotNull String normalizeComponentId(@NotNull String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** Strips a leading {@code minecraft:} namespace so property matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String property) {
        int colon = property.indexOf(':');
        return colon < 0 ? property : property.substring(colon + 1);
    }

}
