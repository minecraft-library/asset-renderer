package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Materialises the renderer's item index from the parsed item asset tables and the block-entity
 * geometry table, returning the finished {@code itemId -> }{@link Item} map the renderer context
 * wraps directly.
 * <p>
 * One item is built per parsed {@code models/item/*.json}, except those whose matching block
 * carries a {@link Block.BlockEntity} (beds, chests, banners, shulkers, signs, skulls, conduit,
 * decorated_pot, copper golem statues): their vanilla item models have neither elements nor a
 * {@code layer0} and would render as blank 2D sprites, so those tiles render through the block
 * path instead. Filtering them out here keeps the renderer free of a separate redirect bridge.
 * <p>
 * Each of those items is named by its file's name alone, which is the item id for every vanilla
 * file, so two files in different folders under {@code models/item} that share a name become one
 * item backed by only one of them.
 * <p>
 * The index is then filtered to drop the parent / template item models that render nothing -
 * {@code item/generated}, {@code item/handheld}, {@code item/template_*}, {@code item/air} - which
 * carry no {@code layerN} sprite and no elements ({@link ModelData#rendersNothing}). Every model
 * that actually renders a tile is kept: flat sprites, 3D item models, the armor-trim variants, and
 * the {@code clock_00..63} / {@code compass_*} / {@code light_*} predicate frames. No hardcoded id list.
 * <p>
 * Last, each item definition whose id the index does not hold adds an item drawing the
 * {@code models/item} model its walk lands on. That model is found by its whole model id, as
 * vanilla's model lookup finds it, so each of two files that share a name backs the definitions
 * that name it.
 * <p>
 * An item the stack registers - one it holds an {@code items/*.json} definition for - that draws
 * nothing gains no row and is kept apart instead ({@link IndexRows#drawsNothing()}): one whose model
 * {@linkplain ModelData#declaresNothingToDraw declares nothing to draw}, as {@code minecraft:air}'s
 * does, and one whose definition's root is {@code minecraft:empty} where no row of its name draws.
 * A template is not registered, and a model whose layers or faces name references that resolve
 * nowhere declares something to draw, so neither is one.
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
     * Builds and filters the renderer's item index, and gathers the registered items that draw nothing
     * beside it.
     *
     * @param itemTints the per-layer tint lists keyed by stripped item id
     * @param glintItems the set of intrinsically-foil item ids
     * @param itemModels the parsed item model data keyed by full model id
     * @param itemTrees the item-definition dispatch trees keyed by stripped item id
     * @param beEntries the block-entity geometry table; ids in here render via the block path and are skipped
     * @return the finished item rows keyed by stripped item id, and the registered item ids that draw
     *     nothing
     */
    public static @NotNull IndexRows<Item> load(
        @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints,
        @NotNull Set<String> glintItems,
        @NotNull ConcurrentMap<String, ModelData> itemModels,
        @NotNull ConcurrentMap<String, ItemModelTree> itemTrees,
        @NotNull ConcurrentMap<String, Block.BlockEntity> beEntries
    ) {
        ConcurrentMap<String, Item> itemIndex = itemModels.entrySet()
            .stream()
            .filter(entry -> !beEntries.containsKey(ResourceId.ofModelId(entry.getKey()).id()))
            .map(entry -> itemOf(ResourceId.ofModelId(entry.getKey()), entry.getValue(), itemTints, glintItems))
            .collect(Concurrent.toMap(item -> item.id().id(), item -> item, (a, b) -> b));

        Set<String> drawsNothing = new HashSet<>();
        int before = itemIndex.size();
        itemIndex.entrySet().removeIf(entry -> {
            Item item = entry.getValue();
            if (keeps(entry.getKey(), item.model()))
                return false;

            // A definition registers the item, so a registered one whose model declares nothing to
            // draw is an item that draws nothing rather than one the index does not know.
            if (itemTrees.containsKey(entry.getKey()) && item.model().declaresNothingToDraw(true))
                drawsNothing.add(entry.getKey());
            return true;
        });
        System.out.printf("Atlas empty-model filter: removed %d template items%n", before - itemIndex.size());

        addDispatchOnlyItems(itemIndex, drawsNothing, itemTints, glintItems, itemModels, itemTrees, beEntries);

        return new IndexRows<>(itemIndex.toUnmodifiable(), Concurrent.newUnmodifiableTreeSet(drawsNothing));
    }

    /**
     * Materialises one item from its parsed model: the model's texture bindings flattened to sprite
     * ids, the per-layer tints registered under its id, and whether that id is intrinsically foil.
     *
     * @param itemResource the item's resource id - a file's name under its namespace, or the id of
     *     the item definition that lands on the model
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
     * Tests whether the index keeps an item drawing a {@code models/item} model: one that renders a
     * tile, or the shield's, whose geometry {@code ShieldKit} builds instead of its model's sprites.
     *
     * @param fileItemId the item id the model's file name gives
     * @param model the model the item draws
     * @return whether the item stays in the index
     */
    private static boolean keeps(@NotNull String fileItemId, @NotNull ModelData model) {
        return fileItemId.equals(SHIELD_ITEM_ID) || !model.rendersNothing(true);
    }

    /**
     * Adds index entries for item ids that resolve through their {@code items/*.json} dispatch tree to
     * a renderable model but carry no same-named {@code models/item/*.json} - {@code clock} (root
     * {@code select(context_dimension) -> range_dispatch(time)} &rarr; {@code clock_00}), {@code compass}
     * (root {@code condition(lodestone_tracker) -> range_dispatch(compass)} &rarr; {@code compass_16}),
     * and similar predicate-frame items. Each is materialised from the model the neutral
     * ({@link ItemModelContext#gui()}) resolution lands on, found among the {@code models/item} models
     * by its whole model id, so two nested files that share a file name each back the definitions that
     * name them. That model passes the filters an item named by its file passes, so no blank tiles slip
     * in.
     * <p>
     * Additive only: an id already in the index (its model shares its name), a block-entity-backed id
     * (renders via the block path), a special / nothing leaf, a leaf outside {@code models/item}, or a
     * model the block-entity or empty-model filter drops is skipped. So no item named by its file is
     * touched, and a definition gains an item only where its walk lands on a model that draws.
     * <p>
     * Two of the skipped ids draw nothing and join {@code drawsNothing}: one whose definition's root is
     * {@code minecraft:empty}, which declares that the item draws nothing, and one whose walk lands on a
     * model that {@linkplain ModelData#declaresNothingToDraw declares nothing to draw}. A root that
     * draws vanilla's missing item model - a refused definition, or a node type in a mod's namespace -
     * is neither. An id gaining an item here leaves {@code drawsNothing}, which its same-named model may
     * have put it in, since what the definition draws is the item.
     *
     * @param itemIndex the index built from the {@code models/item} files, which the new entries join
     * @param drawsNothing the registered item ids that draw nothing, which the skipped ids above join
     * @param itemTints the per-layer tint lists keyed by stripped item id
     * @param glintItems the set of intrinsically-foil item ids
     * @param itemModels the parsed item model data keyed by full model id
     * @param itemTrees the item-definition dispatch trees keyed by stripped item id
     * @param beEntries the block-entity geometry table; ids in here render via the block path and are skipped
     */
    private static void addDispatchOnlyItems(
        @NotNull ConcurrentMap<String, Item> itemIndex,
        @NotNull Set<String> drawsNothing,
        @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints,
        @NotNull Set<String> glintItems,
        @NotNull ConcurrentMap<String, ModelData> itemModels,
        @NotNull ConcurrentMap<String, ItemModelTree> itemTrees,
        @NotNull ConcurrentMap<String, Block.BlockEntity> beEntries
    ) {
        ItemModelContext neutral = ItemModelContext.gui();
        int added = 0;
        for (Map.Entry<String, ItemModelTree> entry : itemTrees.entrySet()) {
            String itemId = entry.getKey();
            if (itemIndex.containsKey(itemId) || beEntries.containsKey(itemId)) continue;

            ItemModelTree tree = entry.getValue();
            if (tree.root() instanceof ItemModelNode.Empty) {
                drawsNothing.add(itemId);
                continue;
            }

            String modelId = neutral.resolve(tree).modelId().orElse(null);
            if (modelId == null) continue;
            ModelData model = itemModels.get(modelId);
            String fileItemId = ResourceId.ofModelId(modelId).id();
            if (model == null || beEntries.containsKey(fileItemId) || !keeps(fileItemId, model)) {
                if (model != null && !beEntries.containsKey(fileItemId) && model.declaresNothingToDraw(true))
                    drawsNothing.add(itemId);
                continue;
            }

            itemIndex.put(itemId, itemOf(ResourceId.parse(itemId), model, itemTints, glintItems));
            drawsNothing.remove(itemId);
            added++;
        }
        System.out.printf("Dispatch-only item projection: added %d id(s) (clock/compass/predicate frames)%n", added);
    }

}
