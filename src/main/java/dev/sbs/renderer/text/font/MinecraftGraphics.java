package dev.sbs.renderer.text.font;

import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelGraphics;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * A {@link PixelGraphics} subclass that provides text rendering via the {@link MinecraftFont}
 * glyph atlas. Glyphs are pre-rasterized as white-on-transparent bitmaps and tinted per-call
 * with the current drawing color - no AWT font rasterization at render time.
 * <p>
 * Supports a glyph scale factor for nearest-neighbor magnification (used by stack count
 * rendering where the logical 16px font is scaled to match the canvas size).
 *
 * @see MinecraftFont
 * @see PixelGraphics
 */
public class MinecraftGraphics extends PixelGraphics {

    private @NotNull MinecraftFont currentMcFont;
    private final int scale;

    /**
     * Creates a Minecraft graphics context for the given buffer.
     *
     * @param target the pixel buffer to draw onto
     */
    public MinecraftGraphics(@NotNull PixelBuffer target) {
        this(target, 1);
    }

    /**
     * Creates a Minecraft graphics context with a glyph scale factor.
     *
     * @param target the pixel buffer to draw onto
     * @param scale the glyph scale factor (1 = native size)
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

    // --- text rendering ---

    @Override
    public void drawString(@NotNull String str, int x, int y) {
        if (str.isEmpty()) return;
        int cx = translateX() + x;
        int cy = translateY() + y;

        for (int i = 0; i < str.length(); i++) {
            int cp = str.charAt(i);
            MinecraftFont.GlyphData glyph = this.currentMcFont.glyph(cp);
            blitGlyph(glyph, cx, cy);
            cx += glyph.advanceWidth() * this.scale;
        }
    }

    private void blitGlyph(@NotNull MinecraftFont.GlyphData glyph, int x, int y) {
        // TODO: supersampled glyph edge rendering.
        //
        // Today every pixel of the cached glyph bitmap is nearest-neighbor replicated by
        // `this.scale` into an s*s block. When TextRenderer renders at supersampling > 1 and
        // box-filters the result back down, those replicated blocks collapse unchanged - the
        // scale+downsample round-trip is the identity transform, so SSAA produces no actual
        // glyph anti-aliasing.
        //
        // To make supersampling smooth glyph edges, rasterize an HD bitmap on demand (AWT at
        // `font.getSize2D() * this.scale`) and blit directly into the buffer at 1:1, letting
        // the downsample pass mix it with neighboring pixels. Cache keyed by
        // (codepoint, scale) on MinecraftFont.GlyphData - lazily populated to keep ASCII warm
        // at scale=1 and avoid rasterizing the full BMP upfront for every scale.
        //
        // Touch points when ready:
        //   - MinecraftFont.rasterizeGlyph(Font, int, int scale) returns an HD GlyphData
        //   - MinecraftFont.glyph(int, int scale) looks up the scale-specific cache
        //   - this method picks the HD bitmap and skips the inner sx/sy replication loop
        //
        // Decorations (strikethrough/underline) already use sub-pixel positioning when
        // `scale >= 2` (see TextKit#drawSegment), so once glyphs go HD the whole tooltip will
        // benefit from SSAA uniformly.

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
