package dev.sbs.renderer.text.font;

import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelGraphics;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * A {@link PixelGraphics} subclass that renders {@link MinecraftFont} glyphs directly into a
 * {@link PixelBuffer}. Callers pass glyph origins in logical mcPixel space; the graphics applies
 * the {@code mcPixel -> output-pixel} conversion via {@link MinecraftFont#MC_PIXEL_SCALE}, plus
 * an optional integer {@link #scale} pixel-replication factor for GUI-density use cases (e.g.
 * rendering stack counts at 16x native size on a {@code 256x256} item icon).
 *
 * @see MinecraftFont
 * @see PixelGraphics
 */
public class MinecraftGraphics extends PixelGraphics {

    private @NotNull MinecraftFont currentMcFont;

    /**
     * Glyph pixel-replication factor. {@code 1} renders at the font's native rasterization; {@code >= 2}
     * replicates each glyph pixel into an {@code s x s} block for GUI-density scaling.
     */
    private final int scale;

    /**
     * Creates a Minecraft graphics context for the given buffer at native scale.
     *
     * @param target the pixel buffer to draw onto
     */
    public MinecraftGraphics(@NotNull PixelBuffer target) {
        this(target, 1);
    }

    /**
     * Creates a Minecraft graphics context with a glyph pixel-replication factor. At
     * {@code scale = N}, each bitmap pixel of a rendered glyph becomes an {@code N x N} output
     * block and decoration geometry (line spacing, shadow offsets) is multiplied by the same
     * factor. This path is a nearest-neighbor replication - no anti-aliasing is added, matching
     * the Minecraft font's integer-aligned retro aesthetic.
     *
     * @param target the pixel buffer to draw onto
     * @param scale the glyph pixel-replication factor ({@code >= 1})
     */
    public MinecraftGraphics(@NotNull PixelBuffer target, int scale) {
        super(target);
        this.currentMcFont = MinecraftFont.REGULAR;
        this.scale = Math.max(1, scale);
    }

    private MinecraftGraphics(@NotNull MinecraftGraphics source) {
        super(source);
        this.currentMcFont = source.currentMcFont;
        this.scale = source.scale;
    }

    /**
     * Returns the glyph pixel-replication factor this graphics is configured with.
     *
     * @return the scale factor
     */
    public int scale() {
        return this.scale;
    }

    // --- text rendering ---

    /**
     * Draws a string at the given mcPixel origin. The baseline sits at {@code yMcPx}; the
     * cursor starts at {@code xMcPx}. Both are converted to output-pixel coordinates via
     * {@code mcPx * MinecraftFont.MC_PIXEL_SCALE * scale}.
     *
     * @param str the text to draw
     * @param xMcPx the starting cursor X in mcPixels
     * @param yMcPx the baseline Y in mcPixels
     */
    @Override
    public void drawString(@NotNull String str, int xMcPx, int yMcPx) {
        if (str.isEmpty()) return;
        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE * this.scale;
        int cx = translateX() + xMcPx * pxPerMcPx;
        int cy = translateY() + yMcPx * pxPerMcPx;

        for (int i = 0; i < str.length(); i++) {
            int cp = str.charAt(i);
            MinecraftFont.GlyphData glyph = this.currentMcFont.glyph(cp);
            blitGlyph(glyph, cx, cy);
            cx += glyph.advanceWidth() * this.scale;
        }
    }

    private void blitGlyph(@NotNull MinecraftFont.GlyphData glyph, int x, int y) {
        PixelBuffer bitmap = glyph.bitmap();
        int bw = bitmap.width();
        int bh = bitmap.height();
        int gx = x + glyph.bearingX() * this.scale;
        int gy = y + glyph.bearingY() * this.scale;
        PixelBuffer target = target();
        int tw = target.width();
        int th = target.height();

        int tintR = ColorMath.red(colorArgb());
        int tintG = ColorMath.green(colorArgb());
        int tintB = ColorMath.blue(colorArgb());

        Shape clipShape = getClip();
        int clipX0 = clipShape instanceof Rectangle c ? c.x : 0;
        int clipY0 = clipShape instanceof Rectangle c ? c.y : 0;
        int clipX1 = clipShape instanceof Rectangle c ? c.x + c.width : tw;
        int clipY1 = clipShape instanceof Rectangle c ? c.y + c.height : th;

        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int pixel = bitmap.getPixel(bx, by);
                int alpha = ColorMath.alpha(pixel);
                if (alpha == 0) continue;

                int tinted = ColorMath.pack(alpha, tintR, tintG, tintB);

                for (int sy = 0; sy < this.scale; sy++) {
                    int py = gy + by * this.scale + sy;
                    if (py < clipY0 || py >= clipY1) continue;

                    for (int sx = 0; sx < this.scale; sx++) {
                        int px = gx + bx * this.scale + sx;
                        if (px < clipX0 || px >= clipX1) continue;

                        int dst = target.getPixel(px, py);
                        target.setPixel(px, py, ColorMath.blend(tinted, dst, BlendMode.NORMAL));
                    }
                }
            }
        }
    }

    // --- font and metrics ---

    /**
     * Selects a {@link MinecraftFont} variant directly, bypassing AWT's style-bit round trip.
     * <p>
     * Custom-loaded OTF fonts always report {@link Font#PLAIN} from {@link Font#getStyle()}
     * because AWT does not introspect the typeface file - style is whatever was set with
     * {@code deriveFont(style)} (never, for us). Going through {@link #setFont(Font)} would
     * therefore always resolve to {@link MinecraftFont#REGULAR}. Callers that already know
     * which variant they want (e.g. the text pipeline picking BOLD from a
     * {@link dev.sbs.renderer.text.ColorSegment}'s {@code &l} flag) should use this method
     * instead.
     *
     * @param font the Minecraft font variant to use for subsequent {@link #drawString} calls
     */
    public void setFont(@NotNull MinecraftFont font) {
        this.currentMcFont = font;
    }

    @Override
    public void setFont(@NotNull Font font) {
        this.currentMcFont = MinecraftFont.of(MinecraftFont.Style.of(font.getStyle()));
    }

    @Override
    public @NotNull Font getFont() {
        return this.currentMcFont.getActual();
    }

    @Override
    public @NotNull FontMetrics getFontMetrics(@NotNull Font f) {
        return MinecraftFont.of(MinecraftFont.Style.of(f.getStyle())).getFontMetrics();
    }

    @Override
    public @NotNull FontMetrics getFontMetrics() {
        return this.currentMcFont.getFontMetrics();
    }

    // --- copy ---

    @Override
    public @NotNull Graphics create() {
        return new MinecraftGraphics(this);
    }

}
