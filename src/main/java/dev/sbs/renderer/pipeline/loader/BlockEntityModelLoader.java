package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.model.EntityModelData;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * A loader that reads the bundled block entity resource from
 * {@code /renderer/block_entity_models.json}. The resource carries both the entity model
 * geometry (bone/cube trees for chests, signs, beds, etc.) and the block-to-model mappings
 * that tell the block renderer which entity model and texture to use for element-less blocks.
 * <p>
 * Block entity models are hand-authored because neither Java nor Bedrock Edition ships them
 * as structured JSON - the geometry is hardcoded in the client. The UV coordinates and bone
 * layout are verified against the MC 26.1 deobfuscated client source.
 *
 * @see Block.EntityMapping
 * @see EntityModelData
 */
@UtilityClass
public class BlockEntityModelLoader {

    private static final @NotNull String RESOURCE_PATH = "/renderer/block_entity_models.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled block entity resource and returns both the parsed entity models and
     * the block-to-entity mappings.
     *
     * @return the parsed result containing entity models and block mappings
     * @throws AssetPipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull LoadResult load() {
        try (InputStream stream = BlockEntityModelLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Block entity models resource '%s' not found on the classpath", RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null)
                throw new AssetPipelineException("Block entity models resource '%s' is empty", RESOURCE_PATH);

            ConcurrentMap<String, Entity> entities = Concurrent.newMap();
            if (root.has("models") && root.get("models").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("models").entrySet()) {
                    String entityId = entry.getKey();
                    EntityModelData model = GSON.fromJson(entry.getValue(), EntityModelData.class);
                    String name = entityId.contains(":") ? entityId.substring(entityId.indexOf(':') + 1) : entityId;
                    entities.put(entityId, new Entity(entityId, "minecraft", name, model, Optional.empty()));
                }
            }

            ConcurrentMap<String, Block.EntityMapping> mappings = Concurrent.newMap();
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("mappings").entrySet())
                    mappings.put(entry.getKey(), GSON.fromJson(entry.getValue(), Block.EntityMapping.class));
            }

            return new LoadResult(entities, mappings);
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load block entity models resource '%s'", RESOURCE_PATH);
        }
    }

    /**
     * The result of loading the block entity models resource, containing both the parsed
     * entity models and the block-to-entity mappings.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class LoadResult {

        private final @NotNull ConcurrentMap<String, Entity> entities;
        private final @NotNull ConcurrentMap<String, Block.EntityMapping> mappings;

    }

}
