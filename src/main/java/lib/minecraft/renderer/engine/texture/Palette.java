package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The paletted-permutation pixel op - a grayscale pattern recoloured through a key strip and a
 * colour strip.
 * <p>
 * It names no subject: the three buffers arrive already resolved, and which pattern they came from
 * and what the result is composited onto are the caller's. The algorithm is verified against the
 * MC 26.1 deobfuscated client source
 * ({@code net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations}).
 */
@UtilityClass
@Parity(claim = "trim-palette")
public class Palette {

    /**
     * Applies the paletted-permutation algorithm to produce a coloured overlay from a grayscale base
     * pattern.
     * <p>
     * For each pixel in the base texture whose grayscale value (lowest 8 bits) matches an entry
     * in the palette key strip, the corresponding material colour is written to the output at
     * full opacity. Non-matching pixels remain transparent.
     *
     * @param baseTrim the grayscale pattern texture
     * @param paletteKey the palette key strip (grayscale, one row)
     * @param materialPalette the material colour strip (RGB, same width as the key)
     * @return the permuted ARGB overlay
     */
    public static @NotNull PixelBuffer permute(
        @NotNull PixelBuffer baseTrim,
        @NotNull PixelBuffer paletteKey,
        @NotNull PixelBuffer materialPalette
    ) {
        int paletteSize = Math.min(paletteKey.width(), materialPalette.width());
        int[] keyGrays = new int[paletteSize];
        int[] materialColors = new int[paletteSize];

        for (int i = 0; i < paletteSize; i++) {
            keyGrays[i] = paletteKey.getPixel(i, 0) & 0xFF;
            materialColors[i] = materialPalette.getPixel(i, 0) | 0xFF000000;
        }

        int w = baseTrim.width();
        int h = baseTrim.height();
        int[] pixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = baseTrim.getPixel(x, y);
                if ((pixel >>> 24) == 0) continue; // skip fully transparent pixels
                int gray = pixel & 0xFF;

                for (int i = 0; i < paletteSize; i++) {
                    if (keyGrays[i] == gray) {
                        pixels[y * w + x] = materialColors[i];
                        break;
                    }
                }
            }
        }

        return PixelBuffer.of(pixels, w, h);
    }

}
