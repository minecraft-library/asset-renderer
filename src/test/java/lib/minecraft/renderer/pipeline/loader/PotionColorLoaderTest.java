package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link PotionColorLoader} against the bundled {@code potion_colors.json} snapshot.
 * Spot-checks a handful of representative effect colours so regressions in either the ASM tooling or
 * the JSON loader get caught early, without depending on the full 30+ entry list matching
 * byte-for-byte.
 */
class PotionColorLoaderTest {

    @Test
    @DisplayName("fire_resistance effect resolves to the MC 26.1 orange colour")
    void fireResistanceMatches() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        assertThat(colors.get("minecraft:fire_resistance"), is(new Color(0xFFFF9900, true).getRGB()));
    }

    @Test
    @DisplayName("conduit_power resolves to its MC 26.1 cyan colour")
    void conduitPowerMatches() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        assertThat(colors.get("minecraft:conduit_power"), is(new Color(0xFF1DC2D1, true).getRGB()));
    }

    @Test
    @DisplayName("every entry has the full opaque alpha channel")
    void alphaIsOpaque() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        colors.forEach((effectId, argb) -> assertThat(effectId + " alpha", (argb >>> 24) & 0xFF, is(0xFF)));
    }

}
