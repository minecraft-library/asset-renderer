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
 * Loads bundled entity model definitions from two paired classpath resources:
 * {@code /lib/minecraft/renderer/entity_models.json} holds per-entity metadata
 * (geometry reference, texture, armor type, optional {@code variant_of} back-link) and
 * {@code /lib/minecraft/renderer/entity_geometry.json} holds the deduplicated bone/cube trees.
 * <p>
 * Bedrock ships one geometry file per <i>base</i> model but the Java {@code EntityType} registry
 * has many entities sharing one geometry (e.g. {@code horse}, {@code donkey}, {@code mule},
 * {@code skeleton_horse}, {@code zombie_horse} all reference {@code geometry.horse}). Splitting
 * the data into two files lets each entity metadata row be a few hundred bytes while the
 * potentially-multi-kilobyte bone tree is stored exactly once.
 * <p>
 * At runtime the loader joins them back together - each entity's {@code geometry_ref} is
 * resolved against the geometry file and packaged into a combined {@link EntityDefinition}, so
 * callers see the same API as before the split.
 * <p>
 * Callers can register additional entities at runtime via
 * {@link PipelineRendererContext#registerEntity(String, EntityModelData, Optional)}.
 *
 * @see ToolingEntityModels.Parser
 * @see PipelineRendererContext
 */
@UtilityClass
public class EntityModelLoader {

    private static final @NotNull String MODELS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * An entity definition loaded from the bundled resources.
     *
     * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
     * @param textureId the default texture reference, or empty if not specified
     */
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureId
    ) {}

    /**
     * Loads all bundled entity model definitions, joining the per-entity metadata against the
     * deduplicated geometry table.
     *
     * @return a map of entity id to definition
     * @throws AssetPipelineException if either resource is missing or cannot be parsed, or any
     *     entity metadata row references a geometry id absent from the geometry table
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        Map<String, EntityModelData> geometries = loadGeometries();
        JsonObject entities = loadEntitiesBlock();

        ConcurrentMap<String, EntityDefinition> definitions = Concurrent.newMap();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();

            if (!entityJson.has("geometry_ref"))
                throw new AssetPipelineException(
                    "Entity '%s' in '%s' has no geometry_ref", entityId, MODELS_RESOURCE_PATH
                );
            String geometryRef = entityJson.get("geometry_ref").getAsString();
            EntityModelData model = geometries.get(geometryRef);
            if (model == null)
                throw new AssetPipelineException(
                    "Entity '%s' references geometry '%s' which is not present in '%s'",
                    entityId, geometryRef, GEOMETRY_RESOURCE_PATH
                );

            Optional<String> textureId = entityJson.has("texture_id")
                ? Optional.of(entityJson.get("texture_id").getAsString())
                : Optional.empty();

            definitions.put(entityId, new EntityDefinition(model, textureId));
        }

        return definitions;
    }

    /**
     * Parses {@code entity_geometry.json} into a map from geometry id to the parsed
     * {@link EntityModelData} bone tree.
     */
    private static @NotNull Map<String, EntityModelData> loadGeometries() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(GEOMETRY_RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Entity geometry resource '%s' not found on the classpath", GEOMETRY_RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("geometries"))
                throw new AssetPipelineException("Entity geometry resource '%s' has no 'geometries' object", GEOMETRY_RESOURCE_PATH);

            JsonObject geometriesJson = root.getAsJsonObject("geometries");
            Map<String, EntityModelData> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : geometriesJson.entrySet()) {
                EntityModelData model = GSON.fromJson(entry.getValue(), EntityModelData.class);
                out.put(entry.getKey(), model);
            }
            return out;
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load entity geometry resource '%s'", GEOMETRY_RESOURCE_PATH);
        }
    }

    /**
     * Parses {@code entity_models.json} and returns its {@code entities} object for iteration.
     */
    private static @NotNull JsonObject loadEntitiesBlock() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Entity models resource '%s' not found on the classpath", MODELS_RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities"))
                throw new AssetPipelineException("Entity models resource '%s' has no 'entities' object", MODELS_RESOURCE_PATH);

            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new AssetPipelineException(ex, "Failed to load entity models resource '%s'", MODELS_RESOURCE_PATH);
        }
    }

}
