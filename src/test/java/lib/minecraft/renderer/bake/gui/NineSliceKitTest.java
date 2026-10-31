package lib.minecraft.renderer.bake.gui;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link NineSliceKit}, exercising the three scaling modes against the real 26.1
 * tooltip sprites (background border 9 / frame border 10 + stretch_inner) over a rect that overhangs
 * the canvas, alpha multiplier included, plus the pure-algorithm cases (stretch, tile, a sprite off its
 * declared size, inner stretch, the short-cut branches, a zero border side, degenerate-rect clamp) on
 * synthetic sprites so they run without the client jar.
 * <p>
 * The real-sprite cases blit over the rect the tooltip chrome hands the kit for vanilla's pair: the
 * client's rect, overhanging the canvas by 8 Minecraft pixels a side - the 8 outer texels both sprites
 * leave transparent - so the kit's clip to the destination bounds drops exactly that border and the box
 * lands flush with the canvas edge. These cases are what pins that clip.
 * <p>
 * The extent cases hold the kit's two answers to one another: {@link NineSliceKit#extent} against
 * {@link PixelBuffer#opaqueBounds()} of what {@link NineSliceKit#draw} lays down on a clear canvas the
 * rect's size, read in Minecraft pixels, over the real sprites and over a synthetic sprite in every
 * scaling mode.
 * <p>
 * The real-sprite cases pin the bytes the kit's blend leaves on a clear canvas: each translucent texel
 * keeps its alpha and has its colour scaled by it, as the client's framebuffer holds it. The synthetic
 * sprites are opaque, and the blend lays an opaque texel down as it is.
 * <p>
 * The real-sprite cases skip per method rather than per class, so they run wherever the client jar is
 * cached while the synthetic ones run everywhere.
 */
class NineSliceKitTest {

    /** The vanilla tooltip sprite directory in the vanilla pack; the real-sprite tests skip without a cached client jar. */
    private static final String TOOLTIP_DIR = "assets/minecraft/textures/gui/sprites/tooltip";

    private static final int PX_PER_MCPX = MinecraftFont.MC_PIXEL_SCALE;

    // The overhanging blit geometry, the tooltip chrome's own for vanilla's pair: a fresh canvas of
    // CANVAS_W_MCPX x CANVAS_H_MCPX Minecraft pixels, with both sprites drawn over the client's rect
    // overhanging it 8 mcPx each side - the 8 outer texels both sprites leave transparent - so the clip
    // drops exactly that border, the background fill lands flush to the canvas edge and the frame ring
    // 1 mcPx inside it. Every pixel assertion reads the canvas in output pixels.
    private static final int CANVAS_W_MCPX = 40;
    private static final int CANVAS_H_MCPX = 20;
    private static final int INFLATE_MCPX = 8;
    private static final int BLIT_X = -INFLATE_MCPX;
    private static final int BLIT_Y = -INFLATE_MCPX;
    private static final int BLIT_W = CANVAS_W_MCPX + 2 * INFLATE_MCPX;
    private static final int BLIT_H = CANVAS_H_MCPX + 2 * INFLATE_MCPX;
    private static final int CANVAS_W = CANVAS_W_MCPX * PX_PER_MCPX;
    private static final int CANVAS_H = CANVAS_H_MCPX * PX_PER_MCPX;

    private static PixelBuffer load(String name) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(tooltipFile(name)));
            return PixelBuffer.wrap(image);
        } catch (IOException ex) {
            throw new AssertionError("Failed to read tooltip sprite '" + name + "'", ex);
        }
    }

    private static MCMeta.GuiScaling scaling(String mcmetaName) {
        String json = new String(tooltipFile(mcmetaName), StandardCharsets.UTF_8);
        MCMeta meta = MCMetaParser.parse(json, new ResourceId("minecraft", "tooltip"));
        return meta.gui().orElseThrow(() -> new AssertionError("no gui.scaling in " + mcmetaName));
    }

    private static byte[] tooltipFile(String name) {
        return ClientAssetsExtension.vanilla().bytes(TOOLTIP_DIR + "/" + name)
            .orElseThrow(() -> new AssertionError("no tooltip file '" + name + "' in the vanilla pack"));
    }

    private static void assumeSprites() {
        Assumptions.assumeTrue(ClientAssetsExtension.isCached(), "no cached vanilla 26.1 client jar");
    }

    @Test
    @DisplayName("real background border-9: corner notched, fill flush to canvas edges")
    void backgroundNineSliceNotchAndFill() {
        assumeSprites();
        PixelBuffer bg = load("background.png");
        MCMeta.GuiScaling gui = scaling("background.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.canvas(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, bg, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, 1.0f);

        // Corner mcPixel is notched (sprite (8,8) is the cut corner) -> transparent.
        assertThat("notched top-left corner", ColorMath.alpha(canvas.getPixel(0, 0)), is(0));
        // Fill reaches the top edge mid-span and the left edge mid-span (only corners are cut), the
        // F0100010 texel blended onto the clear canvas.
        assertThat("fill flush top edge", canvas.getPixel(CANVAS_W / 2, 0), is(0xF00F000F));
        assertThat("fill flush left edge", canvas.getPixel(0, CANVAS_H / 2), is(0xF00F000F));
        assertThat("center fill", canvas.getPixel(CANVAS_W / 2, CANVAS_H / 2), is(0xF00F000F));
    }

    @Test
    @DisplayName("real frame border-10 + stretch_inner: ring 1 mcPx in, open corners, stretched gradient")
    void frameNineSliceRingAndGradient() {
        assumeSprites();
        PixelBuffer frame = load("frame.png");
        MCMeta.GuiScaling gui = scaling("frame.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.canvas(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, frame, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, 1.0f);

        // Ring top stroke sits at output y=2 (1 mcPx inset), its 505000FF texel blended onto the clear canvas.
        assertThat("ring top", canvas.getPixel(CANVAS_W / 2, 2), is(0x50190050));
        // Ring bottom stroke at output y=canvasH-3, its 5028007F texel blended onto the clear canvas.
        assertThat("ring bottom", canvas.getPixel(CANVAS_W / 2, CANVAS_H - 3), is(0x500D0028));
        // The ring corner pixel is transparent in the sprite -> open corner (destination untouched).
        assertThat("open ring corner", ColorMath.alpha(canvas.getPixel(2, 2)), is(0));
        // Left edge mid-height is the stretched gradient interior: border alpha 0x50, green constant 0,
        // colour strictly between the two endpoints.
        int mid = canvas.getPixel(2, CANVAS_H / 2);
        assertThat("left edge alpha", ColorMath.alpha(mid), is(0x50));
        assertThat("left edge green constant", ColorMath.green(mid), is(0));
        assertThat("left edge is interpolated (not top)", mid, is(not(0x50190050)));
        assertThat("left edge is interpolated (not bottom)", mid, is(not(0x500D0028)));
    }

    @Test
    @DisplayName("alpha multiplier scales the ring texel's alpha before it blends")
    void alphaMultiplierScalesRingAlpha() {
        assumeSprites();
        PixelBuffer frame = load("frame.png");
        MCMeta.GuiScaling gui = scaling("frame.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.canvas(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, frame, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, 0.5f);

        // 0x50 (80) * 0.5 = 40 = 0x28, then the halved 285000FF texel blends onto the clear canvas:
        // 0x50 * 40/255 rounds to 0x0D and 0xFF * 40/255 to 0x28.
        assertThat("halved ring texel blended", canvas.getPixel(CANVAS_W / 2, 2), is(0x280D0028));
    }

    @Test
    @DisplayName("the extent of the real sprites is the bounds of what they draw, at every rect and multiplier")
    void extentIsWhatTheRealSpritesDraw() {
        assumeSprites();
        int[][] rects = {{16, 16}, {40, 20}, {165, 85}};
        float[] multipliers = {1.0f, 0.5f, 0.0f};
        for (String name : new String[]{"background.png", "frame.png"}) {
            PixelBuffer sprite = load(name);
            MCMeta.GuiScaling gui = scaling(name + ".mcmeta");
            for (int[] rect : rects)
                for (float mul : multipliers)
                    assertExtentIsDrawn(name, sprite, gui, rect[0], rect[1], mul);
        }
    }

    @Test
    @DisplayName("the extent is the bounds of what the kit draws in every scaling mode, a texture off its declared size and a texel the multiplier clears")
    void extentIsWhatEveryScalingDraws() {
        PixelBuffer inner = opaqueSquare(10, 2, 7);
        assertExtentIsDrawn("stretch", inner, new MCMeta.GuiScaling.Stretch(), 19, 12, 1.0f);
        assertExtentIsDrawn("tile", inner, new MCMeta.GuiScaling.Tile(10, 10), 25, 15, 1.0f);
        assertExtentIsDrawn("nine_slice border 3 stretch_inner", inner,
            new MCMeta.GuiScaling.NineSlice(10, 10, new MCMeta.GuiScaling.NineSlice.Border(3, 3, 3, 3), true),
            21, 15, 1.0f);
        assertExtentIsDrawn("20x20 declared 10x10", opaqueSquare(20, 4, 15),
            new MCMeta.GuiScaling.Tile(10, 10), 19, 12, 1.0f);

        // An alpha-80 texel at the corner scales to alpha 0.4 at 0.005, which rounds to 0 and is
        // discarded, while the opaque texels keep alpha 1.
        PixelBuffer faint = opaqueSquare(10, 2, 7);
        faint.setPixel(0, 0, 0x50FFFFFF);
        assertExtentIsDrawn("alpha-80 texel cleared by the multiplier", faint,
            new MCMeta.GuiScaling.Stretch(), 19, 12, 0.005f);

        // The sprites authored at twice their declared size, each texel drawn.
        assertExtentIsDrawn("4x4 tile declared 2x2", distinctTexels(4, 4), new MCMeta.GuiScaling.Tile(2, 2), 4, 2, 1.0f);
        assertExtentIsDrawn("20x20 nine_slice declared 10x10", opaqueSquare(20, 1, 18),
            new MCMeta.GuiScaling.NineSlice(10, 10, new MCMeta.GuiScaling.NineSlice.Border(2, 2, 2, 2), true),
            12, 12, 1.0f);
        assertExtentIsDrawn("4x4 stretch", opaqueSquare(4, 1, 2), new MCMeta.GuiScaling.Stretch(), 2, 2, 1.0f);
    }

    /**
     * Asserts that {@link NineSliceKit#extent} answers {@link PixelBuffer#opaqueBounds()} of the sprite
     * drawn at the origin of a clear canvas the rect's size, read in Minecraft pixels, and nothing where
     * the draw lays nothing.
     *
     * @param label the sprite's name in the assertion
     * @param sprite the sprite
     * @param gui the sprite's scaling
     * @param wMcPx the rect width in mcPixels
     * @param hMcPx the rect height in mcPixels
     * @param mul the alpha multiplier
     */
    private static void assertExtentIsDrawn(String label, PixelBuffer sprite, MCMeta.GuiScaling gui, int wMcPx, int hMcPx, float mul) {
        PixelBuffer canvas = PixelBuffer.canvas(wMcPx * PX_PER_MCPX, hMcPx * PX_PER_MCPX);
        NineSliceKit.draw(canvas, sprite, gui, 0, 0, wMcPx, hMcPx, mul);
        Rectangle drawn = canvas.opaqueBounds();
        Optional<Rectangle2D> expected = drawn.isEmpty() ? Optional.empty() : Optional.of(TextKit.mcPixels(drawn));
        assertThat(String.format("extent of %s over %dx%d mcPx at multiplier %s", label, wMcPx, hMcPx, mul),
            NineSliceKit.extent(sprite, gui, wMcPx, hMcPx, mul), is(expected));
    }

    /**
     * Builds a square sprite, transparent but for an opaque white square over the given texel span on
     * both axes.
     *
     * @param size the sprite's width and height in texels
     * @param first the first opaque texel on each axis
     * @param last the last opaque texel on each axis
     * @return the sprite
     */
    private static PixelBuffer opaqueSquare(int size, int first, int last) {
        PixelBuffer sprite = PixelBuffer.straight(size, size);
        for (int y = first; y <= last; y++)
            for (int x = first; x <= last; x++)
                sprite.setPixel(x, y, 0xFFFFFFFF);
        return sprite;
    }

    /**
     * Builds a sprite whose every texel is a distinct opaque colour, so a drawn pixel names the texel it
     * read.
     *
     * @param width the sprite's width in texels
     * @param height the sprite's height in texels
     * @return the sprite
     */
    private static PixelBuffer distinctTexels(int width, int height) {
        PixelBuffer sprite = PixelBuffer.straight(width, height);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                sprite.setPixel(x, y, 0xFF000000 | (x << 16) | (y << 8) | 0x55);
        return sprite;
    }

    /**
     * Answers the pixels of a buffer's top-left area, row by row.
     *
     * @param buffer the buffer
     * @param width the area's width in output pixels
     * @param height the area's height in output pixels
     * @return the area's pixels, left to right and top to bottom
     */
    private static int[] pixels(PixelBuffer buffer, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y * width + x] = buffer.getPixel(x, y);
        return pixels;
    }

    /**
     * Answers the pixels of an output-pixel area where each pixel shows the sprite texel the two maps
     * name for its column and its row.
     *
     * @param sprite the sprite read
     * @param width the area's width in output pixels
     * @param height the area's height in output pixels
     * @param column the texel column each output column reads
     * @param row the texel row each output row reads
     * @return the expected pixels, left to right and top to bottom
     */
    private static int[] texelsRead(PixelBuffer sprite, int width, int height, IntUnaryOperator column, IntUnaryOperator row) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y * width + x] = sprite.getPixel(column.applyAsInt(x), row.applyAsInt(y));
        return pixels;
    }

    @Test
    @DisplayName("stretch mode expands a 2x2 sprite across quadrants")
    void stretchExpandsSprite() {
        int[] px = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF}; // R G B W, row-major
        PixelBuffer sprite = PixelBuffer.ofStraight(px, 2, 2);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling.Stretch();
        PixelBuffer dest = PixelBuffer.canvas(4, 4);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 2, 2, 1.0f);

        assertThat(dest.getPixel(0, 0), is(0xFFFF0000));
        assertThat(dest.getPixel(3, 0), is(0xFF00FF00));
        assertThat(dest.getPixel(0, 3), is(0xFF0000FF));
        assertThat(dest.getPixel(3, 3), is(0xFFFFFFFF));
    }

    @Test
    @DisplayName("tile mode repeats the sprite from the top-left")
    void tileRepeatsSprite() {
        int[] px = {0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD};
        PixelBuffer sprite = PixelBuffer.ofStraight(px, 2, 2);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling.Tile(2, 2);
        PixelBuffer dest = PixelBuffer.canvas(8, 8);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 4, 4, 1.0f);

        assertThat("tile repeats horizontally", dest.getPixel(4, 0), is(dest.getPixel(0, 0)));
        assertThat("tile repeats vertically", dest.getPixel(0, 4), is(dest.getPixel(0, 0)));
        assertThat("period-2 second column", dest.getPixel(6, 0), is(dest.getPixel(2, 0)));
    }

    @Test
    @DisplayName("a sprite authored at twice its declared size draws every texel of its full texture")
    void aSpriteOffItsDeclaredSizeDrawsEveryTexel() {
        // A 4x4 tile declared 2x2 over a 4x2 mcPx rect: each 2x2 mcPx tile is 4x4 output px reading the
        // 4x4 texture one texel per output pixel, so all four columns are drawn and repeat with the tile.
        PixelBuffer tileTexture = distinctTexels(4, 4);
        PixelBuffer tiled = PixelBuffer.canvas(8, 4);
        NineSliceKit.draw(tiled, tileTexture, new MCMeta.GuiScaling.Tile(2, 2), 0, 0, 4, 2, 1.0f);
        assertThat("tile declared 2x2 reads every texel", pixels(tiled, 8, 4),
            is(texelsRead(tileTexture, 8, 4, p -> p % 4, q -> q)));

        // A 20x20 nine-slice declared 10x10 with a border of 2: its top-left corner is 2x2 mcPx, 4x4
        // output px, reading texels (0..3, 0..3) one each.
        PixelBuffer nineTexture = distinctTexels(20, 20);
        PixelBuffer nine = PixelBuffer.canvas(24, 24);
        NineSliceKit.draw(nine, nineTexture,
            new MCMeta.GuiScaling.NineSlice(10, 10, new MCMeta.GuiScaling.NineSlice.Border(2, 2, 2, 2), true),
            0, 0, 12, 12, 1.0f);
        assertThat("nine-slice declared 10x10 corner reads every texel", pixels(nine, 4, 4),
            is(texelsRead(nineTexture, 4, 4, p -> p, q -> q)));

        // A stretch sidecar declaring a size: the client's stretch holds none, so the whole 4x4 texture
        // stretches over the 2x2 mcPx rect, one texel per output pixel.
        MCMeta.GuiScaling stretch = MCMetaParser.parse(
                "{\"gui\":{\"scaling\":{\"type\":\"stretch\",\"width\":2,\"height\":2}}}", new ResourceId("minecraft", "tooltip"))
            .gui().orElseThrow();
        PixelBuffer stretchTexture = distinctTexels(4, 4);
        PixelBuffer stretched = PixelBuffer.canvas(4, 4);
        NineSliceKit.draw(stretched, stretchTexture, stretch, 0, 0, 2, 2, 1.0f);
        assertThat("stretch declaring 2x2 reads every texel", pixels(stretched, 4, 4),
            is(texelsRead(stretchTexture, 4, 4, p -> p, q -> q)));
    }

    @Test
    @DisplayName("a rect the nine-slice's declared size draws the whole sprite one Minecraft pixel per texel")
    void theWholeSpriteBranchDrawsTheSpriteOneToOne() {
        PixelBuffer sprite = distinctTexels(6, 6);
        PixelBuffer dest = PixelBuffer.canvas(12, 12);

        NineSliceKit.draw(dest, sprite,
            new MCMeta.GuiScaling.NineSlice(6, 6, new MCMeta.GuiScaling.NineSlice.Border(2, 2, 2, 2), false),
            0, 0, 6, 6, 1.0f);

        assertThat(pixels(dest, 12, 12), is(texelsRead(sprite, 12, 12, p -> p / PX_PER_MCPX, q -> q / PX_PER_MCPX)));
    }

    @Test
    @DisplayName("a rect only the nine-slice's declared height draws the sprite's whole height, its centre stretched or tiled across")
    void theAcrossBranchKeepsTheSpritesHeight() {
        // An 11x6 mcPx rect on a 6x6 sprite with a border of 2: output columns 0-3 read the left border's
        // texel columns 0-1, 18-21 the right border's 4-5, and the 14 between the centre's 2-3.
        PixelBuffer sprite = distinctTexels(6, 6);
        for (boolean stretchInner : new boolean[]{false, true}) {
            PixelBuffer dest = PixelBuffer.canvas(22, 12);
            NineSliceKit.draw(dest, sprite,
                new MCMeta.GuiScaling.NineSlice(6, 6, new MCMeta.GuiScaling.NineSlice.Border(2, 2, 2, 2), stretchInner),
                0, 0, 11, 6, 1.0f);

            IntUnaryOperator centre = stretchInner ? (off -> off < 7 ? 2 : 3) : (off -> 2 + (off % 4) / 2);
            IntUnaryOperator column = p -> p < 4 ? p / 2 : p < 18 ? centre.applyAsInt(p - 4) : 4 + (p - 18) / 2;
            assertThat("stretch_inner " + stretchInner, pixels(dest, 22, 12), is(texelsRead(sprite, 22, 12, column, q -> q / 2)));
        }
    }

    @Test
    @DisplayName("a rect only the nine-slice's declared width draws the sprite's whole width, its centre stretched or tiled down")
    void theDownBranchKeepsTheSpritesWidth() {
        // A 6x11 mcPx rect on a 6x6 sprite with a border of 2: output rows 0-3 read the top border's
        // texel rows 0-1, 18-21 the bottom border's 4-5, and the 14 between the centre's 2-3.
        PixelBuffer sprite = distinctTexels(6, 6);
        for (boolean stretchInner : new boolean[]{false, true}) {
            PixelBuffer dest = PixelBuffer.canvas(12, 22);
            NineSliceKit.draw(dest, sprite,
                new MCMeta.GuiScaling.NineSlice(6, 6, new MCMeta.GuiScaling.NineSlice.Border(2, 2, 2, 2), stretchInner),
                0, 0, 6, 11, 1.0f);

            IntUnaryOperator centre = stretchInner ? (off -> off < 7 ? 2 : 3) : (off -> 2 + (off % 4) / 2);
            IntUnaryOperator row = q -> q < 4 ? q / 2 : q < 18 ? centre.applyAsInt(q - 4) : 4 + (q - 18) / 2;
            assertThat("stretch_inner " + stretchInner, pixels(dest, 12, 22), is(texelsRead(sprite, 12, 22, p -> p / 2, row)));
        }
    }

    @Test
    @DisplayName("a nine-slice's centre stretches under stretch_inner and tiles without it")
    void stretchInnerStretchesOrTilesTheCentre() {
        // A 4x1 sprite with a border of 1 on the left and the right: columns 0 and 3 are the borders and
        // columns 1 and 2 the centre. Over 6 mcPx each border keeps its one column and the centre spans
        // 4 mcPx, so a stretched centre reads each of its columns twice in turn and a tiled one repeats
        // the pair.
        int[] columns = {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444};
        PixelBuffer sprite = PixelBuffer.ofStraight(columns, 4, 1);
        MCMeta.GuiScaling.NineSlice.Border border = new MCMeta.GuiScaling.NineSlice.Border(1, 0, 1, 0);

        assertThat("stretched centre", drawnRow(sprite, new MCMeta.GuiScaling.NineSlice(4, 1, border, true), 6),
            is(perOutputPixel(columns[0], columns[1], columns[1], columns[2], columns[2], columns[3])));
        assertThat("tiled centre", drawnRow(sprite, new MCMeta.GuiScaling.NineSlice(4, 1, border, false), 6),
            is(perOutputPixel(columns[0], columns[1], columns[2], columns[1], columns[2], columns[3])));
    }

    /**
     * Draws a sprite one texel high across a rect one Minecraft pixel high, and answers the first output
     * row it lays down.
     *
     * @param sprite the sprite, one texel high
     * @param gui the sprite's scaling
     * @param widthMcPx the rect width in mcPixels
     * @return the drawn row's pixels, left to right
     */
    private static int[] drawnRow(PixelBuffer sprite, MCMeta.GuiScaling gui, int widthMcPx) {
        PixelBuffer dest = PixelBuffer.canvas(widthMcPx * PX_PER_MCPX, PX_PER_MCPX);
        NineSliceKit.draw(dest, sprite, gui, 0, 0, widthMcPx, 1, 1.0f);
        return pixels(dest, dest.width(), 1);
    }

    /**
     * Answers a row of output pixels showing one colour per Minecraft pixel.
     *
     * @param perMcPx the colour of each Minecraft pixel, left to right
     * @return each colour repeated over the output pixels its Minecraft pixel covers
     */
    private static int[] perOutputPixel(int... perMcPx) {
        int[] row = new int[perMcPx.length * PX_PER_MCPX];
        for (int x = 0; x < row.length; x++)
            row[x] = perMcPx[x / PX_PER_MCPX];
        return row;
    }

    @Test
    @DisplayName("a nine-slice's zero border side lays nothing over a rect off its declared size both ways, the regions beside it drawn")
    void aZeroBorderSideLaysNothingInItsRegions() {
        // The 4x1 sprite with a border of 1 on the left and the right and none on the top or the bottom,
        // over 6x3 mcPx: neither side is the declared size, so the walk lays all nine regions, the three
        // of the top row and the three of the bottom row 0 mcPx tall. Every output row reads as the
        // one-high rect's does, each column stretched or tiled down the 3 mcPx.
        int[] columns = {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444};
        PixelBuffer sprite = PixelBuffer.ofStraight(columns, 4, 1);
        MCMeta.GuiScaling.NineSlice.Border border = new MCMeta.GuiScaling.NineSlice.Border(1, 0, 1, 0);
        for (boolean stretchInner : new boolean[]{true, false}) {
            MCMeta.GuiScaling gui = new MCMeta.GuiScaling.NineSlice(4, 1, border, stretchInner);
            int[] texelColumn = stretchInner ? new int[]{0, 1, 1, 2, 2, 3} : new int[]{0, 1, 2, 1, 2, 3};
            PixelBuffer dest = PixelBuffer.canvas(6 * PX_PER_MCPX, 3 * PX_PER_MCPX);
            NineSliceKit.draw(dest, sprite, gui, 0, 0, 6, 3, 1.0f);

            assertThat("stretch_inner " + stretchInner, pixels(dest, dest.width(), dest.height()),
                is(texelsRead(sprite, dest.width(), dest.height(), p -> texelColumn[p / PX_PER_MCPX], q -> 0)));
            assertExtentIsDrawn("4x1 nine_slice border 1, 0, 1, 0 stretch_inner " + stretchInner, sprite, gui, 6, 3, 1.0f);
        }
    }

    @Test
    @DisplayName("degenerate far border keeps the sprite's outermost edge, not its inner pixels")
    void degenerateFarBorderKeepsOuterEdge() {
        // 20x20 sprite: leftmost column blue, rightmost column red, else gray. border 9 all sides.
        // Blitted into a 5 mcPx rect, near+far (18) exceeds 5, so both borders clamp to 2. The near edge
        // must show column 0 (blue) and the far edge column 19 (red) - the sprite's OUTERMOST columns, not
        // the inner border pixels a mis-anchored far slice would read.
        int[] px = new int[20 * 20];
        for (int y = 0; y < 20; y++)
            for (int x = 0; x < 20; x++)
                px[y * 20 + x] = x == 0 ? 0xFF0000FF : x == 19 ? 0xFFFF0000 : 0xFF808080;
        PixelBuffer sprite = PixelBuffer.ofStraight(px, 20, 20);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling.NineSlice(20, 20, new MCMeta.GuiScaling.NineSlice.Border(9, 9, 9, 9), false);
        PixelBuffer dest = PixelBuffer.canvas(10, 10);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 5, 5, 1.0f);

        assertThat("near edge is the outermost near column", dest.getPixel(0, 5), is(0xFF0000FF));
        assertThat("far edge is the outermost far column", dest.getPixel(9, 5), is(0xFFFF0000));
    }

    @Test
    @DisplayName("degenerate rect clamps borders so slices never overlap or overrun")
    void degenerateRectClamps() {
        int[] px = new int[20 * 20];
        Arrays.fill(px, 0xFF808080);
        PixelBuffer sprite = PixelBuffer.ofStraight(px, 20, 20);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling.NineSlice(20, 20, new MCMeta.GuiScaling.NineSlice.Border(9, 9, 9, 9), false);
        PixelBuffer dest = PixelBuffer.canvas(10, 10);

        // 9+9 border mcPx exceed the 5 mcPx rect; must clamp rather than throw / read OOB.
        NineSliceKit.draw(dest, sprite, gui, 0, 0, 5, 5, 1.0f);

        assertThat("clamped center still painted", ColorMath.alpha(dest.getPixel(5, 5)), is(0xFF));
        assertThat("clamped corner painted", ColorMath.alpha(dest.getPixel(0, 0)), is(0xFF));
    }
}
