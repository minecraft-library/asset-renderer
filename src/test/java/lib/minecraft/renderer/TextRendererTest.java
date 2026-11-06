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
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.NineSliceKit;
import lib.minecraft.renderer.bake.gui.TextKit;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.call.slot.TextSlot;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.content.index.IndexedRendererContext;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.SpriteAtlas;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.FontId;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static lib.minecraft.renderer.fixture.TooltipFixtures.BG_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.FRAME_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.guiMeta;
import static lib.minecraft.renderer.fixture.TooltipFixtures.itemWithStyle;
import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static lib.minecraft.renderer.fixture.TooltipFixtures.withPalette;
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
 * framebuffer bytes - its corners, ring and fill through the code-drawn box it equals off the side
 * lines, vanilla's box over vanilla's sprites and a wider or uneven pack box whole, a stretched pack box
 * whose edges fall inside a Minecraft pixel whole on a canvas rounded out to whole Minecraft pixels, and
 * a canvas the alpha overrides empty of a sprite, and the alpha multipliers applied to each texel before
 * it blends, each drawn from a context seeded with the real background and frame sprites or a synthetic
 * pair - along with a styled pair reached end to end through an item's {@code minecraft:tooltip_style}
 * component, a style no pack ships drawn as the missing texture over the client's whole sprite rect and
 * named in the result as a stand-in for each sprite it replaces, and the raise a renderer holding no
 * context gives the arm. The cases needing the real sprites off the offline extraction skip per method
 * rather than per class, so the procedural pins still run on a host without it.
 * <p>
 * A request naming no chrome is drawn in the sprite box by a renderer holding a context and in the
 * code-drawn box by one holding none, pixel for pixel the box a request naming that chrome draws.
 * <p>
 * Each image is also pinned to the mark it reports, {@link AlphaMode#PREMULTIPLIED} - the code-drawn
 * box, a sprite tooltip, chat text and a render of no line alike - and to holding no colour channel
 * above its alpha, since a premultiplied channel is the straight one scaled by that alpha; a tooltip's
 * fill reads back as vanilla's background colour through the image Java2D is handed.
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
 * The {@link TextOptions.Style#BOOK} style is pinned in {@link Book}: the refusal of a renderer holding no
 * context, the book texture laid as the client's one quad at every texture size, the canvas ending at the
 * first pixel the texture or the text draws over every frame, the lines' origin, spacing, cap and wrap,
 * their colours, the palette, a layer decorator's place between the texture and the text, a fresh scramble
 * on every frame and every line, and the premultiplied image.
 * <p>
 * The page indicator a book render leaves uncalled, {@link TextRenderer#drawPageIndicator}, is pinned in
 * {@link PageIndicator}, which calls it directly: its ink's place from the book's corner, its right alignment,
 * its black with no shadow under a text palette, the width it answers, and its text the client's - a count
 * below one shown as one, a page past the count shown as given, its numbers written as the client writes
 * them whatever the default locale.
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
        PixelBuffer background = PixelBuffer.straight(100, 100);
        for (int y = firstRow; y <= lastRow; y++)
            for (int x = firstColumn; x <= lastColumn; x++)
                background.setPixel(x, y, BACKGROUND_FILL);

        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", background);
        tex.put("minecraft:gui/sprites/tooltip/frame", PixelBuffer.straight(100, 100));
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
     * Builds single-line {@code LORE}-style options ("Test") naming no chrome - the shared fixture for the
     * procedural chrome-pixel assertions, each drawn by a renderer holding no context, which draws a
     * request naming no chrome in the code-drawn box.
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
        PixelBuffer clientRect = PixelBuffer.canvas(rectWMcPx * scale, rectHMcPx * scale);
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
    @DisplayName("a request naming no chrome is drawn in the sprite box by a renderer holding a context, and in the code-drawn box by one holding none")
    void aRequestNamingNoChromeIsDrawnInTheRenderersDefault() {
        // The even synthetic pair leaves 8 clear texels inside the client's 12, so its box is the text
        // plus 4 a side, the code-drawn box's size; its frame is clear and its corners square, so the two
        // boxes share a canvas and differ on the ring and the four corners.
        TextRenderer holdingOne = new TextRenderer(syntheticContext(BG_META, 8, 91, 8, 91));
        TextRenderer holdingNone = new TextRenderer();
        PixelBuffer namingNone = render(holdingOne, loreBuilder().build());
        PixelBuffer sprite = render(holdingOne, loreBuilder().chromeStyle(ChromeStyle.SPRITE).build());
        PixelBuffer codeDrawn = render(holdingOne, loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build());
        PixelBuffer namingNoneWithout = render(holdingNone, loreBuilder().build());
        PixelBuffer codeDrawnWithout = render(holdingNone, loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build());

        assertAll(
            () -> assertThat("the code-drawn box's canvas under a context, against the sprite box's",
                List.of(codeDrawn.width(), codeDrawn.height()), is(List.of(sprite.width(), sprite.height()))),
            () -> assertThat("pixels the code-drawn box under a context differs from the sprite box on",
                differing(codeDrawn, sprite), is(greaterThan(0))),
            () -> assertThat("canvas of a request naming no chrome under a context, against the sprite box's",
                List.of(namingNone.width(), namingNone.height()), is(List.of(sprite.width(), sprite.height()))),
            () -> assertThat("pixels a request naming no chrome under a context differs from the sprite box on",
                differing(namingNone, sprite), is(0)),
            () -> assertThat("canvas of a request naming no chrome with no context, against the code-drawn box's",
                List.of(namingNoneWithout.width(), namingNoneWithout.height()),
                is(List.of(codeDrawnWithout.width(), codeDrawnWithout.height()))),
            () -> assertThat("pixels a request naming no chrome with no context differs from the code-drawn box on",
                differing(namingNoneWithout, codeDrawnWithout), is(0))
        );
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
        PixelBuffer frame = PixelBuffer.straight(20, 20);
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
    @DisplayName("a code-drawn tooltip is marked premultiplied, still and animated, and its fill's straight colour is vanilla's background colour")
    void aCodeDrawnTooltipIsMarkedPremultiplied() {
        TextRenderer renderer = new TextRenderer();
        ImageData still = renderer.render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build()).image();
        ImageData animated = renderer.render(TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(LineSegment.fromLegacy("&7Mark &kab", '&'))
            .chromeStyle(ChromeStyle.PROCEDURAL)
            .build()).image();

        assertPremultiplied(still);
        // The fill is held as the client's framebuffer holds it, F00F000F; the image a caller hands
        // Java2D shares the canvas premultiplied, and reading a pixel of it un-premultiplies the fill back
        // to vanilla's background colour, where a fill laid without blending reads F0110011.
        assertThat("the fill's straight colour", still.toBufferedImage().getRGB(still.getWidth() / 2, 0), is(BACKGROUND_FILL));
        assertThat("the obfuscated line animates", animated.isAnimated(), is(true));
        assertPremultiplied(animated);
    }

    @Test
    @DisplayName("a sprite tooltip is marked premultiplied, still and animated, and its fill's straight colour is the sprite's texel")
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
        // The fill is held as the client's framebuffer holds it, F00F000F; the image a caller hands
        // Java2D shares the canvas premultiplied, and reading a pixel of it un-premultiplies the fill back
        // to the background sprite's texel.
        assertThat("the fill's straight colour", still.toBufferedImage().getRGB(still.getWidth() / 2, 0), is(BACKGROUND_FILL));
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
        PixelBuffer background = PixelBuffer.straight(100, 100);
        background.fill(BACKGROUND_FILL);
        PixelBuffer frame = PixelBuffer.straight(100, 100);
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
     * Pins for the {@link TextOptions.Style#BOOK} style. A book texture is a straight seed served by a
     * context that resolves only what it is seeded with, but for six cases that stage it as a file in a
     * one-pack stack read by the production context - beside a sidecar, or cut short - so the texture is
     * read as the client's book screen reads it, a plain texture: an animation section, refused or with a
     * frame size that does not divide the image, leaves it drawn, and a sidecar that does not parse, a
     * refused texture section or an image that does not decode draws the missing texture. No client jar is
     * needed but for the one case that reads vanilla's own texture, which skips without one. Over an opaque
     * texture the quad lies at the canvas's corner on a 384 x 384 canvas, so a line's band - the 18 output
     * rows from its cell's top - sits at a fixed place: line {@code i}'s from row {@code 60 + 18 i}, its
     * first column 72.
     */
    @Nested
    @DisplayName("a book page")
    class Book {

        /** The book texture's id */
        private static final String TEXTURE = "minecraft:gui/book";

        /** The one colour of the synthetic paper */
        private static final int PAPER = 0xFFC8B48C;

        /** The one colour of a book texture staged in a pack */
        private static final int STAGED = 0xFF3366CC;

        /** The side of the canvas a book over an opaque texture draws on, in output pixels - the quad's 192 Minecraft pixels */
        private static final int QUAD_PX = 384;

        /** The output row the first line's band starts at - its cell's top, 30 Minecraft pixels below the quad's corner */
        private static final int FIRST_BAND_ROW = 60;

        /** The output rows one line's band spans - the 9 Minecraft pixels from one line's cell top to the next */
        private static final int BAND_PX = 18;

        /** The message a renderer holding no context refuses a book with */
        private static final String NO_CONTEXT = "The BOOK text style draws its page from the texture 'minecraft:gui/book'; "
            + "the text renderer needs a RendererContext to read it";

        /**
         * Builds BOOK options drawing the given page, left unbuilt so a case can set its own fields.
         *
         * @param page the page drawn
         * @return the partly built text options
         */
        private static TextOptions.Builder book(TextSegment page) {
            return TextOptions.builder().style(TextOptions.Style.BOOK).page(page);
        }

        /**
         * Seeds a context serving the given pixels as the book texture and nothing else.
         *
         * @param texture the book texture's pixels
         * @return the seeded context
         */
        private static RendererContext serving(PixelBuffer texture) {
            return stubContext(Map.of(TEXTURE, texture), Map.of());
        }

        /**
         * Builds the synthetic paper, a 256 x 256 texture every texel of which is {@link #PAPER}.
         *
         * @return the paper
         */
        private static PixelBuffer paper() {
            PixelBuffer paper = PixelBuffer.straight(256, 256);
            paper.fill(PAPER);
            return paper;
        }

        /**
         * Builds an opaque texture every texel of which names its own place - its column above the low twelve
         * bits and its row in them - so a drawn pixel says which texel it read.
         *
         * @param width the texture's width in texels
         * @param height the texture's height in texels
         * @return the texture
         */
        private static PixelBuffer coded(int width, int height) {
            PixelBuffer coded = PixelBuffer.straight(width, height);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    coded.setPixel(x, y, 0xFF000000 | (x << 12) | y);
            return coded;
        }

        /**
         * Draws a request through a renderer holding the given context and takes its first frame.
         *
         * @param context the context the renderer holds
         * @param options the text options to render
         * @return the first frame drawn
         */
        private static PixelBuffer draw(RendererContext context, TextOptions options) {
            return new TextRenderer(context).render(options).image().getFrames().getFirst().pixels();
        }

        /**
         * Answers the texel the client's book quad reads under an output pixel's centre along one axis - the
         * quad's 384 output pixels reading 192 / 256 of the texture's size, whatever that size.
         *
         * @param size the texture's size along the axis, in texels
         * @param outPx the output pixel along the axis, from the quad's corner
         * @return the texel read
         */
        private static int clientTexel(int size, int outPx) {
            return (int) ((long) size * (2L * outPx + 1) / 1024);
        }

        /**
         * Counts the pixels of a canvas whose quad sits at its corner that are not the texel the client's quad
         * reads under them.
         *
         * @param canvas the drawn page
         * @param texture the opaque texture the page was drawn on
         * @return how many pixels hold another colour
         */
        private static int missedTexels(PixelBuffer canvas, PixelBuffer texture) {
            int missed = 0;
            for (int m = 0; m < canvas.height(); m++)
                for (int l = 0; l < canvas.width(); l++)
                    if (canvas.getPixel(l, m) != texture.getPixel(clientTexel(texture.width(), l), clientTexel(texture.height(), m))) missed++;
            return missed;
        }

        /**
         * Gathers every colour but the paper's in a line's band of a canvas whose quad sits at its corner.
         *
         * @param canvas the drawn page
         * @param line the line's number, from 0
         * @return the colours drawn in the band
         */
        private static Set<Integer> bandColours(PixelBuffer canvas, int line) {
            Set<Integer> colours = new TreeSet<>();
            for (int y = FIRST_BAND_ROW + BAND_PX * line; y < FIRST_BAND_ROW + BAND_PX * (line + 1); y++)
                for (int x = 0; x < canvas.width(); x++)
                    if (canvas.getPixel(x, y) != PAPER) colours.add(canvas.getPixel(x, y));
            return colours;
        }

        /**
         * Answers the bounds of the pixels other than the paper's in a line's band of a canvas whose quad sits
         * at its corner.
         *
         * @param canvas the drawn page
         * @param line the line's number, from 0
         * @return the bounds of what the band holds in output pixels, empty where it holds only paper
         */
        private static Optional<Rectangle> bandInk(PixelBuffer canvas, int line) {
            Rectangle ink = null;
            for (int y = FIRST_BAND_ROW + BAND_PX * line; y < FIRST_BAND_ROW + BAND_PX * (line + 1); y++) {
                for (int x = 0; x < canvas.width(); x++) {
                    if (canvas.getPixel(x, y) == PAPER) continue;
                    Rectangle pixel = new Rectangle(x, y, 1, 1);
                    ink = ink == null ? pixel : ink.union(pixel);
                }
            }
            return Optional.ofNullable(ink);
        }

        /**
         * Answers a canvas's size as a pair, for a size assertion that names both sides.
         *
         * @param canvas the drawn page
         * @return its width and height in output pixels
         */
        private static List<Integer> size(PixelBuffer canvas) {
            return List.of(canvas.width(), canvas.height());
        }

        /**
         * Answers the last output row a rectangle holds.
         *
         * @param bounds the rectangle
         * @return its last row
         */
        private static int lastRow(Rectangle bounds) {
            return bounds.y + bounds.height - 1;
        }

        @Test
        @DisplayName("a book on a renderer holding no context throws the book's own refusal, whatever its page and chrome style")
        void aBookWithoutAContextThrows() {
            TextRenderer renderer = new TextRenderer();
            List<TextOptions> requests = new ArrayList<>();
            for (TextSegment page : List.of(TextSegment.literal(""), TextSegment.literal("Text"))) {
                requests.add(book(page).build());
                requests.add(book(page).chromeStyle(ChromeStyle.SPRITE).build());
                requests.add(book(page).chromeStyle(ChromeStyle.PROCEDURAL).build());
            }

            for (TextOptions options : requests) {
                RenderException thrown = assertThrows(RenderException.class, () -> renderer.render(options));
                assertThat(thrown.getMessage(), is(NO_CONTEXT));
            }
        }

        @Test
        @DisplayName("a book with no page, an empty page, or a page of codes alone draws the texture alone")
        void anEmptyPageIsTheTextureAlone() {
            PixelBuffer seed = coded(256, 256);
            RendererContext context = serving(seed);
            PixelBuffer noPage = draw(context, TextOptions.builder().style(TextOptions.Style.BOOK).build());
            PixelBuffer emptyPage = draw(context, book(TextSegment.fromJson("{\"text\": \"\"}")).build());
            PixelBuffer codesAlone = draw(context, book(TextSegment.literal("§l")).build());

            for (PixelBuffer canvas : List.of(noPage, emptyPage, codesAlone)) {
                assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
                assertThat("pixels that are not the texel under them", missedTexels(canvas, seed), is(0));
            }
            assertThat("pixels no page and an empty page differ on", differing(noPage, emptyPage), is(0));
            assertThat("pixels no page and a page of codes differ on", differing(noPage, codesAlone), is(0));
            assertThat("pixels an empty page and a page of codes differ on", differing(emptyPage, codesAlone), is(0));
        }

        @Test
        @DisplayName("a book draws its page and never the request's lines")
        void aBookReadsItsPageAndNeverItsLines() {
            RendererContext context = serving(paper());
            for (TextSegment page : List.of(TextSegment.literal("Page"), TextSegment.literal(""))) {
                PixelBuffer without = draw(context, book(page).build());
                PixelBuffer with = draw(context, book(page).lines(LineSegment.fromLegacy("Lines", '&')).build());

                assertThat("'" + page.getText() + "': the canvas holding lines", size(with), is(size(without)));
                assertThat("'" + page.getText() + "': pixels the lines change", differing(with, without), is(0));
            }
        }

        @Test
        @DisplayName("the book texture is drawn as the client's one quad, three quarters of it across and down at any size")
        void theTextureIsSampledAsTheClientsQuadSamplesIt() {
            for (int textureSize : List.of(256, 512, 768)) {
                PixelBuffer seed = coded(textureSize, textureSize);
                PixelBuffer canvas = draw(serving(seed), book(TextSegment.literal("")).build());

                assertThat(textureSize + ": canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
                assertThat(textureSize + ": pixels that are not the texel the client's quad reads under them",
                    missedTexels(canvas, seed), is(0));
            }
        }

        @Test
        @DisplayName("a book texture no pack ships draws the missing texture as the client's quad draws it, and is a stand-in")
        void aBookTextureNoPackShipsIsTheMissingTextureAndAStandIn() {
            RenderResult result = new TextRenderer(stubContext(Map.of(), Map.of())).render(book(TextSegment.literal("")).build());
            PixelBuffer canvas = result.image().getFrames().getFirst().pixels();

            assertThat("the book texture named as a stand-in", result.substitutions(),
                contains(Substitution.texture(TEXTURE, Possible.State.ABSENT)));
            assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
            assertThat("pixels that are not the missing texture's texel the client's quad reads under them",
                missedTexels(canvas, MissingSprite.sprite()), is(0));
            // The quad reads twelve of the sixteen texels a side: the black check whole, then the magenta one
            // beside it from output column 256.
            assertThat("the corner and the first magenta column", List.of(canvas.getPixel(0, 0), canvas.getPixel(256, 0)),
                is(List.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
        }

        @Test
        @DisplayName("a book texture beside an animation section the client's codec refuses draws the book")
        void aRefusedAnimationSidecarDrawsTheBook(@TempDir Path dir) throws IOException {
            assertDrawsTheBook(bookContext(dir, solidPng(256, 256), Optional.of("{\"animation\":{\"frametime\":0}}")));
        }

        @Test
        @DisplayName("a book texture whose declared frame width does not divide it draws the book")
        void aWidthThatDoesNotDivideDrawsTheBook(@TempDir Path dir) throws IOException {
            assertDrawsTheBook(bookContext(dir, solidPng(256, 256), Optional.of("{\"animation\":{\"width\":100}}")));
        }

        @Test
        @DisplayName("a book texture taller than wide by less than a frame, beside an animation section, draws the book on the square's canvas")
        void aNonSquareStripDrawsTheBook(@TempDir Path dir) throws IOException {
            PixelBuffer canvas = assertDrawsTheBook(bookContext(dir, solidPng(256, 384), Optional.of("{\"animation\":{}}")));

            assertThat("the canvas a square book draws on", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
        }

        @Test
        @DisplayName("a book texture beside a sidecar that is not JSON draws the missing texture, and is a stand-in")
        void aSidecarThatIsNotJsonDrawsTheMissingTexture(@TempDir Path dir) throws IOException {
            assertDrawsTheMissingTexture(bookContext(dir, solidPng(256, 256), Optional.of("{ \"animation\": ")));
        }

        @Test
        @DisplayName("a book texture beside a texture section the client's codec refuses draws the missing texture, and is a stand-in")
        void aRefusedTextureSectionDrawsTheMissingTexture(@TempDir Path dir) throws IOException {
            assertDrawsTheMissingTexture(bookContext(dir, solidPng(256, 256), Optional.of("{\"texture\":{\"blur\":1}}")));
        }

        @Test
        @DisplayName("a book texture whose image does not decode draws the missing texture, and is a stand-in")
        void anImageThatDoesNotDecodeDrawsTheMissingTexture(@TempDir Path dir) throws IOException {
            byte[] png = solidPng(256, 256);
            assertDrawsTheMissingTexture(bookContext(dir, Arrays.copyOf(png, png.length / 2), Optional.empty()));
        }

        /**
         * Asserts an empty page over a staged book texture draws the texture's one colour at the quad's
         * first texel and names no stand-in.
         *
         * @param context the context the staged texture is read through
         * @return the drawn page
         */
        private static PixelBuffer assertDrawsTheBook(RendererContext context) {
            RenderResult result = new TextRenderer(context).render(book(TextSegment.literal("")).build());
            PixelBuffer canvas = result.image().getFrames().getFirst().pixels();

            assertThat("the book texture's colour at the quad's first texel", canvas.getPixel(0, 0), is(STAGED));
            assertThat("stand-ins", result.substitutions(), is(empty()));
            return canvas;
        }

        /**
         * Asserts an empty page over a staged book texture draws the missing texture as the client's quad
         * draws it and names the texture as a stand-in that is served and yields no pixels.
         *
         * @param context the context the staged texture is read through
         */
        private static void assertDrawsTheMissingTexture(RendererContext context) {
            RenderResult result = new TextRenderer(context).render(book(TextSegment.literal("")).build());
            PixelBuffer canvas = result.image().getFrames().getFirst().pixels();

            assertThat("the book texture named as a stand-in", result.substitutions(),
                contains(Substitution.texture(TEXTURE, Possible.State.EMPTY)));
            assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
            assertThat("pixels that are not the missing texture's texel the client's quad reads under them",
                missedTexels(canvas, MissingSprite.sprite()), is(0));
        }

        /**
         * Stages one pack holding the book texture, and its sidecar where one is given, indexes it and
         * builds the production context over it.
         *
         * @param dir the directory the pack is staged in
         * @param png the book texture's bytes
         * @param sidecar the sidecar's text, which need not parse, or empty for none
         * @return the production context over the staged pack
         * @throws IOException if writing the pack fails
         */
        private static RendererContext bookContext(Path dir, byte[] png, Optional<String> sidecar) throws IOException {
            Path gui = dir.resolve("assets/minecraft/textures/gui");
            Files.createDirectories(gui);
            Files.write(gui.resolve("book.png"), png);
            if (sidecar.isPresent()) Files.writeString(gui.resolve("book.png.mcmeta"), sidecar.get());

            ResourcePack pack = new ResourcePack(
                PackId.VANILLA, new PackContainer.Directory(dir), MCMeta.EMPTY,
                Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
                Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
            PackStack bare = PackStack.of(Concurrent.newList(pack));
            PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
            return new IndexedRendererContext(
                stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
                new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
                Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
                Concurrent.newMap(), TextureSynthesizer.EMPTY, Concurrent.newMap(),
                Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList(),
                SpriteAtlas.load(stack, SpriteAtlas.ITEMS), SpriteAtlas.load(stack, SpriteAtlas.BLOCKS));
        }

        /**
         * Encodes an image every texel of which is {@link #STAGED}.
         *
         * @param width the image width
         * @param height the image height
         * @return the PNG bytes
         * @throws IOException if encoding fails
         */
        private static byte[] solidPng(int width, int height) throws IOException {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    image.setRGB(x, y, STAGED);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        }

        @Test
        @DisplayName("a texture whose drawn edge falls inside a Minecraft pixel puts the canvas's edge at the whole one outside it")
        void aTexturesFirstPixelOnAnOddColumnIsWhereTheCanvasRoundsOut() {
            PixelBuffer seed = PixelBuffer.straight(512, 512);
            for (int y = 10; y <= 200; y++)
                for (int x = 41; x <= 300; x++)
                    seed.setPixel(x, y, PAPER);
            PixelBuffer canvas = draw(serving(seed), book(TextSegment.literal("")).build());

            // Over 512 texels the quad reads one texel to an output pixel, so the drawn output bounds are x 41
            // to 300 and y 10 to 200 - Minecraft x 20.5 to 150.5 and y 5 to 100.5, rounded out to 20 to 151 and
            // 5 to 101.
            int drawnAtTheSides = 0;
            for (int y = 0; y < canvas.height(); y++)
                if (ColorMath.alpha(canvas.getPixel(0, y)) != 0 || ColorMath.alpha(canvas.getPixel(canvas.width() - 1, y)) != 0) drawnAtTheSides++;

            assertThat("canvas", size(canvas), is(List.of(262, 192)));
            assertThat("rows drawn in the first or last column", drawnAtTheSides, is(0));
            assertThat("the second column's first row and last row",
                List.of(canvas.getPixel(1, 0), canvas.getPixel(1, canvas.height() - 1)), is(List.of(PAPER, 0)));
        }

        @Test
        @DisplayName("vanilla's book texture draws a page 292 x 360 from its first drawn texel")
        void vanillasBookEndsAtItsFirstDrawnPixel() throws IOException {
            assumeSprites();
            PixelBuffer texture = PixelBuffer.wrap(ImageIO.read(new ByteArrayInputStream(
                ClientAssetsExtension.vanilla().bytes("assets/minecraft/textures/gui/book.png").orElseThrow())));
            PixelBuffer canvas = draw(serving(texture), book(TextSegment.literal("")).build());

            // The first texel drawn is column 20 and row 1, so the canvas starts at Minecraft (20, 1) and its
            // pixel (l, m) reads texel (20 + l / 2, 1 + m / 2).
            int missed = 0;
            for (int m = 0; m < Math.min(canvas.height(), 360); m++) {
                for (int l = 0; l < Math.min(canvas.width(), 292); l++) {
                    int texel = texture.getPixel(20 + l / 2, 1 + m / 2);
                    if (canvas.getPixel(l, m) != (ColorMath.alpha(texel) == 0 ? 0 : texel)) missed++;
                }
            }

            assertThat("canvas", size(canvas), is(List.of(292, 360)));
            assertThat("pixels that are not the texel under them", missed, is(0));
        }

        @Test
        @DisplayName("the canvas ends at the first pixel the texture or the text draws, and is the empty image where neither draws")
        void theCanvasIsTheUnionOfTheTexturesAndTheTextsInk() {
            PixelBuffer clear = PixelBuffer.straight(256, 256);
            PixelBuffer patch = PixelBuffer.straight(256, 256);
            for (int y = 0; y < 10; y++)
                for (int x = 0; x < 10; x++)
                    patch.setPixel(x, y, PAPER);

            PixelBuffer textAlone = draw(serving(clear), book(TextSegment.literal("Hi")).build());
            Set<Integer> textColours = new TreeSet<>();
            for (int y = 0; y < textAlone.height(); y++)
                for (int x = 0; x < textAlone.width(); x++)
                    if (ColorMath.alpha(textAlone.getPixel(x, y)) != 0) textColours.add(textAlone.getPixel(x, y));
            assertThat("the text alone: canvas", size(textAlone), is(List.of(14, 14)));
            assertThat("the text alone: colours drawn", textColours, is(Set.of(0xFF000000)));

            // Two lines are 9 apart, so the second's ink is 39 to 46 down and the pair's 30 to 46.
            PixelBuffer twoLines = draw(serving(clear), book(TextSegment.literal("Hi\nHi")).build());
            assertThat("two lines alone: canvas", size(twoLines), is(List.of(14, 32)));
            assertThat("two lines alone: the bounds of what they draw", twoLines.opaqueBounds(), is(new Rectangle(0, 0, 14, 32)));

            // The patch is Minecraft 0 to 10 a side, the text's ink 36 to 43 across and 30 to 37 down.
            PixelBuffer joined = draw(serving(patch), book(TextSegment.literal("Hi")).build());
            assertThat("the patch and the text: canvas", size(joined), is(List.of(86, 74)));
            assertThat("the patch's corners and the pixel past it",
                List.of(joined.getPixel(0, 0), joined.getPixel(19, 19), joined.getPixel(20, 20)), is(List.of(PAPER, PAPER, 0)));

            ImageData nothing = new TextRenderer(serving(clear)).render(book(TextSegment.literal("")).build()).image();
            PixelBuffer empty = nothing.getFrames().getFirst().pixels();
            assertThat("nothing drawn: animated, size and alpha",
                List.of(nothing.isAnimated(), empty.width(), empty.height(), ColorMath.alpha(empty.getPixel(0, 0))),
                is(List.of(false, 1, 1, 0)));
        }

        @Test
        @DisplayName("an obfuscated page's canvas ends at the first pixel its text draws over every frame, each frame's glyphs uncut")
        void anObfuscatedPagesCanvasHoldsEveryFramesInk() {
            TextOptions options = book(TextSegment.literal("§kHello")).frameCount(20).build();
            ConcurrentList<ImageFrame> onPaper = new TextRenderer(serving(paper())).render(options).image().getFrames();
            ConcurrentList<ImageFrame> alone = new TextRenderer(serving(PixelBuffer.straight(256, 256))).render(options).image().getFrames();

            // Over the paper the quad is the whole canvas, so every frame's glyphs lie uncut where the page puts
            // them; their bounds over every frame, rounded out to whole Minecraft pixels, are the canvas the page
            // draws on over a clear texture, each frame's glyphs at their place on it.
            Rectangle ink = null;
            for (ImageFrame frame : onPaper) {
                PixelBuffer canvas = frame.pixels();
                for (int y = 0; y < canvas.height(); y++) {
                    for (int x = 0; x < canvas.width(); x++) {
                        if (canvas.getPixel(x, y) == PAPER) continue;
                        Rectangle pixel = new Rectangle(x, y, 1, 1);
                        ink = ink == null ? pixel : ink.union(pixel);
                    }
                }
            }
            assertThat("the page drew ink over the paper", ink == null, is(false));
            int left = ink.x / 2 * 2;
            int top = ink.y / 2 * 2;
            int right = (ink.x + ink.width + 1) / 2 * 2;
            int bottom = (ink.y + ink.height + 1) / 2 * 2;

            assertThat("frames over the clear texture", alone.size(), is(onPaper.size()));
            assertThat("canvas", size(alone.getFirst().pixels()), is(List.of(right - left, bottom - top)));
            int missed = 0;
            for (int f = 0; f < alone.size(); f++) {
                PixelBuffer canvas = alone.get(f).pixels();
                PixelBuffer under = onPaper.get(f).pixels();
                for (int m = 0; m < canvas.height(); m++) {
                    for (int l = 0; l < canvas.width(); l++) {
                        int drawn = under.getPixel(left + l, top + m);
                        if (canvas.getPixel(l, m) != (drawn == PAPER ? 0 : drawn)) missed++;
                    }
                }
            }
            assertThat("pixels that are not the frame's own glyphs", missed, is(0));
        }

        @Test
        @DisplayName("a book texture's animation section neither animates the page nor crops the texture to a frame")
        void aTexturesAnimationNeitherAnimatesNorCropsTheBook() {
            PixelBuffer strip = coded(256, 512);
            RendererContext context = stubContext(Map.of(TEXTURE, strip), Map.of(),
                Map.of(TEXTURE, new MCMeta.Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty())));
            ImageData image = new TextRenderer(context).render(book(TextSegment.literal("")).build()).image();
            PixelBuffer canvas = image.getFrames().getFirst().pixels();

            assertThat("animated", image.isAnimated(), is(false));
            assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
            assertThat("pixels that are not the strip's texel the client's quad reads under them",
                missedTexels(canvas, strip), is(0));
        }

        @Test
        @DisplayName("a book reads no chrome style: every one draws the same page")
        void everyChromeStyleDrawsTheSamePage() {
            RendererContext context = serving(paper());
            PixelBuffer namingNone = draw(context, book(TextSegment.literal("Same page")).build());
            PixelBuffer sprite = draw(context, book(TextSegment.literal("Same page")).chromeStyle(ChromeStyle.SPRITE).build());
            PixelBuffer procedural = draw(context, book(TextSegment.literal("Same page")).chromeStyle(ChromeStyle.PROCEDURAL).build());

            assertThat("the sprite chrome's canvas", size(sprite), is(size(namingNone)));
            assertThat("the procedural chrome's canvas", size(procedural), is(size(namingNone)));
            assertThat("pixels naming none and the sprite chrome differ on", differing(namingNone, sprite), is(0));
            assertThat("pixels naming none and the procedural chrome differ on", differing(namingNone, procedural), is(0));
            assertThat("pixels the sprite and procedural chromes differ on", differing(sprite, procedural), is(0));
        }

        @Test
        @DisplayName("a page's lines start at the client's text origin, nine Minecraft pixels apart, and stop at fourteen")
        void linesStartAtTheClientsOriginNineApartAndStopAtFourteen() {
            StringBuilder page = new StringBuilder("Line 1");
            for (int line = 2; line <= 16; line++)
                page.append("\nLine ").append(line);
            PixelBuffer canvas = draw(serving(paper()), book(TextSegment.literal(page.toString())).build());

            assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
            for (int i = 0; i < 14; i++) {
                Optional<Rectangle> ink = bandInk(canvas, i);
                assertThat("line " + (i + 1) + " drawn", ink.isPresent(), is(true));
                assertThat("line " + (i + 1) + ": its first ink row and column", List.of(ink.get().y, ink.get().x),
                    is(List.of(FIRST_BAND_ROW + BAND_PX * i, 72)));
            }
            assertThat("the ink of lines 15 and 16, which the page drops", List.of(bandInk(canvas, 14), bandInk(canvas, 15)),
                is(List.of(Optional.empty(), Optional.empty())));
        }

        @Test
        @DisplayName("a page wraps at the client's width of 114 Minecraft pixels, a line exactly that wide kept whole")
        void aPageWrapsAtTheClientsWidth() {
            RendererContext context = serving(paper());
            PixelBuffer exact = draw(context, book(TextSegment.literal("a".repeat(19))).build());
            PixelBuffer over = draw(context, book(TextSegment.literal("a".repeat(18) + "lii")).build());

            assertThat("nineteen a's, 114 wide: lines 1 and 2 drawn",
                List.of(bandInk(exact, 0).isPresent(), bandInk(exact, 1).isPresent()), is(List.of(true, false)));
            assertThat("eighteen a's and lii, 115 wide: lines 1 and 2 drawn",
                List.of(bandInk(over, 0).isPresent(), bandInk(over, 1).isPresent()), is(List.of(true, true)));
        }

        @Test
        @DisplayName("every line the library wraps a mixed page into measures within the page's width by the kit's measure")
        void everyWrappedLineMeasuresWithinThePageWidth() {
            ConcurrentList<LineSegment> lines = TextSegment.fromJson("[\"\", \"The quick brown fox jumps over the lazy dog and \", "
                + "{\"text\": \"keeps running in bold \", \"bold\": true}, {\"text\": \"through italic words \", \"italic\": true}, "
                + "{\"text\": \"and alt font letters \", \"font\": \"minecraft:alt\"}, "
                + "\"until the page has wrapped several times over its width\"]").wrap(114);
            List<Integer> overWide = lines.stream().map(TextKit::measureLineMcPixels).filter(width -> width > 114).toList();

            assertThat("lines", lines.size(), is(greaterThan(1)));
            assertThat("widths over the page's 114", overWide, is(empty()));
        }

        @Test
        @DisplayName("a page's text is black with no shadow, and a span's own colour and shadow colour win")
        void aPagesTextIsBlackWithNoShadowAndASpanKeepsItsOwn() {
            PixelBuffer canvas = draw(serving(paper()), book(TextSegment.fromJson("[\"\", \"Black\\n\", "
                + "{\"text\": \"Red\\n\", \"color\": \"red\"}, {\"text\": \"Shade\", \"shadow_color\": -16776961}]")).build());

            assertThat("line 1's colours", bandColours(canvas, 0), is(Set.of(0xFF000000)));
            assertThat("line 1's last ink row, which no shadow row follows", lastRow(bandInk(canvas, 0).orElseThrow()), is(73));
            assertThat("line 2's colours", bandColours(canvas, 1), is(Set.of(0xFFFF5555)));
            assertThat("line 3's colours, its own shadow among them", bandColours(canvas, 2), is(Set.of(0xFF000000, 0xFF0000FF)));
            assertThat("line 3's last ink row, its shadow's", lastRow(bandInk(canvas, 2).orElseThrow()), is(111));
        }

        @Test
        @DisplayName("a line the wrap opens after a reset draws white with a drop shadow, as the client draws it, and the lines before it black with none")
        void aLineAResetBreaksIntoDrawsWhiteWithAShadow() {
            PixelBuffer canvas = draw(serving(paper()), book(TextSegment.literal("§lTitle§r\nBody")).build());

            assertThat("line 1's colours", bandColours(canvas, 0), is(Set.of(0xFF000000)));
            assertThat("line 2's colours, white over its derived shadow", bandColours(canvas, 1), is(Set.of(0xFFFFFFFF, 0xFF3F3F3F)));
        }

        @Test
        @DisplayName("the context's text palette recolours the page's text and not the texture")
        void theContextsTextPaletteReachesThePage() {
            TextPalette palette = new TextPalette(new ResourceId("minecraft", "shaders/core/rendertype_text.vsh"), new PackId("pack"),
                Concurrent.newUnmodifiableMap(Map.of(0x000000, 0x2B5D8A)));
            PixelBuffer swapped = draw(withPalette(serving(paper()), palette), book(TextSegment.literal("Black")).build());
            PixelBuffer plain = draw(serving(paper()), book(TextSegment.literal("Black")).build());

            assertThat("line 1's colours under the palette", bandColours(swapped, 0), is(Set.of(0xFF2B5D8A)));
            assertThat("line 1's colours without it", bandColours(plain, 0), is(Set.of(0xFF000000)));
            assertThat("the paper's corner under the palette and without it",
                List.of(swapped.getPixel(0, 0), plain.getPixel(0, 0)), is(List.of(PAPER, PAPER)));
        }

        @Test
        @DisplayName("a layer decorator splices into the book's stack, after the texture and before the text")
        void aLayerDecoratorSplicesBetweenTheTextureAndTheText() {
            int fill = 0xFF00FF00;
            PixelBuffer canvas = draw(serving(paper()), book(TextSegment.literal("Hi"))
                .layerDecorator(stack -> stack.addAfter(TextSlot.BACKGROUND, frame -> frame.fill(fill)))
                .build());
            Set<Integer> colours = new TreeSet<>();
            for (int y = 0; y < canvas.height(); y++)
                for (int x = 0; x < canvas.width(); x++)
                    colours.add(canvas.getPixel(x, y));

            assertThat("canvas", size(canvas), is(List.of(QUAD_PX, QUAD_PX)));
            assertThat("the corner, the fill over the paper", canvas.getPixel(0, 0), is(fill));
            assertThat("colours drawn, the text over the fill", colours, is(Set.of(0xFF000000, fill)));
        }

        @Test
        @DisplayName("a book page is marked premultiplied, still and animated")
        void aBookIsMarkedPremultiplied() {
            PixelBuffer translucent = PixelBuffer.straight(256, 256);
            translucent.fill(0x80C8B48C);
            TextRenderer renderer = new TextRenderer(serving(translucent));
            ImageData still = renderer.render(book(TextSegment.literal("Mark")).build()).image();
            ImageData animated = renderer.render(book(TextSegment.fromJson("{\"text\": \"ab\", \"obfuscated\": true}")).build()).image();

            assertPremultiplied(still);
            assertThat("the obfuscated page animates", animated.isAnimated(), is(true));
            assertPremultiplied(animated);
        }

        @Test
        @DisplayName("an obfuscated page draws a fresh scramble on every frame")
        void anObfuscatedPageDrawsAFreshScrambleOnEveryFrame() {
            ConcurrentList<ImageFrame> frames = new TextRenderer(serving(paper()))
                .render(book(TextSegment.literal("§kHello")).build()).image().getFrames();
            ConcurrentList<Integer> alike = Concurrent.newList();
            for (int f = 1; f < frames.size(); f++)
                if (differing(frames.getFirst().pixels(), frames.get(f).pixels()) == 0) alike.add(f);

            assertThat("frames", frames.size(), is(20));
            assertThat("frames drawing the first frame's scramble", alike, is(empty()));
        }

        @Test
        @DisplayName("two lines of the same obfuscated text draw two scrambles")
        void twoObfuscatedLinesScrambleApart() {
            ConcurrentList<ImageFrame> frames = new TextRenderer(serving(paper()))
                .render(book(TextSegment.literal("§kHello\n§kHello")).build()).image().getFrames();
            ConcurrentList<Integer> alike = Concurrent.newList();
            for (int f = 0; f < frames.size(); f++) {
                PixelBuffer canvas = frames.get(f).pixels();
                PixelBuffer first = canvas.crop(0, FIRST_BAND_ROW, canvas.width(), BAND_PX);
                PixelBuffer second = canvas.crop(0, FIRST_BAND_ROW + BAND_PX, canvas.width(), BAND_PX);
                if (differing(first, second) == 0) alike.add(f);
            }

            assertThat("the obfuscated lines animate", frames.size(), is(greaterThan(1)));
            assertThat("frames whose two lines draw one scramble", alike, is(empty()));
        }

    }

    /**
     * Pins for the page indicator a book render leaves uncalled, {@link TextRenderer#drawPageIndicator}, each case
     * calling it directly on a clear {@link PixelBuffer#canvas canvas}. With the book quad's corner at the canvas's
     * own, {@code Page 1 of 1} inks output columns 178 to 293 and rows 32 to 47 - the client's 89 to 146 and 16 to
     * 23 Minecraft pixels from the corner, the {@code g}'s descender its last row - and every indicator's ink ends
     * at column 293, whatever its numbers. A shadow of black text is black too, so it is told apart by the column
     * and the row it adds past the glyphs.
     */
    @Nested
    @DisplayName("the book's page indicator")
    class PageIndicator {

        /** The ink of {@code Page 1 of 1} with the book quad's corner at the canvas's, in output pixels */
        private static final Rectangle ONE_OF_ONE = new Rectangle(178, 32, 116, 16);

        /** A text palette swapping the page's black for one other colour */
        private static final TextPalette SWAPS_BLACK = new TextPalette(new ResourceId("minecraft", "shaders/core/rendertype_text.vsh"),
            new PackId("pack"), Concurrent.newUnmodifiableMap(Map.of(0x000000, 0x2B5D8A)));

        /**
         * Draws the indicator on a clear canvas the size of the book quad, the quad's corner at the canvas's.
         *
         * @param palette the text palette drawn through
         * @param page the page number shown
         * @param pageCount the page count shown
         * @return the canvas drawn on
         */
        private static PixelBuffer drawn(TextPalette palette, int page, int pageCount) {
            PixelBuffer canvas = PixelBuffer.canvas(Book.QUAD_PX, Book.QUAD_PX);
            TextRenderer.drawPageIndicator(new MinecraftGraphics(canvas), palette, 0, 0, page, pageCount);
            return canvas;
        }

        /**
         * Answers the width the indicator answers, drawn on a canvas of its own.
         *
         * @param page the page number shown
         * @param pageCount the page count shown
         * @return the width in whole Minecraft pixels
         */
        private static int width(int page, int pageCount) {
            return TextRenderer.drawPageIndicator(new MinecraftGraphics(PixelBuffer.canvas(Book.QUAD_PX, Book.QUAD_PX)),
                TextPalette.VANILLA, 0, 0, page, pageCount);
        }

        /**
         * Answers the bounds of every pixel drawn on a clear canvas.
         *
         * @param canvas the canvas drawn on
         * @return the bounds in output pixels, empty where nothing is drawn
         */
        private static Optional<Rectangle> ink(PixelBuffer canvas) {
            Rectangle ink = null;
            for (int y = 0; y < canvas.height(); y++) {
                for (int x = 0; x < canvas.width(); x++) {
                    if (canvas.getPixel(x, y) == 0) continue;
                    Rectangle pixel = new Rectangle(x, y, 1, 1);
                    ink = ink == null ? pixel : ink.union(pixel);
                }
            }
            return Optional.ofNullable(ink);
        }

        /**
         * Gathers every colour drawn on a clear canvas.
         *
         * @param canvas the canvas drawn on
         * @return the colours drawn
         */
        private static Set<Integer> colours(PixelBuffer canvas) {
            Set<Integer> colours = new TreeSet<>();
            for (int y = 0; y < canvas.height(); y++)
                for (int x = 0; x < canvas.width(); x++)
                    if (canvas.getPixel(x, y) != 0) colours.add(canvas.getPixel(x, y));
            return colours;
        }

        /**
         * Draws the client's indicator line on a clear canvas the size of the book quad, the quad's corner at the
         * canvas's: the text in the page's text style, spelled as JSON - black, with a shadow colour of no alpha -
         * drawn in white with the shadow asked for, its left the given width short of 148 Minecraft pixels from
         * the corner and the top of its glyph cell 16 below it.
         *
         * @param text the indicator's text as the client writes it
         * @param widthMcPx the text's width as the client's font measures it, in mcPixels
         * @return the canvas drawn on
         */
        private static PixelBuffer clientsLine(String text, int widthMcPx) {
            PixelBuffer canvas = PixelBuffer.canvas(Book.QUAD_PX, Book.QUAD_PX);
            LineSegment line = LineSegment.builder().segments(Concurrent.newList(TextSegment.fromJson(
                "{\"text\": \"" + text + "\", \"color\": \"#000000\", \"shadow_color\": 0}"))).build();
            int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
            TextKit.drawLine(new MinecraftGraphics(canvas), line, 148 - widthMcPx, 16 + ascentMcPx, ColorMath.WHITE,
                TextPalette.VANILLA, 0, 0L, 0L, true);
            return canvas;
        }

        @Test
        @DisplayName("'Page 1 of 1' inks where the client draws it from the book's corner, the corner at the canvas's and at the one the commented-out call passes on vanilla's page")
        void theIndicatorInksWhereTheClientDrawsItFromTheCorner() {
            PixelBuffer pageCanvas = PixelBuffer.canvas(292, 360);
            TextRenderer.drawPageIndicator(new MinecraftGraphics(pageCanvas), TextPalette.VANILLA, -20, -1, 1, 1);

            assertThat("ink, the corner at the canvas's", ink(drawn(TextPalette.VANILLA, 1, 1)), is(Optional.of(ONE_OF_ONE)));
            assertThat("ink on the 292 x 360 canvas vanilla's page draws on, the corner at (-20, -1)", ink(pageCanvas),
                is(Optional.of(new Rectangle(138, 30, 116, 16))));
        }

        @Test
        @DisplayName("a wider number starts the indicator further left and ends it at the same edge, its text the client's")
        void aWiderNumberEndsAtTheSameEdge() {
            PixelBuffer threeOfTwelve = drawn(TextPalette.VANILLA, 3, 12);

            assertThat("'Page 3 of 12' ink", ink(threeOfTwelve), is(Optional.of(new Rectangle(166, 32, 128, 16))));
            assertThat("'Page 100 of 100' ink", ink(drawn(TextPalette.VANILLA, 100, 100)),
                is(Optional.of(new Rectangle(130, 32, 164, 16))));
            assertThat("pixels 'Page 3 of 12' differs on from the client's text in the page style, 65 left of the edge",
                differing(threeOfTwelve, clientsLine("Page 3 of 12", 65)), is(0));
        }

        @Test
        @DisplayName("the indicator is the page's black with no shadow, in the colour a text palette swaps black for")
        void theIndicatorIsBlackWithNoShadowInThePalettesColour() {
            PixelBuffer vanilla = drawn(TextPalette.VANILLA, 1, 1);
            PixelBuffer swapped = drawn(SWAPS_BLACK, 1, 1);

            assertThat("colours drawn", colours(vanilla), is(Set.of(0xFF000000)));
            assertThat("ink, no shadow column or row past the glyphs'", ink(vanilla), is(Optional.of(ONE_OF_ONE)));
            assertThat("colours drawn under a palette swapping black", colours(swapped), is(Set.of(0xFF2B5D8A)));
            assertThat("ink under that palette", ink(swapped), is(Optional.of(ONE_OF_ONE)));
        }

        @Test
        @DisplayName("the indicator answers its width as the client's font measures it, shows a count below one as one and a page past the count as given, and writes its numbers in ASCII digits whatever the default locale")
        void theIndicatorAnswersItsWidthAndWritesItsNumbersAsTheClientDoes() {
            PixelBuffer asWritten = drawn(TextPalette.VANILLA, 1234, 5678);
            PixelBuffer underThaiDigits = PixelBuffer.canvas(Book.QUAD_PX, Book.QUAD_PX);
            Locale format = Locale.getDefault(Locale.Category.FORMAT);
            int underThaiDigitsWidth;
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("th-TH-u-nu-thai"));
                underThaiDigitsWidth = TextRenderer.drawPageIndicator(new MinecraftGraphics(underThaiDigits),
                    TextPalette.VANILLA, 0, 0, 1234, 5678);
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, format);
            }

            assertThat("'Page 1 of 1'", width(1, 1), is(59));
            assertThat("'Page 3 of 12'", width(3, 12), is(65));
            assertThat("'Page 1234 of 5678'", width(1234, 5678), is(95));
            assertThat("'Page 1234 of 5678' where the default locale writes Thai digits", underThaiDigitsWidth, is(95));
            assertThat("pixels the Thai-digit locale changes", differing(underThaiDigits, asWritten), is(0));
            assertThat("a count of 0", width(1, 0), is(59));
            assertThat("pixels 'Page 1 of 1' differs on from the client's text",
                differing(drawn(TextPalette.VANILLA, 1, 1), clientsLine("Page 1 of 1", 59)), is(0));
            assertThat("pixels page 1 of a count of 0 differs on from the client's 'Page 1 of 1'",
                differing(drawn(TextPalette.VANILLA, 1, 0), clientsLine("Page 1 of 1", 59)), is(0));
            assertThat("pixels page 3 of a count of 2 differs on from the client's 'Page 3 of 2'",
                differing(drawn(TextPalette.VANILLA, 3, 2), clientsLine("Page 3 of 2", 59)), is(0));
        }

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
            PixelBuffer canvas = PixelBuffer.canvas(400, 120);
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
