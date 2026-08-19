package lib.minecraft.renderer.engine.texture;

import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit coverage for {@link RedstoneTint} and the {@link RendererContext#sampleRedstoneTint}
 * resolution it backs. Pins the tint lookup against both a vanilla-only and an override-bearing
 * context so a broken {@link RendererContext#findColorOverride} cannot satisfy both rows at once.
 */
@DisplayName("Redstone tint resolution")
class RedstoneTintTest {

    /**
     * Pins the vanilla row: with no override map every power level resolves to the bundled
     * {@link RedstoneTint#VALUES} entry at its own index, so the table is consulted by power
     * rather than collapsed to one colour.
     */
    @Test
    @DisplayName("Vanilla context returns the bundled COLORS table for every power level")
    void vanillaContextMatchesBundledTable() {
        RendererContext context = stubContext(Map.of());
        for (int power = 0; power < RedstoneTint.VALUES.length; power++)
            assertThat("power " + power, context.sampleRedstoneTint(power), equalTo(RedstoneTint.VALUES[power]));
    }

    /**
     * Pins the override row against the vanilla one. The synthetic gradient is spaced evenly around
     * the HSV wheel, deliberately unlike vanilla's red ramp, so a leak of the bundled table is
     * visible; and because the two rows are asserted against each other a broken
     * {@link RendererContext#findColorOverride} cannot make both pass.
     */
    @Test
    @DisplayName("Override context returns the per-power override for every power level")
    void overrideContextReturnsOverrideTable() {
        Map<String, Integer> overrides = new HashMap<>();
        for (int power = 0; power < 16; power++)
            overrides.put("redstone." + power, syntheticOverrideForPower(power));
        RendererContext context = stubContext(overrides);

        for (int power = 0; power < RedstoneTint.VALUES.length; power++)
            assertThat("power " + power, context.sampleRedstoneTint(power), equalTo(syntheticOverrideForPower(power)));
    }

    /** Pins the guard at both ends of the 0..15 power domain, either side of a valid index. */
    @Test
    @DisplayName("sampleRedstoneTint rejects out-of-range power levels")
    void rejectsOutOfRange() {
        RendererContext context = stubContext(Map.of());
        assertThrows(IllegalArgumentException.class, () -> context.sampleRedstoneTint(-1));
        assertThrows(IllegalArgumentException.class, () -> context.sampleRedstoneTint(16));
    }

    /**
     * Pins that the range guard runs before the pack is consulted. A pack shipping a key for an
     * out-of-range power must not answer it - which is what a lazy {@code orElseGet} over the
     * vanilla lookup would let happen.
     */
    @Test
    @DisplayName("An out-of-range power is rejected even when a pack supplies its key")
    void rejectsOutOfRangeAheadOfThePackOverride() {
        RendererContext context = stubContext(Map.of("redstone.16", 0xFF00FF00));
        assertThrows(IllegalArgumentException.class, () -> context.sampleRedstoneTint(16));
    }

    /** Pins the table length the power domain and both rows above are indexed over. */
    @Test
    @DisplayName("The vanilla table has 16 entries")
    void tableHasSixteenEntries() {
        assertThat(RedstoneTint.VALUES.length, is(16));
    }

    /**
     * Builds an HSV gradient evenly spaced around the wheel for a given power level. Used as the
     * synthetic override gradient so the test's expected values are derivable rather than baked.
     *
     * @param power the redstone power level
     * @return the opaque ARGB colour for that level
     */
    static int syntheticOverrideForPower(int power) {
        float hue = power / 16f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /**
     * Builds a minimal {@link RendererContext} stub whose every asset lookup returns empty, but
     * whose {@code findColorOverride} honours the supplied override map - the one method
     * {@link RendererContext#sampleRedstoneTint} consults.
     *
     * @param overrides the colour overrides the stub answers with, keyed as {@code redstone.<power>}
     * @return the stub context
     */
    private static @NotNull RendererContext stubContext(@NotNull Map<String, Integer> overrides) {
        return StubRendererContext.builder()
            .colorOverrides(overrides)
            .build();
    }

}
