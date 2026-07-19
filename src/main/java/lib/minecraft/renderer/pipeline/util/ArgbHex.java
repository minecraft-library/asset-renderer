package lib.minecraft.renderer.pipeline.util;

import dev.simplified.gson.adapter.ColorTypeAdapter;
import dev.simplified.gson.exception.JsonException;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * The diagnostics-aware, packed-int entry point onto the shared {@link ColorTypeAdapter} colour
 * policy, dissolving the divergent per-loader parsers into one place.
 *
 * <p>The parse policy itself - {@code 0x} / {@code #} / bare hex forms in 6- or 8-hex-digit lengths,
 * a 6-or-fewer-digit value forced fully opaque - lives on the {@link ColorTypeAdapter} codec, which
 * throws {@link JsonException} on a malformed value. This class adds the asset-side domain tolerance:
 * a malformed value falls back to opaque {@link #WHITE} - the no-op {@code MULTIPLY} tint - so a typo
 * never tints a subject black, and the diagnostics overload warns when it does. Banner tints stay
 * {@code DyeColor} names and are resolved elsewhere, never through this parser.
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
        try {
            return ColorTypeAdapter.parse(hex).getRGB();
        } catch (JsonException ex) {
            return WHITE;
        }
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
        try {
            return ColorTypeAdapter.parse(hex).getRGB();
        } catch (JsonException ex) {
            diagnostics.warn("malformed hex colour '%s' (expected 0xAARRGGBB / #RRGGBB); using white 0xFFFFFFFF", hex);
            return WHITE;
        }
    }
}
