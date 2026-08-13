package lib.minecraft.renderer.tooling.snapshot;

import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import lib.minecraft.renderer.tooling.policy.Trace;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The tint walk's complete policy roster - the renderer-capability drops recorded in
 * {@code dropped[]} (never silent) and the argument a two-colour {@code constant} registration
 * resolves to. Never fetches ({@code PolicyPurityTest}): the walk does the fetching, and a row
 * naming a coordinate and the steps to replay from it is DATA the walk executes, not a walk of its
 * own. Every hard-coded judgment and its hard-won provenance lives in one place here.
 *
 * <p>Everything derivable - tint colormap targets (from the source body's
 * {@code BiomeColors.getAverage*Color} call), stem colour (symbolic eval at age 0), block ids
 * ({@code BlockRegistryIndex}), and the bytecode shapes the potion and glint walks match on -
 * ships as derivation with NO policy fallback and is absent from this roster.
 */
enum SnapshotShapePolicies implements NavigationPolicy {

    /**
     * The tint sources the static GUI/parity render cannot sample: their colour is
     * per-state / biome-dynamic, so the icon would show the default state anyway. Recorded as
     * {@code dropped[]} rows with reason {@code dynamic_source} rather than emitted.
     */
    TINT_DYNAMIC_SOURCE_DROPS(
        Set.of("water", "waterParticles", "redstone"),
        "renderer cannot sample per-state / biome-dynamic tints at static render time; the GUI icon shows the"
            + " default state. water / waterParticles resolve BiomeColors.getAverageWaterColor - no WATER colormap"
            + " target exists; redstone resolves RedStoneWireBlock.getColorForPower - power-driven, no colormap"),

    /**
     * A {@code List.of(a, b, ...)} tint registration composes multiple sources
     * ({@code pink_petals} = {@code [BLANK_LAYER, grass()]}); the renderer tints one source per
     * block, so the registration is recorded as {@code dropped[]} with reason
     * {@code multi_source}. The value IS the reason string.
     */
    TINT_MULTI_SOURCE_DROP(
        "multi_source",
        "composed List.of registrations are unsupported: the renderer tints one source per block (pink_petals"
            + " [BLANK_LAYER, grass()])"),

    /**
     * The coordinate of the {@code constant(colorInHand, colorInWorld)} argument the GUI block icon
     * reads. The source that factory builds captures both colours as constructor parameters, and its
     * no-context {@code color(state)} returns one of them, so the field that accessor reads names the
     * capture and the capture's constructor position is the argument the walk keeps. The anonymous
     * class is named off its outer, so the outer keeps one spelling and a drifted ordinal fails at
     * the invoke the walk checks for rather than silently.
     */
    TINT_CONSTANT_IN_HAND(
        new Navigation.Dataflow(VanillaSourceClasses.Types.BLOCK_TINT_SOURCES + "$1", "color",
            Trace.of(new Trace.Step.FollowGetField(null), new Trace.Step.MapParamSlot())),
        "the GUI icon uses vanilla's no-context in-hand colour, which is the capture color(state) reads;"
            + " TintWalk replays the trace to the constructor parameter that field is assigned from, and"
            + " separately proves constant forwards its own parameters to that constructor in order, which is"
            + " what makes the recovered constructor index the factory argument index the walk keeps");

    /** The reason recorded for {@link #TINT_DYNAMIC_SOURCE_DROPS} rows. */
    static final @NotNull String REASON_DYNAMIC_SOURCE = "dynamic_source";

    private final @NotNull Object value;
    private final @NotNull String provenance;

    SnapshotShapePolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        if (this.value instanceof Navigation coordinate) return coordinate;
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /** The tint-source method names the renderer cannot sample (dropped as {@code dynamic_source}). */
    @SuppressWarnings("unchecked")
    static @NotNull Set<String> dynamicSourceDrops() {
        return (Set<String>) TINT_DYNAMIC_SOURCE_DROPS.value;
    }

    /** The reason recorded for a composed multi-source registration. */
    static @NotNull String multiSourceReason() {
        return (String) TINT_MULTI_SOURCE_DROP.value;
    }

}
