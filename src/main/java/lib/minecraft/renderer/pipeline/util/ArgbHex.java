package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.asset.ArgbColor;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * The diagnostics-aware entry point onto the {@link ArgbColor} hex-string policy, dissolving the
 * divergent per-loader parsers into one place.
 *
 * <p>The parse policy itself - {@code 0x} / {@code #} / bare forms in 6- or 8-hex-digit lengths, a
 * 6-or-fewer-digit value forced fully opaque, the malformed-value opaque-{@link #WHITE} fallback -
 * lives on {@link ArgbColor}; this class only adds the warn-on-malformed overload the loaders use.
 * Banner tints stay {@code DyeColor} names and are resolved elsewhere, never through this parser.
 */
@UtilityClass
public final class ArgbHex {

    /** Opaque white - the malformed-value fallback and the no-op {@code MULTIPLY} tint. */
    public static final int WHITE = ArgbColor.WHITE.argb();

    /**
     * Parses an ARGB hex string, falling back to {@link #WHITE} when the value is malformed.
     *
     * @param hex the colour string in {@code 0xAARRGGBB} / {@code #RRGGBB} / bare form
     * @return the parsed ARGB int, or {@link #WHITE} when {@code hex} is not valid hex
     */
    public static int parse(@NotNull String hex) {
        return ArgbColor.parse(hex).argb();
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
        OptionalInt value = ArgbColor.tryParse(hex);
        if (value.isPresent()) return value.getAsInt();
        diagnostics.warn("malformed hex colour '%s' (expected 0xAARRGGBB / #RRGGBB); using white 0xFFFFFFFF", hex);
        return WHITE;
    }
}
