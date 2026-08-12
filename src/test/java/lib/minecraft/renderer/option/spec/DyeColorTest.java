package lib.minecraft.renderer.option.spec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The sixteen {@link DyeColor.Vanilla} colours, their wool variants and the lookup helpers around
 * them. A few of the colours (WHITE, RED, BLACK) are pinned against the {@code textureDiffuseColor}
 * values shipped by {@code net.minecraft.world.item.DyeColor} in MC 26.1 - stable across every version
 * since 1.8 - and every vanilla dye is asserted fully opaque ({@code alpha == 0xFF}). The helpers are
 * {@link DyeColor.Vanilla#ofName(String)} on a hit and on a null miss, {@link DyeColor#ofName(String)}
 * delegating to it, and {@link DyeColor#of(int)} wrapping a caller RGB as an opaque
 * {@link DyeColor.Custom}.
 * <p>
 * The wool colours are pinned against the vanilla reference renders rather than against the formula
 * that produces them, so the assertions can fail: a scale that drifted would still agree with itself.
 * Each is the full-lit top face of the matching {@code sheep~wool_color=*} reference.
 */
@DisplayName("DyeColor vanilla colours, wool scale and name lookup")
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
    @DisplayName("WHITE wool is vanilla's override, not the scaled dye")
    void whiteWoolIsOverridden() {
        assertThat(DyeColor.Vanilla.WHITE.woolArgb(), is(equalTo(0xFFE6E6E6)));
        // What the scale alone would give. If these ever coincide the override has stopped mattering.
        assertThat(DyeColor.Vanilla.WHITE.woolArgb(), is(not(equalTo(0xFFBABFBE))));
    }

    @Test
    @DisplayName("wool scales every other dye to three quarters")
    void woolScalesOtherDyes() {
        assertThat(DyeColor.Vanilla.PINK.woolArgb(), is(equalTo(0xFFB6687F)));
        assertThat(DyeColor.Vanilla.YELLOW.woolArgb(), is(equalTo(0xFFBEA22D)));
        assertThat(DyeColor.Vanilla.BLACK.woolArgb(), is(equalTo(0xFF151518)));
    }

    @Test
    @DisplayName("a custom dye's wool colour is its own")
    void customWoolIsItsOwnColour() {
        DyeColor dye = DyeColor.of(0x123456);
        assertThat(dye.woolArgb(), is(equalTo(dye.argb())));
    }

    @Test
    @DisplayName("every vanilla dye has fully opaque alpha")
    void alphaIsOpaque() {
        for (DyeColor.Vanilla dye : DyeColor.Vanilla.values()) {
            int alpha = (dye.argb() >>> 24) & 0xFF;
            assertThat(dye.name() + " alpha", alpha, is(0xFF));
            assertThat(dye.name() + " wool alpha", (dye.woolArgb() >>> 24) & 0xFF, is(0xFF));
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
