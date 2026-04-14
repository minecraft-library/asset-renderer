package dev.sbs.renderer.text.font;

import dev.sbs.renderer.exception.FontException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.SystemUtil;
import lombok.Cleanup;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The Minecraft vanilla font family plus its two alternate-script companions, each wrapping a
 * pre-loaded {@link Font} and a lazy glyph atlas for pure {@link PixelBuffer} text rendering.
 * <p>
 * At enum initialization, the underlying {@code .otf} font is loaded via AWT and font-level
 * metrics (ascent, descent, height) are captured. Printable ASCII glyphs (codepoints 32-126)
 * are eagerly rasterized into the glyph cache so the first render has zero AWT overhead. All
 * remaining glyphs are lazily rasterized on first use and cached for subsequent renders.
 * <p>
 * Glyph bitmaps are stored as white-on-transparent {@link PixelBuffer} instances. At draw
 * time, callers tint each pixel by multiplying the glyph's alpha with a target color - no AWT
 * {@link Graphics2D} is needed after initialization.
 *
 * @see GlyphData
 * @see MinecraftFontMetrics
 */
@Getter
@RequiredArgsConstructor
public enum MinecraftFont {

    // Size 16.0f = 2x vanilla mcPixel resolution. The cached bitmap-derived OTFs are
    // designed on a unitsPerEm = 1024 grid where 128 units = 1 mcPixel, so 1 em corresponds
    // to 8 mcPixels. AWT renders at 72 DPI, so the load size must be an integer multiple of
    // 8 to keep every glyph on integer output pixel boundaries. See #MC_PIXEL_SCALE.
    REGULAR("Minecraft-Regular.otf", Style.REGULAR, 16.0f),
    BOLD("Minecraft-Bold.otf", Style.BOLD, 16.0f),
    ITALIC("Minecraft-Italic.otf", Style.ITALIC, 16.0f),
    BOLD_ITALIC("Minecraft-BoldItalic.otf", Style.BOLD_ITALIC, 16.0f),
    GALACTIC("Minecraft-Galactic.otf", Style.GALACTIC, 16.0f),
    ILLAGERALT("Minecraft-Illageralt.otf", Style.ILLAGERALT, 16.0f);

    private static final int EAGER_START = 32;
    private static final int EAGER_END = 126;

    /**
     * Output pixels per vanilla Minecraft pixel for the current native {@code 16.0f} load
     * size. Driven by the {@code unitsPerEm = 1024}, {@code 128 units = 1 mcPixel} layout
     * that the bitmap-to-OTF generator bakes into every font file. Callers positioning
     * text-adjacent geometry (line spacing, tooltip padding, decoration offsets) should
     * express their measurements in terms of this constant rather than hardcoding the
     * {@code 2x} factor.
     */
    public static final int MC_PIXEL_SCALE = 2;

    /** The underlying AWT font, retained for lazy glyph rasterization. */
    private final @NotNull java.awt.Font actual;

    /** The style category this enum value belongs to. */
    private final @NotNull Style style;

    /** The size in points used when the font was loaded. */
    private final float size;

    /** Font-level metrics backed by the glyph atlas, captured at init time. */
    private final @NotNull MinecraftFontMetrics fontMetrics;

    /** Lazily populated glyph cache - eagerly filled with ASCII at init, rest on demand. */
    private final @NotNull ConcurrentMap<Integer, GlyphData> glyphCache;

    MinecraftFont(@NotNull String fileName, @NotNull Style style, float size) {
        this.actual = initFont(String.format("fonts/%s", fileName), size);
        this.style = style;
        this.size = size;
        this.glyphCache = Concurrent.newMap();
        this.fontMetrics = MinecraftFontMetrics.capture(this);

        // Eagerly rasterize printable ASCII so the first render has zero AWT overhead.
        for (int cp = EAGER_START; cp <= EAGER_END; cp++)
            this.glyphCache.put(cp, rasterizeGlyph(cp));
    }

