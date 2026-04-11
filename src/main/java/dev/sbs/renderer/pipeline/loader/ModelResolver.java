package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        ConcurrentMap<String, JsonObject> raw = scanJsonFiles(packRoot.resolve("assets/minecraft/models/block"), "minecraft:block/");
        ConcurrentMap<String, BlockModelData> resolved = Concurrent.newMap();
        for (Map.Entry<String, JsonObject> entry : raw.entrySet().stream().toList()) {
            JsonObject merged = mergeParentChain(entry.getValue(), raw, packRoot, "block");
            resolved.put(entry.getKey(), GSON.fromJson(merged, BlockModelData.class));
        }
        return resolved;
    }

    /**
     * Loads every item model JSON under {@code packRoot/assets/minecraft/models/item} and
     * returns them keyed by resolved model id ({@code "minecraft:item/diamond_sword"}).
     *
     * @param packRoot the pack root directory
     * @return a map of model id to resolved item model data
     */
    public static @NotNull ConcurrentMap<String, ItemModelData> loadItemModels(@NotNull Path packRoot) {
        ConcurrentMap<String, JsonObject> raw = scanJsonFiles(packRoot.resolve("assets/minecraft/models/item"), "minecraft:item/");
        ConcurrentMap<String, ItemModelData> resolved = Concurrent.newMap();
        for (Map.Entry<String, JsonObject> entry : raw.entrySet().stream().toList()) {
            JsonObject merged = mergeParentChain(entry.getValue(), raw, packRoot, "item");
            resolved.put(entry.getKey(), GSON.fromJson(merged, ItemModelData.class));
        }
        return resolved;
    }

    private static @NotNull ConcurrentMap<String, JsonObject> scanJsonFiles(@NotNull Path directory, @NotNull String idPrefix) {
        ConcurrentMap<String, JsonObject> result = Concurrent.newMap();
        if (!Files.isDirectory(directory)) return result;

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    String relative = directory.relativize(p).toString().replace('\\', '/');
                    if (!relative.endsWith(".json")) return;
                    String id = idPrefix + relative.substring(0, relative.length() - ".json".length());
                    try {
                        String content = Files.readString(p);
                        JsonObject json = GSON.fromJson(content, JsonObject.class);
                        if (json != null) result.put(id, json);
                    } catch (IOException | JsonSyntaxException ex) {
                        throw new AssetPipelineException(ex, "Failed to parse model '%s'", p);
                    }
                });
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to scan model directory '%s'", directory);
        }
        return result;
    }

    /**
     * Recursively merges a model's parent chain, returning a new JSON object whose textures and
     * elements inherit from every ancestor. Cycle detection is not needed - vanilla chains are
     * acyclic and shallow (at most 3 deep).
     */
    private static @NotNull JsonObject mergeParentChain(
        @NotNull JsonObject model,
        @NotNull ConcurrentMap<String, JsonObject> raw,
        @NotNull Path packRoot,
        @NotNull String kindPrefix
    ) {
        Optional<String> parentId = Optional.ofNullable(model.get("parent")).map(e -> e.getAsString());
        if (parentId.isEmpty()) return model;

        String fqParent = parentId.get().contains(":") ? parentId.get() : "minecraft:" + parentId.get();
        JsonObject parentJson = raw.get(fqParent);
        if (parentJson == null) {
            // Parent lives outside this tree (e.g. minecraft:builtin/generated) - keep the reference
            // and stop walking.
            return model;
        }
        JsonObject merged = mergeParentChain(parentJson, raw, packRoot, kindPrefix).deepCopy();

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

        return merged;
    }

}
