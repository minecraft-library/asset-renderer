package lib.minecraft.renderer.bake.gui;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.SubstitutionCollector;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static lib.minecraft.renderer.fixture.TooltipFixtures.BG_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.FRAME_META;
import static lib.minecraft.renderer.fixture.TooltipFixtures.itemWithStyle;
import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * The {@code minecraft:tooltip_style} resolution surface that decides which sprite pair
 * {@link TooltipChrome.Vanilla#SPRITE} is drawn with - reading the style off an item's component,
 * mapping a style onto its background and frame pair, falling to the default pair for an item with no
 * style, drawing the missing texture for each sprite of a style no pack ships, or one that cannot be
 * read, or one whose {@code gui} section the client's codec refuses, stretched, and flattening an
 * animated sprite to its tick-0 frame - and the code-drawn box {@link TooltipChrome.Vanilla#PROCEDURAL}
 * draws round a text block too narrow for its ring.
 * <p>
 * Nothing here renders text: each case reads an item's component, resolves against a stub context
 * seeded with stand-in sprites, or draws the code-drawn box alone through the chrome's own calls, so the
 * class needs neither the font atlas nor the offline extraction.
 */
class TooltipChromeTest {

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
        return PixelBuffer.ofStraight(px, 20, 20);
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

        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolveForItem(
            stubContext(tex, metas), itemWithStyle("hypixel_skyblock:rare"));

        assertThat(resolved.backgroundId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/rare_background")));
        assertThat(resolved.frameId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/rare_frame")));
    }

    @Test
    @DisplayName("resolveForItem answers the missing texture, stretched, for each sprite of a style no pack ships")
    void resolveForItemMissingStyleIsTheMissingTexture() {
        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolveForItem(
            stubContext(new HashMap<>(), new HashMap<>()), itemWithStyle("hypixel_skyblock:missing"));

        assertThat(resolved.backgroundId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/missing_background")));
        assertThat(resolved.frameId(), is(new ResourceId("hypixel_skyblock", "gui/sprites/tooltip/missing_frame")));
        assertThat("background is the missing texture", MissingSprite.isSprite(resolved.background()), is(true));
        assertThat("frame is the missing texture", MissingSprite.isSprite(resolved.frame()), is(true));
        assertThat("background stretches", resolved.backgroundScaling().type(), is(MCMeta.GuiScaling.Type.STRETCH));
        assertThat("frame stretches", resolved.frameScaling().type(), is(MCMeta.GuiScaling.Type.STRETCH));
    }

    @Test
    @DisplayName("a sprite a pack ships that cannot be read is the missing texture, stretched whatever its sidecar declares, and is reported unreadable")
    void anUnreadableSpriteIsTheMissingTextureStretchedOverItsSidecar() {
        String backgroundId = "minecraft:gui/sprites/tooltip/background";
        String frameId = "minecraft:gui/sprites/tooltip/frame";
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put(frameId, solid(0xFF445566));
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put(backgroundId, BG_META);
        metas.put(frameId, FRAME_META);
        RendererContext stub = stubContext(tex, metas);
        // A file a pack ships that cannot be read answers no pixels and keeps the sidecar it ships.
        RendererContext unreadable = new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return stub;
            }

            @Override
            public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
                return textureId.equals(backgroundId) ? Possible.empty() : stub.resolveTexture(textureId);
            }
        };
        SubstitutionCollector collector = new SubstitutionCollector();

        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolve(unreadable.collecting(collector), Optional.empty());

        assertThat("the shipped sidecar nine-slices", BG_META.gui().orElseThrow().type(), is(MCMeta.GuiScaling.Type.NINE_SLICE));
        assertThat("background is the missing texture", MissingSprite.isSprite(resolved.background()), is(true));
        assertThat("background stretches over its sidecar", resolved.backgroundScaling().type(), is(MCMeta.GuiScaling.Type.STRETCH));
        assertThat("frame is the pack's", MissingSprite.isSprite(resolved.frame()), is(false));
        assertThat("frame takes its sidecar's scaling", resolved.frameScaling(), is(FRAME_META.gui().orElseThrow()));
        assertThat("the background reported unreadable", collector.snapshot(),
            contains(Substitution.texture(backgroundId, Possible.State.EMPTY)));
    }

    @Test
    @DisplayName("a sprite whose gui section the client's codec refuses is the missing texture, stretched, and is reported unreadable")
    void aSpriteWhoseGuiSectionTheClientRefusesIsTheMissingTextureStretched() {
        String backgroundId = "minecraft:gui/sprites/tooltip/background";
        String frameId = "minecraft:gui/sprites/tooltip/frame";
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put(backgroundId, solid(0xFF112233));
        tex.put(frameId, solid(0xFF445566));
        // A border of 5 a side leaves a 10-wide nine-slice no centre slice, which the client refuses.
        MCMeta refused = MCMetaParser.parse(
            "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":5}}}",
            ResourceId.parse(backgroundId));
        Map<String, MCMeta> metas = new HashMap<>();
        metas.put(backgroundId, refused);
        metas.put(frameId, FRAME_META);
        SubstitutionCollector collector = new SubstitutionCollector();

        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolve(
            stubContext(tex, metas).collecting(collector), Optional.empty());

        assertThat("the parsed section is refused", refused.gui().getState(), is(Possible.State.EMPTY));
        assertThat("background is the missing texture", MissingSprite.isSprite(resolved.background()), is(true));
        assertThat("background stretches", resolved.backgroundScaling(), is(MCMeta.GuiScaling.DEFAULT));
        assertThat("frame is the pack's", MissingSprite.isSprite(resolved.frame()), is(false));
        assertThat("frame takes its sidecar's scaling", resolved.frameScaling(), is(FRAME_META.gui().orElseThrow()));
        assertThat("the background reported unreadable", collector.snapshot(),
            contains(Substitution.texture(backgroundId, Possible.State.EMPTY)));

        // A sidecar carrying no gui section refuses nothing: the sprite is the pack's, stretched.
        metas.put(frameId, MCMetaParser.parse("{\"texture\":{\"blur\":true}}", ResourceId.parse(frameId)));
        TooltipChrome.ChromeSprites sectionless = TooltipChrome.ChromeSprites.resolve(stubContext(tex, metas), Optional.empty());
        assertThat("a frame whose sidecar carries no gui section is the pack's", MissingSprite.isSprite(sectionless.frame()), is(false));
        assertThat("and stretches", sectionless.frameScaling(), is(MCMeta.GuiScaling.DEFAULT));
    }

    @Test
    @DisplayName("resolveForItem falls to the default pair for an item with no style")
    void resolveForItemNoStyleDefaults() {
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", solid(0xFF112233));
        tex.put("minecraft:gui/sprites/tooltip/frame", solid(0xFF445566));

        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolveForItem(
            stubContext(tex, new HashMap<>()), ItemContext.ofItem("minecraft:stone"));

        assertThat(resolved.backgroundId(), is(new ResourceId("minecraft", "gui/sprites/tooltip/background")));
    }

    @Test
    @DisplayName("resolve flattens an animated chrome sprite to its tick-0 frame")
    void resolveFlattensAnimatedSprite() {
        // A 4x8 background flipbook: top 4x4 frame red, bottom 4x4 frame blue, with a 2-frame sidecar.
        // resolve must pin to frame 0 (top 4x4 red), not hand NineSliceKit the whole strip.
        int[] px = new int[4 * 8];
        for (int i = 0; i < 4 * 4; i++) px[i] = 0xFFFF0000;
        for (int i = 4 * 4; i < 4 * 8; i++) px[i] = 0xFF0000FF;
        PixelBuffer strip = PixelBuffer.ofStraight(px, 4, 8);
        Map<String, PixelBuffer> tex = new HashMap<>();
        tex.put("minecraft:gui/sprites/tooltip/background", strip);
        tex.put("minecraft:gui/sprites/tooltip/frame", solid(0xFF445566));
        Map<String, MCMeta.Animation> anims = new HashMap<>();
        anims.put("minecraft:gui/sprites/tooltip/background", new MCMeta.Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty()));

        TooltipChrome.ChromeSprites resolved = TooltipChrome.ChromeSprites.resolve(
            stubContext(tex, new HashMap<>(), anims), Optional.empty());

        PixelBuffer bg = resolved.background();
        assertThat("flattened to one frame height", bg.height(), is(4));
        assertThat("frame 0 pixel is red", bg.getPixel(0, 0), is(0xFFFF0000));
    }

    @Test
    @DisplayName("a code-drawn box narrower than its ring draws none of the ring and nothing off the box - its fill's middle band alone")
    void aCodeDrawnBoxNarrowerThanItsRingDrawsNoRing() {
        // A text block 1 Minecraft pixel wide at padding 0 is a box 1 wide: its side lines have no room in it.
        TextOptions options = TextOptions.builder().padding(0).build();
        TooltipChrome chrome = TooltipChrome.of(ChromeStyle.PROCEDURAL);
        TooltipChrome.ChromeBox box = chrome.box(1, 8, Optional.empty(), options);
        LayerStack<ImageLayer> stack = new LayerStack<>();
        chrome.contribute(stack, box, Optional.empty(), options);
        PixelBuffer canvas = PixelBuffer.canvas(
            Math.round(box.width() * MinecraftFont.MC_PIXEL_SCALE), Math.round(box.height() * MinecraftFont.MC_PIXEL_SCALE));
        Layers.foldInto(stack, UnaryOperator.identity(), canvas);

        assertThat("canvas size", List.of(canvas.width(), canvas.height()), is(List.of(2, 16)));
        // The box's top and bottom Minecraft pixel rows are its corners, left clear; the rows between are
        // the fill blended onto the clear canvas.
        List<String> wrong = new ArrayList<>();
        for (int y = 0; y < canvas.height(); y++) {
            int expected = y >= 2 && y <= 13 ? 0xF00F000F : ColorMath.TRANSPARENT;
            for (int x = 0; x < canvas.width(); x++)
                if (canvas.getPixel(x, y) != expected)
                    wrong.add(String.format("(%d, %d) holds %08X where %08X", x, y, canvas.getPixel(x, y), expected));
        }
        assertThat("pixels other than the fill's middle band and the clear corner rows", wrong, is(empty()));
    }

}