    /**
     * Returns the {@link MinecraftFont} whose {@link #getStyle() style} matches the given
     * {@link Style}, falling back to {@link #REGULAR} when no match exists.
     *
     * @param style the style to look up
     * @return the matching font, or {@link #REGULAR} when none is registered for the style
     */
    public static @NotNull MinecraftFont of(@NotNull Style style) {
        for (MinecraftFont font : values())
            if (font.getStyle() == style) return font;

        return REGULAR;
    }

    /**
     * Returns the cached glyph data for the given codepoint, lazily rasterizing it on first
     * access. Thread-safe via {@link ConcurrentMap#computeIfAbsent}.
     *
     * @param codepoint the Unicode codepoint
     * @return the cached glyph data
     */
    public @NotNull GlyphData glyph(int codepoint) {
        return this.glyphCache.computeIfAbsent(codepoint, this::rasterizeGlyph);
    }

    // --- init ---

    /**
     * Rasterizes a single glyph as white-on-transparent into a {@link PixelBuffer}. Queries the
     * AWT {@link FontMetrics} and {@link java.awt.font.FontRenderContext} already captured on
     * {@link #fontMetrics} rather than spinning up a throwaway scratch {@link Graphics2D} for
     * every codepoint - only the per-glyph {@link BufferedImage} (sized to the visual bounds)
     * is newly allocated.
     */
    private @NotNull GlyphData rasterizeGlyph(int codepoint) {
        int advanceWidth = this.fontMetrics.getAwtMetrics().charWidth(codepoint);
        char[] chars = Character.toChars(codepoint);
        GlyphVector gv = this.actual.createGlyphVector(this.fontMetrics.getAwtFrc(), chars);
        Rectangle2D bounds = gv.getVisualBounds();

        int bw = Math.max(1, (int) Math.ceil(bounds.getWidth()));
        int bh = Math.max(1, (int) Math.ceil(bounds.getHeight()));
        int bearingX = (int) Math.floor(bounds.getX());
        int bearingY = (int) Math.floor(bounds.getY());

        BufferedImage glyphImage = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glyphImage.createGraphics();

        try {
            gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gg.setFont(this.actual);
            gg.setColor(Color.WHITE);
            gg.drawString(new String(chars), -bearingX, -bearingY);
        } finally {
            gg.dispose();
        }

        return new GlyphData(PixelBuffer.wrap(glyphImage), advanceWidth, bearingX, bearingY);
    }

    private static @NotNull java.awt.Font initFont(@NotNull String resourcePath, float size) throws FontException {
        try {
            @Cleanup InputStream inputStream = SystemUtil.getResource(resourcePath);
            Font font = Font.createFont(
                Font.TRUETYPE_FONT,
                Objects.requireNonNull(inputStream)
                )
                .deriveFont(size);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (IOException | FontFormatException | NullPointerException ex) {
            throw new FontException(ex, resourcePath);
        }
    }

    // --- inner types ---

    /**
     * Rasterized glyph data: the bitmap pixels and positioning metrics needed to blit the
     * glyph at the correct location relative to the text cursor.
     *
     * @param bitmap the glyph pixels (white-on-transparent)
     * @param advanceWidth the horizontal cursor advance after this glyph
     * @param bearingX the left bearing - horizontal offset from cursor to left edge of bitmap
     * @param bearingY the top bearing - vertical offset from baseline to top edge of bitmap
     */
    public record GlyphData(
        @NotNull PixelBuffer bitmap,
        int advanceWidth,
        int bearingX,
        int bearingY
    ) {}

    /**
     * The style category a {@link MinecraftFont} entry belongs to.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Style {

        REGULAR(0),
        BOLD(1),
        ITALIC(2),
        BOLD_ITALIC(3),
        GALACTIC(4),
        ILLAGERALT(5);

        private final int id;

        /**
         * Returns the {@link Style} whose {@link #getId() id} matches the given value, or
         * {@link #REGULAR} when no match exists.
         *
         * @param id the style id to look up
         * @return the matching style, or {@link #REGULAR} when none matches
         */
        public static @NotNull Style of(int id) {
            for (Style style : values())
                if (style.getId() == id) return style;

            return REGULAR;
        }

    }

}
