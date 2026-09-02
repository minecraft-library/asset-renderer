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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        Map<String, List<String>> merged = PackSubtree.walk(stack, BLOCK_TAGS)
            .stream()
            .map(BlockTagLoader::parseRawTag)
            .flatMap(Optional::stream)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (lower, higher) -> higher));

        return merged.keySet()
            .stream()
            .collect(Concurrent.toUnmodifiableMap(Function.identity(), tagId -> resolveTag(tagId, merged)));
    }

    /**
     * Parses one tag file's raw (unresolved) {@code "values"} list, keyed by namespaced tag id - the
     * file's stem under its owning namespace, so a nested tag folder keeps its path segments. A file
     * missing a {@code "values"} array, and a malformed one, yield nothing.
     *
     * @param entry the tag file the subtree walk resolved
     * @return the tag id paired with its raw value list, or empty when the file carries no usable values
     */
    private static @NotNull Optional<Map.Entry<String, List<String>>> parseRawTag(@NotNull PackSubtree.Entry entry) {
        String tagId = VanillaSourcePaths.namespacePrefix(entry.namespace()) + entry.stem();

        try {
            TagDoc doc = GSON.fromJson(new String(entry.container().bytes(entry.entryPath()).orElseThrow(), StandardCharsets.UTF_8), TagDoc.class);
            if (doc == null || doc.values() == null) return Optional.empty();
            return Optional.of(Map.entry(tagId, new ArrayList<>(doc.values())));
        } catch (JsonSyntaxException ex) {
            // Skip malformed tag files
            return Optional.empty();
        }
    }

    /**
     * Flattens one tag id into the {@link BlockTag} entity holding only concrete block ids, each
     * resolution starting from an empty visited set so a tag referenced by two others still flattens
     * fully under both.
     *
     * @param tagId the namespaced tag id to flatten
     * @param raw the full raw tag map from {@link #parseRawTag}, keyed by tag id
     * @return the resolved tag entity
     */
    private static @NotNull BlockTag resolveTag(@NotNull String tagId, @NotNull Map<String, List<String>> raw) {
        ArrayList<String> resolved = new ArrayList<>();
        resolve(tagId, raw, resolved, new HashSet<>());
        return new BlockTag(ResourceId.parse(tagId), Concurrent.adoptList(resolved).toUnmodifiable());
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
        @NotNull Map<String, List<String>> raw,
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
