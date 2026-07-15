package lib.minecraft.renderer.pipeline.pack.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The immutable evaluation context an item-definition tree is walked against
 * ({@link ItemModelWalker}) - a fixed set of neutral GUI defaults plus the handful of caller
 * overrides an icon renderer can honestly supply (trim material, dye colour, clock time, compass
 * angle), mirroring how {@code EntityOptions} carries {@code state}/{@code collarColor}/{@code age}.
 *
 * <p>Every dispatch property a vanilla tree branches on resolves through one of three accessors -
 * {@link #conditionValue(String)} (booleans), {@link #selectValue(String)} (case keys),
 * {@link #rangeValue(String)} (numeric thresholds). A property this context has no value for is
 * <b>unevaluable</b>: the walker takes the {@code on_false} / no-case-match / {@code fallback}
 * branch, which is the Catharsis degradation contract. The default {@link #gui()} context leaves
 * every override neutral, so it resolves each vanilla tree to its fallback branch.
 *
 * @param displayContext the {@code minecraft:display_context} case key; {@code "gui"} for icons
 * @param usingItem the {@code minecraft:using_item} flag; {@code false} renders bow unpulled (today's output)
 * @param broken the {@code minecraft:broken} flag; {@code false}
 * @param trimMaterial the {@code minecraft:trim_material} case key, or {@code null} to take the fallback (today's output)
 * @param dyeColor the dye colour override, or {@code null} so tint sources use their declared defaults
 * @param time the {@code minecraft:time} range input; {@code 0} selects clock frame 0
 * @param compassAngle the {@code minecraft:compass} range input; {@code 0} selects the neutral compass frame
 * @param customModelData the {@code custom_model_data} range input, or {@code null} to fall back on every such dispatch
 * @param components the render-time NBT component tree (nbt-factory {@code CompoundTag}); parsed and held here, component matching not yet evaluated
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
    @Nullable Object components
) {

    /** The GUI display-context key every icon renders at. */
    public static final @NotNull String DISPLAY_CONTEXT_GUI = "gui";

    /**
     * The neutral GUI context: {@code display_context = gui} and every caller override left at its
     * default so each vanilla tree resolves to its fallback branch.
     *
     * @return the neutral GUI evaluation context
     */
    public static @NotNull ItemModelContext gui() {
        return new ItemModelContext(DISPLAY_CONTEXT_GUI, false, false, null, null, 0f, 0f, null, null);
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
     * Resolves a {@code condition} node's boolean property. Only the properties an icon can honestly
     * evaluate are wired ({@code using_item}, {@code broken}); every other property - and any live
     * gameplay flag ({@code has_component}, {@code damaged}, {@code fishing_rod/cast}, ...) - reads
     * {@code false}, so the walker takes the {@code on_false} branch.
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
     * Resolves a {@code select} node's case key. Only {@code display_context} (always {@code gui})
     * and {@code trim_material} (the caller override, absent by default) are wired; every other
     * property is unevaluable and returns empty so the walker takes the no-case-match fallback.
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @return the case key to match, or empty when unevaluable
     */
    public @NotNull Optional<String> selectValue(@NotNull String property) {
        return switch (strip(property)) {
            case "display_context" -> Optional.of(this.displayContext);
            case "trim_material" -> Optional.ofNullable(this.trimMaterial);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves a {@code range_dispatch} node's numeric input. {@code time} and {@code compass} read
     * their caller overrides ({@code 0} by default); {@code custom_model_data} reads its override when
     * present; every other property reads {@code 0} (the neutral use-duration / charge / cast input).
     *
     * @param property the node's {@code property} id, with or without the {@code minecraft:} prefix
     * @return the numeric dispatch input
     */
    public float rangeValue(@NotNull String property) {
        return switch (strip(property)) {
            case "time" -> this.time;
            case "compass" -> this.compassAngle;
            case "custom_model_data" -> this.customModelData != null ? this.customModelData : 0f;
            default -> 0f;
        };
    }

    /** Strips a leading {@code minecraft:} namespace so property matching accepts both id forms. */
    private static @NotNull String strip(@NotNull String property) {
        int colon = property.indexOf(':');
        return colon < 0 ? property : property.substring(colon + 1);
    }

}
