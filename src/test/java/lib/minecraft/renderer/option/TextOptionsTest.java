package lib.minecraft.renderer.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The chrome alpha defaults {@link TextOptions#defaults()} carries - vanilla's background {@code 240}
 * and border {@code 80}, which every tooltip render starts from and an override multiplies against.
 * <p>
 * Options-level rather than render-level: neither reaches the renderer, so a render would only pin
 * them by way of the pixels the chrome happens to paint.
 */
@DisplayName("TextOptions defaults")
class TextOptionsTest {

    @Test
    @DisplayName("default background alpha is 240 (0xF0) matching vanilla")
    void defaultBackgroundAlphaIsVanilla() {
        TextOptions opts = TextOptions.defaults();
        assertThat(opts.getBackgroundAlpha(), is(240));
    }

    @Test
    @DisplayName("default border alpha is 80 (0x50) matching vanilla")
    void defaultBorderAlphaIsVanilla() {
        TextOptions opts = TextOptions.defaults();
        assertThat(opts.getBorderAlpha(), is(80));
    }

}
