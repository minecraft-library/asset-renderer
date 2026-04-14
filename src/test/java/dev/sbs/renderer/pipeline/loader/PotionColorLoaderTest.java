package dev.sbs.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link PotionColorLoader} against the bundled {@code /renderer/potion_colors.json}
 * snapshot. Spot-checks a handful of representative effect colours so regressions in either the
 * ASM tooling or the JSON loader get caught early, without depending on the full 30+ entry list
 * matching byte-for-byte.
 */
class PotionColorLoaderTest {

    @Test
    @DisplayName("loads the bundled snapshot with at least the vanilla 1.21 effect set")
    void loadsEntries() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        // Vanilla MC 1.21 ships 30+ effects; subsequent versions add more. Anything less than 20
        // points at a parser regression or a stripped-down JSON.
        assertThat(colors.size(), greaterThan(20));
    }

    @Test
    @DisplayName("fire_resistance effect resolves to the MC 26.1 orange colour")
    void fireResistanceMatches() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        Integer argb = colors.get("minecraft:fire_resistance");
        assertThat(argb, is(equalTo(0xFFFF9900)));
    }

    @Test
    @DisplayName("conduit_power resolves to its MC 26.1 cyan colour")
    void conduitPowerMatches() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        Integer argb = colors.get("minecraft:conduit_power");
        assertThat(argb, is(equalTo(0xFF1DC2D1)));
    }

    @Test
    @DisplayName("every entry has the full opaque alpha channel")
    void alphaIsOpaque() {
        ConcurrentMap<String, Integer> colors = PotionColorLoader.load();
        colors.forEach((effectId, argb) -> {
            int alpha = (argb >>> 24) & 0xFF;
            assertThat(effectId + " alpha", alpha, is(0xFF));
        });
    }

    @Test
    @DisplayName("parse() accepts a minimal JSON fixture")
    void parseAcceptsMinimalJson() {
        String json = "{\"effects\":[{\"effect\":\"minecraft:speed\",\"color\":\"0xFF33EBFF\"}]}";
        ConcurrentMap<String, Integer> colors = PotionColorLoader.parse(json);
        assertThat(colors.get("minecraft:speed"), is(equalTo(0xFF33EBFF)));
    }

    @Test
    @DisplayName("parse() returns empty when the effects array is absent")
    void parseHandlesMissingArray() {
        String json = "{\"source_version\":\"26.1\"}";
        ConcurrentMap<String, Integer> colors = PotionColorLoader.parse(json);
        assertThat(colors.size(), is(0));
    }

}
