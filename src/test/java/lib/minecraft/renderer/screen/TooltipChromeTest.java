package lib.minecraft.renderer.screen;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code minecraft:tooltip_style} resolution surface that decides which sprite pair
 * {@link TooltipChrome.Vanilla#SPRITE} is drawn with - reading the style off an item's component,
 * mapping a style onto its background and frame pair, falling to the default pair for an item with no
 * style, dropping a styled pair whose sprites are missing, and flattening an animated sprite to its
 * tick-0 frame.
 * <p>
 * Nothing here renders: each case reads an item's component or resolves against a stub context seeded
 * with stand-in sprites, so the class needs neither the font atlas nor the offline extraction.
 */
class TooltipChromeTest {

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

    /**
     * A minimal renderer context that resolves only the textures + sidecars + animations it was seeded
     * with.
     *
     * @param textures the texture id to pixels bindings this context can resolve
     * @param metas the texture id to sidecar bindings this context can resolve
     * @param animations the texture id to animation sidecar bindings this context can resolve
     */
    private record StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta> metas,
                               Map<String, MCMeta.Animation> animations) implements RendererContext {
        private StubContext(Map<String, PixelBuffer> textures, Map<String, MCMeta> metas) {
            this(textures, metas, Map.of());
        }
        @Override public @NotNull Optional<Block> findBlock(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<ColorMap> findColorMap(@NotNull TintSource target) { return Optional.empty(); }
        @Override public @NotNull Optional<Entity> findEntity(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<Item> findItem(@NotNull String id) { return Optional.empty(); }
        @Override public @NotNull Optional<PixelBuffer> resolveTexture(@NonNull String textureId) { return Optional.ofNullable(this.textures.get(textureId)); }
        @Override public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) { return Optional.ofNullable(this.metas.get(textureId)); }
        @Override public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) { return Optional.ofNullable(this.animations.get(textureId)); }
        @Override public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) { return Flipbook.of(findAnimation(textureId), () -> resolveTexture(textureId)); }
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
        Map<String, MCMeta.Animation> anims = new HashMap<>();
        anims.put("minecraft:gui/sprites/tooltip/background", new MCMeta.Animation(1, false, -1, -1, Concurrent.newList()));

        Optional<TooltipChrome.ChromeSprites> resolved = TooltipChrome.ChromeSprites.resolve(
            new StubContext(tex, new HashMap<>(), anims), Optional.empty());

        assertTrue(resolved.isPresent(), "pair resolves");
        PixelBuffer bg = resolved.get().background();
        assertThat("flattened to one frame height", bg.height(), is(4));
        assertThat("frame 0 pixel is red", bg.getPixel(0, 0), is(0xFFFF0000));
    }

}
