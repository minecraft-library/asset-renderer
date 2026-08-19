package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.Gson;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.item.ItemModelContext;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import lib.minecraft.renderer.pipeline.pack.PackSubtree;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads MC 26.1 item-definition dispatch trees from every pack's {@code assets/<namespace>/items/}
 * subtree, parsing each into an immutable {@link ItemModelTree}. A single scan yields the parsed
 * trees, from which the two projections the pipeline needs are derived by walking each tree against
 * the neutral
 * {@link ItemModelContext#gui()} context - {@link #deriveBlockItemModels(Map)} (the block-item
 * inventory-model map {@code BlockIndexBuilder} consumes) and {@link #deriveTints(Map)} (the per-layer
 * tint list {@code ItemIndexBuilder} attaches).
 *
 * <p>The block-item map is the root-plain-model-block-ref set (a dispatch-rooted item is never a
 * block-item override), and the neutral walk reaches the tint-carrying branch. Packs merge
 * ascending (higher priority winning); each pack's {@code filter.block} erases matching accumulated
 * ids before it merges; item ids are namespace-qualified to their owning namespace.
 */
@UtilityClass
public class ItemModelTreeLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The native item-definition subtree every pack contributes. */
    private static final @NotNull PackSubtree.Subtree ITEMS =
        PackSubtree.Subtree.of(VanillaSourcePaths.ITEMS_SUBDIR, ".json");

    /**
     * The legacy {@code models/item} subtree, contributed only by a pre-format-46 pack. Such a pack
     * ships no {@code items/*.json} trees; its {@code overrides} arrays map onto the same tree form,
     * so the walker serves them like a native items file. A vanilla or modern stack lists nothing
     * here.
     */
    private static final @NotNull PackSubtree.Subtree LEGACY_ITEM_MODELS =
        PackSubtree.Subtree.gated(VanillaSourcePaths.MODELS_ITEM_SUBDIR, ".json", LegacyOverrideMapper::isLegacyPack);

    /**
     * Loads and merges the item-definition trees across the whole pack stack, keyed by item id
     * ({@code "minecraft:compass"}).
     *
     * @param stack the resolved pack stack
     * @return the merged item-definition trees
     */
    public static @NotNull ConcurrentMap<String, ItemModelTree> load(@NotNull PackStack stack) {
        HashMap<String, ItemModelTree> merged = new HashMap<>();

        // The two subtrees are walked together rather than one after the other, because a legacy
        // pack's own models/item overrides must beat its own items trees while still losing to a
        // higher pack's - which is the interleaving the shared walk produces by listing both
        // subtrees per pack, in this declared order.
        for (PackSubtree.Entry entry : PackSubtree.walk(stack, ITEMS, LEGACY_ITEM_MODELS)) {
            if (entry.subtree().equals(ITEMS))
                parseTree(entry).ifPresent(tree -> merged.put(tree.getKey(), tree.getValue()));
            else
                parseLegacyOverride(entry, merged).ifPresent(tree -> merged.put(tree.getKey(), tree.getValue()));
        }

        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Parses one item-definition file into an {@code itemId -> }{@link ItemModelTree} entry, or empty
     * when the file has no {@code model} object. The item id is the entry path relative to
     * {@code itemsPrefix} with the {@code .json} suffix stripped and the owning {@code <namespace>:}
     * prepended. Malformed / unreadable input is skipped (logged) so a bad entry falls back to a
     * lower-priority pack.
     */
    private static @NotNull Optional<Map.Entry<String, ItemModelTree>> parseTree(@NotNull PackSubtree.Entry entry) {
        String itemId = VanillaSourcePaths.namespacePrefix(entry.namespace()) + entry.stem();

        try {
            JsonTree json = JsonTree.parse(entry.container().bytes(entry.entryPath()).orElseThrow());
            Optional<JsonTree> model = json.findObject("model");
            if (model.isEmpty()) return Optional.empty();
            ItemModelNode root = GSON.fromJson(model.get().toGson(), ItemModelNode.class);
            return Optional.of(Map.entry(itemId, new ItemModelTree(ResourceId.parse(itemId), root)));
        } catch (RuntimeException ex) {
            // Resource packs sometimes ship deeply nested or otherwise malformed item definitions
            // (e.g. Hypixel+ player_head.json with 255+ levels of conditional nesting, or a semantically
            // malformed field Gson accepts but a typed read rejects), or an unreadable / non-UTF-8 file
            // (surfaced by the container as an unchecked read failure). Skip the entry so the merge
            // falls back to a lower-priority pack's version rather than aborting the whole load.
            System.err.printf("Skipping malformed item definition '%s': %s%n", entry, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Scans one {@code (root x namespace)} {@code models/item} directory for legacy {@code overrides}
     * arrays, merging each synthesised {@link ItemModelTree} into {@code merged} keyed by the derived
     * item id ({@code minecraft:diamond_sword}). A file with no {@code overrides} array yields nothing;
     * a malformed / unreadable file is skipped (logged) so it degrades to a lower pack rather than
     * aborting the load.
     *
     * <p>The synthesised tree's fallback is the item's EXISTING accumulated tree (its native
     * items-tree from a lower pack, else a plain {@code Model(<ns>:item/<stem>)}), so the neutral
     * render and the native tree's tints survive under the override. A native tree that is a plain
     * block-item model reference is left untouched (the legacy override is dropped with a diagnostic)
     * so the block-item inventory projection {@code deriveBlockItemModels} keys on is preserved.
     */
    /**
     * Parses one {@code models/item/*.json} file's {@code overrides} array into an
     * {@code itemId -> }{@link ItemModelTree} entry, or empty when the file carries no mappable
     * {@code overrides} or the id's existing tree is a block-item projection to preserve. The item id
     * is the path relative to {@code itemModelsDir} sans {@code .json} under the owning
     * {@code <namespace>:}; the tree's fallback is the existing accumulated tree's root (else the
     * file's own {@code <namespace>:item/<stem>} model). Malformed or unreadable files are skipped
     * (logged), matching the native scan's skip-not-abort contract.
     */
    private static @NotNull Optional<Map.Entry<String, ItemModelTree>> parseLegacyOverride(
        @NotNull PackSubtree.Entry entry, @NotNull Map<String, ItemModelTree> merged
    ) {
        String namespace = entry.namespace();
        PackId packId = entry.pack().id();
        String stem = entry.stem();
        String itemId = VanillaSourcePaths.namespacePrefix(namespace) + stem;

        // Preserve a native block-item inventory projection: deriveBlockItemModels keys on a
        // root-plain block-model tree, which a dispatch-rooted legacy override would shadow. A legacy
        // custom_model_data override on a block item is unusual; drop it (diagnosed) rather than break
        // the item's default inventory model.
        ItemModelTree existing = merged.get(itemId);
        if (existing != null && existing.root() instanceof ItemModelNode.Model model && VanillaSourcePaths.isBlockModelRef(model.model())) {
            System.err.printf("Pack '%s' item '%s': legacy overrides ignored to preserve its block-item inventory model%n", packId, itemId);
            return Optional.empty();
        }
        ItemModelNode fallback = existing != null
            ? existing.root()
            : new ItemModelNode.Model(VanillaSourcePaths.modelIdPrefix(namespace, VanillaSourcePaths.ITEM_KIND) + stem, List.of());

        try {
            JsonTree json = JsonTree.parse(entry.container().bytes(entry.entryPath()).orElseThrow());
            Optional<JsonTree> overridesOpt = json.findArray("overrides");
            if (overridesOpt.isEmpty()) return Optional.empty();
            JsonTree overrides = overridesOpt.get();
            if (overrides.size() == 0) return Optional.empty();
            return LegacyOverrideMapper.map(itemId, overrides, packId, fallback)
                .map(root -> Map.entry(itemId, new ItemModelTree(ResourceId.parse(itemId), root)));
        } catch (RuntimeException ex) {
            // A malformed override, or an unreadable / non-UTF-8 file (surfaced by the container as an
            // unchecked read failure); skip it rather than aborting the whole load.
            System.err.printf("Skipping malformed legacy item model '%s': %s%n", entry, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Derives the block-item inventory-model map ({@code itemId -> blockModelId}) from the parsed
     * trees - the block-item projection {@code BlockIndexBuilder} consumes to swap a block's in-world
     * model for its inventory model (e.g. {@code piston -> block/piston_inventory}). Only a
     * root-plain-{@code model} node whose ref is a block model qualifies; a dispatch-rooted item
     * (beehive, bee_nest) is never a block-item override.
     *
     * @param trees the merged item-definition trees
     * @return the item-to-block-model mapping for block items
     */
    public static @NotNull ConcurrentMap<String, String> deriveBlockItemModels(@NotNull Map<String, ItemModelTree> trees) {
        HashMap<String, String> models = new HashMap<>();
        trees.forEach((itemId, tree) -> {
            if (tree.root() instanceof ItemModelNode.Model model && VanillaSourcePaths.isBlockModelRef(model.model()))
                models.put(itemId, model.model());
        });
        return Concurrent.adoptMap(models).toUnmodifiable();
    }

    /**
     * Derives the per-layer tint map ({@code itemId -> tints}) from the parsed trees by walking each
     * against the neutral {@link ItemModelContext#gui()} context, so tints come from the branch the
     * icon actually renders. Items whose rendered branch declares no tints are absent.
     *
     * @param trees the merged item-definition trees
     * @return the item-to-tint-list mapping for tinted items
     */
    public static @NotNull ConcurrentMap<String, List<LayerTint>> deriveTints(@NotNull Map<String, ItemModelTree> trees) {
        HashMap<String, List<LayerTint>> tintMap = new HashMap<>();
        ItemModelContext neutral = ItemModelContext.gui();
        trees.forEach((itemId, tree) -> {
            List<LayerTint> tints = tree.resolve(neutral).tints();
            if (!tints.isEmpty()) tintMap.put(itemId, tints);
        });
        return Concurrent.adoptMap(tintMap).toUnmodifiable();
    }

}
