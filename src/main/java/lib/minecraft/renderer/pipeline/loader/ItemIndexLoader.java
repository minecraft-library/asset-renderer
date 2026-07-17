package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.util.Models;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Materialises the renderer's item index from a {@link Pipeline.Result} and the block-entity
 * geometry table, returning the finished {@code itemId -> }{@link Item} map the renderer context
 * wraps directly.
 * <p>
 * One item is built per parsed {@code models/item/*.json}, except those whose matching block
 * carries a {@link Block.Entity} (beds, chests, banners, shulkers, signs, skulls, conduit,
 * decorated_pot, copper golem statues): their vanilla item models have neither elements nor a
 * {@code layer0} and would render as blank 2D sprites, so those tiles render through the block
 * path instead. Filtering them out here keeps the renderer free of a separate redirect bridge.
 * <p>
 * The index is then filtered to drop the parent / template item models that render nothing -
 * {@code item/generated}, {@code item/handheld}, {@code item/template_*}, {@code item/air} - which
 * carry no {@code layerN} sprite and no elements ({@link Models#rendersNothing}). Every model
 * that actually renders a tile is kept: flat sprites, 3D item models, the armor-trim variants, and
 * the {@code clock_00..63} / {@code compass_*} / {@code light_*} predicate frames. No hardcoded id list.
 * <p>
 * Each item also carries its per-layer {@link LayerTint} list from
 * {@link Pipeline.Result#getItemTints()} (parsed from the item definition's {@code model.tints[]}),
 * so the renderer multiplies the dye / potion / firework colour into the matching layer, plus an
 * {@code alwaysGlinted} flag from {@link Pipeline.Result#getGlintItems()} (parsed from the vanilla
 * {@code Items} registry) that drives the automatic enchantment foil on intrinsically-foil items.
 */
@UtilityClass
public class ItemIndexLoader {

    /**
     * The shield item id. Its vanilla item model carries neither elements nor a {@code layer0}
     * (it renders through a {@code minecraft:special} 3D {@code ShieldModel}), so it would be
     * dropped by the {@link Models#rendersNothing} filter; it is exempted because the renderer
     * builds its geometry from {@code ShieldKit} instead of the model's sprites.
     */
    private static final @NotNull String SHIELD_ITEM_ID = "minecraft:shield";

    /**
     * Builds and filters the renderer's item index.
     *
     * @param result the pipeline result supplying item models
     * @param beEntries the block-entity geometry table; ids in here render via the block path and are skipped
     * @return the finished item index, keyed by stripped item id, unmodifiable
     */
    public static @NotNull ConcurrentMap<String, Item> load(
        @NotNull Pipeline.Result result,
        @NotNull ConcurrentMap<String, Block.Entity> beEntries
    ) {
        ConcurrentMap<String, List<LayerTint>> itemTints = result.getItemTints();
        ConcurrentSet<String> glintItems = result.getGlintItems();
        HashMap<String, Item> itemIndex = new HashMap<>();
        for (Map.Entry<String, ModelData> itemEntry : result.getItemModels().entrySet()) {
            String modelId = itemEntry.getKey();
            ModelData model = itemEntry.getValue();
            ResourceId itemResource = ResourceId.ofModelId(modelId);
            String itemId = itemResource.id();
            if (beEntries.containsKey(itemId)) continue;
            HashMap<String, String> textures = new HashMap<>(model.getTextures());
            List<LayerTint> tints = itemTints.getOrDefault(itemId, List.of());
            boolean alwaysGlinted = glintItems.contains(itemId);
            itemIndex.put(itemId, new Item(itemResource, model, Concurrent.adoptMap(textures), 0, tints, alwaysGlinted));
        }

        int before = itemIndex.size();
        itemIndex.values().removeIf(item ->
            !item.id().id().equals(SHIELD_ITEM_ID)
                && Models.rendersNothing(item.model().getElements(), item.model().getTextures(), true));
        System.out.printf("Atlas empty-model filter: removed %d template items%n", before - itemIndex.size());

        return Concurrent.adoptMap(itemIndex).toUnmodifiable();
    }

}
