package lib.minecraft.renderer.pipeline.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link PotionColorLoader} against the bundled {@code potion_colors.json} snapshot.
 * Spot-checks a handful of representative effect colours so regressions in either the ASM tooling or
 * the JSON loader get caught early, without depending on the full 30+ entry list matching
 * byte-for-byte.
 */
class PotionColorLoaderTest {

    @Test
    @DisplayName("loads the bundled snapshot with at least the vanilla 26.1 effect set")
    void loadsEntries() {
        Map<String, Color> colors = PotionColorLoader.load();
        // Vanilla MC 26.1 ships 30+ effects; subsequent versions add more. Anything less than 20
        // points at a parser regression or a stripped-down JSON.
        assertThat(colors.size(), greaterThan(20));
    }

    @Test
    @DisplayName("fire_resistance effect resolves to the MC 26.1 orange colour")
    void fireResistanceMatches() {
        Map<String, Color> colors = PotionColorLoader.load();
        Color argb = colors.get("minecraft:fire_resistance");
        assertThat(argb, is(equalTo(new Color(0xFFFF9900, true))));
    }

    @Test
    @DisplayName("conduit_power resolves to its MC 26.1 cyan colour")
    void conduitPowerMatches() {
        Map<String, Color> colors = PotionColorLoader.load();
        Color argb = colors.get("minecraft:conduit_power");
        assertThat(argb, is(equalTo(new Color(0xFF1DC2D1, true))));
    }

    @Test
    @DisplayName("every entry has the full opaque alpha channel")
    void alphaIsOpaque() {
        Map<String, Color> colors = PotionColorLoader.load();
        colors.forEach((effectId, color) -> {
            int alpha = (color.getRGB() >>> 24) & 0xFF;
            assertThat(effectId + " alpha", alpha, is(0xFF));
        });
    }

}
