package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sbs.renderer.asset.BlockTag;
import dev.sbs.renderer.pipeline.VanillaPaths;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

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
     * Loads and resolves all block tags from the given pack root.
     *
     * @param packRoot the extracted pack root directory
     * @return a map of tag id to resolved tag entity
     */
    public static @NotNull ConcurrentMap<String, BlockTag> load(@NotNull Path packRoot) {
        Path tagsDir = packRoot.resolve("data/minecraft/tags/block");
        ConcurrentMap<String, BlockTag> result = Concurrent.newMap();
        if (!Files.isDirectory(tagsDir)) return result;

        // First pass: parse all tag files into raw value lists
        ConcurrentMap<String, ConcurrentList<String>> raw = Concurrent.newMap();
        try (Stream<Path> files = Files.walk(tagsDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(file -> {
                    String relative = tagsDir.relativize(file).toString().replace('\\', '/');
                    String tagId = VanillaPaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - 5);
                    try {
                        JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
                        if (root == null || !root.has("values")) return;
                        JsonArray values = root.getAsJsonArray("values");
                        ConcurrentList<String> entries = Concurrent.newList();
                        for (int i = 0; i < values.size(); i++)
                            entries.add(values.get(i).getAsString());
                        raw.put(tagId, entries);
                    } catch (IOException | com.google.gson.JsonSyntaxException ex) {
                        // Skip malformed tag files
                    }
                });
        } catch (IOException ex) {
            // Directory scan failure is non-fatal
        }

        // Second pass: recursively resolve tag references
        for (String tagId : raw.keySet()) {
            ConcurrentList<String> resolved = Concurrent.newList();
            resolve(tagId, raw, resolved, new HashSet<>());
            result.put(tagId, new BlockTag(tagId, resolved));
        }

        return result;
    }

    private static void resolve(
        @NotNull String tagId,
        @NotNull ConcurrentMap<String, ConcurrentList<String>> raw,
        @NotNull ConcurrentList<String> out,
        @NotNull Set<String> visited
    ) {
        if (!visited.add(tagId)) return; // cycle guard
        ConcurrentList<String> entries = raw.get(tagId);
        if (entries == null) return;

        for (String entry : entries) {
            if (entry.startsWith("#"))
                resolve(entry.substring(1), raw, out, visited);
            else if (!out.contains(entry))
                out.add(entry);
        }
    }

}
