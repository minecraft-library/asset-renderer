package dev.sbs.renderer.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link ChatColor} foreground/shadow pairs match the vanilla 1.13+ formula
 * {@code shadow = (rgb & 0xFCFCFC) >> 2}. Includes a targeted check that gold no longer
 * carries the 1.8.9 {@code colorCode[]} table quirk.
 */
class ChatColorTest {

    @Test
    @DisplayName("every entry's backgroundRgb equals (fgRgb & 0xFCFCFC) >> 2")
    void backgroundMatchesVanillaFormula() {
        for (ChatColor.Legacy color : ChatColor.Legacy.values()) {
            int fg = color.rgb() & 0xFFFFFF;
            int expected = (fg & 0xFCFCFC) >> 2;
            int actual = color.backgroundRgb() & 0xFFFFFF;
            assertThat(color.name() + " shadow", actual, is(equalTo(expected)));
        }
    }

    @Test
    @DisplayName("GOLD shadow is 0x3F2A00 (1.13+ formula), not 0x2A2A00 (1.8.9 table quirk)")
    void goldShadowMatches113Plus() {
        assertThat(ChatColor.Legacy.GOLD.rgb() & 0xFFFFFF, is(0xFFAA00));
        assertThat(ChatColor.Legacy.GOLD.backgroundRgb() & 0xFFFFFF, is(0x3F2A00));
    }

    @Test
    @DisplayName("GREEN shadow is 0x153F15")
    void greenShadow() {
        assertThat(ChatColor.Legacy.GREEN.rgb() & 0xFFFFFF, is(0x55FF55));
        assertThat(ChatColor.Legacy.GREEN.backgroundRgb() & 0xFFFFFF, is(0x153F15));
    }

    @Test
    @DisplayName("WHITE shadow is 0x3F3F3F")
    void whiteShadow() {
        assertThat(ChatColor.Legacy.WHITE.backgroundRgb() & 0xFFFFFF, is(0x3F3F3F));
    }

    @Test
    @DisplayName("BLACK shadow is 0x000000")
    void blackShadow() {
        assertThat(ChatColor.Legacy.BLACK.backgroundRgb() & 0xFFFFFF, is(0x000000));
    }

    @Test
    @DisplayName("Custom color derives shadow via shadowOf formula")
    void customColorDerivesShadow() {
        ChatColor custom = ChatColor.of(0xFF8040);
        assertThat(custom.rgb() & 0xFFFFFF, is(0xFF8040));
        assertThat(custom.backgroundRgb() & 0xFFFFFF, is((0xFF8040 & 0xFCFCFC) >> 2));
    }

    @Test
    @DisplayName("Custom color emits hex in JSON and has no legacy code")
    void customColorJsonAndCode() {
        ChatColor custom = ChatColor.builder().color(0xAB12CD).build();
        assertThat(custom.toJsonString(), is("#AB12CD"));
        assertThat(custom.toLegacyString(), is(""));
        assertThat(custom.code().isPresent(), is(false));
    }

    @Test
    @DisplayName("fromJsonString resolves both legacy names and hex")
    void fromJsonStringRoundTrip() {
        assertThat(ChatColor.fromJsonString("red"), is(equalTo(ChatColor.Legacy.RED)));
        ChatColor parsed = ChatColor.fromJsonString("#123456");
        assertThat(parsed, is(equalTo(ChatColor.of(0x123456))));
    }
}
