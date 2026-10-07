package lib.minecraft.renderer.content.pack;

import com.google.gson.Gson;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
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
 * <p>Both projections read the branch that neutral walk reaches: the block-item map holds an item
 * whose branch is one block model, and the tint list is that branch's tints. Packs merge
 * ascending (higher priority winning); each pack's {@code filter.block} erases matching accumulated
 * ids before it merges; item ids are namespace-qualified to their owning namespace.
 */
@UtilityClass
public class ItemModelTreeLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The native item-definition subtree every pack contributes. */
    private static final @NotNull PackSubtree.Subtree ITEMS =
        PackSubtree.Subtree.of(VanillaPaths.ITEMS_SUBDIR, ".json");

    /**
     * The legacy {@code models/item} subtree, contributed only by a pre-format-46 pack. Such a pack
     * ships no {@code items/*.json} trees; its {@code overrides} arrays map onto the same tree form,
     * so the walker serves them like a native items file. A vanilla or modern stack lists nothing
     * here.
     */
    private static final @NotNull PackSubtree.Subtree LEGACY_ITEM_MODELS =
        PackSubtree.Subtree.gated(VanillaPaths.MODELS_ITEM_SUBDIR, ".json", LegacyOverrideMapper::isLegacyPack);

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
        for (PackSubtree.Entry entry : PackSubtree.walk(stack.ascending(), ITEMS, LEGACY_ITEM_MODELS)) {
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
        String itemId = VanillaPaths.namespacePrefix(entry.namespace()) + entry.stem();

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
     * Parses one legacy {@code models/item/*.json} file's {@code overrides} array into an
     * {@code itemId -> }{@link ItemModelTree} entry, or empty when the file carries no mappable
     * {@code overrides}. The item id is the entry's stem under its owning {@code <namespace>:}
     * ({@code minecraft:diamond_sword}). A malformed or unreadable file is skipped (logged), matching
     * the native scan's skip-not-abort contract, so it degrades to a lower pack rather than aborting
     * the load.
     *
     * <p>The synthesised tree's fallback is the item's EXISTING accumulated tree root (its native
     * items tree from a lower pack, else a plain {@code Model(<ns>:item/<stem>)}), so the neutral
     * render and the native tree's tints survive under the override. A block item is no exception:
     * where the neutral walk passes every override by - each keyed on a range threshold above zero,
     * where every neutral range input reads zero, or on a gate's true side - it lands on the native
     * block model, and {@link #deriveBlockItemModels(Map)} projects the item exactly as it would
     * without the pack. An override the neutral walk does select is what a stack carrying no dispatch
     * value draws, so it is the item's icon too.
     *
     * @param entry the legacy {@code models/item} file
     * @param merged the trees merged so far, whose entry for this item becomes the fallback
     * @return the synthesised entry, or empty when the file maps no override or fails to read
     */
    private static @NotNull Optional<Map.Entry<String, ItemModelTree>> parseLegacyOverride(
        @NotNull PackSubtree.Entry entry, @NotNull Map<String, ItemModelTree> merged
    ) {
        String namespace = entry.namespace();
        PackId packId = entry.pack().id();
        String stem = entry.stem();
        String itemId = VanillaPaths.namespacePrefix(namespace) + stem;

        ItemModelTree existing = merged.get(itemId);
        ItemModelNode fallback = existing != null
            ? existing.root()
            : new ItemModelNode.Model(VanillaPaths.modelIdPrefix(namespace, VanillaPaths.ITEM_KIND) + stem,
                Concurrent.newUnmodifiableList());

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
     * model for its inventory model (e.g. {@code piston -> block/piston_inventory}). An item projects
     * when its tree's walk at the neutral {@link ItemModelContext#gui()} context lands on a model leaf
     * naming a block model through no {@code composite}, which is
     * {@link ItemModelNode.Resolution#blockModel()}. That is a plain root naming one, and equally a
     * dispatch whose neutral branch names one: vanilla's {@code beehive}, {@code bee_nest} and
     * {@code test_block} select on a block state and draw their fallback block model in a slot. A
     * composite's icon paints every child, so it never projects.
     *
     * @param trees the merged item-definition trees
     * @return the item-to-block-model mapping for block items
     */
    public static @NotNull ConcurrentMap<String, String> deriveBlockItemModels(@NotNull Map<String, ItemModelTree> trees) {
        ItemModelContext neutral = ItemModelContext.gui();
        return trees.entrySet()
            .stream()
            .flatMap(entry -> neutral.resolve(entry.getValue())
                .blockModel()
                .map(model -> Map.entry(entry.getKey(), model))
                .stream())
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Derives the per-layer tint map ({@code itemId -> tints}) from the parsed trees by walking each
     * against the neutral {@link ItemModelContext#gui()} context, so tints come from the branch the
     * icon actually renders. Items whose rendered branch declares no tints are absent.
     *
     * @param trees the merged item-definition trees
     * @return the item-to-tint-list mapping for tinted items
     */
    public static @NotNull ConcurrentMap<String, ConcurrentList<LayerTint>> deriveTints(@NotNull Map<String, ItemModelTree> trees) {
        ItemModelContext neutral = ItemModelContext.gui();
        return trees.entrySet()
            .stream()
            .map(entry -> Map.entry(entry.getKey(), neutral.resolve(entry.getValue()).tints()))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Concurrent.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Concurrent.newUnmodifiableList(entry.getValue())));
    }

}
