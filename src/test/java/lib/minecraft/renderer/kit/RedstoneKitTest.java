package lib.minecraft.renderer.kit;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.TexturePack;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the per-power resolution path under both a vanilla-only context (no override map) and
 * an override-bearing context (every {@code redstone.0..15} key remapped). The two assertions
 * together pin the wiring: if {@link RendererContext#findColorOverride(String)} is broken, the
 * override row equals the vanilla row and the second batch of asserts fails.
 */
class RedstoneKitTest {

    @Test
    @DisplayName("Vanilla context returns the bundled COLORS table for every power level")
    void vanillaContextMatchesBundledTable() {
        RendererContext vanilla = stubContext(Map.of());

        for (int power = 0; power < RedstoneKit.VANILLA.length; power++)
            assertThat("power " + power, RedstoneKit.resolve(vanilla, power), equalTo(RedstoneKit.VANILLA[power]));
    }

    @Test
    @DisplayName("Override context returns the per-power override for every power level")
    void overrideContextReturnsOverrideTable() {
        // Distinct gradient evenly spaced around the HSV wheel, deliberately unlike the vanilla
        // red gradient so any leak of the bundled table would be obvious.
        Map<String, Integer> overrides = new HashMap<>();
        for (int power = 0; power < 16; power++)
            overrides.put("redstone." + power, syntheticOverrideForPower(power));
        RendererContext withOverrides = stubContext(overrides);

        for (int power = 0; power < RedstoneKit.VANILLA.length; power++)
            assertThat("power " + power, RedstoneKit.resolve(withOverrides, power), equalTo(syntheticOverrideForPower(power)));
    }

    @Test
    @DisplayName("Resolve rejects out-of-range power levels")
    void rejectsOutOfRange() {
        RendererContext vanilla = stubContext(Map.of());
        try {
            RedstoneKit.resolve(vanilla, -1);
            throw new AssertionError("expected IllegalArgumentException for power=-1");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            RedstoneKit.resolve(vanilla, 16);
            throw new AssertionError("expected IllegalArgumentException for power=16");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /**
     * Builds an HSV gradient evenly spaced around the wheel for a given power level. Used as the
     * synthetic override gradient so the test's expected values are derivable rather than baked.
     */
    static int syntheticOverrideForPower(int power) {
        float hue = power / 16f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /**
     * Minimal {@link RendererContext} stub that returns no textures, no colormaps, and no entities,
     * but honours the supplied {@code findColorOverride} map.
     */
    static @NotNull RendererContext stubContext(@NotNull Map<String, Integer> overrides) {
        return new RendererContext() {
            @Override public @NotNull Optional<TexturePack> findPack(@NotNull String id) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<lib.minecraft.renderer.asset.Block> findBlock(@NotNull String id) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<lib.minecraft.renderer.asset.Item> findItem(@NotNull String id) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<lib.minecraft.renderer.asset.Entity> findEntity(@NotNull String id) {
                return Optional.empty();
            }

            @Override public @NotNull Optional<Integer> findColorOverride(@NotNull String key) {
                return Optional.ofNullable(overrides.get(key));
            }
        };
    }

    @Test
    @DisplayName("VANILLA table has 16 entries")
    void vanillaTableHasSixteenEntries() {
        assertThat(RedstoneKit.VANILLA.length, is(16));
    }

}
