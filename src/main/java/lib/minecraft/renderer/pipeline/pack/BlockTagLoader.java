package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A loader that reads vanilla block tag JSON files from {@code data/minecraft/tags/block/} and
 * recursively resolves tag inheritance to produce fully flattened {@link BlockTag} entities.
 * <p>
 * Tag files contain a {@code "values"} array of block IDs and {@code #}-prefixed tag references.
 * References are resolved transitively so each returned {@link BlockTag} contains only concrete
 * block IDs with no remaining {@code #} references. Cycles are guarded against but do not occur
 * in vanilla data.
 *
 * @see BlockTag
 */
@UtilityClass
public class BlockTagLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * The block-tag registry subtree. A {@code data} subtree rather than an {@code assets} one, and
     * scanned at the vanilla namespace alone - the registry ships in the client jar and no resource
     * pack in this stack carries a {@code data} root.
     */
    private static final @NotNull PackSubtree.Subtree BLOCK_TAGS = PackSubtree.Subtree.data(
        VanillaSourcePaths.MINECRAFT_NAMESPACE_DIR, "tags/block", ".json");

    /**
     * Loads and resolves block tags across the whole pack stack. Packs are visited ascending, each
     * over its base and overlay roots; raw {@code data/minecraft/tags/block} values merge later-wins
     * per tag id, and the recursive resolution pass runs once on the merged map so a tag definition
     * supplied by a higher pack still resolves through references that live only in vanilla. A
     * vanilla-only stack scans exactly the vanilla root.
     *
     * @param stack the resolved pack stack
     * @return a map of tag id to resolved tag entity
     */
    public static @NotNull ConcurrentMap<String, BlockTag> load(@NotNull PackStack stack) {
        HashMap<String, List<String>> merged = new HashMap<>();
        for (PackSubtree.Entry entry : PackSubtree.walk(stack, BLOCK_TAGS))
            parseRawTag(entry, merged);

        HashMap<String, BlockTag> result = new HashMap<>(merged.size());
        for (String tagId : merged.keySet()) {
            ArrayList<String> resolved = new ArrayList<>();
            resolve(tagId, merged, resolved, new HashSet<>());
            result.put(tagId, new BlockTag(ResourceId.parse(tagId), Concurrent.adoptList(resolved).toUnmodifiable()));
        }
        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * Parses one tag file's raw (unresolved) {@code "values"} list into {@code raw}, keyed by
     * namespaced tag id - the file's stem under its owning namespace, so a nested tag folder keeps
     * its path segments. A file missing a {@code "values"} array, and a malformed one, are skipped
     * silently. Writes into a plain {@code HashMap} / {@code ArrayList} to skip per-element
     * write-locks; the caller wraps with {@link Concurrent#adoptMap} at the end.
     *
     * @param entry the tag file the subtree walk resolved
     * @param raw the running raw map, mutated in place
     */
    private static void parseRawTag(@NotNull PackSubtree.Entry entry, @NotNull HashMap<String, List<String>> raw) {
        String tagId = VanillaSourcePaths.namespacePrefix(entry.namespace()) + entry.stem();

        try {
            TagDoc doc = GSON.fromJson(new String(entry.container().bytes(entry.entryPath()).orElseThrow(), StandardCharsets.UTF_8), TagDoc.class);
            if (doc == null || doc.values() == null) return;
            raw.put(tagId, new ArrayList<>(doc.values()));
        } catch (JsonSyntaxException ex) {
            // Skip malformed tag files
        }
    }

    /**
     * Recursively flattens one tag's raw value list into concrete block ids, appending to
     * {@code out} in encounter order. A {@code #}-prefixed entry recurses into the referenced tag
     * (the {@code #} is stripped before lookup); a plain entry is a block id, appended only when not
     * already present so duplicates across referenced tags collapse. A tag id already in
     * {@code visited} is skipped (cycle guard - vanilla data has no cycles but a re-visit would
     * also re-append already-flattened ids); an unknown tag id resolves to nothing.
     *
     * @param tagId the namespaced tag id to flatten
     * @param raw the full raw tag map from {@link #parseRawTag}, keyed by tag id
     * @param out the accumulating concrete-block-id list, mutated in place
     * @param visited tag ids already entered on this resolution, mutated in place
     */
    private static void resolve(
        @NotNull String tagId,
        @NotNull HashMap<String, List<String>> raw,
        @NotNull ArrayList<String> out,
        @NotNull Set<String> visited
    ) {
        if (!visited.add(tagId)) return; // cycle guard
        List<String> entries = raw.get(tagId);
        if (entries == null) return;

        for (String entry : entries) {
            if (entry.startsWith("#"))
                resolve(entry.substring(1), raw, out, visited);
            else if (!out.contains(entry))
                out.add(entry);
        }
    }

    /** One tag file's payload: its {@code values} array of block ids and {@code #}-prefixed tag refs. */
    record TagDoc(@NotNull List<String> values) {}

}
