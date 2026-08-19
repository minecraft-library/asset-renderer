package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.BannerLayer;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.DyeColor;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.support.StubRendererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link BannerKit#composite2D} banner / shield compositing against in-memory fixture
 * textures, plus {@link BannerKit.Variant#textureFor} path building. A
 * {@link StubRendererContext} serves fixture textures by id - and answers empty for every id absent
 * from the fixture map - so the tests assert on output pixels directly: base-dye fill with no
 * layers, a single dye-tinted pattern mask blitted over the base, and the
 * {@link BannerKit.Variant#SHIELD_ITEM} variant pulling the {@code entity/shield/} atlas rather
 * than {@code entity/banner/}.
 */
class BannerKitTest {

    @Test
    @DisplayName("composite with zero layers returns a canvas filled with the base dye")
    void baseDyeOnly() {
        PixelBuffer bannerBase = solid(4, 4, 0xFFFFFFFF);
        RendererContext engine = fixtures(Map.of(
            "minecraft:entity/banner_base", bannerBase
        ));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.RED.argb(), Concurrent.newList(),
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
        RendererContext engine = fixtures(Map.of(
            "minecraft:entity/banner_base", base,
            "minecraft:entity/banner/creeper", mask
        ));

        BannerPattern pattern = new BannerPattern(
            "minecraft:creeper", "minecraft:creeper", "block.minecraft.banner.creeper"
        );
        ConcurrentList<BannerLayer> layers = Concurrent.newList();
        layers.add(new BannerLayer(pattern, DyeColor.Vanilla.BLUE));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.WHITE.argb(), layers,
            BannerKit.Variant.BANNER_ITEM);

        // Pixel (0, 0) is the one opaque mask texel, so the blue layer blitted over it; assert it
        // stayed opaque (the exact tint blend is BlendMode.NORMAL's business). The other three
        // texels were transparent in the mask, so they kept the untouched white base.
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
        RendererContext engine = fixtures(Map.of(
            "minecraft:entity/banner_base", base,
            // Only the shield variant is registered - the banner variant would miss.
            "minecraft:entity/shield/creeper", shieldMask
        ));

        BannerPattern pattern = new BannerPattern(
            "minecraft:creeper", "minecraft:creeper", ""
        );
        ConcurrentList<BannerLayer> layers = Concurrent.newList();
        layers.add(new BannerLayer(pattern, DyeColor.Vanilla.BLACK));

        PixelBuffer canvas = BannerKit.composite2D(engine, DyeColor.Vanilla.WHITE.argb(), layers,
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

    @Test
    @DisplayName("textureFor delegates to the shared BannerPattern builder for namespaced and bare asset ids")
    void variantTexturePathMatchesSharedBuilder() {
        // The banner-kit variant path and the BannerPattern mask path splice the atlas sub-path with
        // the same rule: a namespaced asset id keeps its namespace, a bare id defaults to minecraft.
        for (String assetId : new String[] {"minecraft:creeper", "creeper", "custom:mob"}) {
            assertThat(
                BannerKit.Variant.BANNER_ITEM.textureFor(assetId),
                is(equalTo(BannerPattern.atlasTexture(assetId, "entity/banner")))
            );
            assertThat(
                BannerKit.Variant.SHIELD_ITEM.textureFor(assetId),
                is(equalTo(BannerPattern.atlasTexture(assetId, "entity/shield")))
            );
        }

        assertThat(BannerPattern.atlasTexture("creeper", "entity/banner"), is(equalTo("minecraft:entity/banner/creeper")));
        assertThat(BannerPattern.atlasTexture("custom:mob", "entity/shield"), is(equalTo("custom:entity/shield/mob")));
    }

    /** Builds a {@code w}-by-{@code h} buffer filled with a single ARGB value. */
    private static PixelBuffer solid(int w, int h, int argb) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = argb;
        return PixelBuffer.of(pixels, w, h);
    }

    /**
     * Serves the given fixture textures by id, which is all {@link BannerKit#composite2D} needs.
     */
    private static StubRendererContext fixtures(Map<String, PixelBuffer> textures) {
        return StubRendererContext.builder().texturesById(textures).build();
    }

}
