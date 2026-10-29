package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Blits a {@code gui.scaling}-governed sprite (stretch, tile, nine_slice) into a rect in Minecraft pixels.
 * <p>
 * The reusable GUI-sprite compositor matching the {@link GlintKit} / {@link TextKit} static-kit
 * convention. Every {@code gui/sprites/**} widget scales through the same {@link MCMeta.GuiScaling}
 * system, and the themable tooltip chrome draws its sprite pair through it.
 * <p>
 * Each texel is laid down as the client lays a GUI sprite: its alpha scaled by the caller's
 * multiplier, a texel left at alpha 0 discarded so the destination pixel stays as it was, and every
 * other texel blended over the destination with {@link BlendMode#NORMAL}, the client's source-alpha
 * blend. A sprite drawn onto a transparent canvas therefore holds each translucent texel already
 * scaled by its alpha - the bytes the client's framebuffer holds - and a translucent ring texel
 * composites over the fill beneath it.
 * <p>
 * A sprite is drawn as the client draws a GUI sprite, in Minecraft pixels: {@code stretch} as one quad
 * over the rect, {@code tile} as a run of tiles of its declared size laid column by column, and
 * {@code nine_slice} with each border clamped to half the rect, as the whole sprite where the rect is its
 * declared size, three regions across where only the height is, three down where only the width is, and
 * otherwise its nine regions, corner by corner and edge by edge, the edges and centre stretched or tiled
 * as {@code stretch_inner} says. Every region is one quad whose texture coordinates are fractions of the
 * sprite's declared size, converted to output pixels at {@link MinecraftFont#MC_PIXEL_SCALE}; each output
 * pixel reads the one texel of the full texture under its centre, the texel after an edge that runs
 * through the centre, so a sprite authored at twice its declared size draws every texel.
 */
@UtilityClass
@Parity(claim = "text-closure", mode = Mode.DEMOTE)
public class NineSliceKit {

    /**
     * Blits {@code sprite} over the destination rect according to its scaling metadata, clipping to
     * the destination bounds (the rect may start off-canvas at a negative origin).
     *
     * @param dest the target buffer
     * @param sprite the sprite texture, at its declared size or any multiple of it
     * @param scaling the {@code gui.scaling} sidecar; {@code stretch} when a pack ships no mcmeta
     * @param x the rect's left edge in mcPixels
     * @param y the rect's top edge in mcPixels
     * @param w the rect's width in mcPixels
     * @param h the rect's height in mcPixels
     * @param alphaMul the per-texel alpha multiplier, applied before the texel blends; {@code 1.0} draws
     * the sprite as authored
     */
    public static void draw(
        @NotNull PixelBuffer dest,
        @NotNull PixelBuffer sprite,
        @NotNull MCMeta.GuiScaling scaling,
        int x, int y, int w, int h,
        float alphaMul
    ) {
        if (w <= 0 || h <= 0 || sprite.width() <= 0 || sprite.height() <= 0) return;

        blitSprite(sprite, scaling, x, y, w, h, (quadX, quadY, columns, rows) -> {
            int fromColumn = Math.max(0, -quadX);
            int toColumn = Math.min(columns.length, dest.width() - quadX);
            int fromRow = Math.max(0, -quadY);
            int toRow = Math.min(rows.length, dest.height() - quadY);
            for (int j = fromRow; j < toRow; j++) {
                int dy = quadY + j;
                int row = rows[j];
                for (int i = fromColumn; i < toColumn; i++) {
                    int dx = quadX + i;
                    int argb = multiply(sprite.getPixel(columns[i], row), alphaMul);
                    if (ColorMath.alpha(argb) != 0) dest.setPixel(dx, dy, ColorMath.blend(argb, dest.getPixel(dx, dy), BlendMode.NORMAL));
                }
            }
        });
    }

    /**
     * Answers the bounds of every pixel {@link #draw} would lay down for {@code sprite} over a
     * {@code w x h} rect at the origin, without drawing it - the same walk, each quad measured where the
     * draw lays it.
     * <p>
     * The bounds are rect-local and unclipped, in mcPixels and fractional where a drawn pixel's edge
     * falls inside a Minecraft pixel. They equal {@link PixelBuffer#opaqueBounds()} of the sprite drawn at
     * the origin of a clear canvas the rect's size, read in mcPixels, so a caller sizes its canvas to the
     * first pixel the sprite draws before drawing it. A sprite that draws nothing over the rect answers
     * none.
     *
     * @param sprite the sprite texture, at its declared size or any multiple of it
     * @param scaling the {@code gui.scaling} sidecar; {@code stretch} when a pack ships no mcmeta
     * @param w the rect's width in mcPixels
     * @param h the rect's height in mcPixels
     * @param alphaMul the per-texel alpha multiplier {@link #draw} would apply
     * @return the bounds of the pixels drawn, from the rect's origin in mcPixels, empty where the sprite
     * draws nothing
     */
    public static @NotNull Optional<Rectangle2D> extent(
        @NotNull PixelBuffer sprite,
        @NotNull MCMeta.GuiScaling scaling,
        int w, int h,
        float alphaMul
    ) {
        if (w <= 0 || h <= 0 || sprite.width() <= 0 || sprite.height() <= 0) return Optional.empty();

        List<Rectangle> drawn = new ArrayList<>();
        blitSprite(sprite, scaling, 0, 0, w, h, (quadX, quadY, columns, rows) ->
            measure(sprite, alphaMul, quadX, quadY, columns, rows).ifPresent(drawn::add));
        return drawn.stream().reduce(Rectangle::union).map(TextKit::mcPixels);
    }

    /**
     * Answers the output pixels one quad lays down - the bounds of every pixel whose texel the
     * multiplier leaves visible.
     *
     * @param sprite the sprite texture
     * @param alphaMul the per-texel alpha multiplier the draw applies
     * @param quadX the quad's first output column
     * @param quadY the quad's first output row
     * @param columns the texel column each output column of the quad reads
     * @param rows the texel row each output row of the quad reads
     * @return the bounds of the pixels the quad lays down in output pixels, empty where it lays none
     */
    private static @NotNull Optional<Rectangle> measure(
        @NotNull PixelBuffer sprite,
        float alphaMul,
        int quadX, int quadY,
        int @NotNull [] columns, int @NotNull [] rows
    ) {
        // A quad reads its texels in order, so its texel columns and rows each run from the first to the last.
        int firstColumn = columns[0];
        int firstRow = rows[0];
        boolean[] sampledColumn = new boolean[columns[columns.length - 1] - firstColumn + 1];
        boolean[] sampledRow = new boolean[rows[rows.length - 1] - firstRow + 1];
        for (int column : columns) sampledColumn[column - firstColumn] = true;
        for (int row : rows) sampledRow[row - firstRow] = true;

        // A texel is drawn wherever its column and its row are both sampled, so a sampled column holding
        // a drawn texel in any sampled row reaches every output column of the quad that reads it.
        boolean[] drawnColumn = new boolean[sampledColumn.length];
        boolean[] drawnRow = new boolean[sampledRow.length];
        for (int row = 0; row < sampledRow.length; row++) {
            if (!sampledRow[row]) continue;
            for (int column = 0; column < sampledColumn.length; column++) {
                if (!sampledColumn[column]) continue;
                if (ColorMath.alpha(multiply(sprite.getPixel(firstColumn + column, firstRow + row), alphaMul)) == 0) continue;
                drawnColumn[column] = true;
                drawnRow[row] = true;
            }
        }

        int left = -1;
        int right = -1;
        for (int i = 0; i < columns.length; i++) {
            if (!drawnColumn[columns[i] - firstColumn]) continue;
            if (left < 0) left = i;
            right = i + 1;
        }
        if (left < 0) return Optional.empty();

        int top = -1;
        int bottom = -1;
        for (int j = 0; j < rows.length; j++) {
            if (!drawnRow[rows[j] - firstRow]) continue;
            if (top < 0) top = j;
            bottom = j + 1;
        }
        return Optional.of(new Rectangle(quadX + left, quadY + top, right - left, bottom - top));
    }

    /**
     * Walks a sprite over a rect as the client's {@code GuiGraphicsExtractor#blitSprite} does, by the mode
     * its scaling names: a stretch as one quad over the whole texture, a tile as a run of tiles of its
     * declared size, and a nine-slice through {@link #blitNineSlicedSprite}.
     *
     * @param sprite the sprite texture
     * @param scaling the sprite's scaling
     * @param x the rect's left edge in mcPixels
     * @param y the rect's top edge in mcPixels
     * @param w the rect's width in mcPixels
     * @param h the rect's height in mcPixels
     * @param run what each quad of the walk does
     */
    private static void blitSprite(
        @NotNull PixelBuffer sprite,
        @NotNull MCMeta.GuiScaling scaling,
        int x, int y, int w, int h,
        @NotNull QuadRun run
    ) {
        switch (scaling) {
            case MCMeta.GuiScaling.Stretch stretch ->
                innerBlit(sprite, x, x + w, y, y + h, 0, sprite.width(), sprite.width(), 0, sprite.height(), sprite.height(), run);
            case MCMeta.GuiScaling.Tile tile ->
                blitTiledSprite(sprite, x, y, w, h, 0, 0, tile.width(), tile.height(), tile.width(), tile.height(), run);
            case MCMeta.GuiScaling.NineSlice nineSlice -> blitNineSlicedSprite(sprite, nineSlice, x, y, w, h, run);
        }
    }

    /**
     * Walks one region of a sprite drawn one Minecraft pixel per declared pixel, as the client's private
     * {@code blitSprite} overload does: the region read from {@code (u, v)} is the size it is drawn at.
     *
     * @param sprite the sprite texture
     * @param spriteW the sprite's declared width
     * @param spriteH the sprite's declared height
     * @param u the region's left edge in the sprite, in declared pixels
     * @param v the region's top edge in the sprite, in declared pixels
     * @param x the region's left edge in mcPixels
     * @param y the region's top edge in mcPixels
     * @param w the region's width in mcPixels
     * @param h the region's height in mcPixels
     * @param run what the region's quad does
     */
    private static void blitSprite(
        @NotNull PixelBuffer sprite,
        int spriteW, int spriteH,
        int u, int v,
        int x, int y, int w, int h,
        @NotNull QuadRun run
    ) {
        if (w == 0 || h == 0) return;

        innerBlit(sprite, x, x + w, y, y + h, u, u + w, spriteW, v, v + h, spriteH, run);
    }

    /**
     * Walks a nine-sliced sprite over a rect as the client's {@code blitNineSlicedSprite} does. Each
     * border is clamped to half the rect; then a rect the sprite's declared size draws the whole sprite
     * as one region, a rect only its height draws the left column, the inner segment and the right
     * column, a rect only its width draws the top row, the inner segment and the bottom row, and any
     * other draws the nine regions - the top-left corner, the top edge, the top-right corner, the
     * bottom-left corner, the bottom edge, the bottom-right corner, the left edge, the centre and the
     * right edge. Every inner segment reads the span the clamped borders leave, and the far corners read
     * from the sprite's far edge.
     *
     * @param sprite the sprite texture
     * @param nineSlice the sprite's nine-slice scaling
     * @param x the rect's left edge in mcPixels
     * @param y the rect's top edge in mcPixels
     * @param w the rect's width in mcPixels
     * @param h the rect's height in mcPixels
     * @param run what each quad of the walk does
     */
    private static void blitNineSlicedSprite(
        @NotNull PixelBuffer sprite,
        @NotNull MCMeta.GuiScaling.NineSlice nineSlice,
        int x, int y, int w, int h,
        @NotNull QuadRun run
    ) {
        MCMeta.GuiScaling.NineSlice.Border border = nineSlice.border();
        int left = Math.min(border.left(), w / 2);
        int right = Math.min(border.right(), w / 2);
        int top = Math.min(border.top(), h / 2);
        int bottom = Math.min(border.bottom(), h / 2);
        int spriteW = nineSlice.width();
        int spriteH = nineSlice.height();

        if (w == spriteW && h == spriteH) {
            blitSprite(sprite, spriteW, spriteH, 0, 0, x, y, w, h, run);
            return;
        }

        if (h == spriteH) {
            blitSprite(sprite, spriteW, spriteH, 0, 0, x, y, left, h, run);
            blitNineSliceInnerSegment(nineSlice, sprite, x + left, y, w - right - left, h,
                left, 0, spriteW - right - left, spriteH, spriteW, spriteH, run);
            blitSprite(sprite, spriteW, spriteH, spriteW - right, 0, x + w - right, y, right, h, run);
            return;
        }

        if (w == spriteW) {
            blitSprite(sprite, spriteW, spriteH, 0, 0, x, y, w, top, run);
            blitNineSliceInnerSegment(nineSlice, sprite, x, y + top, w, h - bottom - top,
                0, top, spriteW, spriteH - bottom - top, spriteW, spriteH, run);
            blitSprite(sprite, spriteW, spriteH, 0, spriteH - bottom, x, y + h - bottom, w, bottom, run);
            return;
        }

        blitSprite(sprite, spriteW, spriteH, 0, 0, x, y, left, top, run);
        blitNineSliceInnerSegment(nineSlice, sprite, x + left, y, w - right - left, top,
            left, 0, spriteW - right - left, top, spriteW, spriteH, run);
        blitSprite(sprite, spriteW, spriteH, spriteW - right, 0, x + w - right, y, right, top, run);
        blitSprite(sprite, spriteW, spriteH, 0, spriteH - bottom, x, y + h - bottom, left, bottom, run);
        blitNineSliceInnerSegment(nineSlice, sprite, x + left, y + h - bottom, w - right - left, bottom,
            left, spriteH - bottom, spriteW - right - left, bottom, spriteW, spriteH, run);
        blitSprite(sprite, spriteW, spriteH, spriteW - right, spriteH - bottom, x + w - right, y + h - bottom, right, bottom, run);
        blitNineSliceInnerSegment(nineSlice, sprite, x, y + top, left, h - bottom - top,
            0, top, left, spriteH - bottom - top, spriteW, spriteH, run);
        blitNineSliceInnerSegment(nineSlice, sprite, x + left, y + top, w - right - left, h - bottom - top,
            left, top, spriteW - right - left, spriteH - bottom - top, spriteW, spriteH, run);
        blitNineSliceInnerSegment(nineSlice, sprite, x + w - right, y + top, right, h - bottom - top,
            spriteW - right, top, right, spriteH - bottom - top, spriteW, spriteH, run);
    }

    /**
     * Walks one edge or the centre of a nine-sliced sprite as the client's
     * {@code blitNineSliceInnerSegment} does: the source region stretched over the segment as one quad
     * under {@code stretch_inner}, else tiled over it from its top-left corner.
     *
     * @param nineSlice the sprite's nine-slice scaling
     * @param sprite the sprite texture
     * @param x the segment's left edge in mcPixels
     * @param y the segment's top edge in mcPixels
     * @param w the segment's width in mcPixels
     * @param h the segment's height in mcPixels
     * @param u the source region's left edge in the sprite, in declared pixels
     * @param v the source region's top edge in the sprite, in declared pixels
     * @param regionW the source region's width in declared pixels
     * @param regionH the source region's height in declared pixels
     * @param spriteW the sprite's declared width
     * @param spriteH the sprite's declared height
     * @param run what each quad of the segment does
     */
    private static void blitNineSliceInnerSegment(
        @NotNull MCMeta.GuiScaling.NineSlice nineSlice,
        @NotNull PixelBuffer sprite,
        int x, int y, int w, int h,
        int u, int v,
        int regionW, int regionH,
        int spriteW, int spriteH,
        @NotNull QuadRun run
    ) {
        if (w <= 0 || h <= 0) return;

        if (nineSlice.stretchInner())
            innerBlit(sprite, x, x + w, y, y + h, u, u + regionW, spriteW, v, v + regionH, spriteH, run);
        else
            blitTiledSprite(sprite, x, y, w, h, u, v, regionW, regionH, spriteW, spriteH, run);
    }

    /**
     * Walks a tiled span as the client's {@code blitTiledSprite} and {@code TiledBlitRenderState} lay it:
     * column by column, each column's tiles top to bottom, every tile one quad, and a tile cut by the
     * span's right or bottom edge keeping its leading part. Every tile it is handed is at least one pixel
     * each way - a tile's declared size, or a source span of a nine-slice whose borders leave a centre.
     *
     * @param sprite the sprite texture
     * @param x the span's left edge in mcPixels
     * @param y the span's top edge in mcPixels
     * @param w the span's width in mcPixels
     * @param h the span's height in mcPixels
     * @param u the tile's left edge in the sprite, in declared pixels
     * @param v the tile's top edge in the sprite, in declared pixels
     * @param tileW the tile's width in declared pixels
     * @param tileH the tile's height in declared pixels
     * @param spriteW the sprite's declared width
     * @param spriteH the sprite's declared height
     * @param run what each tile's quad does
     */
    private static void blitTiledSprite(
        @NotNull PixelBuffer sprite,
        int x, int y, int w, int h,
        int u, int v,
        int tileW, int tileH,
        int spriteW, int spriteH,
        @NotNull QuadRun run
    ) {
        for (int i = 0; i < w; i += tileW) {
            int columnW = Math.min(tileW, w - i);
            for (int j = 0; j < h; j += tileH) {
                int rowH = Math.min(tileH, h - j);
                innerBlit(sprite, x + i, x + i + columnW, y + j, y + j + rowH, u, u + columnW, spriteW, v, v + rowH, spriteH, run);
            }
        }
    }

    /**
     * Lays one quad, as the client's {@code innerBlit} does: the rect from {@code (x0, y0)} to
     * {@code (x1, y1)} in mcPixels, textured across from {@code u0 / spriteW} to {@code u1 / spriteW} and
     * down from {@code v0 / spriteH} to {@code v1 / spriteH} of the full texture, converted to output
     * pixels at {@link MinecraftFont#MC_PIXEL_SCALE}.
     *
     * @param sprite the sprite texture
     * @param x0 the quad's left edge in mcPixels
     * @param x1 the quad's right edge in mcPixels
     * @param y0 the quad's top edge in mcPixels
     * @param y1 the quad's bottom edge in mcPixels
     * @param u0 the quad's left texture coordinate, in the sprite's declared pixels
     * @param u1 the quad's right texture coordinate, in the sprite's declared pixels
     * @param spriteW the sprite's declared width
     * @param v0 the quad's top texture coordinate, in the sprite's declared pixels
     * @param v1 the quad's bottom texture coordinate, in the sprite's declared pixels
     * @param spriteH the sprite's declared height
     * @param run what the quad does
     */
    private static void innerBlit(
        @NotNull PixelBuffer sprite,
        int x0, int x1, int y0, int y1,
        int u0, int u1, int spriteW,
        int v0, int v1, int spriteH,
        @NotNull QuadRun run
    ) {
        int pxPerMcPx = MinecraftFont.MC_PIXEL_SCALE;
        run.lay(x0 * pxPerMcPx, y0 * pxPerMcPx,
            texels(sprite.width(), u0, u1, spriteW, (x1 - x0) * pxPerMcPx),
            texels(sprite.height(), v0, v1, spriteH, (y1 - y0) * pxPerMcPx));
    }

    /**
     * Answers the texel each output pixel of one quad axis reads: the one of the full texture under the
     * pixel's centre, the coordinate interpolated from {@code from} to {@code to} across the quad and
     * scaled from the sprite's declared size to the texture's, and the texel after an edge that runs
     * through the centre.
     *
     * @param textureSize the texture's size on the axis, in texels
     * @param from the quad's first texture coordinate on the axis, in the sprite's declared pixels
     * @param to the quad's last texture coordinate on the axis, in the sprite's declared pixels
     * @param declaredSize the sprite's declared size on the axis
     * @param outPx the quad's size on the axis, in output pixels
     * @return the texel each output pixel reads, in order
     */
    private static int @NotNull [] texels(int textureSize, int from, int to, int declaredSize, int outPx) {
        // Output pixel o's centre lies textureSize * (2 * outPx * from + (to - from) * (2o + 1)) / denominator
        // texels in, and each pixel adds 2 * textureSize * (to - from) to that numerator, so the texel and
        // the remainder below it step by that quotient and remainder, carrying a whole texel when the
        // remainder fills.
        long denominator = 2L * outPx * declaredSize;
        long numerator = (long) textureSize * (2L * outPx * from + (to - from));
        long step = 2L * textureSize * (to - from);
        long texelStep = step / denominator;
        long remainderStep = step % denominator;
        long texel = numerator / denominator;
        long remainder = numerator % denominator;

        int[] texels = new int[outPx];
        for (int o = 0; o < outPx; o++) {
            texels[o] = (int) texel;
            texel += texelStep;
            remainder += remainderStep;
            if (remainder >= denominator) {
                texel++;
                remainder -= denominator;
            }
        }
        return texels;
    }

    /** Scales a texel's alpha by {@code alphaMul}, leaving the rgb and (at {@code 1.0}) the exact bytes untouched. */
    private static int multiply(int argb, float alphaMul) {
        if (alphaMul == 1.0f) return argb;
        int alpha = Math.clamp(Math.round(ColorMath.alpha(argb) * alphaMul), 0, 255);
        return ColorMath.withAlpha(argb, alpha);
    }

    /**
     * One quad of the walk, drawn or measured.
     */
    @FunctionalInterface
    private interface QuadRun {

        /**
         * Runs one quad where the walk lays it.
         *
         * @param x the quad's first output column
         * @param y the quad's first output row
         * @param columns the texel column each output column reads
         * @param rows the texel row each output row reads
         */
        void lay(int x, int y, int @NotNull [] columns, int @NotNull [] rows);

    }

}
