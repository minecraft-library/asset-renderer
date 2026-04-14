package dev.sbs.renderer.text.font;

import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

/**
 * A {@link FontMetrics} implementation backed by the {@link MinecraftFont} glyph atlas. All
 * values come from cached data captured at font initialization - no AWT rendering pipeline is
 * needed at query time.
 * <p>
 * Also retains the underlying AWT {@link FontMetrics} and {@link FontRenderContext} that were
 * obtained at init time, so {@link MinecraftFont#glyph(int) glyph rasterization} can query
 * advance widths and build glyph vectors without spinning up a fresh scratch
 * {@link Graphics2D} for every codepoint.
 * <p>
 * Each {@link MinecraftFont} enum value holds a single instance, accessible via
 * {@link MinecraftFont#getFontMetrics()}.
 */
@Getter
public final class MinecraftFontMetrics extends FontMetrics {

    @Getter(AccessLevel.NONE)
    private final @NotNull MinecraftFont mcFont;

    /** The AWT {@link FontMetrics} captured at init - reused by glyph rasterization. */
    @Getter(AccessLevel.PACKAGE)
    private final @NotNull FontMetrics awtMetrics;

    /** The AWT {@link FontRenderContext} captured at init - reused by glyph rasterization. */
    @Getter(AccessLevel.PACKAGE)
    private final @NotNull FontRenderContext awtFrc;

    private final int ascent;
    private final int descent;
    private final int height;

    private MinecraftFontMetrics(@NotNull MinecraftFont mcFont, @NotNull FontMetrics awtMetrics, @NotNull FontRenderContext awtFrc) {
        super(mcFont.getActual());
        this.mcFont = mcFont;
        this.awtMetrics = awtMetrics;
        this.awtFrc = awtFrc;
        this.ascent = awtMetrics.getAscent();
        this.descent = awtMetrics.getDescent();
        this.height = awtMetrics.getHeight();
    }

    /**
     * Captures font-level AWT metrics and the render context for {@code mcFont}, disposing
     * the scratch {@link Graphics2D} used to obtain them. The returned instance retains both
     * so subsequent glyph rasterizations can reuse them directly.
     *
     * @param mcFont the Minecraft font whose underlying AWT {@link Font} is being measured
     * @return a fully-populated metrics instance bound to {@code mcFont}
     */
    static @NotNull MinecraftFontMetrics capture(@NotNull MinecraftFont mcFont) {
        BufferedImage temp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = temp.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(mcFont.getActual());
            return new MinecraftFontMetrics(mcFont, g.getFontMetrics(), g.getFontRenderContext());
        } finally {
            g.dispose();
        }
    }

    @Override
    public int stringWidth(@NotNull String str) {
        int w = 0;

        for (int i = 0; i < str.length(); i++)
            w += this.mcFont.glyph(str.charAt(i)).advanceWidth();

        return w;
    }

    @Override
    public int charWidth(int ch) {
        return this.mcFont.glyph(ch).advanceWidth();
    }

    @Override
    public int charWidth(char ch) {
        return this.mcFont.glyph(ch).advanceWidth();
    }

}
