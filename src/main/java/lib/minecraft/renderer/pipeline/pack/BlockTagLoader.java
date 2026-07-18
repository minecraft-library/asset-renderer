package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        for (ResourcePack pack : stack.ascending()) {
            PackContainer container = pack.container();
            for (PackRoot root : pack.roots())
                for (Map.Entry<String, List<String>> entry : scanRawTags(container, root.prefix()).entrySet())
                    merged.put(entry.getKey(), entry.getValue());
        }

        HashMap<String, BlockTag> result = new HashMap<>(merged.size());
        for (String tagId : merged.keySet()) {
            ArrayList<String> resolved = new ArrayList<>();
            resolve(tagId, merged, resolved, new HashSet<>());
            result.put(tagId, new BlockTag(ResourceId.parse(tagId), Concurrent.adoptList(resolved).toUnmodifiable()));
        }
        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * Scans one asset root's {@code data/minecraft/tags/block/} subtree and returns the raw
     * (unresolved) value lists keyed by namespaced tag id. The tag id is the file path relative to
     * the {@code block} tag dir with the {@code .json} suffix stripped and the
     * {@link VanillaSourcePaths#MINECRAFT_NAMESPACE} prefix prepended (backslashes normalised to
     * forward slashes for nested tag folders). Files missing a {@code "values"} array and malformed
     * files are skipped silently; a missing tag dir returns an empty map. Walks tag files into plain
     * HashMap / ArrayList to skip per-element write-locks - callers wrap with
     * {@link Concurrent#adoptMap} at the end.
     *
     * @param container the pack container to read through
     * @param prefix the container root prefix ({@code ""} for base, {@code "<overlay>/"} for an overlay)
     * @return raw {@code "values"} lists keyed by namespaced tag id, references still {@code #}-prefixed
     */
    private static @NotNull HashMap<String, List<String>> scanRawTags(@NotNull PackContainer container, @NotNull String prefix) {
        String tagsPrefix = prefix + "data/minecraft/tags/block";
        HashMap<String, List<String>> raw = new HashMap<>();

        container.entries(tagsPrefix)
            .filter(p -> p.endsWith(".json"))
            .forEach(entry -> {
                String relative = entry.substring(tagsPrefix.length() + 1);
                String tagId = VanillaSourcePaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - 5);
                try {
                    TagDoc doc = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), TagDoc.class);
                    if (doc == null || doc.values() == null) return;
                    raw.put(tagId, new ArrayList<>(doc.values()));
                } catch (JsonSyntaxException ex) {
                    // Skip malformed tag files
                }
            });
        return raw;
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
     * @param raw the full raw tag map from {@link #scanRawTags}, keyed by tag id
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
