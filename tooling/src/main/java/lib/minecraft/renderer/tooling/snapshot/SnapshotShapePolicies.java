package lib.minecraft.renderer.tooling.snapshot;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The tint walk's complete policy roster - the renderer-capability drops recorded in
 * {@code dropped[]} (never silent) and the argument a two-colour {@code constant} registration
 * resolves to. Never fetches ({@code PolicyPurityTest}): the walk does the fetching; these
 * constants home the declared facts + their hard-won provenance so every hard-coded judgment
 * lives in one place.
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
     * {@code constant(colorInHand, colorInWorld)}: the GUI block icon uses vanilla's
     * no-context in-hand colour ({@code BlockTintSource.color(state)}), which is the FIRST arg.
     * The value is the picked argument index.
     */
    TINT_CONSTANT_IN_HAND(
        0,
        "constant(colorInHand, colorInWorld): the GUI icon uses vanilla's no-context in-hand colour = the first arg");

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

    /** The {@code constant(colorInHand, colorInWorld)} argument index the GUI icon uses (the first). */
    static int constantInHandArg() {
        return (int) TINT_CONSTANT_IN_HAND.value;
    }

}
