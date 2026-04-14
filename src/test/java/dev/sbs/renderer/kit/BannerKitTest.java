package dev.sbs.renderer.kit;

import dev.sbs.renderer.asset.binding.BannerLayer;
import dev.sbs.renderer.asset.binding.BannerPattern;
import dev.sbs.renderer.asset.binding.DyeColor;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link BannerKit#composite2D} against in-memory fixture textures. The kit is the
 * single call-site that drives banner / shield rendering, so we stub the context and assert on
 * output pixels directly.
 */
class BannerKitTest {

    @Test
    @DisplayName("composite with zero layers returns a canvas filled with the base dye")
    void baseDyeOnly() {
        PixelBuffer bannerBase = solid(4, 4, 0xFFFFFFFF);
        TextureEngine engine = new TextureEngine(new StubContext(Map.of(
            "minecraft:entity/banner_base", bannerBase
        )));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.RED, Concurrent.newList(),
            BannerKit.Variant.BANNER_ITEM);

        assertThat(canvas.width(), is(4));
        assertThat(canvas.height(), is(4));
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat("pixel " + x + "," + y, canvas.getPixel(x, y), is(equalTo(DyeColor.Vanilla.RED.argb())));
            }
        }
    }

    @Test
    @DisplayName("single pattern layer blits its mask over the base dye")
    void singleLayer() {
        // 2x2 white banner base + a 2x2 mask that's opaque white only at (0, 0).
        PixelBuffer base = solid(2, 2, 0xFFFFFFFF);
        PixelBuffer mask = PixelBuffer.of(new int[]{
            0xFFFFFFFF, 0x00000000,
            0x00000000, 0x00000000
        }, 2, 2);
        TextureEngine engine = new TextureEngine(new StubContext(Map.of(
            "minecraft:entity/banner_base", base,
            "minecraft:entity/banner/creeper", mask
        )));

        BannerPattern pattern = new BannerPattern(
            "minecraft:creeper", "minecraft:creeper", "block.minecraft.banner.creeper"
        );
        ConcurrentList<BannerLayer> layers = Concurrent.newList();
        layers.add(new BannerLayer(pattern, DyeColor.Vanilla.BLUE));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.WHITE, layers,
            BannerKit.Variant.BANNER_ITEM);

        // Pixel (0, 0) got the blue layer blitted on top; the other three kept the white base.
        assertThat((canvas.getPixel(0, 0) >>> 24) & 0xFF, is(0xFF));
        assertThat(canvas.getPixel(1, 0), is(equalTo(DyeColor.Vanilla.WHITE.argb())));
        assertThat(canvas.getPixel(0, 1), is(equalTo(DyeColor.Vanilla.WHITE.argb())));
        assertThat(canvas.getPixel(1, 1), is(equalTo(DyeColor.Vanilla.WHITE.argb())));
    }

    @Test
    @DisplayName("SHIELD_ITEM variant pulls the shield atlas path")
    void shieldVariantUsesShieldAtlas() {
        PixelBuffer base = solid(2, 2, 0xFFFFFFFF);
        PixelBuffer shieldMask = solid(2, 2, 0xFFFFFFFF);
        TextureEngine engine = new TextureEngine(new StubContext(Map.of(
            "minecraft:entity/banner_base", base,
            // Only the shield variant is registered - the banner variant would miss.
            "minecraft:entity/shield/creeper", shieldMask
        )));

        BannerPattern pattern = new BannerPattern(
            "minecraft:creeper", "minecraft:creeper", ""
        );
        ConcurrentList<BannerLayer> layers = Concurrent.newList();
        layers.add(new BannerLayer(pattern, DyeColor.Vanilla.BLACK));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.WHITE, layers,
            BannerKit.Variant.SHIELD_ITEM);

        // The shield mask is fully opaque white and the layer colour is black, so every
        // output pixel should end up black.
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertThat("pixel " + x + "," + y, canvas.getPixel(x, y), is(equalTo(DyeColor.Vanilla.BLACK.argb())));
            }
        }
    }

    @Test
    @DisplayName("Variant.textureFor builds the expected namespaced path")
    void variantTexturePath() {
        assertThat(
            BannerKit.Variant.BANNER_ITEM.textureFor("minecraft:creeper"),
            is(equalTo("minecraft:entity/banner/creeper"))
        );
        assertThat(
            BannerKit.Variant.SHIELD_ITEM.textureFor("minecraft:flow"),
            is(equalTo("minecraft:entity/shield/flow"))
        );
    }

    private static PixelBuffer solid(int w, int h, int argb) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = argb;
        return PixelBuffer.of(pixels, w, h);
    }

    /** A lightweight RendererContext stub backed by an in-memory texture map. */
    private static final class StubContext implements RendererContext {

        private final @NotNull Map<String, PixelBuffer> textures;

        StubContext(@NotNull Map<String, PixelBuffer> textures) {
            this.textures = new HashMap<>(textures);
        }

        @Override
        public @NotNull ConcurrentList<dev.sbs.renderer.asset.pack.TexturePack> activePacks() {
            return Concurrent.newList();
        }

        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            return Optional.ofNullable(this.textures.get(textureId));
        }

        @Override
        public @NotNull Optional<dev.sbs.renderer.asset.pack.ColorMap> colorMap(
            dev.sbs.renderer.asset.pack.ColorMap.@NotNull Type type
        ) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<dev.sbs.renderer.asset.Block> findBlock(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<dev.sbs.renderer.asset.Item> findItem(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<dev.sbs.renderer.asset.Entity> findEntity(@NotNull String id) {
            return Optional.empty();
        }

    }

}
