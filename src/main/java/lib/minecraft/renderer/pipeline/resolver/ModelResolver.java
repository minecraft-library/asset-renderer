package lib.minecraft.renderer.pipeline.resolver;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.VanillaPaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A loader and resolver that walks a pack's {@code assets/minecraft/models/} subtree, parses
 * every JSON file into {@link BlockModelData} or {@link ItemModelData}, and eagerly merges
 * parent chains so the resulting DTOs carry everything needed for rendering without further
 * resolution at render time.
 * <p>
 * Parent chain merging is deep: child textures and elements win on conflicting keys, and the
 * merged result records the original parent id in its {@code parent} field for introspection.
 * Vanilla chains are acyclic and shallow (at most 3 deep), so no cycle detection is needed.
 *
 * @see BlockModelData
 * @see ItemModelData
 * @see PipelineRendererContext
 */
@UtilityClass
public class ModelResolver {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads every block model JSON under {@code packRoot/assets/minecraft/models/block} and
     * returns them keyed by resolved model id ({@code "minecraft:block/grass_block"}).
     *
     * @param packRoot the pack root directory
     * @return a map of model id to resolved block model data
     */
    public static @NotNull ConcurrentMap<String, BlockModelData> loadBlockModels(@NotNull Path packRoot) {
        return loadBlockModels(Concurrent.newList(packRoot));
    }

    /**
     * Loads block models from every asset root in priority order (lowest first, highest last).
     * Raw JSON merges later-wins so an overlay or higher-priority pack supersedes earlier roots
     * before parent-chain inheritance runs - this is what lets a Hypixel+ child model still
     * inherit from a vanilla parent that lives only in the base pack.
     *
     * @param assetRoots the ordered asset roots
     * @return a map of model id to resolved block model data
     */
    public static @NotNull ConcurrentMap<String, BlockModelData> loadBlockModels(@NotNull ConcurrentList<Path> assetRoots) {
        ConcurrentMap<String, JsonObject> raw = mergeRawAcrossRoots(assetRoots, VanillaPaths.MODEL_BLOCK_DIR, VanillaPaths.MODEL_BLOCK_ID_PREFIX);
        return resolveTypedModels(raw, BlockModelData.class, "block");
    }

    /**
     * Loads every item model JSON under {@code packRoot/assets/minecraft/models/item} and
     * returns them keyed by resolved model id ({@code "minecraft:item/diamond_sword"}).
     *
     * @param packRoot the pack root directory
     * @return a map of model id to resolved item model data
     */
    public static @NotNull ConcurrentMap<String, ItemModelData> loadItemModels(@NotNull Path packRoot) {
        return loadItemModels(Concurrent.newList(packRoot));
    }

    /**
     * Loads item models from every asset root in priority order. See
     * {@link #loadBlockModels(ConcurrentList)} for merge semantics.
     *
     * @param assetRoots the ordered asset roots
     * @return a map of model id to resolved item model data
     */
    public static @NotNull ConcurrentMap<String, ItemModelData> loadItemModels(@NotNull ConcurrentList<Path> assetRoots) {
        ConcurrentMap<String, JsonObject> raw = mergeRawAcrossRoots(assetRoots, VanillaPaths.MODEL_ITEM_DIR, VanillaPaths.MODEL_ITEM_ID_PREFIX);
        return resolveTypedModels(raw, ItemModelData.class, "item");
    }

    /**
     * Walks every asset root, scans its model subtree into a raw JSON map, then merges across
     * roots with later-wins semantics on the resolved model id.
     */
    private static @NotNull ConcurrentMap<String, JsonObject> mergeRawAcrossRoots(
        @NotNull ConcurrentList<Path> assetRoots,
        @NotNull String modelSubdir,
        @NotNull String idPrefix
    ) {
        HashMap<String, JsonObject> merged = new HashMap<>();
        for (Path root : assetRoots)
            merged.putAll(scanJsonFiles(root.resolve(modelSubdir), idPrefix));
        return Concurrent.adoptMap(merged);
    }

