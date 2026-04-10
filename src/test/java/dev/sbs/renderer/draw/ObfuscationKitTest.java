package dev.sbs.renderer.draw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ObfuscationKitTest {

    @Test
    @DisplayName("substitute is deterministic for a fixed seed")
    void substituteIsDeterministic() {
        String result1 = ObfuscationKit.substitute("Hello World", 42L);
        String result2 = ObfuscationKit.substitute("Hello World", 42L);
        assertThat(result1, equalTo(result2));
    }

    @Test
    @DisplayName("different seeds produce different outputs")
    void differentSeedsProduceDifferentOutput() {
        String a = ObfuscationKit.substitute("Hello World", 1L);
        String b = ObfuscationKit.substitute("Hello World", 2L);
        assertThat(a, is(not(equalTo(b))));
    }

    @Test
    @DisplayName("substitute preserves whitespace")
    void substitutePreservesWhitespace() {
        String result = ObfuscationKit.substitute("a b c", 123L);
        assertThat(result.length(), equalTo("a b c".length()));
        assertThat(result.charAt(1), equalTo(' '));
        assertThat(result.charAt(3), equalTo(' '));
    }

    @Test
    @DisplayName("substitute on empty string returns empty")
    void substituteEmpty() {
        assertThat(ObfuscationKit.substitute("", 0L), equalTo(""));
    }

    @Test
    @DisplayName("output length matches input length")
    void outputLengthMatches() {
        String input = "The quick brown fox jumps over the lazy dog";
        String output = ObfuscationKit.substitute(input, 999L);
        assertThat(output.length(), equalTo(input.length()));
    }

}
