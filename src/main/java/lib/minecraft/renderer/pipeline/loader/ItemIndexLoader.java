package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.resolver.OverlayResolver;
import lib.minecraft.renderer.pipeline.util.Models;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
 */
@UtilityClass
public class ItemIndexLoader {

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
        HashMap<String, Item> itemIndex = new HashMap<>();
        for (Map.Entry<String, ItemModelData> itemEntry : result.getItemModels().entrySet()) {
            String modelId = itemEntry.getKey();
            ItemModelData model = itemEntry.getValue();
            String itemId = Models.stripPrefix(modelId, ":item/");
            String name = Models.localName(modelId);
            if (beEntries.containsKey(itemId)) continue;
            HashMap<String, String> textures = new HashMap<>(model.getTextures());
            Optional<Item.Overlay> overlay = OverlayResolver.resolve(itemId, model);
            itemIndex.put(itemId, new Item(itemId, "minecraft", name, model, Concurrent.adoptMap(textures), 0, 64, overlay));
        }

        int before = itemIndex.size();
        itemIndex.values().removeIf(item -> Models.rendersNothing(item.getModel().getElements(), item.getModel().getTextures(), true));
        System.out.printf("Atlas empty-model filter: removed %d template items%n", before - itemIndex.size());

        return Concurrent.adoptMap(itemIndex).toUnmodifiable();
    }

}
