package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins for {@link ArgbHex}: the one hex policy every tint-bearing loader parses through, over the
 * three accepted prefixes ({@code 0x} / {@code #} / bare) and both digit widths - a 6-digit form
 * forces opaque alpha, an 8-digit form carries its own - falling back to white on malformed input.
 */
@DisplayName("ArgbHex prefix and digit-width value contract")
class ArgbHexTest {

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    @Test
    @DisplayName("6-digit forms force alpha FF across 0x / # / bare prefixes")
    void sixDigitForcesOpaque() {
        assertEquals(0xFFFF00FF, ArgbHex.parse("0xFF00FF"));
        assertEquals(0xFFFF00FF, ArgbHex.parse("FF00FF"));
        assertEquals(0xFFFF00FF, ArgbHex.parse("#FF00FF"));
        assertEquals(0xFF000000, ArgbHex.parse("000000"));
    }

    @Test
    @DisplayName("8-digit forms carry their own alpha across 0x / # / bare prefixes")
    void eightDigitKeepsAlpha() {
        assertEquals(0x7F00FF00, ArgbHex.parse("0x7F00FF00"));
        assertEquals(0x7F00FF00, ArgbHex.parse("7F00FF00"));
        assertEquals(0x8040C020, ArgbHex.parse("#8040C020"));
        assertEquals(0xFFFFFFFF, ArgbHex.parse("0xFFFFFFFF"));
    }

    @Test
    @DisplayName("malformed values fall back to white (the one failure policy)")
    void malformedFallsBackToWhite() {
        assertEquals(ArgbHex.WHITE, ArgbHex.parse("zzz"));
        assertEquals(ArgbHex.WHITE, ArgbHex.parse(""));
        assertEquals(ArgbHex.WHITE, ArgbHex.parse("0x"));
        assertEquals(ArgbHex.WHITE, ArgbHex.parse("#nothex"));
    }

    @Test
    @DisplayName("the diagnostics overload warns exactly once on a malformed fallback, never on a valid value")
    void malformedWarnsThroughDiagnostics() {
        Diagnostics diag = diagnostics();

        assertEquals(0xFFFF00FF, ArgbHex.parse("0xFF00FF", diag));
        assertEquals(0, diag.count(Diagnostics.Severity.WARN), "a valid value must not warn");

        assertEquals(ArgbHex.WHITE, ArgbHex.parse("zzz", diag));
        assertEquals(1, diag.count(Diagnostics.Severity.WARN), "a malformed value must warn once");
    }
}
