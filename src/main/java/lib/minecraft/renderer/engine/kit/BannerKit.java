package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.BannerLayer;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Composites banner and shield pattern stacks into a single {@link PixelBuffer} at render time.
 * <p>
 * The algorithm matches vanilla's {@code net.minecraft.client.renderer.BannerRenderer} from MC
 * 26.1: start with the base dye colour painted onto a blank canvas of the pattern-texture's
 * dimensions, then for each {@link BannerLayer layer} load the grayscale pattern texture and
 * blit it tinted with the layer's dye colour using
 * {@link BlendMode#NORMAL straight-alpha compositing}. The grayscale's red channel drives the
 * tint intensity and the alpha channel masks which pixels receive colour.
 * <p>
 * Banners and shields share the same pattern registry but use distinct texture atlases under
 * {@code entity/banner/} and {@code entity/shield/}; the {@link Variant} selects which texture
 * path to load for each layer.
 */
@UtilityClass
public class BannerKit {

    /**
     * The banner-background texture id. Painted under every layer with the base dye tint.
     */
    private static final @NotNull String BANNER_BASE_TEXTURE_ID = "minecraft:entity/banner_base";

    /**
     * Composites a banner or shield in its GUI-item orientation: base dye background, then each
     * pattern layer blitted as a dye-tinted grayscale mask.
     *
     * @param context the texture context for resolving pattern + base textures
     * @param baseDyeArgb the base dye colour (the field of the banner / shield) as packed ARGB
     * @param layers the ordered list of pattern layers to composite on top
     * @param variant the texture atlas variant to pull pattern textures from
     * @return a newly-created buffer containing the composite; dimensions match the base texture
     */
    public static @NotNull PixelBuffer composite2D(
        @NotNull RendererContext context,
        int baseDyeArgb,
        @NotNull ConcurrentList<BannerLayer> layers,
        @NotNull Variant variant
    ) {
        // The banner_base texture in the vanilla atlas is 64x64; the item-icon region we
        // actually want occupies the top-left portion. We composite at full texture size and
        // let downstream scaling handle the final icon crop / scale.
        Optional<PixelBuffer> baseTexture = context.resolveTexture(BANNER_BASE_TEXTURE_ID);
        int width = baseTexture.map(PixelBuffer::width).orElse(64);
        int height = baseTexture.map(PixelBuffer::height).orElse(64);

        PixelBuffer canvas = PixelBuffer.create(width, height);
        canvas.fill(baseDyeArgb);

        for (BannerLayer layer : layers) {
            String textureId = variant.textureFor(layer.pattern().assetId());
            Optional<PixelBuffer> mask = context.resolveTexture(textureId);
            if (mask.isEmpty()) continue;
            canvas.blitTinted(mask.get(), 0, 0, layer.color().argb(), BlendMode.NORMAL);
        }

        return canvas;
    }

    /**
     * The texture atlas variant to pull pattern masks from. Banners use
     * {@code entity/banner/<assetId>}; shields use {@code entity/shield/<assetId>}.
     */
    @RequiredArgsConstructor
    public enum Variant {

        /**
         * The 2D banner item icon (GUI slot sprite).
         */
        BANNER_ITEM("entity/banner"),

        /**
         * The 2D shield item icon (GUI slot sprite).
         */
        SHIELD_ITEM("entity/shield"),

        /**
         * The 3D banner block / held variant texture. Same path as the item icon variant.
         */
        BANNER_BLOCK_3D("entity/banner"),

        /**
         * The 3D shield held variant texture. Same path as the item icon variant.
         */
        SHIELD_BLOCK_3D("entity/shield");

        private final @NotNull String atlasPath;

        /**
         * Builds the namespaced texture id for a pattern's mask under this variant's atlas.
         *
         * @param assetId the pattern asset id from {@link BannerPattern#assetId()}
         * @return the namespaced texture id
         */
        public @NotNull String textureFor(@NotNull String assetId) {
            return BannerPattern.atlasTexture(assetId, this.atlasPath);
        }

    }

}
