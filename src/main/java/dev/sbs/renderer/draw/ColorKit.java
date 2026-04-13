package dev.sbs.renderer.draw;

import dev.simplified.image.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static helpers for ARGB colour math, blending, tinting, and colormap sampling.
 * <p>
 * All methods operate on packed 32-bit ARGB ints in the native byte order
 * {@code 0xAARRGGBB}. No object allocation - pure bit math.
 */
@UtilityClass
public class ColorKit {

    /** A fully transparent ARGB pixel. */
    public static final int TRANSPARENT = 0x00000000;

    /** An opaque white ARGB pixel. */
    public static final int WHITE = 0xFFFFFFFF;

    /** An opaque black ARGB pixel. */
    public static final int BLACK = 0xFF000000;

    // --- channel accessors ---

    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    public static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    /**
     * Packs individual ARGB channel values into a single 32-bit int.
     *
     * @param a the alpha channel in {@code [0, 255]}
     * @param r the red channel in {@code [0, 255]}
     * @param g the green channel in {@code [0, 255]}
     * @param b the blue channel in {@code [0, 255]}
     * @return the packed ARGB pixel
     */
    public static int pack(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Combines a 24-bit RGB integer with an explicit alpha channel into a packed ARGB pixel.
     *
     * @param rgb the 24-bit RGB value
     * @param alpha the alpha channel in {@code [0, 255]}
     * @return the packed ARGB pixel
     */
    public static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    // --- HSV conversion ---

    /**
     * Converts an HSV triplet into a packed ARGB pixel with full opacity.
     *
     * @param hue the hue in {@code [0, 360]} degrees
     * @param saturation the saturation in {@code [0, 1]}
     * @param value the value in {@code [0, 1]}
     * @return the packed ARGB pixel
     */
    public static int hsvToArgb(float hue, float saturation, float value) {
        return hsvToArgb(hue, saturation, value, 255);
    }

    /**
     * Converts an HSV triplet with an explicit alpha into a packed ARGB pixel.
     *
     * @param hue the hue in {@code [0, 360]} degrees
     * @param saturation the saturation in {@code [0, 1]}
     * @param value the value in {@code [0, 1]}
     * @param alpha the alpha channel in {@code [0, 255]}
     * @return the packed ARGB pixel
     */
    public static int hsvToArgb(float hue, float saturation, float value, int alpha) {
        float h = (((hue % 360f) + 360f) % 360f) / 60f;
        float c = value * saturation;
        float x = c * (1 - Math.abs((h % 2) - 1));
        float m = value - c;

        float r = 0, g = 0, b = 0;
        int sector = (int) Math.floor(h);
        switch (sector) {
            case 0 -> { r = c; g = x; }
            case 1 -> { r = x; g = c; }
            case 2 -> { g = c; b = x; }
            case 3 -> { g = x; b = c; }
            case 4 -> { r = x; b = c; }
            case 5, 6 -> { r = c; b = x; }
        }

        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return pack(alpha, ri, gi, bi);
    }

    // --- blending ---

    /**
     * Blends {@code src} on top of {@code dst} using the given blend mode.
     *
     * @param src the source (incoming) pixel
     * @param dst the destination (existing) pixel
     * @param mode the blend mode
     * @return the composited ARGB pixel
     */
    public static int blend(int src, int dst, @NotNull BlendMode mode) {
        return switch (mode) {
            case NORMAL -> blendNormal(src, dst);
            case ADD -> blendAdd(src, dst);
            case MULTIPLY -> blendMultiply(src, dst);
            case OVERLAY -> blendOverlay(src, dst);
        };
    }

    private static int blendNormal(int src, int dst) {
        int sa = alpha(src);
        if (sa == 0xFF) return src;
        if (sa == 0) return dst;

        int da = alpha(dst);
        float srcA = sa / 255f;
        float invSrcA = 1f - srcA;

        int r = Math.round(red(src) * srcA + red(dst) * invSrcA);
        int g = Math.round(green(src) * srcA + green(dst) * invSrcA);
        int b = Math.round(blue(src) * srcA + blue(dst) * invSrcA);
        int a = Math.round(sa + da * invSrcA);
        return pack(a, r, g, b);
    }

    private static int blendAdd(int src, int dst) {
        int sa = alpha(src);
        if (sa == 0) return dst;

        float srcA = sa / 255f;
        int r = Math.min(255, red(dst) + Math.round(red(src) * srcA));
        int g = Math.min(255, green(dst) + Math.round(green(src) * srcA));
        int b = Math.min(255, blue(dst) + Math.round(blue(src) * srcA));
        int a = Math.min(255, alpha(dst) + sa);
        return pack(a, r, g, b);
    }

    private static int blendMultiply(int src, int dst) {
        int sa = alpha(src);
        if (sa == 0) return dst;

        int r = (red(src) * red(dst)) / 255;
        int g = (green(src) * green(dst)) / 255;
        int b = (blue(src) * blue(dst)) / 255;
        return pack(alpha(dst), r, g, b);
    }

    private static int blendOverlay(int src, int dst) {
        int sa = alpha(src);
        if (sa == 0) return dst;

        int r = overlayChannel(red(src), red(dst));
        int g = overlayChannel(green(src), green(dst));
        int b = overlayChannel(blue(src), blue(dst));
        return pack(alpha(dst), r, g, b);
    }

    private static int overlayChannel(int s, int d) {
        return d < 128
            ? (2 * s * d) / 255
            : 255 - (2 * (255 - s) * (255 - d)) / 255;
    }

    // --- tinting ---

    /**
     * Multiplies every pixel's RGB channels by the given tint, preserving alpha.
     *
     * @param source the source pixel buffer
     * @param argbTint the packed ARGB tint colour
     * @return a new tinted pixel buffer with the same dimensions
     */
    public static @NotNull PixelBuffer tint(@NotNull PixelBuffer source, int argbTint) {
        int w = source.width();
        int h = source.height();
        int[] result = new int[w * h];
        int[] src = source.pixels();

        int tr = red(argbTint);
        int tg = green(argbTint);
        int tb = blue(argbTint);

        for (int i = 0; i < src.length; i++) {
            int pixel = src[i];
            int a = alpha(pixel);
            if (a == 0) {
                result[i] = pixel;
                continue;
            }
            int r = (red(pixel) * tr) / 255;
            int g = (green(pixel) * tg) / 255;
            int b = (blue(pixel) * tb) / 255;
            result[i] = pack(a, r, g, b);
        }

        return PixelBuffer.of(result, w, h);
    }

    // --- colormap sampling ---

    /**
     * Samples a 256x256 ARGB colormap at the location described by a biome's temperature and
     * downfall.
     * <p>
     * The sampling formula is byte-for-byte identical to vanilla's
     * {@code net.minecraft.world.level.ColorMapColorUtil.get(double, double, int[], int)} from the
     * MC 26.1 deobfuscated client, verified via {@code javap} disassembly:
     * <pre>{@code
     * adjTemp = clamp(temperature, 0, 1)   // vanilla clamps in Biome.getGrassColorFromTexture
     * adjRain = clamp(downfall, 0, 1) * adjTemp
     * x = floor((1 - adjTemp) * 255)
     * y = floor((1 - adjRain) * 255)
     * index = (y << 8) | x
     * }</pre>
     * Vanilla returns a magenta fallback ({@code 0xFFFF00FF}) when the index is out of bounds;
     * this helper clamps instead for defensive parity with malformed colormaps.
     *
     * @param colormap the 256x256 colormap pixels in row-major ARGB order
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @return the sampled ARGB pixel
     */
    public static int sampleColormap(int @NotNull [] colormap, float temperature, float downfall) {
        float adjTemp = clamp01(temperature);
        float adjRain = clamp01(downfall) * adjTemp;

        int x = (int) ((1.0f - adjTemp) * 255f);
        int y = (int) ((1.0f - adjRain) * 255f);

        x = Math.clamp(x, 0, 255);
        y = Math.clamp(y, 0, 255);

        return colormap[y * 256 + x];
    }

    private static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }

}
