package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * A loader that reads bundled entity model definitions from the
 * {@code /lib/minecraft/renderer/entity_models.json} classpath resource.
 * <p>
 * Each entry maps an entity id to its geometry ({@link EntityModelData}) and an optional
 * default texture reference. Vanilla Minecraft does not ship entity model JSON files - entity
 * geometry is hardcoded in the client source. The bundled resource is a hand-curated snapshot
 * of the bone/cube trees for common entities, verified against the MC 26.1 deobfuscated client
 * source. The JSON is produced offline by {@link ToolingEntityModels.Parser} from Bedrock Edition
 * {@code .geo.json} files and checked into the repository.
 * <p>
 * Callers can register additional entities at runtime via
 * {@link PipelineRendererContext#registerEntity(String, EntityModelData, Optional)}.
 *
 * @see ToolingEntityModels.Parser
 * @see PipelineRendererContext
 */
@UtilityClass
public class EntityModelLoader {

    private static final @NotNull String RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * An entity definition loaded from the bundled resource.
     *
     * @param model the parsed bone/cube tree
     * @param textureId the default texture reference, or empty if not specified
     */
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureId
    ) {}

    /**
     * Loads all bundled entity model definitions.
     *
     * @return a map of entity id to definition
     * @throws AssetPipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        ConcurrentMap<String, EntityDefinition> definitions = Concurrent.newMap();

        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Entity models resource '%s' not found on the classpath", RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities"))
                throw new AssetPipelineException("Entity models resource '%s' has no 'entities' object", RESOURCE_PATH);

            JsonObject entities = root.getAsJsonObject("entities");
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                String entityId = entry.getKey();
                JsonObject entityJson = entry.getValue().getAsJsonObject();

                Optional<String> textureId = entityJson.has("texture_id")
                    ? Optional.of(entityJson.get("texture_id").getAsString())
                    : Optional.empty();

                EntityModelData model = GSON.fromJson(entityJson, EntityModelData.class);
                definitions.put(entityId, new EntityDefinition(model, textureId));
            }
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load entity models resource '%s'", RESOURCE_PATH);
        }

        return definitions;
    }

}
