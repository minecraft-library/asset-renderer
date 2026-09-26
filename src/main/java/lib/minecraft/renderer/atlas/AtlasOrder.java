package lib.minecraft.renderer.atlas;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.content.index.BlockTag;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;

/**
 * The atlas-grouping order the known block and item ids are offered in - the sort that clusters
 * semantically related subjects into neighbouring tiles.
 */
@UtilityClass
@Parity(subject = Subject.ATLAS)
@Parity(claim = "pipeline-reads")
public final class AtlasOrder {

    /**
     * Sorts the block ids by primary tag then id (both case-insensitive); the shared, precomputed order.
     *
     * @param blockIndex the materialised block index
     * @param blockTags the materialised block tag index
     * @return the block ids in atlas-grouping order
     */
    public static @NotNull ConcurrentList<String> sortedBlockIds(@NotNull ConcurrentMap<String, Block> blockIndex, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        return blockIndex.keySet()
            .stream()
            .sorted((a, b) -> {
                String groupA = primaryTag(a, blockIndex, blockTags);
                String groupB = primaryTag(b, blockIndex, blockTags);
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(groupA, groupB);
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Sorts the item ids by material prefix then id (both case-insensitive); the shared, precomputed order.
     *
     * @param itemIndex the materialised item index
     * @return the item ids in atlas-grouping order
     */
    public static @NotNull ConcurrentList<String> sortedItemIds(@NotNull ConcurrentMap<String, Item> itemIndex) {
        return itemIndex.keySet()
            .stream()
            .sorted((a, b) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(idPrefix(a), idPrefix(b));
                return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Returns the most specific tag name for a block (the tag with fewest members), or the
     * block's material prefix as a fallback for untagged blocks. Used as the primary sort key
     * so semantically related blocks cluster together in atlas output.
     *
     * @param blockId the namespaced block id
     * @param blockIndex the materialised block index
     * @param blockTags the materialised block tag index
     * @return the primary grouping key
     */
    public static @NotNull String primaryTag(@NotNull String blockId,
        @NotNull ConcurrentMap<String, Block> blockIndex, @NotNull ConcurrentMap<String, BlockTag> blockTags) {
        Block block = blockIndex.get(blockId);

        if (block != null && !block.tags().isEmpty()) {
            return block.tags()
                .stream()
                .filter(blockTags::containsKey)
                .min(Comparator.comparingInt(tag -> blockTags.get(tag).values().size()))
                .orElse(blockId);
        }

        return idPrefix(blockId);
    }

    /**
     * Returns the material prefix of a namespaced id, used as a grouping key when no richer
     * signal (such as block tags) is available. Strips the namespace and the trailing
     * {@code _suffix}, then prepends {@code ~} so heuristic groups sort distinctly from real
     * tag groups. {@code "minecraft:oak_stairs"} becomes {@code "~oak"}.
     *
     * @param id the namespaced id
     * @return the material prefix, prefixed with {@code ~}
     */
    public static @NotNull String idPrefix(@NotNull String id) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        int lastUnderscore = name.lastIndexOf('_');
        return lastUnderscore > 0 ? "~" + name.substring(0, lastUnderscore) : "~" + name;
    }

}
