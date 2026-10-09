package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static lib.minecraft.renderer.fixture.TooltipFixtures.BG_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.FRAME_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.guiMeta;
import static lib.minecraft.renderer.fixture.TooltipFixtures.itemWithStyle;
import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel pins for {@link TextRenderer}'s {@code LORE}-style tooltip chrome, both of its arms drawn
 * through the renderer.
 * <p>
 * {@link ChromeStyle#PROCEDURAL}'s fill and gradient ring are sampled at known output coordinates
 * against vanilla's exact colours - background fill {@code 0xF0100010}, gradient top
 * {@code 0x505000FF}, gradient bottom {@code 0x5028007F}, and the interpolated left edge bracketed
 * between them.
 * <p>
 * {@link ChromeStyle#SPRITE}'s cases pin its nine-slice pair - the notched corners and open ring
 * corners, the canvas its smaller padding sizes and the alpha multipliers applied to the sprite bytes,
 * each drawn from a context seeded with the real background and frame sprites - along with a styled
 * pair reached end to end through an item's {@code minecraft:tooltip_style} component, and the raise a
 * renderer holding no context gives the arm. The cases needing the real sprites off the offline
 * extraction skip per method rather than per class, so the procedural pins still run on a host without
 * it.
 * <p>
 * {@link MinecraftFontsExtension} supplies the loaded Minecraft font atlas so the renderer can lay
 * out glyphs and size the tooltip canvas.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextRendererTest {

    /** Vanilla's tooltip background fill, painted by both chromes */
    private static final int BACKGROUND_FILL = 0xF0100010;

    /** Vanilla's border gradient at the ring's top stroke */
    private static final int RING_TOP = 0x505000FF;

    /** Vanilla's border gradient at the ring's bottom stroke */
    private static final int RING_BOTTOM = 0x5028007F;

    /** Output row of the ring's top stroke - the stroke is 1 mcPixel thick, inset 1 mcPixel from the edge */
    private static final int RING_TOP_ROW = 2;

    /** Output rows from the bottom the ring's bottom stroke sits at */
    private static final int RING_BOTTOM_ROW_FROM_END = 3;

    /** The vanilla tooltip sprite directory in the offline extraction */
    private static final Path TOOLTIP_DIR = Path.of(
        "cache/asset-renderer/vanilla/26.1/assets/minecraft/textures/gui/sprites/tooltip");

    /** Skips the calling test when the extraction holding the real tooltip sprites is absent. */
    private static void assumeSprites() {
        Assumptions.assumeTrue(Files.isDirectory(TOOLTIP_DIR), "vanilla 26.1 extraction not present");
    }

    /**
     * Reads one tooltip sprite out of the extraction.
     *
     * @param name the sprite's file name below the tooltip sprite directory
     * @return the decoded sprite
     * @throws IOException if the sprite cannot be read
     */
    private static PixelBuffer sprite(String name) throws IOException {
        BufferedImage image = ImageIO.read(TOOLTIP_DIR.resolve(name).toFile());
        return PixelBuffer.wrap(image);
    }

    /**
     * Reads a sprite's GUI scaling out of the sidecar shipped beside it.
     *
     * @param mcmetaName the sidecar's file name below the tooltip sprite directory
     * @return the nine-slice scaling the sidecar declares
     * @throws IOException if the sidecar cannot be read
     */
    private static MCMeta.GuiScaling scaling(String mcmetaName) throws IOException {
        MCMeta meta = MCMetaParser.parse(Files.readString(TOOLTIP_DIR.resolve(mcmetaName)), new ResourceId("minecraft", "tooltip"));
        return meta.gui().orElseThrow();
    }

    /**
     * Seeds a context with the real background and frame sprites at the default pair's ids, each beside
     * the scaling its shipped sidecar declares, failing the test on an unreadable file rather than
     * declaring a checked exception every case would have to thread through. A missing extraction is
     * the {@link #assumeSprites()} skip; a present but unreadable one is a hard failure.
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
     * Builds a one-line LORE tooltip, left unbuilt so each case names its own chrome.
     *
     * @return the partly built text options
     */
    private static TextOptions.Builder loreBuilder() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().withSegments(ColorSegment.builder().withText("Sprite Chrome").build()).build());
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
            .withSegments(ColorSegment.builder().withText("Test").build())
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
        ImageData image = renderer.render(options);
        return image.getFrames().getFirst().pixels();
    }

    /**
     * Samples the ring's top stroke at the canvas mid-column.
     *
     * @param buf the rendered tooltip
     * @return the sampled pixel
     */
    private static int ringTop(PixelBuffer buf) {
        return buf.getPixel(buf.width() / 2, RING_TOP_ROW);
    }

    /**
     * Samples the ring's bottom stroke at the canvas mid-column.
     *
     * @param buf the rendered tooltip
     * @return the sampled pixel
     */
    private static int ringBottom(PixelBuffer buf) {
        return buf.getPixel(buf.width() / 2, buf.height() - RING_BOTTOM_ROW_FROM_END);
    }

    @Test
    @DisplayName("lore background fill pixel matches 0xF0100010")
    void backgroundFillMatchesVanilla() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Border occupies y in [2, 4) (1 mcPixel inset + 1 mcPixel stroke = 4 output pixels).
        // Sample at y=5 which is well inside the padding interior above the first glyph.
        int cx = buf.width() / 2;
        int cy = 5;
        int px = buf.getPixel(cx, cy);
        assertThat("alpha in padding", ColorMath.alpha(px), is(ColorMath.alpha(BACKGROUND_FILL)));
        assertThat("RGB in padding", px & 0xFFFFFF, is(BACKGROUND_FILL & 0xFFFFFF));
    }

    @Test
    @DisplayName("lore border top row uses gradient top color (α=80, RGB=0x5000FF)")
    void borderTopMatchesVanillaGradientTop() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Border stroke is 1 mcPixel (2 output pixels) thick, inset 1 mcPixel from edge.
        // So the top stroke spans y in [2, 4). Sample at y=2.
        int px = buf.getPixel(buf.width() / 2, 2);
        assertThat("top border alpha", ColorMath.alpha(px), is(ColorMath.alpha(RING_TOP)));
        assertThat("top border RGB", px & 0xFFFFFF, is(RING_TOP & 0xFFFFFF));
    }

    @Test
    @DisplayName("lore border bottom row uses gradient bottom color (α=80, RGB=0x28007F)")
    void borderBottomMatchesVanillaGradientBottom() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Bottom stroke spans y in [h-4, h-2). Sample at y = h - 3.
        int px = buf.getPixel(buf.width() / 2, buf.height() - 3);
        assertThat("bottom border alpha", ColorMath.alpha(px), is(ColorMath.alpha(RING_BOTTOM)));
        assertThat("bottom border RGB", px & 0xFFFFFF, is(RING_BOTTOM & 0xFFFFFF));
    }

    @Test
    @DisplayName("left edge interior row interpolates between gradient endpoints")
    void borderLeftEdgeInterpolates() {
        TextOptions opts = singleLineLore();
        ImageData image = new TextRenderer().render(opts);
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // Left edge spans x in [2, 4). Sample at x=2 on the middle row.
        int px = buf.getPixel(2, buf.height() / 2);
        int r = ColorMath.red(px);
        int b = ColorMath.blue(px);
        assertThat("red is at or above the bottom endpoint", r, is(greaterThan(ColorMath.red(RING_BOTTOM) - 1)));
        assertThat("red is at or below the top endpoint", r, is(lessThanOrEqualTo(ColorMath.red(RING_TOP))));
        assertThat("blue is at or above the bottom endpoint", b, is(greaterThan(ColorMath.blue(RING_BOTTOM) - 1)));
        assertThat("blue is at or below the top endpoint", b, is(lessThanOrEqualTo(ColorMath.blue(RING_TOP))));
        assertThat("border alpha preserved", ColorMath.alpha(px), is(ColorMath.alpha(RING_TOP)));
    }

    @Test
    @DisplayName("sprite background: corner notched, fill flush to the canvas edges")
    void notchedCornerAndFlushFill() {
        assumeSprites();
        PixelBuffer buf = renderSprite(loreBuilder());

        assertThat("notched top-left corner", ColorMath.alpha(buf.getPixel(0, 0)), is(0));
        assertThat("notched bottom-right corner", ColorMath.alpha(buf.getPixel(buf.width() - 1, buf.height() - 1)), is(0));
        assertThat("fill flush top edge", buf.getPixel(buf.width() / 2, 0), is(BACKGROUND_FILL));
        assertThat("fill flush left edge", buf.getPixel(0, buf.height() / 2), is(BACKGROUND_FILL));
    }

    @Test
    @DisplayName("sprite frame: ring 1 mcPx inset, open corners, gradient endpoints")
    void ringInsetAndOpenCorner() {
        assumeSprites();
        PixelBuffer buf = renderSprite(loreBuilder());

        assertThat("ring top gradient", ringTop(buf), is(RING_TOP));
        assertThat("ring bottom gradient", ringBottom(buf), is(RING_BOTTOM));
        // The ring corner texel is transparent in the sprite (open corner), so the background fill shows
        // through there - unlike PROCEDURAL, whose top/bottom strokes span the full width and paint the
        // ring corner purple.
        assertThat("open ring corner shows background fill", buf.getPixel(2, 2), is(BACKGROUND_FILL));
    }

    @Test
    @DisplayName("sprite padding 4 shrinks the canvas 4 output px per axis vs procedural padding 5")
    void canvasShrinksWithPadding() {
        assumeSprites();
        PixelBuffer procedural = render(loreBuilder().chromeStyle(ChromeStyle.PROCEDURAL).build());
        PixelBuffer spriteBuf = renderSprite(loreBuilder());

        // padding 5 -> 4 removes 1 mcPixel per side = 2 mcPixels per axis = 4 output px per axis.
        assertThat("width shrinks 4 px", spriteBuf.width(), is(procedural.width() - 4));
        assertThat("height shrinks 4 px", spriteBuf.height(), is(procedural.height() - 4));
    }

    @Test
    @DisplayName("default alphas leave the sprite bytes untouched (multiplier 1.0)")
    void multiplierNeutrality() {
        assumeSprites();
        PixelBuffer buf = renderSprite(loreBuilder());

        assertThat("background alpha untouched", ColorMath.alpha(buf.getPixel(buf.width() / 2, 0)),
            is(ColorMath.alpha(BACKGROUND_FILL)));
        assertThat("ring alpha untouched", ColorMath.alpha(ringTop(buf)), is(ColorMath.alpha(RING_TOP)));
    }

    @Test
    @DisplayName("lowered background alpha multiplies the sprite alpha proportionally")
    void alphaOverrideMultiplies() {
        assumeSprites();
        // backgroundAlpha 120 / vanilla 240 = 0.5 multiplier -> baked 0xF0 becomes 0x78.
        PixelBuffer buf = renderSprite(loreBuilder().backgroundAlpha(120));

        int px = buf.getPixel(buf.width() / 2, 0);
        assertThat("halved background alpha", ColorMath.alpha(px), is(0x78));
        assertThat("background rgb untouched", px & 0xFFFFFF, is(BACKGROUND_FILL & 0xFFFFFF));
    }

    @Test
    @DisplayName("SPRITE chrome on a renderer holding no context throws rather than silently falling back")
    void missingSpritesThrows() {
        TextOptions options = loreBuilder().chromeStyle(ChromeStyle.SPRITE).build();
        assertThrows(RenderException.class, () -> new TextRenderer().render(options));
    }

    @Test
    @DisplayName("styled-fixture tooltip renders end to end through the item component path")
    void styledFixtureRenders() throws IOException {
        assumeSprites();
        PixelBuffer vanillaBg = PixelBuffer.wrap(ImageIO.read(TOOLTIP_DIR.resolve("background.png").toFile()));
        PixelBuffer goldFrame = recolour(PixelBuffer.wrap(ImageIO.read(TOOLTIP_DIR.resolve("frame.png").toFile())), 0xFFAA00);

        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("fixture:gui/sprites/tooltip/gold_background", vanillaBg);
        tex.put("fixture:gui/sprites/tooltip/gold_frame", goldFrame);
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put("fixture:gui/sprites/tooltip/gold_background", BG_META);
        metas.put("fixture:gui/sprites/tooltip/gold_frame", FRAME_META);

        ItemContext item = itemWithStyle("fixture:gold");
        RendererContext context = stubContext(tex, metas);
        assertTrue(TooltipChrome.ChromeSprites.resolveForItem(context, item).isPresent(), "styled fixture sprites resolve");

        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().withSegments(ColorSegment.builder().withText("Styled Tooltip").build()).build());
        ImageData image = new TextRenderer(context).render(
            TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(lines)
                .chromeStyle(ChromeStyle.SPRITE)
                .tooltipStyle(TooltipChrome.ChromeSprites.styleOf(item))
                .build()
        );
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // The gold-recoloured ring drove the render: ring top carries alpha 0x50 with the gold rgb.
        assertThat("styled gold ring", ringTop(buf), is(0x50FFAA00));
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
