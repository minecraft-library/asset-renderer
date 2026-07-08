package lib.minecraft.renderer.engine.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Pins {@link TextKit}'s obfuscation glyph scrambling for the {@code §k} effect: seed-determinism
 * (same seed &rarr; same output, different seeds &rarr; different output), whitespace passthrough,
 * empty-string passthrough, and length preservation - the invariants that let a deterministic
 * per-frame scramble stand in for vanilla's per-tick glyph shuffle.
 */
class TextKitTest {

    @Test
    @DisplayName("substitute is deterministic for a fixed seed")
    void substituteIsDeterministic() {
        String result1 = TextKit.substitute("Hello World", 42L);
        String result2 = TextKit.substitute("Hello World", 42L);
        assertThat(result1, equalTo(result2));
    }

    @Test
    @DisplayName("different seeds produce different outputs")
    void differentSeedsProduceDifferentOutput() {
        String a = TextKit.substitute("Hello World", 1L);
        String b = TextKit.substitute("Hello World", 2L);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    @DisplayName("substitute preserves whitespace")
    void substitutePreservesWhitespace() {
        String result = TextKit.substitute("a b c", 123L);
        assertThat(result.length(), equalTo("a b c".length()));
        assertThat(result.charAt(1), equalTo(' '));
        assertThat(result.charAt(3), equalTo(' '));
    }

    @Test
    @DisplayName("substitute on empty string returns empty")
    void substituteEmpty() {
        assertThat(TextKit.substitute("", 0L), equalTo(""));
    }

    @Test
    @DisplayName("output length matches input length")
    void outputLengthMatches() {
        String input = "The quick brown fox jumps over the lazy dog";
        String output = TextKit.substitute(input, 999L);
        assertThat(output.length(), equalTo(input.length()));
    }

}
