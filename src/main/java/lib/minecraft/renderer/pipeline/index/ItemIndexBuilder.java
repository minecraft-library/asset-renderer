package lib.minecraft.renderer.pipeline.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.item.ItemModelContext;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * Materialises the renderer's item index from the parsed item asset tables and the block-entity
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
 * carry no {@code layerN} sprite and no elements ({@link ModelData#rendersNothing}). Every model
 * that actually renders a tile is kept: flat sprites, 3D item models, the armor-trim variants, and
 * the {@code clock_00..63} / {@code compass_*} / {@code light_*} predicate frames. No hardcoded id list.
 * <p>
 * Each item also carries its per-layer {@link LayerTint} list from the item-tint table
 * (parsed from the item definition's {@code model.tints[]}),
 * so the renderer multiplies the dye / potion / firework colour into the matching layer, plus an
 * {@code alwaysGlinted} flag from the glint-item set (parsed from the vanilla
 * {@code Items} registry) that drives the automatic enchantment foil on intrinsically-foil items.
 *
 * <p><b>Parity.</b> Reached only across the pipeline context, which is wiring, so no producer root
 * reaches it. It materialises the item index out of the item tables and the block-entity geometry,
 * so it is under every render that draws an item and under the block icons baked from one.
 */
@Parity(claim = "index-resolution")
@UtilityClass
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
public class ItemIndexBuilder {

    /**
     * The shield item id. Its vanilla item model carries neither elements nor a {@code layer0}
     * (it renders through a {@code minecraft:special} 3D {@code ShieldModel}), so it would be
     * dropped by the {@link ModelData#rendersNothing} filter; it is exempted because the renderer
     * builds its geometry from {@code ShieldKit} instead of the model's sprites.
     */
    private static final @NotNull String SHIELD_ITEM_ID = "minecraft:shield";

    /**
     * Builds and filters the renderer's item index.
     *
     * @param itemTints the per-layer tint lists keyed by stripped item id
     * @param glintItems the set of intrinsically-foil item ids
     * @param itemModels the parsed item model data keyed by full model id
     * @param itemTrees the item-definition dispatch trees keyed by stripped item id
     * @param beEntries the block-entity geometry table; ids in here render via the block path and are skipped
     * @return the finished item index, keyed by stripped item id, unmodifiable
     */
    public static @NotNull ConcurrentMap<String, Item> load(
        @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints,
        @NotNull Set<String> glintItems,
        @NotNull ConcurrentMap<String, ModelData> itemModels,
        @NotNull ConcurrentMap<String, ItemModelTree> itemTrees,
        @NotNull ConcurrentMap<String, Block.Entity> beEntries
    ) {
        ConcurrentMap<String, Item> itemIndex = itemModels.entrySet()
            .stream()
            .filter(entry -> !beEntries.containsKey(ResourceId.ofModelId(entry.getKey()).id()))
            .map(entry -> itemOf(ResourceId.ofModelId(entry.getKey()), entry.getValue(), itemTints, glintItems))
            .collect(Concurrent.toMap(item -> item.id().id(), item -> item, (a, b) -> b));

        int before = itemIndex.size();
        itemIndex.values().removeIf(item ->
            !item.id().id().equals(SHIELD_ITEM_ID)
                && item.model().rendersNothing(true));
        System.out.printf("Atlas empty-model filter: removed %d template items%n", before - itemIndex.size());

        addDispatchOnlyItems(itemIndex, itemTints, glintItems, itemTrees, beEntries);

        return itemIndex.toUnmodifiable();
    }

    /**
     * Materialises one item from its parsed model: the model's texture bindings flattened to sprite
     * ids, the per-layer tints registered under its id, and whether that id is intrinsically foil.
     *
     * @param itemResource the item's resource id, stripped of the {@code item/} model prefix
     * @param model the parsed model the item renders
     * @param itemTints the per-layer tint lists keyed by stripped item id
     * @param glintItems the set of intrinsically-foil item ids
     * @return the materialised item
     */
    private static @NotNull Item itemOf(
        @NotNull ResourceId itemResource,
        @NotNull ModelData model,
        @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints,
        @NotNull Set<String> glintItems
    ) {
        String itemId = itemResource.id();
        ConcurrentMap<String, String> textures = model.getTextures()
            .entrySet()
            .stream()
            .collect(Concurrent.toMap(Map.Entry::getKey, binding -> binding.getValue().sprite()));
        return new Item(itemResource, model, textures, 0,
            itemTints.getOrDefault(itemId, Concurrent.newUnmodifiableList()), glintItems.contains(itemId));
    }

    /**
     * Adds index entries for item ids that resolve through their {@code items/*.json} dispatch tree to
     * a renderable model but carry no same-named {@code models/item/*.json} - {@code clock} (root
     * {@code select(context_dimension) -> range_dispatch(time)} &rarr; {@code clock_00}), {@code compass}
     * (root {@code condition(lodestone_tracker) -> range_dispatch(compass)} &rarr; {@code compass_16}),
     * and similar predicate-frame items. Each is materialised from the neutral
     * ({@link ItemModelContext#gui()}) resolution's backing model - the already-built, already-filtered
     * frame item ({@code clock_00}/{@code compass_16}/...) - so no blank tiles slip in.
     * <p>
     * Additive only: an id already in the index (its model shares its name), a block-entity-backed id
     * (renders via the block path), a special / nothing leaf, or a backing model that failed the
     * empty-model filter is skipped. So every existing tile is untouched; the vanilla item sweep gains
     * exactly the previously-unrenderable ids.
     */
    private static void addDispatchOnlyItems(
        @NotNull ConcurrentMap<String, Item> itemIndex,
        @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints,
        @NotNull Set<String> glintItems,
        @NotNull ConcurrentMap<String, ItemModelTree> itemTrees,
        @NotNull ConcurrentMap<String, Block.Entity> beEntries
    ) {
        ItemModelContext neutral = ItemModelContext.gui();
        int added = 0;
        for (Map.Entry<String, ItemModelTree> entry : itemTrees.entrySet()) {
            String itemId = entry.getKey();
            if (itemIndex.containsKey(itemId) || beEntries.containsKey(itemId)) continue;

            String modelId = entry.getValue().resolve(neutral).modelId().orElse(null);
            if (modelId == null) continue;
            Item backing = itemIndex.get(ResourceId.ofModelId(modelId).id());
            if (backing == null) continue;

            ConcurrentList<LayerTint> tints = itemTints.getOrDefault(itemId, Concurrent.newUnmodifiableList());
            itemIndex.put(itemId, new Item(ResourceId.parse(itemId), backing.model(), backing.textures(),
                0, tints, glintItems.contains(itemId)));
            added++;
        }
        System.out.printf("Dispatch-only item projection: added %d id(s) (clock/compass/predicate frames)%n", added);
    }

}