    /**
     * Resolves a raw model map into typed DTOs by walking each entry's parent chain (against the
     * fully merged raw map) and Gson-reparsing the merged JSON. Parallel - the read-only chain
     * walk and thread-safe Gson scale across the FJP common pool.
     */
    private static <T> @NotNull ConcurrentMap<String, T> resolveTypedModels(
        @NotNull ConcurrentMap<String, JsonObject> raw,
        @NotNull Class<T> dtoClass,
        @NotNull String kindPrefix
    ) {
        return raw.parallelStream().collect(Concurrent.toMap(
            Map.Entry::getKey,
            entry -> GSON.fromJson(mergeParentChain(entry.getValue(), raw, kindPrefix), dtoClass)
        )).toUnmodifiable();
    }

    private static @NotNull ConcurrentMap<String, JsonObject> scanJsonFiles(@NotNull Path directory, @NotNull String idPrefix) {
        if (!Files.isDirectory(directory)) return Concurrent.newMap();

        // Two-phase walk: collect paths serially (Files.walk spliterators don't split well for
        // parallel work), then parallelise readString + Gson parse across the FJP common pool.
        // Concurrent.toMap collects per-shard HashMaps lock-free and adopts the merged result.
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan model directory '%s'", directory);
        }

        return files.parallelStream()
            .map(p -> parseModelFile(p, directory, idPrefix))
            .filter(Objects::nonNull)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static @Nullable Map.Entry<String, JsonObject> parseModelFile(@NotNull Path p, @NotNull Path directory, @NotNull String idPrefix) {
        String relative = directory.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return null;
        String id = idPrefix + relative.substring(0, relative.length() - ".json".length());
        try {
            String content = Files.readString(p);
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            return json == null ? null : Map.entry(id, json);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read model '%s'", p);
        } catch (JsonSyntaxException ex) {
            // Resource packs occasionally ship malformed or pathologically-nested model JSON. Skip
            // so the merge falls back to a lower-priority pack's version.
            System.err.printf("Skipping malformed model '%s': %s%n", p, ex.getMessage());
            return null;
        }
    }

    /**
     * Recursively merges a model's parent chain, returning a new JSON object whose textures and
     * elements inherit from every ancestor. Cycle detection is not needed - vanilla chains are
     * acyclic and shallow (at most 3 deep). The {@code kindPrefix} is preserved for future
     * use in fully-qualifying ambiguous parent ids; today every parent reference already carries
     * its kind segment ({@code block/} or {@code item/}).
     */
    private static @NotNull JsonObject mergeParentChain(
        @NotNull JsonObject model,
        @NotNull ConcurrentMap<String, JsonObject> raw,
        @NotNull String kindPrefix
    ) {
        Optional<String> parentId = Optional.ofNullable(model.get("parent")).map(JsonElement::getAsString);
        if (parentId.isEmpty()) return model;

        String fqParent = parentId.get().contains(":") ? parentId.get() : VanillaPaths.MINECRAFT_NAMESPACE + parentId.get();
        JsonObject parentJson = raw.get(fqParent);
        if (parentJson == null) {
            // Parent lives outside this tree (e.g. minecraft:builtin/generated) - keep the reference
            // and stop walking.
            return model;
        }
        JsonObject merged = mergeParentChain(parentJson, raw, kindPrefix).deepCopy();

        // Child values override parent for keys present on both sides.
        for (String key : model.keySet()) {
            if (key.equals("textures") && merged.has("textures") && model.get("textures").isJsonObject()) {
                JsonObject mergedTextures = merged.getAsJsonObject("textures").deepCopy();
                JsonObject childTextures = model.getAsJsonObject("textures");
                for (String tKey : childTextures.keySet())
                    mergedTextures.add(tKey, childTextures.get(tKey));
                merged.add("textures", mergedTextures);
            } else {
                merged.add(key, model.get(key));
            }
        }

        normalizeTextureEntries(merged);
        return merged;
    }

    /**
     * Replaces object-valued texture entries with their {@code sprite} string. MC 26.1 uses
     * {@code {"force_translucent": true, "sprite": "minecraft:block/glass"}} for translucent
     * blocks; the renderer only needs the sprite id.
     */
    private static void normalizeTextureEntries(@NotNull JsonObject model) {
        if (!model.has("textures") || !model.get("textures").isJsonObject()) return;

        JsonObject textures = model.getAsJsonObject("textures");
        HashMap<String, String> normalized = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject() && value.getAsJsonObject().has("sprite"))
                normalized.put(entry.getKey(), value.getAsJsonObject().get("sprite").getAsString());
        }

        for (Map.Entry<String, String> entry : normalized.entrySet())
            textures.addProperty(entry.getKey(), entry.getValue());
    }

}
