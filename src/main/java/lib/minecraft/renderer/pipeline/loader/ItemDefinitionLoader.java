package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
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
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * A loader that reads MC 26.1 item definition files from every pack's {@code assets/<namespace>/items/}
 * subtree and extracts the block model reference for each block item.
 * <p>
 * Vanilla uses these files to determine which block model to render when a block appears as an
 * item (inventory, held item, dropped item). Many blocks have an inventory-specific model that
 * differs from the blockstate model used for in-world rendering - for example, pistons use
 * {@code piston_inventory} (a simple cube with the head on top) rather than
 * {@code template_piston} (which places the head on the north face for in-world placement).
 * <p>
 * Only entries with {@code model.type == "minecraft:model"} and a {@code model.model} reference
 * that is a block model ({@link VanillaSourcePaths#isBlockModelRef}, namespace-agnostic so a pack's
 * {@code <ns>:block/...} ref carries across) are included. Non-block items (sprites, special
 * renderers) are skipped. The full item-definition dispatch tree is unconsumed here - the item-tree
 * walker lands in a later phase; this loader remains the block-item projection.
 * <p>
 * The second entry point, {@link #loadTints(PackStack)}, reads the same item definition files for
 * their {@code model.tints[]} array - the per-layer {@link LayerTint} rules (dye / potion / firework
 * / constant colours) the renderer multiplies into each {@code layerN} sprite. Both scans merge over
 * the pack stack ascending (higher priority winning), with each pack's {@code filter.block} erasing
 * matching lower-pack ids and item ids namespace-qualified to their owning namespace. A vanilla-only
 * stack scans exactly {@code assets/minecraft/items} and merges identically to the former walk.
 *
 * @see ModelResolver
 * @see PipelineRendererContext
 */
@UtilityClass
public class ItemDefinitionLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads item definitions across the whole pack stack and returns a map from item id
     * ({@code "minecraft:piston"}) to block model id ({@code "minecraft:block/piston_inventory"}).
     * Packs are visited ascending; before each pack's rows merge in, its {@code filter.block}
     * patterns erase matching accumulated ids, then every {@code (root x namespace)} items subtree it
     * owns is scanned. Per item id, a higher pack's definition fully replaces lower packs'.
     *
     * @param stack the resolved pack stack
     * @return the item-to-block-model mapping for block items
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull PackStack stack) {
        HashMap<String, String> merged = new HashMap<>();
        forEachItemsDir(stack, merged, (itemsDir, namespace) -> merged.putAll(scanRoot(itemsDir, namespace)));
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Scans one {@code (root x namespace)} items directory and returns the item-id -> block-model-id
     * map for every block item it carries. Returns an empty map when the items subtree is absent.
     */
    private static @NotNull ConcurrentMap<String, String> scanRoot(@NotNull Path itemsDir, @NotNull String namespace) {
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
            .map(p -> parseItemDef(p, itemsDir, namespace))
            .flatMap(Optional::stream)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Parses one item definition file into an {@code itemId -> blockModelId} entry, or {@code null}
     * when the file is not a block item ({@code model.type != "minecraft:model"}, no
     * {@code model.model} reference, or a reference that is not a block model per
     * {@link VanillaSourcePaths#isBlockModelRef}). The item id is derived from the path relative to
     * {@code itemsDir} with the {@code .json} suffix stripped and the owning {@code <namespace>:}
     * prepended. Malformed JSON is skipped (logged) so a bad entry in one pack falls back to a
     * lower-priority pack's version.
     *
     * @param p the item definition file
     * @param itemsDir the {@code items/} directory the id is relativised against
     * @param namespace the owning namespace, prepended to the derived item id
     * @return the item-to-block-model entry, or empty for non-block or malformed entries
     * @throws PipelineException when the file cannot be read
     */
    private static @NotNull Optional<Map.Entry<String, String>> parseItemDef(@NotNull Path p, @NotNull Path itemsDir, @NotNull String namespace) {
        String relative = itemsDir.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return Optional.empty();
        String itemId = VanillaSourcePaths.namespacePrefix(namespace) + relative.substring(0, relative.length() - ".json".length());

        try {
            String content = Files.readString(p);
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            if (json == null || !json.has("model")) return Optional.empty();

            JsonObject model = json.getAsJsonObject("model");
            if (model == null) return Optional.empty();
            if (!model.has("type") || !model.has("model")) return Optional.empty();
            if (!"minecraft:model".equals(model.get("type").getAsString())) return Optional.empty();

            String modelRef = model.get("model").getAsString();
            return VanillaSourcePaths.isBlockModelRef(modelRef)
                ? Optional.of(Map.entry(itemId, modelRef))
                : Optional.empty();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read item definition '%s'", p);
        } catch (JsonSyntaxException ex) {
            // Resource packs sometimes ship deeply nested or otherwise malformed item definitions
            // (e.g. Hypixel+ player_head.json with 255+ levels of conditional nesting). Skip the
            // entry so the merge falls back to a lower-priority pack's version of this id.
            System.err.printf("Skipping malformed item definition '%s': %s%n", p, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Iterates every pack's {@code (root x namespace)} items directory in ascending priority,
     * applying each pack's {@code filter.block} to {@code filterTarget} before its own directories
     * are visited. The single shared walk both {@link #load(PackStack)} and
     * {@link #loadTints(PackStack)} route through.
     *
     * @param stack the resolved pack stack
     * @param filterTarget the accumulating merge map whose keys a pack's {@code filter.block} erases
     * @param consumer receives each items directory and its owning namespace
     */
    private static void forEachItemsDir(@NotNull PackStack stack, @NotNull Map<String, ?> filterTarget, @NotNull BiConsumer<Path, String> consumer) {
        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> filterTarget.keySet().removeIf(id -> section.hides(ResourceId.parse(id))));
            if (!(pack.container() instanceof PackContainer.Directory dir)) continue;

            for (PackRoot root : pack.roots())
                for (String namespace : pack.namespaces()) {
                    Path itemsDir = dir.root().resolve(root.prefix()).resolve(VanillaSourcePaths.assetSubdir(namespace, VanillaSourcePaths.ITEMS_SUBDIR));
                    consumer.accept(itemsDir, namespace);
                }
        }
    }

    /**
     * Loads the per-layer {@link LayerTint tint} list for every item across the whole pack stack.
     * Packs are visited ascending; before each pack's rows merge in, its {@code filter.block}
     * patterns erase matching accumulated ids, then every {@code (root x namespace)} items subtree it
     * owns is scanned. The {@code tints[]} array index is the layer's {@code tintindex}; vanilla
     * multiplies each resolved colour into the matching {@code layerN} sprite. Items with no tints
     * (the vast majority) are absent from the map.
     *
     * @param stack the resolved pack stack
     * @return the item-id -&gt; per-layer tint list mapping for every tinted item
     */
    public static @NotNull ConcurrentMap<String, List<LayerTint>> loadTints(@NotNull PackStack stack) {
        HashMap<String, List<LayerTint>> merged = new HashMap<>();
        forEachItemsDir(stack, merged, (itemsDir, namespace) -> merged.putAll(scanRootTints(itemsDir, namespace)));
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Scans one {@code (root x namespace)} items directory and returns the item-id -> per-layer
     * {@link LayerTint} list map for every tinted item it carries. Same two-phase serial-walk /
     * parallel-parse strategy as {@link #scanRoot(Path, String)}. Returns an empty map when the items
     * subtree is absent.
     */
    private static @NotNull ConcurrentMap<String, List<LayerTint>> scanRootTints(@NotNull Path itemsDir, @NotNull String namespace) {
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
            .map(p -> parseItemTints(p, itemsDir, namespace))
            .flatMap(Optional::stream)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Parses one item definition file into an {@code itemId -> }per-layer-{@link LayerTint} entry,
     * or {@code null} when the item carries no resolvable {@code tints} array. Descends the
     * {@code select} / {@code condition} / {@code range_dispatch} wrappers via
     * {@link #descendToTintedModel(JsonObject)} to the default (no-component) model, then maps each
     * {@code tints[]} object through {@link #parseTint(JsonObject)}. Malformed JSON is skipped
     * (logged) so a bad entry falls back to a lower-priority pack.
     *
     * @param p the item definition file
     * @param itemsDir the {@code items/} directory the id is relativised against
     * @param namespace the owning namespace, prepended to the derived item id
     * @return the item-to-tint-list entry, or empty when the item declares no tints
     * @throws PipelineException when the file cannot be read
     */
    private static @NotNull Optional<Map.Entry<String, List<LayerTint>>> parseItemTints(@NotNull Path p, @NotNull Path itemsDir, @NotNull String namespace) {
        String relative = itemsDir.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return Optional.empty();
        String itemId = VanillaSourcePaths.namespacePrefix(namespace) + relative.substring(0, relative.length() - ".json".length());

        try {
            JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (json == null || !json.has("model") || !json.get("model").isJsonObject()) return Optional.empty();

            JsonObject model = descendToTintedModel(json.getAsJsonObject("model"));
            if (model == null || !model.has("tints") || !model.get("tints").isJsonArray()) return Optional.empty();

            List<LayerTint> tints = new ArrayList<>();
            for (JsonElement element : model.getAsJsonArray("tints")) {
                if (element.isJsonObject()) tints.add(parseTint(element.getAsJsonObject()));
            }
            return tints.isEmpty() ? Optional.empty() : Optional.of(Map.entry(itemId, List.copyOf(tints)));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read item definition '%s'", p);
        } catch (JsonSyntaxException ex) {
            System.err.printf("Skipping malformed item definition '%s': %s%n", p, ex.getMessage());
            return Optional.empty();
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

    /**
     * Returns {@code object[key]} as a {@link JsonObject}, or {@code null} when the key is absent or
     * not an object.
     */
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
