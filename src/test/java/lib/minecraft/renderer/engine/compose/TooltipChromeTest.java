package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip chrome's three arms in one place - {@link TooltipChrome.Vanilla#PROCEDURAL}'s fill and
 * gradient ring, {@link TooltipChrome.Vanilla#SPRITE}'s nine-slice pair, and the
 * {@code minecraft:tooltip_style} resolution surface that decides which pair a sprite render gets.
 * <p>
 * All three read the same vanilla chrome colours at the same ring rows, so the colours are declared
 * once here and every arm samples them - the arms were three classes repeating the literals, and a
 * recolour of one arm could leave the other two pinning the old value.
 * <p>
 * The cases needing the real sprites off the offline extraction are tagged per method rather than per
 * class, which keeps the resolution surface and the procedural ring in the fast suite.
 * <p>
 * {@link MinecraftFontsExtension} supplies the loaded Minecraft font atlas so the renderer can lay out
 * glyphs and size the tooltip canvas.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TooltipChromeTest {

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

    /** The background sprite's sidecar, carrying the nine-slice scaling its shipped one declares */
    private static final MCMeta BG_META = guiMeta(new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(9, 9, 9, 9), false));

    /** The frame sprite's sidecar, whose nine-slice scaling stretches its inner slices */
    private static final MCMeta FRAME_META = guiMeta(new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(10, 10, 10, 10), true));

    /**
     * Wraps a GUI scaling in the sidecar document the context answers with, every other section absent -
     * a sprite sidecar declaring {@code gui.scaling} alone, which is what vanilla ships.
     *
     * @param scaling the scaling section the sidecar declares
     * @return the sidecar carrying it
     */
    private static MCMeta guiMeta(MCMeta.GuiScaling scaling) {
        return new MCMeta(new ResourceId("minecraft", "tooltip"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(scaling), Optional.empty());
    }

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
        MCMeta meta = MCMeta.parse(Files.readString(TOOLTIP_DIR.resolve(mcmetaName)), new ResourceId("minecraft", "tooltip"));
        return meta.gui().orElseThrow();
    }

    /**
     * Assembles the chrome pair from the real background and frame sprites, failing the test on an
     * unreadable file rather than declaring a checked exception every case would have to thread
     * through. A missing extraction is the {@link #assumeSprites()} skip; a present but unreadable one
     * is a hard failure.
     *
     * @return the resolved chrome sprite pair the render path consumes
     */
    private static TooltipChrome.ChromeSprites realSprites() {
        try {
            return new TooltipChrome.ChromeSprites(
                new ResourceId("minecraft", "gui/sprites/tooltip/background"), sprite("background.png"), scaling("background.png.mcmeta"),
                new ResourceId("minecraft", "gui/sprites/tooltip/frame"), sprite("frame.png"), scaling("frame.png.mcmeta"));
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
     * Renders a tooltip and takes its only frame.
     *
     * @param options the text options to render
     * @return the rendered frame
     */
    private static PixelBuffer render(TextOptions options) {
        ImageData image = new TextRenderer().render(options);
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
    @DisplayName("procedural background fill pixel matches 0xF0100010")
    void backgroundFillMatchesVanilla() {
        PixelBuffer buf = render(singleLineLore());

        // Border occupies y in [2, 4) (1 mcPixel inset + 1 mcPixel stroke = 4 output pixels).
        // Sample at y=5 which is well inside the padding interior above the first glyph.
        int cx = buf.width() / 2;
        int cy = 5;
        int px = buf.getPixel(cx, cy);
        assertThat("alpha in padding", ColorMath.alpha(px), is(ColorMath.alpha(BACKGROUND_FILL)));
        assertThat("RGB in padding", px & 0xFFFFFF, is(BACKGROUND_FILL & 0xFFFFFF));
    }

    @Test
    @DisplayName("procedural border top row uses gradient top color (alpha=80, RGB=0x5000FF)")
    void borderTopMatchesVanillaGradientTop() {
        PixelBuffer buf = render(singleLineLore());

        // Border stroke is 1 mcPixel (2 output pixels) thick, inset 1 mcPixel from edge.
        // So the top stroke spans y in [2, 4). Sample at y=2.
        int px = ringTop(buf);
        assertThat("top border alpha", ColorMath.alpha(px), is(ColorMath.alpha(RING_TOP)));
        assertThat("top border RGB", px & 0xFFFFFF, is(RING_TOP & 0xFFFFFF));
    }

    @Test
    @DisplayName("procedural border bottom row uses gradient bottom color (alpha=80, RGB=0x28007F)")
    void borderBottomMatchesVanillaGradientBottom() {
        PixelBuffer buf = render(singleLineLore());

        // Bottom stroke spans y in [h-4, h-2). Sample at y = h - 3.
        int px = ringBottom(buf);
        assertThat("bottom border alpha", ColorMath.alpha(px), is(ColorMath.alpha(RING_BOTTOM)));
        assertThat("bottom border RGB", px & 0xFFFFFF, is(RING_BOTTOM & 0xFFFFFF));
    }

    @Test
    @DisplayName("procedural left edge interior row interpolates between gradient endpoints")
    void borderLeftEdgeInterpolates() {
        PixelBuffer buf = render(singleLineLore());

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
    @Tag("slow")
    @DisplayName("sprite background: corner notched, fill flush to the canvas edges")
    void notchedCornerAndFlushFill() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("notched top-left corner", ColorMath.alpha(buf.getPixel(0, 0)), is(0));
        assertThat("notched bottom-right corner", ColorMath.alpha(buf.getPixel(buf.width() - 1, buf.height() - 1)), is(0));
        assertThat("fill flush top edge", buf.getPixel(buf.width() / 2, 0), is(BACKGROUND_FILL));
        assertThat("fill flush left edge", buf.getPixel(0, buf.height() / 2), is(BACKGROUND_FILL));
    }

    @Test
    @Tag("slow")
    @DisplayName("sprite frame: ring 1 mcPx inset, open corners, gradient endpoints")
    void ringInsetAndOpenCorner() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("ring top gradient", ringTop(buf), is(RING_TOP));
        assertThat("ring bottom gradient", ringBottom(buf), is(RING_BOTTOM));
        // The ring corner texel is transparent in the sprite (open corner), so the background fill shows
        // through there - unlike PROCEDURAL, whose top/bottom strokes span the full width and paint the
        // ring corner purple.
        assertThat("open ring corner shows background fill", buf.getPixel(2, 2), is(BACKGROUND_FILL));
    }

    @Test
    @Tag("slow")
    @DisplayName("sprite padding 4 shrinks the canvas 4 output px per axis vs procedural padding 5")
    void canvasShrinksWithPadding() {
        assumeSprites();
        PixelBuffer procedural = render(loreBuilder().chrome(TooltipChrome.Vanilla.PROCEDURAL).build());
        PixelBuffer spriteBuf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        // padding 5 -> 4 removes 1 mcPixel per side = 2 mcPixels per axis = 4 output px per axis.
        assertThat("width shrinks 4 px", spriteBuf.width(), is(procedural.width() - 4));
        assertThat("height shrinks 4 px", spriteBuf.height(), is(procedural.height() - 4));
    }

    @Test
    @Tag("slow")
    @DisplayName("default alphas leave the sprite bytes untouched (multiplier 1.0)")
    void multiplierNeutrality() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("background alpha untouched", ColorMath.alpha(buf.getPixel(buf.width() / 2, 0)),
            is(ColorMath.alpha(BACKGROUND_FILL)));
        assertThat("ring alpha untouched", ColorMath.alpha(ringTop(buf)), is(ColorMath.alpha(RING_TOP)));
    }

    @Test
    @Tag("slow")
    @DisplayName("lowered background alpha multiplies the sprite alpha proportionally")
    void alphaOverrideMultiplies() {
        assumeSprites();
        // backgroundAlpha 120 / vanilla 240 = 0.5 multiplier -> baked 0xF0 becomes 0x78.
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).backgroundAlpha(120).build());

        int px = buf.getPixel(buf.width() / 2, 0);
        assertThat("halved background alpha", ColorMath.alpha(px), is(0x78));
        assertThat("background rgb untouched", px & 0xFFFFFF, is(BACKGROUND_FILL & 0xFFFFFF));
    }

    @Test
    @DisplayName("SPRITE chrome without resolved sprites throws rather than silently falling back")
    void missingSpritesThrows() {
        TextOptions options = loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).build();
        assertThrows(RenderException.class, () -> new TextRenderer().render(options));
    }

    /**
     * A minimal renderer context that resolves only the textures + sidecars + animations it was seeded
     * with.
     *
     * @param textures the texture id to pixels bindings this context can resolve
     * @param metas the texture id to sidecar bindings this context can resolve
     * @param animations the texture id to animation sidecar bindings this context can resolve
     */
    private record StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta> metas,
                               Map<String, AnimationData> animations) implements RendererContext {
        private StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta> metas) {
            this(textures, metas, Map.of());
        }
        @Override public @NotNull Optional<Block> findBlock(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<ColorMap> findColorMap(@NotNull ColorMap.Type type) { return Optional.empty(); }
        @Override public @NotNull Optional<Entity> findEntity(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<Item> findItem(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NonNull String textureId) { return Optional.ofNullable(this.textures.get(textureId)); }
        @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) { return Optional.ofNullable(this.metas.get(textureId)); }
        @Override public @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) { return Optional.ofNullable(this.animations.get(textureId)); }
    }

    /**
     * Builds a 20x20 buffer of one colour, standing in for a sprite the resolution surface only has to
     * find.
     *
     * @param argb the fill colour
     * @return the filled buffer
     */
    private static PixelBuffer solid(int argb) {
        int[] px = new int[20 * 20];
        Arrays.fill(px, argb);
        return PixelBuffer.of(px, 20, 20);
    }

    /**
     * Builds an item context carrying a {@code minecraft:tooltip_style} component.
     *
     * @param style the style key the component names
     * @return the item context the resolution surface reads
     */
    private static ItemContext itemWithStyle(String style) {
        CompoundTag components = new CompoundTag();
        components.put("minecraft:tooltip_style", new StringTag(style));
        CompoundTag root = new CompoundTag();
        root.put("components", components);
        return ItemContext.builder().itemId("minecraft:diamond_sword").nbt(root).build();
    }

    @Test
    @DisplayName("styleOf reads the minecraft:tooltip_style component as a resource id")
    void styleOfReadsComponent() {
        Optional<ResourceId> style = TooltipChrome.ChromeSprites.styleOf(itemWithStyle("hypixel_skyblock:rare"));
        assertThat(style, is(Optional.of(new ResourceId("hypixel_skyblock", "rare"))));
    }

    @Test
    @DisplayName("styleOf is empty for an item carrying no tooltip_style")
    void styleOfEmptyWithoutComponent() {
        assertThat(TooltipChrome.ChromeSprites.styleOf(ItemContext.ofItem("minecraft:stone")), is(Optional.empty()));
    }

    @Test
    @DisplayName("resolveForItem maps the style key onto the per-item sprite pair")
    void resolveForItemStyled() {
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("hypixel_skyblock:gui/sprites/tooltip/rare_background", solid(0xFF112233));
        tex.put("hypixel_skyblock:gui/sprites/tooltip/rare_frame", solid(0xFF445566));
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put("hypixel_skyblock:gui/sprites/tooltip/rare_background", BG_META);
        metas.put("hypixel_skyblock:gui/sprites/tooltip/rare_frame", FRAME_META);

        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(tex, metas), itemWithStyle("hypixel_skyblock:rare"));

        assertTrue(resolved.isPresent(), "styled pair resolves");
        assertThat(resolved.get().backgroundId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/rare_background")));
        assertThat(resolved.get().frameId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/rare_frame")));
    }

    @Test
    @DisplayName("resolveForItem DROPs (empty) when the styled sprites are missing")
    void resolveForItemMissingStyleDrops() {
        // stub supplies nothing -> the styled pair is unresolved -> DROP + diagnostic, no fallback.
        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(new HashMap<>(), new HashMap<>()), itemWithStyle("hypixel_skyblock:missing"));
        assertThat(resolved, is(Optional.empty()));
    }

    @Test
    @DisplayName("resolveForItem falls to the default pair for an item with no style")
    void resolveForItemNoStyleDefaults() {
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", solid(0xFF112233));
        tex.put("minecraft:gui/sprites/tooltip/frame", solid(0xFF445566));

        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(tex, new HashMap<>()), ItemContext.ofItem("minecraft:stone"));

        assertTrue(resolved.isPresent(), "default pair resolves");
        assertThat(resolved.get().backgroundId(), is(new ResourceId("minecraft", "gui/sprites/tooltip/background")));
    }

    @Test
    @DisplayName("resolve flattens an animated chrome sprite to its tick-0 frame")
    void resolveFlattensAnimatedSprite() {
        // A 4x8 background flipbook: top 4x4 frame red, bottom 4x4 frame blue, with a 2-frame sidecar.
        // resolve must pin to frame 0 (top 4x4 red), not hand NineSliceKit the whole strip.
        int[] px = new int[4 * 8];
        for (int i = 0; i < 4 * 4; i++) px[i] = 0xFFFF0000;
        for (int i = 4 * 4; i < 4 * 8; i++) px[i] = 0xFF0000FF;
        PixelBuffer strip = PixelBuffer.of(px, 4, 8);
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", strip);
        tex.put("minecraft:gui/sprites/tooltip/frame", solid(0xFF445566));
        Map<String, AnimationData> anims = new HashMap<>();
        anims.put("minecraft:gui/sprites/tooltip/background", new AnimationData(1, false, Concurrent.newList(), -1, -1));

        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolve(
            new StubContext(tex, new HashMap<>(), anims), null);

        assertTrue(resolved.isPresent(), "pair resolves");
        PixelBuffer bg = resolved.get().background();
        assertThat("flattened to one frame height", bg.height(), is(4));
        assertThat("frame 0 pixel is red", bg.getPixel(0, 0), is(0xFFFF0000));
    }

    @Test
    @Tag("slow")
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

        Optional<TooltipChrome.ChromeSprites> sprites = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(tex, metas), itemWithStyle("fixture:gold"));
        assertTrue(sprites.isPresent(), "styled fixture sprites resolve");

        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().withSegments(ColorSegment.builder().withText("Styled Tooltip").build()).build());
        ImageData image = new TextRenderer().render(
            TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(lines)
                .chrome(TooltipChrome.Vanilla.SPRITE)
                .chromeSprites(sprites)
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
