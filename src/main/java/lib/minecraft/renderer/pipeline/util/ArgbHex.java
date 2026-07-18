package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single ARGB hex-string parser, dissolving the divergent per-loader parsers into one policy.
 *
 * <p>Accepts {@code 0x} / {@code #} / bare forms in 6- or 8-hex-digit lengths: a value of 6 or fewer
 * digits is forced fully opaque (alpha {@code FF}), a longer value carries its own alpha. A malformed
 * value falls back to opaque {@link #WHITE} - the no-op {@code MULTIPLY} tint - so a typo never tints
 * a subject black. Banner tints stay {@code DyeColor} names and are resolved elsewhere, never through
 * this parser.
 */
@UtilityClass
public final class ArgbHex {

    /** Opaque white - the malformed-value fallback and the no-op {@code MULTIPLY} tint. */
    public static final int WHITE = 0xFFFFFFFF;

    /**
     * Parses an ARGB hex string, falling back to {@link #WHITE} when the value is malformed.
     *
     * @param hex the colour string in {@code 0xAARRGGBB} / {@code #RRGGBB} / bare form
     * @return the parsed ARGB int, or {@link #WHITE} when {@code hex} is not valid hex
     */
    public static int parse(@NotNull String hex) {
        return parseOrWhite(hex, null);
    }

    /**
     * Parses an ARGB hex string, recording a warning to {@code diagnostics} when the value is
     * malformed and the {@link #WHITE} fallback is taken.
     *
     * @param hex the colour string in {@code 0xAARRGGBB} / {@code #RRGGBB} / bare form
     * @param diagnostics the scope a malformed value is warned to
     * @return the parsed ARGB int, or {@link #WHITE} when {@code hex} is not valid hex
     */
    public static int parse(@NotNull String hex, @NotNull Diagnostics diagnostics) {
        return parseOrWhite(hex, diagnostics);
    }

    private static int parseOrWhite(@NotNull String hex, @Nullable Diagnostics diagnostics) {
        String digits = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2)
            : hex.startsWith("#") ? hex.substring(1)
            : hex;
        try {
            long value = Long.parseLong(digits, 16);
            if (digits.length() <= 6) value |= 0xFF000000L;
            return (int) value;
        } catch (NumberFormatException ex) {
            if (diagnostics != null)
                diagnostics.warn("malformed hex colour '%s' (expected 0xAARRGGBB / #RRGGBB); using white 0xFFFFFFFF", hex);
            return WHITE;
        }
    }
}
