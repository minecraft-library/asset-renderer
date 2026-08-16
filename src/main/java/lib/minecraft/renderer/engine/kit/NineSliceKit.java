package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.Resample;
import lib.minecraft.renderer.asset.pack.MCMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Blits a {@code gui.scaling}-governed sprite (stretch, tile, nine_slice) into a destination rect.
 * <p>
 * The reusable GUI-sprite compositor matching the {@link AnimationKit} / {@link GlintKit} /
 * {@link TextKit} static-kit convention. Every {@code gui/sprites/**} widget scales through the same
 * {@link MCMeta.GuiScaling} system; the two named consumers are the themable tooltip chrome and the
 * menu chest chrome.
 * <p>
 * Compositing is a straight-alpha <b>paint</b>: each sprite texel whose (post-multiplier) alpha is
 * non-zero overwrites the destination pixel, and a fully transparent texel leaves the destination
 * untouched. This matches the vanilla opaque GUI-sprite blit and the renderer's existing chrome
 * (which places its background fill and gradient ring with straight-alpha writes), so an
 * {@code alphaMul} of {@code 1.0} reproduces the sprite bytes exactly and a semi-transparent ring
 * texel replaces the fill beneath it rather than compositing over it.
 * <p>
 * Slicing happens in <b>sprite space</b> at the sprite's nominal ({@link MCMeta.GuiScaling#width()} /
 * {@link MCMeta.GuiScaling#height()}) resolution; each mapped texel then expands by {@code pxScale}
 * nearest-neighbor, so corners stay pixel-crisp and a baked gradient edge stretches continuously.
 */
@UtilityClass
public class NineSliceKit {

    /**
     * Blits {@code sprite} over the destination rect according to its scaling metadata, clipping to
     * the destination bounds (the rect may start off-canvas at a negative origin).
     *
     * @param dest the target buffer
     * @param sprite the sprite texture (authored at 1:1 GUI px)
     * @param scaling the {@code gui.scaling} sidecar; {@code stretch} when a pack ships no mcmeta
     * @param x the destination rect x origin in output px
     * @param y the destination rect y origin in output px
     * @param w the destination rect width in output px
     * @param h the destination rect height in output px
     * @param pxScale the output px per sprite px ({@code MinecraftFont.MC_PIXEL_SCALE} = 2 for text chrome)
     * @param alphaMul the per-texel alpha multiplier, {@code 1.0} leaving the sprite bytes untouched
     */
    public static void draw(
        @NotNull PixelBuffer dest,
        @NotNull PixelBuffer sprite,
        @NotNull MCMeta.GuiScaling scaling,
        int x, int y, int w, int h,
        int pxScale, float alphaMul
    ) {
        if (w <= 0 || h <= 0 || pxScale <= 0 || sprite.width() <= 0 || sprite.height() <= 0) return;

        PixelBuffer src = nominalize(sprite, scaling);
        int[] sxByLocal = axisMap(scaling, true, src.width(), w, pxScale);
        int[] syByLocal = axisMap(scaling, false, src.height(), h, pxScale);

        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(dest.width(), x + w);
        int y1 = Math.min(dest.height(), y + h);

        for (int dy = y0; dy < y1; dy++) {
            int sy = syByLocal[dy - y];
            for (int dx = x0; dx < x1; dx++) {
                int argb = multiply(src.getPixel(sxByLocal[dx - x], sy), alphaMul);
                if (ColorMath.alpha(argb) != 0) dest.setPixel(dx, dy, argb);
            }
        }
    }

    /**
     * Resamples the sprite to its declared nominal on-screen size when it differs from the texture
     * size (a sprite authored at 2× GUI scale), leaving 1:1-authored sprites - every vanilla tooltip
     * sprite - untouched. Slicing then reads the nominal-resolution buffer.
     */
    private static @NotNull PixelBuffer nominalize(@NotNull PixelBuffer sprite, @NotNull MCMeta.GuiScaling scaling) {
        int nominalW = scaling.width() > 0 ? scaling.width() : sprite.width();
        int nominalH = scaling.height() > 0 ? scaling.height() : sprite.height();
        if (nominalW == sprite.width() && nominalH == sprite.height()) return sprite;
        return sprite.resize(nominalW, nominalH, Resample.NEAREST);
    }

    /**
     * Precomputes the sprite coordinate each destination-local column (or row) reads, so the blit
     * loop is a pure array lookup. {@code horizontal} selects the x axis (left/right border) or the
     * y axis (top/bottom border).
     */
    private static int[] axisMap(@NotNull MCMeta.GuiScaling scaling, boolean horizontal, int spriteSize, int destSize, int pxScale) {
        int[] map = new int[destSize];
        switch (scaling.type()) {
            case STRETCH -> {
                for (int local = 0; local < destSize; local++)
                    map[local] = nearest(local, destSize, spriteSize);
            }
            case TILE -> {
                for (int local = 0; local < destSize; local++)
                    map[local] = (local / pxScale) % spriteSize;
            }
            case NINE_SLICE -> {
                MCMeta.GuiScaling.Border b = scaling.border();
                int nearBorder = horizontal ? b.left() : b.top();
                int farBorder = horizontal ? b.right() : b.bottom();
                // Clamp each border independently to half the destination (in sprite px) so the two
                // borders never overlap - mirroring the vanilla client's min(border, dim/2) per side.
                int half = destSize / (2 * pxScale);
                int clampedNear = Math.min(nearBorder, half);
                int clampedFar = Math.min(farBorder, half);
                for (int local = 0; local < destSize; local++)
                    map[local] = nineSlice(local, destSize, clampedNear, clampedFar, spriteSize, pxScale, scaling.stretchInner());
            }
        }
        return map;
    }

    /**
     * Maps one destination-local coordinate onto its sprite coordinate for a nine-slice axis: the near
     * border reads the sprite's outermost near columns unscaled by {@code pxScale}, the far border its
     * outermost far columns (anchored on the CLAMPED far extent, so a shrunk far slice keeps the sprite
     * edge rather than sliding inward), and the center obeys {@code stretch_inner} (stretch the inner
     * slice, else tile it from the slice's top-left).
     */
    private static int nineSlice(int local, int destSize, int clampedNear, int clampedFar,
                                 int spriteSize, int pxScale, boolean stretchInner) {
        int nearPx = clampedNear * pxScale;
        int farPx = clampedFar * pxScale;
        if (local < nearPx)
            return Math.clamp(local / pxScale, 0, spriteSize - 1);
        if (local >= destSize - farPx) {
            int off = local - (destSize - farPx);
            return Math.clamp(spriteSize - clampedFar + off / pxScale, 0, spriteSize - 1);
        }
        int centerSprite = spriteSize - clampedNear - clampedFar;
        int centerDest = destSize - nearPx - farPx;
        if (centerSprite <= 0 || centerDest <= 0) return Math.clamp(clampedNear, 0, spriteSize - 1);
        int off = local - nearPx;
        if (stretchInner)
            return Math.clamp(clampedNear + nearest(off, centerDest, centerSprite), 0, spriteSize - 1);
        return Math.clamp(clampedNear + (off / pxScale) % centerSprite, 0, spriteSize - 1);
    }

    /** Pixel-centered nearest-neighbor sample of {@code srcSize} texels across {@code destSize} output px. */
    private static int nearest(int local, int destSize, int srcSize) {
        return Math.clamp((int) (((long) (2 * local + 1) * srcSize) / (2L * destSize)), 0, srcSize - 1);
    }

    /** Scales a texel's alpha by {@code alphaMul}, leaving the rgb and (at {@code 1.0}) the exact bytes untouched. */
    private static int multiply(int argb, float alphaMul) {
        if (alphaMul == 1.0f) return argb;
        int alpha = Math.clamp(Math.round(ColorMath.alpha(argb) * alphaMul), 0, 255);
        return ColorMath.withAlpha(argb, alpha);
    }

}
