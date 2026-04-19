package lib.minecraft.renderer.asset.binding;

import org.jetbrains.annotations.NotNull;

/**
 * A banner / shield pattern descriptor parsed from the vanilla
 * {@code data/minecraft/banner_pattern/*.json} registry. Each pattern carries a grayscale mask
 * texture shipped under {@code entity/banner/<assetId>} and {@code entity/shield/<assetId>};
 * banners and shields share the same pattern registry and asset ids, only the texture atlas
 * path differs.
 * <p>
 * Banner composition is a stack of {@link BannerLayer layers}, each binding this pattern to a
 * {@link DyeColor}; the renderer paints a dye-coloured base and blits each pattern's grayscale
 * mask on top tinted with the layer's colour.
 *
 * @param id the pattern registry id (e.g. {@code "minecraft:base"}, {@code "minecraft:creeper"})
 * @param assetId the {@code asset_id} from the pattern JSON - drives both the banner texture
 *     path {@code entity/banner/<assetId>} and the shield texture path
 *     {@code entity/shield/<assetId>}. Typically the same as {@link #id()} but kept separate to
 *     match the JSON schema.
 * @param translationKey the translation key for the pattern's display name
 *     (e.g. {@code "block.minecraft.banner.creeper"})
 */
public record BannerPattern(
    @NotNull String id,
    @NotNull String assetId,
    @NotNull String translationKey
) {

    /**
     * The namespaced texture id for this pattern's banner mask, under
     * {@code entity/banner/<assetId>}.
     *
     * @return the banner texture id
     */
    public @NotNull String bannerTexture() {
        return pathForAtlas("entity/banner");
    }

    /**
     * The namespaced texture id for this pattern's shield mask, under
     * {@code entity/shield/<assetId>}.
     *
     * @return the shield texture id
     */
    public @NotNull String shieldTexture() {
        return pathForAtlas("entity/shield");
    }

    private @NotNull String pathForAtlas(@NotNull String atlasPath) {
        // assetId is like "minecraft:creeper" - split namespace:name, prepend atlas path.
        int colon = this.assetId.indexOf(':');
        if (colon < 0) return "minecraft:" + atlasPath + "/" + this.assetId;
        return this.assetId.substring(0, colon) + ":" + atlasPath + "/" + this.assetId.substring(colon + 1);
    }

}
