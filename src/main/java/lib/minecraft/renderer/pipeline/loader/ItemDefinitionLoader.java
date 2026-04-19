package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.VanillaPaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        Path itemsDir = packRoot.resolve(VanillaPaths.ITEMS_DIR);
        ConcurrentMap<String, String> result = Concurrent.newMap();
        if (!Files.isDirectory(itemsDir)) return result;

        // Two-phase walk: enumerate item definition JSON paths serially, then parallelise
        // readString + Gson parse across the FJP common pool. Each file's parse is fully
        // independent; result is a ConcurrentMap.
        List<Path> files;
        try (Stream<Path> stream = Files.walk(itemsDir)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .collect(Collectors.toList());
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to scan item definitions in '%s'", itemsDir);
        }

        files.parallelStream().forEach(p -> {
            String relative = itemsDir.relativize(p).toString().replace('\\', '/');
            if (!relative.endsWith(".json")) return;
            String itemId = VanillaPaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - ".json".length());

            try {
                String content = Files.readString(p);
                JsonObject json = GSON.fromJson(content, JsonObject.class);
                if (json == null || !json.has("model")) return;

                JsonObject model = json.getAsJsonObject("model");
                if (model == null) return;
                if (!model.has("type") || !model.has("model")) return;
                if (!"minecraft:model".equals(model.get("type").getAsString())) return;

                String modelRef = model.get("model").getAsString();
                if (modelRef.startsWith(VanillaPaths.MODEL_BLOCK_ID_PREFIX))
                    result.put(itemId, modelRef);
            } catch (IOException | JsonSyntaxException ex) {
                throw new AssetPipelineException(ex, "Failed to parse item definition '%s'", p);
            }
        });

        return result;
    }

}
