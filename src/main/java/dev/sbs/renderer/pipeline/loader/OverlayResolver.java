package dev.sbs.renderer.pipeline.loader;

import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.model.ItemModelData;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Synthesises {@link Item.Overlay} metadata for an item from its parsed model textures. Runs at
 * pipeline time with no file I/O - every decision is made from the item id and its
 * {@link ItemModelData#getTextures() texture variable map}.
 * <p>
 * Each overlay kind is recognised by a deterministic naming convention that matches vanilla's
 * own layout under {@code assets/minecraft/textures/item/}:
 * <ul>
 * <li>Leather armor: item id ends in {@code _helmet}/{@code _chestplate}/{@code _leggings}/{@code _boots}
 * with {@code layer0} referencing a {@code leather_*} texture. Overlay is {@code layer1}.</li>
 * <li>Potions: item id is one of {@code potion}, {@code splash_potion}, {@code lingering_potion}.
 * Base is {@code layer0} (glass bottle), overlay is {@code layer1} (liquid).</li>
 * <li>Tipped arrow: item id is {@code tipped_arrow}. Base is {@code layer0} (shaft), overlay is
 * {@code layer1} (head).</li>
 * <li>Firework star: item id is {@code firework_star}. Base is {@code layer0} (star body),
 * overlay is {@code layer1} (center spot tinted by {@link dev.sbs.renderer.options.ItemOptions#getFireworkColor()}).</li>
 * </ul>
 * Spawn eggs shipped pre-composited per-entity textures starting in MC 26.1 and no longer need
 * overlay compositing; they render through the standard layered-sprite path.
 * <p>
 * Items that don't match any rule return {@link Optional#empty()} and render through the
 * standard layered-sprite path.
 */
@UtilityClass
public class OverlayResolver {

    private static final @NotNull String ITEM_ID_POTION          = "minecraft:potion";
    private static final @NotNull String ITEM_ID_SPLASH_POTION   = "minecraft:splash_potion";
    private static final @NotNull String ITEM_ID_LINGERING_POTION = "minecraft:lingering_potion";
    private static final @NotNull String ITEM_ID_TIPPED_ARROW    = "minecraft:tipped_arrow";
    private static final @NotNull String ITEM_ID_FIREWORK_STAR   = "minecraft:firework_star";

    private static final @NotNull String SUFFIX_HELMET     = "_helmet";
    private static final @NotNull String SUFFIX_CHESTPLATE = "_chestplate";
    private static final @NotNull String SUFFIX_LEGGINGS   = "_leggings";
    private static final @NotNull String SUFFIX_BOOTS      = "_boots";

    /**
     * Resolves an overlay for the given item, or returns empty when the item doesn't match any
     * known overlay convention.
     *
     * @param itemId the namespaced item id, e.g. {@code "minecraft:leather_helmet"}
     * @param model the fully-resolved item model (parent chain already merged)
     * @return the synthesised overlay, or empty
     */
    public static @NotNull Optional<Item.Overlay> resolve(
        @NotNull String itemId,
        @NotNull ItemModelData model
    ) {
        String layer0 = model.getTextures().get("layer0");
        String layer1 = model.getTextures().get("layer1");

        if (isLeatherArmor(itemId, layer0) && layer1 != null)
            return Optional.of(new Item.Overlay.Leather(layer0, layer1, Item.Overlay.LEATHER_DEFAULT_ARGB));

        if (layer1 != null && layer0 != null) {
            if (isPotion(itemId))
                return Optional.of(new Item.Overlay.Potion(layer0, layer1));
            if (itemId.equals(ITEM_ID_TIPPED_ARROW))
                return Optional.of(new Item.Overlay.TippedArrow(layer0, layer1));
            if (itemId.equals(ITEM_ID_FIREWORK_STAR))
                return Optional.of(new Item.Overlay.Firework(layer0, layer1, Item.Overlay.FIREWORK_DEFAULT_ARGB));
        }

        return Optional.empty();
    }

    private static boolean isPotion(@NotNull String itemId) {
        return itemId.equals(ITEM_ID_POTION)
            || itemId.equals(ITEM_ID_SPLASH_POTION)
            || itemId.equals(ITEM_ID_LINGERING_POTION);
    }

    /**
     * Matches a leather armor item by id suffix and {@code layer0} texture convention. Vanilla
     * leather pieces use texture ids like {@code minecraft:item/leather_helmet} for the hide base
     * and {@code minecraft:item/leather_helmet_overlay} for the dye overlay layer.
     */
    private static boolean isLeatherArmor(@NotNull String itemId, String layer0) {
        if (layer0 == null || !layer0.contains("leather_")) return false;
        return itemId.endsWith(SUFFIX_HELMET)
            || itemId.endsWith(SUFFIX_CHESTPLATE)
            || itemId.endsWith(SUFFIX_LEGGINGS)
            || itemId.endsWith(SUFFIX_BOOTS);
    }

}
