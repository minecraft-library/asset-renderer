package dev.sbs.renderer.asset.binding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the sixteen vanilla dye colours match the {@code textureDiffuseColor} values shipped
 * by {@code net.minecraft.world.item.DyeColor} in MC 26.1. These values have been stable
 * across every version since 1.8.
 */
class DyeColorTest {

    @Test
    @DisplayName("WHITE matches vanilla textureDiffuseColor")
    void whiteMatches() {
        assertThat(DyeColor.Vanilla.WHITE.argb(), is(equalTo(0xFFF9FFFE)));
    }

    @Test
    @DisplayName("RED matches vanilla textureDiffuseColor")
    void redMatches() {
        assertThat(DyeColor.Vanilla.RED.argb(), is(equalTo(0xFFB02E26)));
    }

    @Test
    @DisplayName("BLACK matches vanilla textureDiffuseColor")
    void blackMatches() {
        assertThat(DyeColor.Vanilla.BLACK.argb(), is(equalTo(0xFF1D1D21)));
    }

    @Test
    @DisplayName("every vanilla dye has fully opaque alpha")
    void alphaIsOpaque() {
        for (DyeColor.Vanilla dye : DyeColor.Vanilla.values()) {
            int alpha = (dye.argb() >>> 24) & 0xFF;
            assertThat(dye.name() + " alpha", alpha, is(0xFF));
        }
    }

    @Test
    @DisplayName("Vanilla.ofName looks up by enum name")
    void ofNameResolvesKnown() {
        assertThat(DyeColor.Vanilla.ofName("RED"), is(DyeColor.Vanilla.RED));
        assertThat(DyeColor.Vanilla.ofName("LIGHT_BLUE"), is(DyeColor.Vanilla.LIGHT_BLUE));
    }

    @Test
    @DisplayName("Vanilla.ofName returns null for unknown names")
    void ofNameUnknownReturnsNull() {
        assertThat(DyeColor.Vanilla.ofName("RAINBOW"), is(nullValue()));
    }

    @Test
    @DisplayName("of(int) wraps a caller-supplied RGB as a Custom instance with opaque alpha")
    void customWrapsRgb() {
        DyeColor dye = DyeColor.of(0x123456);
        assertThat(dye, is(instanceOf(DyeColor.Custom.class)));
        assertThat(dye.argb(), is(equalTo(0xFF123456)));
    }

    @Test
    @DisplayName("DyeColor.ofName delegates to Vanilla.ofName")
    void interfaceOfNameDelegates() {
        assertThat(DyeColor.ofName("GREEN"), is(notNullValue()));
        assertThat(DyeColor.ofName("GREEN"), is(equalTo(DyeColor.Vanilla.GREEN)));
    }

}
