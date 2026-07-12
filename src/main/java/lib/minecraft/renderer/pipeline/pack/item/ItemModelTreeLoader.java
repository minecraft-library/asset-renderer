package lib.minecraft.renderer.pipeline.pack.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads MC 26.1 item-definition dispatch trees from every pack's {@code assets/<namespace>/items/}
 * subtree, parsing each into an immutable {@link ItemModelTree} (05-models.md §3). The successor to
 * the former {@code ItemDefinitionLoader}: a single scan yields the parsed trees, from which the
 * two projections the pipeline needs are derived by walking each tree against the neutral
 * {@link ItemModelContext#gui()} context - {@link #deriveBlockItemModels(Map)} (the block-item
 * inventory-model map {@code BlockIndexLoader} consumes) and {@link #deriveTints(Map)} (the per-layer
 * tint list {@code ItemIndexLoader} attaches).
 *
 * <p>Both projections are byte-identical to the former loader on vanilla: the block-item map is the
 * root-plain-model-block-ref set (a dispatch-rooted item is never a block-item override), and the
 * neutral walk reaches the same tint-carrying branch the former fallback-descent did. Packs merge
 * ascending (higher priority winning); each pack's {@code filter.block} erases matching accumulated
 * ids before it merges; item ids are namespace-qualified to their owning namespace.
 */
@UtilityClass
public class ItemModelTreeLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads and merges the item-definition trees across the whole pack stack, keyed by item id
     * ({@code "minecraft:compass"}).
     *
     * @param stack the resolved pack stack
     * @return the merged item-definition trees
     */
    public static @NotNull ConcurrentMap<String, ItemModelTree> load(@NotNull PackStack stack) {
        HashMap<String, ItemModelTree> merged = new HashMap<>();
        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> merged.keySet().removeIf(id -> section.hides(ResourceId.parse(id))));
            if (!(pack.container() instanceof PackContainer.Directory dir)) continue;

            for (PackRoot root : pack.roots())
                for (String namespace : pack.namespaces()) {
                    Path itemsDir = dir.root().resolve(root.prefix())
                        .resolve(VanillaSourcePaths.assetSubdir(namespace, VanillaSourcePaths.ITEMS_SUBDIR));
                    scanRoot(itemsDir, namespace).forEach(merged::put);
                }
        }
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /** Scans one {@code (root x namespace)} items directory into an itemId -> tree map. */
    private static @NotNull Map<String, ItemModelTree> scanRoot(@NotNull Path itemsDir, @NotNull String namespace) {
        if (!Files.isDirectory(itemsDir)) return Map.of();

        List<Path> files;
        try (Stream<Path> stream = Files.walk(itemsDir)) {
            files = stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan item definitions in '%s'", itemsDir);
        }

        return files.parallelStream()
            .map(p -> parseTree(p, itemsDir, namespace))
            .flatMap(Optional::stream)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Parses one item-definition file into an {@code itemId -> }{@link ItemModelTree} entry, or empty
     * when the file has no {@code model} object. The item id is the path relative to {@code itemsDir}
     * with the {@code .json} suffix stripped and the owning {@code <namespace>:} prepended. Malformed
     * JSON is skipped (logged) so a bad entry falls back to a lower-priority pack.
     */
    private static @NotNull Optional<Map.Entry<String, ItemModelTree>> parseTree(@NotNull Path p, @NotNull Path itemsDir, @NotNull String namespace) {
        String relative = itemsDir.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return Optional.empty();
        String itemId = VanillaSourcePaths.namespacePrefix(namespace) + relative.substring(0, relative.length() - ".json".length());

        try {
            JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (json == null || !json.has("model") || !json.get("model").isJsonObject()) return Optional.empty();
            ItemModelNode root = ItemModelParser.parse(json.getAsJsonObject("model"));
            return Optional.of(Map.entry(itemId, new ItemModelTree(ResourceId.parse(itemId), root)));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read item definition '%s'", p);
        } catch (RuntimeException ex) {
            // Resource packs sometimes ship deeply nested or otherwise malformed item definitions
            // (e.g. Hypixel+ player_head.json with 255+ levels of conditional nesting, or a semantically
            // malformed field Gson accepts but a typed read rejects). Skip the entry so the merge falls
            // back to a lower-priority pack's version of this id rather than aborting the whole load.
            System.err.printf("Skipping malformed item definition '%s': %s%n", p, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Derives the block-item inventory-model map ({@code itemId -> blockModelId}) from the parsed
     * trees - the block-item projection {@code BlockIndexLoader} consumes to swap a block's in-world
     * model for its inventory model (e.g. {@code piston -> block/piston_inventory}). Only a
     * root-plain-{@code model} node whose ref is a block model qualifies; a dispatch-rooted item
     * (beehive, bee_nest) is never a block-item override (byte-identical to the former root-only scan).
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
     * icon actually renders (05-models.md §3.3). Items whose rendered branch declares no tints are
     * absent (byte-identical to the former fallback-descent on vanilla).
     *
     * @param trees the merged item-definition trees
     * @return the item-to-tint-list mapping for tinted items
     */
    public static @NotNull ConcurrentMap<String, List<LayerTint>> deriveTints(@NotNull Map<String, ItemModelTree> trees) {
        HashMap<String, List<LayerTint>> tintMap = new HashMap<>();
        ItemModelContext neutral = ItemModelContext.gui();
        trees.forEach((itemId, tree) -> {
            List<LayerTint> tints = ItemModelWalker.resolve(tree, neutral).tints();
            if (!tints.isEmpty()) tintMap.put(itemId, tints);
        });
        return Concurrent.adoptMap(tintMap).toUnmodifiable();
    }

}
