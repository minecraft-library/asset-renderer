package lib.minecraft.renderer.asset;

import org.jetbrains.annotations.NotNull;

/**
 * A banner / shield pattern descriptor parsed from the vanilla
 * {@code data/minecraft/banner_pattern/*.json} registry. Each pattern carries a grayscale mask
 * texture shipped under {@code entity/banner/<assetId>} and {@code entity/shield/<assetId>};
 * banners and shields share the same pattern registry and asset ids, only the texture atlas
 * path differs.
 * <p>
 * Banner composition is a stack of layers, each binding this pattern to a
 * {@code DyeColor}; the renderer paints a dye-coloured base and blits each pattern's grayscale
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
        return atlasTexture(this.assetId, "entity/banner");
    }

    /**
     * The namespaced texture id for this pattern's shield mask, under
     * {@code entity/shield/<assetId>}.
     *
     * @return the shield texture id
     */
    public @NotNull String shieldTexture() {
        return atlasTexture(this.assetId, "entity/shield");
    }

    /**
     * Builds a namespaced texture id by inserting an atlas sub-path between a pattern
     * {@code assetId}'s namespace and name - {@code namespace:name} becomes
     * {@code namespace:<atlasPath>/name}. An {@code assetId} with no {@code :} defaults to the
     * {@code minecraft} namespace. Shared by the banner / shield mask paths here and the
     * banner-atlas variant paths on the item renderer's banner kit.
     *
     * @param assetId the pattern asset id (e.g. {@code "minecraft:creeper"} or the bare {@code "creeper"})
     * @param atlasPath the atlas sub-path to splice in (e.g. {@code entity/banner})
     * @return the namespaced texture id under the given atlas path
     */
    public static @NotNull String atlasTexture(@NotNull String assetId, @NotNull String atlasPath) {
        // assetId is like "minecraft:creeper" - split namespace:name, prepend atlas path.
        int colon = assetId.indexOf(':');
        if (colon < 0) return "minecraft:" + atlasPath + "/" + assetId;
        return assetId.substring(0, colon) + ":" + atlasPath + "/" + assetId.substring(colon + 1);
    }

}
