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
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link TooltipChrome.ChromeSprites}' static resolution surface under the
 * {@code minecraft:tooltip_style} item component: the component as read from an {@link ItemContext},
 * the style key's mapping onto a per-item sprite pair, the DROP with no fallback when those sprites
 * are missing, the default pair for an item declaring no style, the tick-0 flatten of an animated
 * sprite, and one styled fixture rendered end to end, which needs the sprite extraction on disk.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TooltipChromeStyleTest {

    private static final Path TOOLTIP_DIR = Path.of(
        "cache/asset-renderer/vanilla/26.1/assets/minecraft/textures/gui/sprites/tooltip");

    private static final MCMeta.GuiScaling BG_SCALING = new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(9, 9, 9, 9), false);
    private static final MCMeta.GuiScaling FRAME_SCALING = new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(10, 10, 10, 10), true);

    /**
     * A minimal renderer context that resolves only the textures + scalings + animations it was seeded
     * with.
     *
     * @param textures the texture id to pixels bindings this context can resolve
     * @param scalings the texture id to GUI scaling bindings this context can resolve
     * @param animations the texture id to animation sidecar bindings this context can resolve
     */
    private record StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta.GuiScaling> scalings,
                               Map<String, AnimationData> animations) implements RendererContext {
        private StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta.GuiScaling> scalings) {
            this(textures, scalings, Map.of());
        }
        @Override public Optional<Block> findBlock(String id) { return Optional.empty(); }
        @Override public Optional<ColorMap> findColorMap(ColorMap.Type type) { return Optional.empty(); }
        @Override public Optional<Entity> findEntity(String id) { return Optional.empty(); }
        @Override public Optional<Item> findItem(String id) { return Optional.empty(); }
        @Override public Optional<PixelBuffer> resolveTexture(String textureId) { return Optional.ofNullable(this.textures.get(textureId)); }
        @Override public Optional<MCMeta.GuiScaling> findGuiScaling(String textureId) { return Optional.ofNullable(this.scalings.get(textureId)); }
        @Override public Optional<AnimationData> findAnimation(String textureId) { return Optional.ofNullable(this.animations.get(textureId)); }
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
        Map<String, MCMeta.GuiScaling> scal = new HashMap<>();
        scal.put("hypixel_skyblock:gui/sprites/tooltip/rare_background", BG_SCALING);
        scal.put("hypixel_skyblock:gui/sprites/tooltip/rare_frame", FRAME_SCALING);

        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(tex, scal), itemWithStyle("hypixel_skyblock:rare"));

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
    @DisplayName("styled-fixture tooltip renders end to end through the item component path")
    void styledFixtureRenders() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(TOOLTIP_DIR), "vanilla 26.1 extraction not present");
        PixelBuffer vanillaBg = PixelBuffer.wrap(ImageIO.read(TOOLTIP_DIR.resolve("background.png").toFile()));
        PixelBuffer goldFrame = recolour(PixelBuffer.wrap(ImageIO.read(TOOLTIP_DIR.resolve("frame.png").toFile())), 0xFFAA00);

        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("fixture:gui/sprites/tooltip/gold_background", vanillaBg);
        tex.put("fixture:gui/sprites/tooltip/gold_frame", goldFrame);
        Map<String, MCMeta.GuiScaling> scal = new HashMap<>();
        scal.put("fixture:gui/sprites/tooltip/gold_background", BG_SCALING);
        scal.put("fixture:gui/sprites/tooltip/gold_frame", FRAME_SCALING);

        Optional<TooltipChrome.ChromeSprites> sprites = TooltipChrome.ChromeSprites.resolveForItem(
            new StubContext(tex, scal), itemWithStyle("fixture:gold"));
        assertTrue(sprites.isPresent(), "styled fixture sprites resolve");

        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().withSegments(ColorSegment.builder().withText("Styled Tooltip").build()).build());
        ImageData image = new TextRenderer().render(TextOptions.builder()
            .style(TextOptions.Style.LORE).lines(lines)
            .chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(sprites.get())).build());
        PixelBuffer buf = image.getFrames().getFirst().pixels();

        // The gold-recoloured ring drove the render: ring top carries alpha 0x50 with the gold rgb.
        assertThat("styled gold ring", buf.getPixel(buf.width() / 2, 2), is(0x50FFAA00));
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
