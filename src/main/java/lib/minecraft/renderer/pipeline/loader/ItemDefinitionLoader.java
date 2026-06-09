package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.appearance.LayerTint;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * A loader that reads MC 26.1 item definition files from {@code assets/minecraft/items/} and
 * extracts the block model reference for each block item.
 * <p>
 * Vanilla uses these files to determine which block model to render when a block appears as an
 * item (inventory, held item, dropped item). Many blocks have an inventory-specific model that
 * differs from the blockstate model used for in-world rendering - for example, pistons use
 * {@code piston_inventory} (a simple cube with the head on top) rather than
 * {@code template_piston} (which places the head on the north face for in-world placement).
 * <p>
 * Only entries with {@code model.type == "minecraft:model"} and a {@code model.model} reference
 * starting with {@code "minecraft:block/"} are included. Non-block items (sprites, special
 * renderers) are skipped.
 *
 * @see ModelResolver
 * @see PipelineRendererContext
 */
@UtilityClass
public class ItemDefinitionLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads item definitions from {@code packRoot/assets/minecraft/items/} and returns a map
     * from item id ({@code "minecraft:piston"}) to block model id
     * ({@code "minecraft:block/piston_inventory"}).
     *
     * @param packRoot the pack root directory
     * @return the item-to-block-model mapping for block items
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull Path packRoot) {
        return load(Concurrent.newList(packRoot));
    }

    /**
     * Loads item definitions from every asset root in priority order (lowest first, highest
     * last). Per item id, a higher root's definition fully replaces lower roots'.
     *
     * @param assetRoots the ordered asset roots
     * @return the item-to-block-model mapping for block items
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull ConcurrentList<Path> assetRoots) {
        HashMap<String, String> merged = new HashMap<>();
        for (Path root : assetRoots)
            merged.putAll(scanRoot(root));
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Scans one asset root's {@code assets/minecraft/items/} subtree and returns the item-id ->
     * block-model-id map for every block item it carries. Returns an empty map when the items
     * subtree is absent.
     */
    private static @NotNull ConcurrentMap<String, String> scanRoot(@NotNull Path packRoot) {
        Path itemsDir = packRoot.resolve(VanillaSourcePaths.ITEMS_DIR);
        if (!Files.isDirectory(itemsDir)) return Concurrent.newMap();

        // Two-phase walk: enumerate item definition JSON paths serially, then parallelise
        // readString + Gson parse across the FJP common pool. Concurrent.toMap collects per-shard
        // HashMaps and adopts the merged result, so the build pays zero per-entry write-locks.
        List<Path> files;
        try (Stream<Path> stream = Files.walk(itemsDir)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan item definitions in '%s'", itemsDir);
        }

        return files.parallelStream()
            .map(p -> parseItemDef(p, itemsDir))
            .filter(Objects::nonNull)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static @Nullable Map.Entry<String, String> parseItemDef(@NotNull Path p, @NotNull Path itemsDir) {
        String relative = itemsDir.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return null;
        String itemId = VanillaSourcePaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - ".json".length());

        try {
            String content = Files.readString(p);
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            if (json == null || !json.has("model")) return null;

            JsonObject model = json.getAsJsonObject("model");
            if (model == null) return null;
            if (!model.has("type") || !model.has("model")) return null;
            if (!"minecraft:model".equals(model.get("type").getAsString())) return null;

            String modelRef = model.get("model").getAsString();
            return modelRef.startsWith(VanillaSourcePaths.MODEL_BLOCK_ID_PREFIX)
                ? Map.entry(itemId, modelRef)
                : null;
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read item definition '%s'", p);
        } catch (JsonSyntaxException ex) {
            // Resource packs sometimes ship deeply nested or otherwise malformed item definitions
            // (e.g. Hypixel+ player_head.json with 255+ levels of conditional nesting). Skip the
            // entry so the merge falls back to a lower-priority pack's version of this id.
            System.err.printf("Skipping malformed item definition '%s': %s%n", p, ex.getMessage());
            return null;
        }
    }

    /**
     * Loads the per-layer {@link LayerTint tint} list for every item from every asset root in
     * priority order (lowest first, highest last). The {@code tints[]} array index is the layer's
     * {@code tintindex}; vanilla multiplies each resolved colour into the matching {@code layerN}
     * sprite. Items with no tints (the vast majority) are absent from the map.
     *
     * @param assetRoots the ordered asset roots
     * @return the item-id -&gt; per-layer tint list mapping for every tinted item
     */
    public static @NotNull ConcurrentMap<String, List<LayerTint>> loadTints(@NotNull ConcurrentList<Path> assetRoots) {
        HashMap<String, List<LayerTint>> merged = new HashMap<>();
        for (Path root : assetRoots)
            merged.putAll(scanRootTints(root));
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    private static @NotNull ConcurrentMap<String, List<LayerTint>> scanRootTints(@NotNull Path packRoot) {
        Path itemsDir = packRoot.resolve(VanillaSourcePaths.ITEMS_DIR);
        if (!Files.isDirectory(itemsDir)) return Concurrent.newMap();

        List<Path> files;
        try (Stream<Path> stream = Files.walk(itemsDir)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan item definitions in '%s'", itemsDir);
        }

        return files.parallelStream()
            .map(p -> parseItemTints(p, itemsDir))
            .filter(Objects::nonNull)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static @Nullable Map.Entry<String, List<LayerTint>> parseItemTints(@NotNull Path p, @NotNull Path itemsDir) {
        String relative = itemsDir.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return null;
        String itemId = VanillaSourcePaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - ".json".length());

        try {
            JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (json == null || !json.has("model") || !json.get("model").isJsonObject()) return null;

            JsonObject model = descendToTintedModel(json.getAsJsonObject("model"));
            if (model == null || !model.has("tints") || !model.get("tints").isJsonArray()) return null;

            List<LayerTint> tints = new ArrayList<>();
            for (JsonElement element : model.getAsJsonArray("tints")) {
                if (element.isJsonObject()) tints.add(parseTint(element.getAsJsonObject()));
            }
            return tints.isEmpty() ? null : Map.entry(itemId, List.copyOf(tints));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read item definition '%s'", p);
        } catch (JsonSyntaxException ex) {
            System.err.printf("Skipping malformed item definition '%s': %s%n", p, ex.getMessage());
            return null;
        }
    }

    /**
     * Descends {@code select} / {@code condition} / {@code range_dispatch} wrappers to the model
     * that carries the {@code tints} array for an item's default (no-component) render: a
     * {@code select} resolves through its {@code fallback}, a {@code condition} through its
     * {@code on_false} branch (the component-absent default), a {@code range_dispatch} through its
     * {@code fallback}. Returns the first model carrying {@code tints} (or the terminal model when
     * none is found - the caller treats a missing {@code tints} as "untinted"). Bounded to guard
     * against pathological nesting.
     */
    private static @Nullable JsonObject descendToTintedModel(@NotNull JsonObject model) {
        JsonObject current = model;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (current.has("tints")) return current;
            String type = current.has("type") ? current.get("type").getAsString() : "";
            JsonObject next = switch (type) {
                case "minecraft:select", "minecraft:range_dispatch" -> childObject(current, "fallback");
                case "minecraft:condition" -> childObject(current, "on_false");
                default -> null;
            };
            if (next == null) return current;
            current = next;
        }
        return current;
    }

    private static @Nullable JsonObject childObject(@NotNull JsonObject object, @NotNull String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    /**
     * Parses one {@code tints[]} entry into its {@link LayerTint} variant. Source types this
     * renderer cannot resolve dynamically ({@code grass}, {@code map_color},
     * {@code custom_model_data}, ...) become a white {@link LayerTint.Constant} - rendered
     * untinted rather than guessing.
     */
    private static @NotNull LayerTint parseTint(@NotNull JsonObject tint) {
        String type = tint.has("type") ? tint.get("type").getAsString() : "";
        return switch (type) {
            case "minecraft:dye" -> new LayerTint.Dye(toArgb(tint, "default"));
            case "minecraft:potion" -> new LayerTint.Potion(toArgb(tint, "default"));
            case "minecraft:firework" -> new LayerTint.Firework(toArgb(tint, "default"));
            case "minecraft:constant" -> new LayerTint.Constant(toArgb(tint, "value"));
            default -> new LayerTint.Constant(0xFFFFFFFF);
        };
    }

    /**
     * Reads an RGB tint colour from {@code tint[key]} and forces opaque alpha. The JSON values are
     * 24-bit RGB ({@code -1} / negative ints already carry {@code 0xFF} in the top byte; small
     * positive ints do not), so masking to 24 bits and OR-ing {@code 0xFF000000} normalises every
     * source to the ARGB the multiply-tint blend expects. Defaults to white when the key is absent.
     */
    private static int toArgb(@NotNull JsonObject tint, @NotNull String key) {
        if (!tint.has(key)) return 0xFFFFFFFF;
        return 0xFF000000 | (tint.get(key).getAsInt() & 0xFFFFFF);
    }

}
