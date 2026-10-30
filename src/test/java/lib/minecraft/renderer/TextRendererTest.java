package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.pixel.AlphaMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.NineSliceKit;
import lib.minecraft.renderer.bake.gui.TextKit;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.TooltipFixtures.BG_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.FRAME_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.guiMeta;
import static lib.minecraft.renderer.fixture.TooltipFixtures.itemWithStyle;
import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pixel pins for {@link TextRenderer}'s {@code LORE}-style tooltip chrome, both of its arms drawn
 * through the renderer.
 * <p>
 * {@link ChromeStyle#PROCEDURAL} blends vanilla's colours as the client blends its tooltip sprites, so
 * its fill and ring are sampled at known output coordinates against the client's framebuffer bytes -
 * the fill {@code 0xF00F000F}, the top stroke {@code 0xF523005A}, the bottom stroke {@code 0xF5170032},
 * and the side bracketed between the two strokes - along with its cut corners and open ring corners,
 * its canvas and padding against the sprite box's, which it equals off its side lines, and a part the
 * request's alpha clears leaving the canvas.
 * <p>
 * {@link ChromeStyle#SPRITE}'s cases pin the bytes its blended nine-slice pair leaves, the client's
 * framebuffer bytes - the notched corners and open ring corners, the canvas drawn to the box's first
 * pixel, vanilla's box over vanilla's sprites and a wider or uneven pack box whole, a stretched pack box
 * whose edges fall inside a Minecraft pixel whole on a canvas rounded out to whole Minecraft pixels, and
 * a canvas the alpha overrides empty of a sprite, and the alpha multipliers applied to each texel before
 * it blends, each drawn from a context seeded with the real background and frame sprites or a synthetic
 * pair - along with a styled pair reached end to end through an item's {@code minecraft:tooltip_style}
 * component, a style no pack ships drawn as the missing texture over the client's whole sprite rect and
 * named in the result as a stand-in for each sprite it replaces, and the raise a renderer holding no
 * context gives the arm. The cases needing the real sprites off the offline extraction skip per method
 * rather than per class, so the procedural pins still run on a host without it.
 * <p>
 * Each image is also pinned to the mark it reports, {@link AlphaMode#PREMULTIPLIED} - the code-drawn
 * box, a sprite tooltip, chat text and a render of no line alike - and to holding no colour channel
 * above its alpha, since a premultiplied channel is the straight one scaled by that alpha; a tooltip's
 * straight copy reads its fill back as vanilla's background colour.
 * <p>
 * {@link TextOptions.Style#CHAT} text is pinned to the pixels it draws: drawn with room on every side
 * and cut to the first pixel on each, over every frame of an obfuscated line, the same text is every
 * frame of the render pixel for pixel, so the canvas holds every pixel the text draws and no other and
 * the text sits where its own ink puts it - across descenders, leading and trailing spaces, bars, an
 * italic overhang, a half-pixel glyph, a scramble and two lines, plain and scrambled, a line naming no
 * colour, which draws white, as the client draws a chat line, and a run whose shadow colour the
 * client's text shader discards, which lays its glyphs alone.
 * <p>
 * A render whose canvas holds no pixel is the empty 1x1 image, still even where it is obfuscated: chat
 * text drawing nothing, a code-drawn box padding a line that advances nothing by 0, a code-drawn box
 * whose alphas clear both its parts round one, and a sprite box whose alphas clear both sprites round
 * one.
 * <p>
 * {@link MinecraftFontsExtension} supplies the loaded Minecraft font atlas so the renderer can lay
 * out glyphs and size the tooltip canvas.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextRendererTest {

    /** Vanilla's tooltip background fill - the background sprite's texel */
    private static final int BACKGROUND_FILL = 0xF0100010;

    /** Vanilla's border gradient at the ring's top stroke - the frame sprite's texel */
    private static final int RING_TOP = 0x505000FF;

    /** The fill as the client's framebuffer holds it: {@link #BACKGROUND_FILL} blended onto the cleared canvas */
    private static final int BLENDED_FILL = 0xF00F000F;

    /** {@link #RING_TOP} blended over {@link #BLENDED_FILL} */
    private static final int BLENDED_RING_TOP = 0xF523005A;

    /** Vanilla's border gradient at the ring's bottom stroke, the frame sprite's {@code 0x5028007F} texel, blended over {@link #BLENDED_FILL} */
    private static final int BLENDED_RING_BOTTOM = 0xF5170032;

    /** Output row of the ring's top stroke from the canvas edge, which is the box's - the stroke is 1 mcPixel thick, inset 1 mcPixel from the edge */
    private static final int RING_TOP_ROW = 2;

    /** Output rows from the canvas's bottom edge, which is the box's, the ring's bottom stroke sits at */
    private static final int RING_BOTTOM_ROW_FROM_END = 3;

    /** The margin the client blits both tooltip sprites at round the text, in mcPixels - {@code extractTooltipBackground}'s {@code x - 3 - 9} */
    private static final int CLIENT_MARGIN_MCPX = 12;

    /** The client's text height for a one-line tooltip, in mcPixels - the line's 10, less the 2 it drops for a single line */
    private static final int ONE_LINE_HEIGHT_MCPX = 8;

    /** The vanilla tooltip sprite directory in the vanilla pack */
    private static final String TOOLTIP_DIR = "assets/minecraft/textures/gui/sprites/tooltip";

    /** Skips the calling test when no client jar is cached to read the real tooltip sprites out of. */
    private static void assumeSprites() {
        Assumptions.assumeTrue(ClientAssetsExtension.isCached(), "no cached vanilla 26.1 client jar");
    }

    /**
     * Reads one tooltip sprite out of the vanilla pack.
     *
     * @param name the sprite's file name below the tooltip sprite directory
     * @return the decoded sprite
     * @throws IOException if the sprite cannot be decoded
     */
    private static PixelBuffer sprite(String name) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(tooltipFile(name)));
        return PixelBuffer.wrap(image);
    }

    /**
     * Reads a sprite's GUI scaling out of the sidecar shipped beside it.
     *
     * @param mcmetaName the sidecar's file name below the tooltip sprite directory
     * @return the nine-slice scaling the sidecar declares
     */
    private static MCMeta.GuiScaling scaling(String mcmetaName) {
        MCMeta meta = MCMetaParser.parse(new String(tooltipFile(mcmetaName), StandardCharsets.UTF_8), new ResourceId("minecraft", "tooltip"));
        return meta.gui().orElseThrow();
    }

    /**
     * Reads one file of the tooltip sprite directory out of the vanilla pack.
     *
     * @param name the file's name below the tooltip sprite directory
     * @return the file's bytes
     */
    private static byte[] tooltipFile(String name) {
        return ClientAssetsExtension.vanilla().bytes(TOOLTIP_DIR + "/" + name).orElseThrow();
    }

    /**
     * Seeds a context with the real background and frame sprites at the default pair's ids, each beside
     * the scaling its shipped sidecar declares, failing the test on an unreadable file rather than
     * declaring a checked exception every case would have to thread through. A missing client jar is
     * the {@link #assumeSprites()} skip; a present but unreadable sprite is a hard failure.
     *
     * @return the context a sprite render resolves the default pair through
     */
    private static RendererContext realContext() {
        try {
            Map<String, PixelBuffer> tex = new HashMap<>();
            tex.put("minecraft:gui/sprites/tooltip/background", sprite("background.png"));
            tex.put("minecraft:gui/sprites/tooltip/frame", sprite("frame.png"));
            Map<String, MCMeta> metas = new HashMap<>();
            metas.put("minecraft:gui/sprites/tooltip/background", guiMeta(scaling("background.png.mcmeta")));
            metas.put("minecraft:gui/sprites/tooltip/frame", guiMeta(scaling("frame.png.mcmeta")));
            return stubContext(tex, metas);
        } catch (IOException ex) {
            throw new AssertionError("Failed to load tooltip sprites", ex);
        }
    }

    /**
     * Seeds a context with a synthetic pair at the default pair's ids: a 100 x 100 background holding
     * vanilla's fill texel over the given texel rect and transparent round it, under the given sidecar,
     * and a fully transparent frame.
     *
     * @param backgroundMeta the background's sidecar
     * @param firstColumn the background's first opaque texel column
     * @param lastColumn the background's last opaque texel column
     * @param firstRow the background's first opaque texel row
     * @param lastRow the background's last opaque texel row
     * @return the context a sprite render resolves the synthetic pair through
     */
    private static RendererContext syntheticContext(MCMeta backgroundMeta, int firstColumn, int lastColumn, int firstRow, int lastRow) {
        PixelBuffer background = PixelBuffer.create(100, 100);
        for (int y = firstRow; y <= lastRow; y++)
            for (int x = firstColumn; x <= lastColumn; x++)
                background.setPixel(x, y, BACKGROUND_FILL);

        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", background);
        tex.put("minecraft:gui/sprites/tooltip/frame", PixelBuffer.create(100, 100));
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put("minecraft:gui/sprites/tooltip/background", backgroundMeta);
        metas.put("minecraft:gui/sprites/tooltip/frame", FRAME_META);
        return stubContext(tex, metas);
    }

    /**
     * Finds the first canvas column holding a pixel other than the given colour - over a box of one flat
     * fill, the text's first column.
     *
     * @param buf the rendered tooltip
     * @param argb the colour every pixel of the box but the text's holds
     * @return the first column holding another colour, or {@code -1} when none does
     */
    private static int firstColumnOtherThan(PixelBuffer buf, int argb) {
        for (int x = 0; x < buf.width(); x++)
            for (int y = 0; y < buf.height(); y++)
                if (buf.getPixel(x, y) != argb) return x;
        return -1;
    }

    /**
     * Finds the first canvas row holding a pixel other than the given colour - over a box of one flat
     * fill, the text's first row.
     *
     * @param buf the rendered tooltip
     * @param argb the colour every pixel of the box but the text's holds
     * @return the first row holding another colour, or {@code -1} when none does
     */
    private static int firstRowOtherThan(PixelBuffer buf, int argb) {
        for (int y = 0; y < buf.height(); y++)
            for (int x = 0; x < buf.width(); x++)
                if (buf.getPixel(x, y) != argb) return y;
        return -1;
    }

    /**
     * Counts the pixels of a buffer holding a non-zero alpha.
     *
     * @param buf the buffer
     * @return the count of pixels drawn
     */
    private static int drawnPixels(PixelBuffer buf) {
        int drawn = 0;
        for (int y = 0; y < buf.height(); y++)
            for (int x = 0; x < buf.width(); x++)
                if (ColorMath.alpha(buf.getPixel(x, y)) != 0) drawn++;
        return drawn;
    }

    /**
     * Builds a one-line LORE tooltip, left unbuilt so each case names its own chrome.
     *
     * @return the partly built text options
     */
    private static TextOptions.Builder loreBuilder() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().segments(Concurrent.newList(TextSegment.builder().text("Sprite Chrome").build())).build());
        return TextOptions.builder().style(TextOptions.Style.LORE).lines(lines);
    }

    /**
     * Builds single-line {@code LORE}-style options ("Test") - the shared fixture for the procedural
     * chrome-pixel assertions.
     *
     * @return the options every procedural pixel assertion renders
     */
    private static TextOptions singleLineLore() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder()
            .segments(Concurrent.newList(TextSegment.builder().text("Test").build()))
            .build());
        return TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .build();
    }

    /**
     * Renders a tooltip through a renderer holding no context and takes its only frame.
     *
     * @param options the text options to render
     * @return the rendered frame
     */
    private static PixelBuffer render(TextOptions options) {
        return render(new TextRenderer(), options);
    }

    /**
     * Renders a sprite-chrome tooltip through a renderer holding the {@link #realContext() real
     * sprites} and takes its only frame.
     *
     * @param options the text options to render, left unbuilt so the case sets its own overrides
     * @return the rendered frame
     */
    private static PixelBuffer renderSprite(TextOptions.Builder options) {
        return render(new TextRenderer(realContext()), options.chromeStyle(ChromeStyle.SPRITE).build());
    }

    /**
     * Renders a tooltip through the given renderer and takes its only frame.
     *
     * @param renderer the renderer to draw with
     * @param options the text options to render
     * @return the rendered frame
     */
    private static PixelBuffer render(TextRenderer renderer, TextOptions options) {
        ImageData image = renderer.render(options).image();
        return image.getFrames().getFirst().pixels();
    }

    /**
     * Samples a tooltip's ring top stroke at the canvas mid-column, its row counted from the canvas
     * edge, which is the box's.
     *
     * @param buf the rendered tooltip
     * @return the sampled pixel
     */
    private static int ringTop(PixelBuffer buf) {
        return buf.getPixel(buf.width() / 2, RING_TOP_ROW);
    }

    /**
     * Samples a tooltip's ring bottom stroke at the canvas mid-column, its row counted from the canvas's
     * bottom edge, which is the box's.
     *
     * @param buf the rendered tooltip
     * @return the sampled pixel
     */
    private static int ringBottom(PixelBuffer buf) {
        return buf.getPixel(buf.width() / 2, buf.height() - RING_BOTTOM_ROW_FROM_END);
    }

    @Test
    @DisplayName("a code-drawn tooltip's fill is vanilla's background blended onto the clear canvas")
    void backgroundFillMatchesVanilla() {
        PixelBuffer buf = render(singleLineLore());

        // The ring's top stroke holds output rows 2 and 3 and the text starts at row 8, so row 5 is fill.
        assertThat("fill above the text", buf.getPixel(buf.width() / 2, 5), is(BLENDED_FILL));
    }

    @Test
    @DisplayName("a code-drawn tooltip's top stroke is vanilla's gradient top blended over the fill")
    void borderTopMatchesVanillaGradientTop() {
        assertThat("ring top stroke", ringTop(render(singleLineLore())), is(BLENDED_RING_TOP));
    }

    @Test
    @DisplayName("a code-drawn tooltip's bottom stroke is vanilla's gradient bottom blended over the fill")
    void borderBottomMatchesVanillaGradientBottom() {
        assertThat("ring bottom stroke", ringBottom(render(singleLineLore())), is(BLENDED_RING_BOTTOM));
    }

    @Test
    @DisplayName("a code-drawn tooltip's side lies between its two blended strokes")
    void borderLeftEdgeInterpolates() {
        PixelBuffer buf = render(singleLineLore());

        // The left side holds output columns 2 and 3; sample it on the middle row, which the ramp from the
        // top stroke's colour to the bottom's takes strictly between the two.
        int px = buf.getPixel(2, buf.height() / 2);
        int r = ColorMath.red(px);
        int b = ColorMath.blue(px);
        assertThat("red is above the bottom stroke's", r, is(greaterThan(ColorMath.red(BLENDED_RING_BOTTOM))));
        assertThat("red is below the top stroke's", r, is(lessThan(ColorMath.red(BLENDED_RING_TOP))));
        assertThat("blue is above the bottom stroke's", b, is(greaterThan(ColorMath.blue(BLENDED_RING_BOTTOM))));
        assertThat("blue is below the top stroke's", b, is(lessThan(ColorMath.blue(BLENDED_RING_TOP))));
        assertThat("alpha, the strokes' own", ColorMath.alpha(px), is(ColorMath.alpha(BLENDED_RING_TOP)));
    }

    @Test
    @DisplayName("a code-drawn tooltip cuts its box's corners and leaves its ring's corners open, its fill flush to the canvas edge")
    void aCodeDrawnTooltipCutsItsCornersAndLeavesItsRingCornersOpen() {
        PixelBuffer buf = render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build());
        int w = buf.width();
        int h = buf.height();
        int clear = ColorMath.TRANSPARENT;

        // Each corner Minecraft pixel of the box is left clear; the fill runs to the canvas edge between
        // them, and shows through each ring corner 1 Minecraft pixel in.
        assertThat("the box's corners, the fill's edges and the ring's corners",
            List.of(buf.getPixel(0, 0), buf.getPixel(1, 1), buf.getPixel(w - 1, 0), buf.getPixel(w - 2, 1),
                buf.getPixel(0, h - 1), buf.getPixel(1, h - 2), buf.getPixel(w - 1, h - 1), buf.getPixel(w - 2, h - 2),
                buf.getPixel(w / 2, 0), buf.getPixel(0, h / 2), buf.getPixel(w - 1, h / 2), buf.getPixel(w / 2, h - 1),
                buf.getPixel(2, 2), buf.getPixel(w - 3, 2), buf.getPixel(2, h - 3), buf.getPixel(w - 3, h - 3)),
            is(List.of(clear, clear, clear, clear, clear, clear, clear, clear,
                BLENDED_FILL, BLENDED_FILL, BLENDED_FILL, BLENDED_FILL,
                BLENDED_FILL, BLENDED_FILL, BLENDED_FILL, BLENDED_FILL)));
    }

    @Test
    @DisplayName("a code-drawn tooltip is vanilla's box - the sprite box's canvas and pixels - but on its side lines, where its lerp meets the frame sprite's baked ramp")
    void aCodeDrawnTooltipIsVanillasBoxButItsSideRamp() {
        assumeSprites();
        PixelBuffer codeDrawn = render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build());
        PixelBuffer sprite = renderSprite(loreBuilder());
        int w = sprite.width();
        int h = sprite.height();

        assertThat("canvas size", List.of(codeDrawn.width(), codeDrawn.height()), is(List.of(w, h)));
        // The side lines hold output columns 2, 3, w - 4 and w - 3, from the row below the top stroke to
        // the row above the bottom stroke.
        List<String> offTheSides = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean side = (x == 2 || x == 3 || x == w - 4 || x == w - 3) && y >= 4 && y <= h - 5;
                if (!side && codeDrawn.getPixel(x, y) != sprite.getPixel(x, y))
                    offTheSides.add(String.format("(%d, %d) holds %08X against %08X", x, y, codeDrawn.getPixel(x, y), sprite.getPixel(x, y)));
            }
        }
        assertThat("pixels differing from the sprite box off the side lines", offTheSides, is(empty()));
    }

    @Test
    @DisplayName("a tooltip whose widest line ends on a half pixel is sized to the next whole pixel, as the client sizes it from Font#width")
    void canvasRoundsAHalfPixelLineUp() {
        float exact = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("+5 ✦ Speed");
        assertThat("the line ends on half an mcPixel", exact % 1f, is(0.5f));

        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().segments(Concurrent.newList(TextSegment.literal("+5 ✦ Speed"))).build());
        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .chromeStyle(ChromeStyle.PROCEDURAL)
            .build();
        int padMcPx = options.getPadding();

        assertThat("canvas width", render(options).width(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * padMcPx + (int) exact + 1)));
    }

    @Test
    @DisplayName("a tooltip is sized from the font its line names")
    void aTooltipIsSizedFromTheFontItsLineNames() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().segments(Concurrent.newList(
            TextSegment.builder().text("Galactic").font(FontId.parse("minecraft:alt")).build())).build());
        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .chromeStyle(ChromeStyle.PROCEDURAL)
            .build();
        int padMcPx = options.getPadding();

        assertAll(
            () -> assertThat("'Galactic' in the alt font, which the regular face lays 39 wide",
                MinecraftFont.Vanilla.GALACTIC.metrics().stringAdvanceMcPixels("Galactic"), is(34f)),
            () -> assertThat("canvas width", render(options).width(), is(MinecraftFont.MC_PIXEL_SCALE * (2 * padMcPx + 34)))
        );
    }

    @Test
    @DisplayName("sprite background: drawn to its first pixel - no transparent margin, the box's corners notched, its fill flush to the canvas edge")
    void notchedCornerAndFlushFill() {
        assumeSprites();
        PixelBuffer buf = renderSprite(loreBuilder());
        int w = buf.width();
        int h = buf.height();

        assertThat("bounds of the pixels drawn", buf.opaqueBounds(), is(new Rectangle(0, 0, w, h)));
        assertThat("notched top-left corner", ColorMath.alpha(buf.getPixel(0, 0)), is(0));
        assertThat("notched bottom-right corner", ColorMath.alpha(buf.getPixel(w - 1, h - 1)), is(0));
        assertThat("fill flush top edge", buf.getPixel(w / 2, 0), is(BLENDED_FILL));
        assertThat("fill flush left edge", buf.getPixel(0, h / 2), is(BLENDED_FILL));
    }

    @Test
    @DisplayName("sprite frame: ring 1 mcPx inset, open corners, gradient endpoints")
    void ringInsetAndOpenCorner() {
        assumeSprites();
        PixelBuffer buf = renderSprite(loreBuilder());

        assertThat("ring top gradient", ringTop(buf), is(BLENDED_RING_TOP));
        assertThat("ring bottom gradient", ringBottom(buf), is(BLENDED_RING_BOTTOM));
        // The ring corner texel is transparent in the sprite (open corner), so the background fill shows
        // through there.
        assertThat("open ring corner shows background fill",
            buf.getPixel(RING_TOP_ROW, RING_TOP_ROW), is(BLENDED_FILL));
    }

    @Test
    @DisplayName("a sprite tooltip's canvas is vanilla's box - the client's sprite rect less the transparent texels round the background sprite")
    void canvasIsVanillasBox() throws IOException {
        assumeSprites();
        int inset = sprite("background.png").opaqueBounds().x;
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        PixelBuffer buf = renderSprite(loreBuilder());

        assertThat("canvas width", buf.width(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * (CLIENT_MARGIN_MCPX - inset) + (int) Math.ceil(advance))));
        assertThat("canvas height", buf.height(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * (CLIENT_MARGIN_MCPX - inset) + ONE_LINE_HEIGHT_MCPX)));
    }

    @Test
    @DisplayName("an uneven pack box is drawn whole, each side ending where its sprite does, and the text keeps its place against the box")
    void anUnevenPackBoxIsDrawnWholeAndTheTextKeepsItsPlace() {
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        // Transparent 4 texels on the left, 2 on the right, 6 above and 8 below; the symmetric box is
        // transparent 8 on every side.
        PixelBuffer uneven = render(new TextRenderer(syntheticContext(BG_META, 4, 97, 6, 91)), loreBuilder().chromeStyle(ChromeStyle.SPRITE).build());
        PixelBuffer even = render(new TextRenderer(syntheticContext(BG_META, 8, 91, 8, 91)), loreBuilder().chromeStyle(ChromeStyle.SPRITE).build());

        assertThat("canvas width", uneven.width(),
            is(MinecraftFont.MC_PIXEL_SCALE * (8 + (int) Math.ceil(advance) + 10)));
        assertThat("canvas height", uneven.height(), is(MinecraftFont.MC_PIXEL_SCALE * (6 + ONE_LINE_HEIGHT_MCPX + 4)));
        assertThat("bounds of the pixels drawn", uneven.opaqueBounds(), is(new Rectangle(0, 0, uneven.width(), uneven.height())));
        // The text sits at the client's content origin relative to the sprite rect, so it is 4 mcPixels
        // further from the canvas's left edge and 2 further from its top where the box is.
        assertThat("first text column against the symmetric box's", firstColumnOtherThan(uneven, BLENDED_FILL),
            is(firstColumnOtherThan(even, BLENDED_FILL) + 4 * MinecraftFont.MC_PIXEL_SCALE));
        assertThat("first text row against the symmetric box's", firstRowOtherThan(uneven, BLENDED_FILL),
            is(firstRowOtherThan(even, BLENDED_FILL) + 2 * MinecraftFont.MC_PIXEL_SCALE));
    }

    @Test
    @DisplayName("a stretched pack box whose edges fall inside a Minecraft pixel is drawn whole, on a canvas rounded out to whole Minecraft pixels")
    void aStretchedPackBoxIsDrawnWholeOnACanvasRoundedOut() {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        int rectWMcPx = 2 * CLIENT_MARGIN_MCPX + (int) Math.ceil(advance);
        int rectHMcPx = 2 * CLIENT_MARGIN_MCPX + ONE_LINE_HEIGHT_MCPX;
        // Transparent 5 texels a side and stretched over the client's rect, the box starts and ends on an
        // odd output px on both axes - inside a Minecraft pixel rather than on its edge.
        MCMeta stretch = guiMeta(new MCMeta.GuiScaling.Stretch());
        RendererContext context = syntheticContext(stretch, 5, 94, 5, 94);
        TooltipChrome.ChromeSprites sprites = TooltipChrome.ChromeSprites.resolve(context, Optional.empty());
        PixelBuffer clientRect = PixelBuffer.create(rectWMcPx * scale, rectHMcPx * scale);
        NineSliceKit.draw(clientRect, sprites.background(), sprites.backgroundScaling(), 0, 0, rectWMcPx, rectHMcPx, 1.0f);
        Rectangle box = clientRect.opaqueBounds();
        assertThat("the box's left, right, top and bottom edge, each an odd output px",
            List.of(box.x % scale, (box.x + box.width) % scale, box.y % scale, (box.y + box.height) % scale),
            is(List.of(1, 1, 1, 1)));

        PixelBuffer buf = render(new TextRenderer(context), loreBuilder().chromeStyle(ChromeStyle.SPRITE).build());

        assertThat("canvas width", buf.width(),
            is(scale * (Math.ceilDiv(box.x + box.width, scale) - Math.floorDiv(box.x, scale))));
        assertThat("canvas height", buf.height(),
            is(scale * (Math.ceilDiv(box.y + box.height, scale) - Math.floorDiv(box.y, scale))));
        assertThat("pixels drawn, against the box over the client's rect", drawnPixels(buf), is(drawnPixels(clientRect)));
    }

    @Test
    @DisplayName("a background the request's alpha clears draws nothing, so the canvas ends at the frame's first pixel")
    void aClearedBackgroundTakesTheCanvasToTheFrame() throws IOException {
        assumeSprites();
        int frameInset = sprite("frame.png").opaqueBounds().x;
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        PixelBuffer buf = renderSprite(loreBuilder().backgroundAlpha(0));

        assertThat("canvas width", buf.width(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * (CLIENT_MARGIN_MCPX - frameInset) + (int) Math.ceil(advance))));
        assertThat("bounds of the pixels drawn", buf.opaqueBounds(), is(new Rectangle(0, 0, buf.width(), buf.height())));
    }

    @Test
    @DisplayName("a sprite chrome whose alphas clear both sprites leaves the canvas the text block, never cutting the text")
    void aChromeDrawingNothingLeavesTheTextBlock() {
        assumeSprites();
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        PixelBuffer buf = renderSprite(loreBuilder().backgroundAlpha(0).borderAlpha(0));

        assertThat("canvas width", buf.width(), is(MinecraftFont.MC_PIXEL_SCALE * (int) Math.ceil(advance)));
        assertThat("canvas height", buf.height(), is(MinecraftFont.MC_PIXEL_SCALE * ONE_LINE_HEIGHT_MCPX));
    }

    @Test
    @DisplayName("a code-drawn fill the request's alpha clears draws nothing, so the canvas ends at the ring's first pixel")
    void aClearedCodeDrawnFillTakesTheCanvasToTheRing() {
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        TextOptions options = loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).backgroundAlpha(0).build();
        int padMcPx = options.getPadding();
        PixelBuffer buf = render(options);

        assertThat("canvas width", buf.width(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * (padMcPx - 1) + (int) Math.ceil(advance))));
        assertThat("canvas height", buf.height(),
            is(MinecraftFont.MC_PIXEL_SCALE * (2 * (padMcPx - 1) + ONE_LINE_HEIGHT_MCPX)));
        assertThat("bounds of the pixels drawn", buf.opaqueBounds(), is(new Rectangle(0, 0, buf.width(), buf.height())));
    }

    @Test
    @DisplayName("a code-drawn box whose alphas clear both its parts leaves the canvas the text block, never cutting the text")
    void aCodeDrawnBoxDrawingNothingLeavesTheTextBlock() {
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        PixelBuffer buf = render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).backgroundAlpha(0).borderAlpha(0).build());

        assertThat("canvas width", buf.width(), is(MinecraftFont.MC_PIXEL_SCALE * (int) Math.ceil(advance)));
        assertThat("canvas height", buf.height(), is(MinecraftFont.MC_PIXEL_SCALE * ONE_LINE_HEIGHT_MCPX));
    }

    @Test
    @DisplayName("lowered background alpha multiplies the sprite alpha proportionally")
    void alphaOverrideMultiplies() {
        assumeSprites();
        // backgroundAlpha 120 / vanilla 240 = 0.5 multiplier -> baked 0xF0 becomes 0x78, and the halved
        // 78100010 texel blends onto the clear canvas: 0x10 * 120/255 rounds to 0x08.
        PixelBuffer buf = renderSprite(loreBuilder().backgroundAlpha(120));

        assertThat("halved background texel blended", buf.getPixel(buf.width() / 2, 0), is(0x78080008));
    }

    @Test
    @DisplayName("SPRITE chrome on a renderer holding no context throws rather than silently falling back")
    void aSpriteChromeWithoutAContextThrows() {
        TextOptions options = loreBuilder().chromeStyle(ChromeStyle.SPRITE).build();
        RenderException thrown = assertThrows(RenderException.class, () -> new TextRenderer().render(options));
        assertThat(thrown.getMessage(),
            is("Vanilla.SPRITE tooltip chrome requires resolved sprites; the text renderer needs a RendererContext"));
    }

    @Test
    @DisplayName("a style no pack ships draws the missing texture stretched over the client's whole sprite rect, and the result names both sprites as stand-ins")
    void aStyleNoPackShipsDrawsTheMissingTextureOverTheWholeRect() {
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        float advance = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("Sprite Chrome");
        RenderResult result = new TextRenderer(stubContext(Map.of(), Map.of())).render(loreBuilder()
            .chromeStyle(ChromeStyle.SPRITE)
            .tooltipStyle(Optional.of(new ResourceId("fixture", "absent")))
            .build());
        PixelBuffer buf = result.image().getFrames().getFirst().pixels();
        int w = buf.width();
        int h = buf.height();

        assertThat("both sprites named as stand-ins", result.substitutions(), contains(
            Substitution.texture("fixture:gui/sprites/tooltip/absent_background", Possible.State.ABSENT),
            Substitution.texture("fixture:gui/sprites/tooltip/absent_frame", Possible.State.ABSENT)));
        assertThat("canvas width, the client's whole rect", w, is(scale * (2 * CLIENT_MARGIN_MCPX + (int) Math.ceil(advance))));
        assertThat("canvas height, the client's whole rect", h, is(scale * (2 * CLIENT_MARGIN_MCPX + ONE_LINE_HEIGHT_MCPX)));
        // Stretched once over the whole rect, the checker's quadrant edges fall at its middle; a tiled or
        // nine-sliced checker breaks at least one of these.
        assertThat("the checker's quadrants, along the top row and at the bottom corners",
            List.of(buf.getPixel(0, 0), buf.getPixel(w / 2 - 1, 0), buf.getPixel(w / 2, 0), buf.getPixel(w - 1, 0),
                buf.getPixel(0, h - 1), buf.getPixel(w - 1, h - 1)),
            is(List.of(MissingSprite.BLACK_ARGB, MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB,
                MissingSprite.MAGENTA_ARGB, MissingSprite.MAGENTA_ARGB, MissingSprite.BLACK_ARGB)));
    }

    @Test
    @DisplayName("each sprite no pack ships is replaced on its own")
    void aSpriteNoPackShipsIsDrawnAndRecordedAlone() {
        PixelBuffer frame = PixelBuffer.create(20, 20);
        frame.fill(0xFF445566);
        RenderResult result = new TextRenderer(stubContext(Map.of("fixture:gui/sprites/tooltip/half_frame", frame), Map.of()))
            .render(loreBuilder()
                .chromeStyle(ChromeStyle.SPRITE)
                .tooltipStyle(Optional.of(new ResourceId("fixture", "half")))
                .build());
        PixelBuffer buf = result.image().getFrames().getFirst().pixels();

        assertThat("only the background named as a stand-in", result.substitutions(),
            contains(Substitution.texture("fixture:gui/sprites/tooltip/half_background", Possible.State.ABSENT)));
        assertThat("the pack's frame drawn over the stand-in", buf.getPixel(0, 0), is(0xFF445566));
    }

    @Test
    @DisplayName("styled-fixture tooltip renders end to end through the item component path")
    void styledFixtureRenders() throws IOException {
        assumeSprites();
        PixelBuffer vanillaBg = sprite("background.png");
        PixelBuffer goldFrame = recolour(sprite("frame.png"), 0xFFAA00);

        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("fixture:gui/sprites/tooltip/gold_background", vanillaBg);
        tex.put("fixture:gui/sprites/tooltip/gold_frame", goldFrame);
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put("fixture:gui/sprites/tooltip/gold_background", BG_META);
        metas.put("fixture:gui/sprites/tooltip/gold_frame", FRAME_META);

        ItemContext item = itemWithStyle("fixture:gold");
        RendererContext context = stubContext(tex, metas);

        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().segments(Concurrent.newList(TextSegment.builder().text("Styled Tooltip").build())).build());
        ImageData image = new TextRenderer(context).render(
            TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(lines)
                .chromeStyle(ChromeStyle.SPRITE)
                .tooltipStyle(TooltipChrome.ChromeSprites.styleOf(item))
                .build()
        ).image();
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // The gold-recoloured ring drove the render: its 50FFAA00 top texel blended over the fill gives
        // F55A350A, where the vanilla frame gives F523005A.
        assertThat("styled gold ring", ringTop(buf), is(0xF55A350A));
    }

    @Test
    @DisplayName("a code-drawn tooltip is marked premultiplied, still and animated, and its straight copy holds vanilla's background colour")
    void aCodeDrawnTooltipIsMarkedPremultiplied() {
        TextRenderer renderer = new TextRenderer();
        ImageData still = renderer.render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build()).image();
        ImageData animated = renderer.render(TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(LineSegment.fromLegacy("&7Mark &kab", '&'))
            .chromeStyle(ChromeStyle.PROCEDURAL)
            .build()).image();

        assertPremultiplied(still);
        // The fill is held as the client's framebuffer holds it, F00F000F; the straight copy a caller
        // reads un-premultiplies it back to vanilla's background colour, where a fill laid without
        // blending reads F0110011.
        assertThat("straight copy of the fill", still.toBufferedImage().getRGB(still.getWidth() / 2, 0), is(BACKGROUND_FILL));
        assertThat("the obfuscated line animates", animated.isAnimated(), is(true));
        assertPremultiplied(animated);
    }

    @Test
    @DisplayName("a sprite tooltip is marked premultiplied, still and animated, and its straight copy holds the sprite's texel")
    void aSpriteTooltipIsMarkedPremultiplied() {
        assumeSprites();
        TextRenderer renderer = new TextRenderer(realContext());
        ImageData still = renderer.render(loreBuilder().chromeStyle(ChromeStyle.SPRITE).build()).image();
        ImageData animated = renderer.render(TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(LineSegment.fromLegacy("&7Mark &kab", '&'))
            .chromeStyle(ChromeStyle.SPRITE)
            .build()).image();

        assertPremultiplied(still);
        // The fill is held as the client's framebuffer holds it, F00F000F; the straight copy a caller
        // reads un-premultiplies it back to the background sprite's texel.
        assertThat("straight copy of the fill", still.toBufferedImage().getRGB(still.getWidth() / 2, 0), is(BACKGROUND_FILL));
        assertThat("the obfuscated line animates", animated.isAnimated(), is(true));
        assertPremultiplied(animated);
    }

    @Test
    @DisplayName("chat text and a render of no line are marked premultiplied")
    void aRenderDrawingNoBoxIsMarkedPremultiplied() {
        TextRenderer renderer = new TextRenderer();
        assertPremultiplied(renderer.render(loreBuilder().style(TextOptions.Style.CHAT).build()).image());
        assertPremultiplied(renderer.render(TextOptions.defaults()).image());
    }

    @Test
    @DisplayName("a chat image is its text cut to the first pixel it draws, over every frame")
    void aChatImageIsItsTextCutToTheFirstPixelItDraws() {
        // A line naming no colour is drawn white, as the client draws a chat line; every other line names
        // its own.
        Map<String, TextOptions> corpus = new LinkedHashMap<>();
        corpus.put("no colour", chat("No colour").build());
        corpus.put("no capital", chat("&facemnorsuvwxz").build());
        corpus.put("descenders", chat("&fgpqy").build());
        corpus.put("leading spaces", chat("&f  Lead").build());
        corpus.put("trailing spaces", chat("&fTrail   ").build());
        corpus.put("underlined", chat("&f&nUnderlined").build());
        corpus.put("struck", chat("&f&mStruck").build());
        corpus.put("italic", chat("&f&oItalic text").build());
        corpus.put("a half-pixel glyph", chat("&f+5 ✦ Speed").build());
        corpus.put("obfuscated", chat("&f&kHello").build());
        corpus.put("bold obfuscated", chat("&f&l&kab &r&fBold").build());
        corpus.put("two lines", chat("&f  first", "&fsecond line").build());
        // Over two frames a scramble's bounds fall short of the extremes its picks can reach, so the
        // canvas follows the glyphs each line's own number picks.
        corpus.put("two obfuscated lines", chat("&f&kHello", "&f&l&kab").frameCount(2).build());
        // A run whose shadow colour the client's text shader discards lays its glyphs whole, while its
        // shadow lays no pixel - so the canvas is the glyphs' ink.
        TextSegment faint = TextSegment.builder()
            .text("Faint")
            .color(ChatColor.Legacy.WHITE)
            .shadowColor(ChatColor.builder().color(new Color(0x19FF0000, true)).build())
            .build();
        corpus.put("a run whose shadow colour the text shader discards", TextOptions.builder()
            .style(TextOptions.Style.CHAT)
            .lines(Concurrent.newList(LineSegment.builder().segments(Concurrent.newList(faint)).build()))
            .build());

        ConcurrentList<String> mismatched = Concurrent.newList();
        for (Map.Entry<String, TextOptions> row : corpus.entrySet()) {
            TextOptions options = row.getValue();
            ConcurrentList<LineSegment> lines = options.getLines();
            ConcurrentList<ImageFrame> frames = new TextRenderer().render(options).image().getFrames();

            int frameCount = lines.stream().anyMatch(line -> line.getSegments().stream().anyMatch(TextSegment::isObfuscated))
                ? options.getFrameCount()
                : 1;
            if (frames.size() != frameCount) {
                mismatched.add(row.getKey() + ": " + frames.size() + " frames where the text draws " + frameCount);
                continue;
            }

            List<PixelBuffer> drawn = drawnWithRoom(lines, frameCount);
            Rectangle cut = drawn.getFirst().opaqueBounds();
            for (PixelBuffer frame : drawn)
                cut = cut.union(frame.opaqueBounds());

            Rectangle inked = frames.getFirst().pixels().opaqueBounds();
            for (int f = 0; f < frameCount; f++) {
                PixelBuffer rendered = frames.get(f).pixels();
                PixelBuffer expected = drawn.get(f).crop(cut.x, cut.y, cut.width, cut.height);
                inked = inked.union(rendered.opaqueBounds());
                if (rendered.width() != expected.width() || rendered.height() != expected.height())
                    mismatched.add(String.format("%s, frame %d: a %dx%d canvas where the text draws %dx%d",
                        row.getKey(), f, rendered.width(), rendered.height(), expected.width(), expected.height()));
                else if (differing(rendered, expected) > 0)
                    mismatched.add(String.format("%s, frame %d: %d pixels differ from the text cut to its first pixel",
                        row.getKey(), f, differing(rendered, expected)));
            }

            PixelBuffer canvas = frames.getFirst().pixels();
            if (!inked.equals(new Rectangle(0, 0, canvas.width(), canvas.height())))
                mismatched.add(row.getKey() + ": the frames ink " + inked + " of a " + canvas.width() + "x" + canvas.height() + " canvas");
        }

        assertThat("chat renders that are not their text cut to its first pixel", mismatched, is(empty()));
    }

    @Test
    @DisplayName("a render whose canvas holds no pixel is the empty 1x1 image, still even where it is obfuscated")
    void aCanvasHoldingNoPixelIsTheEmptyImage() {
        Map<String, TextOptions> corpus = new LinkedHashMap<>();
        corpus.put("spaces", chat("   ").build());
        corpus.put("one empty segment", TextOptions.builder()
            .style(TextOptions.Style.CHAT)
            .lines(Concurrent.newList(LineSegment.builder().segments(Concurrent.newList(TextSegment.literal(""))).build()))
            .build());
        corpus.put("obfuscated spaces", chat("&k   ").build());
        corpus.put("two lines of spaces", chat("  ", "   ").build());
        corpus.put("code-drawn box at padding 0: an empty line",
            lore(TextSegment.literal("")).chromeStyle(ChromeStyle.PROCEDURAL).padding(0).build());
        corpus.put("code-drawn box at padding 0: a tab",
            lore(TextSegment.literal("\t")).chromeStyle(ChromeStyle.PROCEDURAL).padding(0).build());
        corpus.put("code-drawn box at padding 0: a tab obfuscated",
            lore(TextSegment.builder().text("\t").obfuscated(true).build()).chromeStyle(ChromeStyle.PROCEDURAL).padding(0).build());
        corpus.put("code-drawn box whose alphas clear it: an empty line",
            lore(TextSegment.literal("")).chromeStyle(ChromeStyle.PROCEDURAL).backgroundAlpha(0).borderAlpha(0).build());
        corpus.put("sprite box whose alphas clear both sprites: an empty line",
            lore(TextSegment.literal("")).chromeStyle(ChromeStyle.SPRITE).backgroundAlpha(0).borderAlpha(0).build());

        // A renderer holding a context draws chat text and the code-drawn box as one holding none does,
        // and the sprite row resolves its pair through it. Both sprites hold a texel over every texel -
        // vanilla's fill and the ring's top - so each draws pixels until the request's alpha clears it.
        PixelBuffer background = PixelBuffer.create(100, 100);
        background.fill(BACKGROUND_FILL);
        PixelBuffer frame = PixelBuffer.create(100, 100);
        frame.fill(RING_TOP);
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", background);
        tex.put("minecraft:gui/sprites/tooltip/frame", frame);
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put("minecraft:gui/sprites/tooltip/background", BG_META);
        metas.put("minecraft:gui/sprites/tooltip/frame", FRAME_META);
        TextRenderer renderer = new TextRenderer(stubContext(tex, metas));
        ConcurrentList<String> mismatched = Concurrent.newList();
        for (Map.Entry<String, TextOptions> row : corpus.entrySet()) {
            TextOptions options = row.getValue();
            if (options.getStyle() == TextOptions.Style.LORE) {
                int widest = options.getLines().stream().mapToInt(TextKit::measureLineMcPixels).max().orElse(0);
                if (widest != 0) {
                    mismatched.add(row.getKey() + ": its lines measure " + widest + " Minecraft pixels, not 0");
                    continue;
                }
            }

            ImageData image = renderer.render(options).image();
            PixelBuffer pixels = image.getFrames().getFirst().pixels();
            boolean empty = !image.isAnimated() && pixels.width() == 1 && pixels.height() == 1
                && ColorMath.alpha(pixels.getPixel(0, 0)) == 0;
            if (!empty)
                mismatched.add(String.format("%s: %s %dx%d", row.getKey(), image.isAnimated() ? "animated" : "still", pixels.width(), pixels.height()));
        }

        assertThat("renders whose canvas holds no pixel that are not the empty image", mismatched, is(empty()));
    }

    /**
     * Builds chat options of one line per legacy string, left unbuilt so a case can name its own frame
     * count.
     *
     * @param legacy each line's text in {@code &} codes
     * @return the partly built text options
     */
    private static TextOptions.Builder chat(String... legacy) {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        for (String line : legacy)
            lines.add(TextSegment.fromLegacy(line, '&'));
        return TextOptions.builder().style(TextOptions.Style.CHAT).lines(lines);
    }

    /**
     * Builds LORE options of one line holding the given segment, left unbuilt so a case names its own
     * chrome.
     *
     * @param segment the line's only segment
     * @return the partly built text options
     */
    private static TextOptions.Builder lore(TextSegment segment) {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().segments(Concurrent.newList(segment)).build());
        return TextOptions.builder().style(TextOptions.Style.LORE).lines(lines);
    }

    /**
     * Draws chat lines as the renderer lays them out, on a clear canvas with room on every side: each
     * line at the same margin, its baseline an ascent and its line pitch down from the block's top,
     * numbered as it is drawn, a run naming no colour in white, shadow on, once per frame.
     *
     * @param lines the lines to draw
     * @param frames how many frames to draw
     * @return each frame drawn
     */
    private static List<PixelBuffer> drawnWithRoom(ConcurrentList<LineSegment> lines, int frames) {
        int marginMcPx = 10;
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        List<PixelBuffer> drawn = new ArrayList<>();
        for (int frame = 0; frame < frames; frame++) {
            PixelBuffer canvas = PixelBuffer.create(400, 120);
            MinecraftGraphics g = new MinecraftGraphics(canvas);
            for (int i = 0; i < lines.size(); i++)
                TextKit.drawLine(g, lines.get(i), marginMcPx, marginMcPx + ascentMcPx + 10 * i, ColorMath.WHITE, TextPalette.VANILLA, i, frame);
            drawn.add(canvas);
        }
        return drawn;
    }

    /**
     * Counts the pixels two buffers of one size disagree about.
     *
     * @param a the first buffer
     * @param b the second buffer
     * @return how many pixels differ
     */
    private static int differing(PixelBuffer a, PixelBuffer b) {
        int count = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++;
        return count;
    }

    /**
     * Asserts that an image is marked {@link AlphaMode#PREMULTIPLIED} and that every frame's pixels are
     * held in it. A premultiplied colour channel is the straight channel scaled by the pixel's alpha, so
     * none can exceed that alpha; the first pixel whose red, green or blue does fails the image, named by
     * its frame, position and colour.
     *
     * @param image the rendered image
     */
    private static void assertPremultiplied(ImageData image) {
        assertThat("alpha mode", image.getAlphaMode(), is(AlphaMode.PREMULTIPLIED));

        String channelAboveAlpha = "none";
        ConcurrentList<ImageFrame> frames = image.getFrames();
        search:
        for (int f = 0; f < frames.size(); f++) {
            PixelBuffer pixels = frames.get(f).pixels();
            for (int y = 0; y < pixels.height(); y++) {
                for (int x = 0; x < pixels.width(); x++) {
                    int argb = pixels.getPixel(x, y);
                    int alpha = ColorMath.alpha(argb);
                    if (ColorMath.red(argb) > alpha || ColorMath.green(argb) > alpha || ColorMath.blue(argb) > alpha) {
                        channelAboveAlpha = String.format("frame %d at (%d, %d) holds %08X", f, x, y, argb);
                        break search;
                    }
                }
            }
        }
        assertThat("first pixel with a colour channel above its alpha", channelAboveAlpha, is("none"));
    }

    /**
     * Copies a sprite with every non-transparent texel forced to one rgb and its own alpha kept, so the
     * colour in the render identifies which sprite drove it.
     *
     * @param source the sprite to recolour
     * @param rgb the replacement rgb
     * @return the recoloured copy
     */
    private static PixelBuffer recolour(PixelBuffer source, int rgb) {
        PixelBuffer out = source.copy();
        for (int y = 0; y < out.height(); y++)
            for (int x = 0; x < out.width(); x++) {
                int alpha = ColorMath.alpha(out.getPixel(x, y));
                if (alpha > 0) out.setPixel(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
            }
        return out;
    }

}
