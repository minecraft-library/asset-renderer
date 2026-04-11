package dev.sbs.renderer.pipeline.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sbs.renderer.model.asset.EntityModelData;
import dev.sbs.renderer.pipeline.loader.EntityModelLoader;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * A parser that converts Minecraft Bedrock Edition {@code .geo.json} entity model files into
 * {@link EntityModelData} instances.
 * <p>
 * Bedrock model files come in two format versions:
 * <ul>
 * <li><b>1.8.0</b> (legacy): top-level keys like {@code "geometry.zombie.v1.8"} containing the
 * model directly.</li>
 * <li><b>1.12.0+</b> (modern): a {@code "minecraft:geometry"} array of objects, each with a
 * {@code "description"} block.</li>
 * </ul>
 * Both are handled transparently. Bones marked {@code neverRender: true} or lacking cubes are
 * excluded from the output. The bone {@code mirror} flag is promoted to each cube when present
 * at the bone level (Bedrock convention) since {@link EntityModelData.Cube} stores mirror
 * per-cube. The parsed output feeds into the bundled {@code /renderer/entity_models.json}
 * resource via the {@code generateEntityModels} Gradle task.
 *
 * @see EntityModelLoader
 * @see EntityModelData
 */
@UtilityClass
public class EntityModelParser {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** Standard humanoid bone names that indicate full armor wearability. */
    private static final @NotNull Set<String> HUMANOID_BONES = Set.of(
        "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg",
        "right_arm", "left_arm", "right_leg", "left_leg"
    );

    /**
     * A parsed entity model definition with metadata.
     *
     * @param identifier the Bedrock geometry identifier (e.g. {@code "geometry.zombie"})
     * @param model the parsed bone/cube tree
     * @param armorType the inferred armor wearability ({@code "humanoid"} or {@code "none"})
     */
    public record ParsedEntity(
        @NotNull String identifier,
        @NotNull EntityModelData model,
        @NotNull String armorType
    ) {}

    /**
     * Parses all geometry definitions from a single {@code .geo.json} file's JSON content.
     * A single file may contain multiple geometry definitions (e.g. adult and baby variants).
     *
     * @param json the raw JSON string
     * @return the parsed entity definitions
     */
    public static @NotNull ConcurrentList<ParsedEntity> parse(@NotNull String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root == null) return Concurrent.newList();

        ConcurrentList<ParsedEntity> results = Concurrent.newList();

        if (root.has("minecraft:geometry"))
            parseModernFormat(root.getAsJsonArray("minecraft:geometry"), results);
        else
            parseLegacyFormat(root, results);

        return results;
    }

    /**
     * Modern format (1.12.0+): {@code "minecraft:geometry": [{"description": {...}, "bones": [...]}]}
     */
    private static void parseModernFormat(
        @NotNull JsonArray geometries,
        @NotNull ConcurrentList<ParsedEntity> results
    ) {
        for (JsonElement element : geometries) {
            JsonObject geometry = element.getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            if (description == null) continue;

            String identifier = description.has("identifier")
                ? description.get("identifier").getAsString() : "unknown";
            int textureWidth = description.has("texture_width")
                ? description.get("texture_width").getAsInt() : 64;
            int textureHeight = description.has("texture_height")
                ? description.get("texture_height").getAsInt() : 64;

            JsonArray bonesArray = geometry.has("bones") ? geometry.getAsJsonArray("bones") : null;
            if (bonesArray == null) continue;

            EntityModelData model = buildModel(bonesArray, textureWidth, textureHeight);
            String armorType = inferArmorType(model.getBones().keySet());
            results.add(new ParsedEntity(identifier, model, armorType));
        }
    }

    /**
     * Legacy format (1.8.0): top-level keys like {@code "geometry.zombie.v1.8": {"bones": [...]}}
     */
    private static void parseLegacyFormat(
        @NotNull JsonObject root,
        @NotNull ConcurrentList<ParsedEntity> results
    ) {
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.equals("format_version")) continue;
            if (!entry.getValue().isJsonObject()) continue;

            // Skip inherited geometry definitions (key contains ":" indicating parent reference)
            if (key.contains(":")) continue;

            JsonObject geometry = entry.getValue().getAsJsonObject();
            int textureWidth = geometry.has("texturewidth")
                ? geometry.get("texturewidth").getAsInt() : 64;
            int textureHeight = geometry.has("textureheight")
                ? geometry.get("textureheight").getAsInt() : 64;

            JsonArray bonesArray = geometry.has("bones") ? geometry.getAsJsonArray("bones") : null;
            if (bonesArray == null) continue;

            EntityModelData model = buildModel(bonesArray, textureWidth, textureHeight);
            String armorType = inferArmorType(model.getBones().keySet());
            results.add(new ParsedEntity(key, model, armorType));
        }
    }

    /**
     * Converts a Bedrock bones array into an {@link EntityModelData} with a flat bone map.
     */
    private static @NotNull EntityModelData buildModel(
        @NotNull JsonArray bonesArray,
        int textureWidth,
        int textureHeight
    ) {
        ConcurrentMap<String, EntityModelData.Bone> bones = Concurrent.newMap();

        for (JsonElement boneElement : bonesArray) {
            JsonObject boneJson = boneElement.getAsJsonObject();
            String name = boneJson.has("name") ? boneJson.get("name").getAsString() : "unnamed";

            // Skip neverRender bones and bones without cubes
            if (boneJson.has("neverRender") && boneJson.get("neverRender").getAsBoolean())
                continue;
            if (!boneJson.has("cubes"))
                continue;

            boolean boneMirror = boneJson.has("mirror") && boneJson.get("mirror").getAsBoolean();

            float[] pivot = readFloatArray(boneJson, "pivot", new float[]{ 0, 0, 0 });
            float[] rotation = readFloatArray(boneJson, "rotation", new float[]{ 0, 0, 0 });

            JsonArray cubesArray = boneJson.getAsJsonArray("cubes");
            ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();

            for (JsonElement cubeElement : cubesArray) {
                JsonObject cubeJson = cubeElement.getAsJsonObject();
                float[] origin = readFloatArray(cubeJson, "origin", new float[]{ 0, 0, 0 });
                float[] size = readFloatArray(cubeJson, "size", new float[]{ 1, 1, 1 });
                int[] uv = readIntArray(cubeJson, "uv", new int[]{ 0, 0 });
                float inflate = cubeJson.has("inflate") ? cubeJson.get("inflate").getAsFloat() : 0f;
                boolean mirror = cubeJson.has("mirror")
                    ? cubeJson.get("mirror").getAsBoolean()
                    : boneMirror;

                EntityModelData.Cube cube = new EntityModelData.Cube();
                setField(cube, "origin", origin);
                setField(cube, "size", size);
                setField(cube, "uv", uv);
                setField(cube, "inflate", inflate);
                setField(cube, "mirror", mirror);
                cubes.add(cube);
            }

            EntityModelData.Bone bone = new EntityModelData.Bone();
            setField(bone, "pivot", pivot);
            setField(bone, "rotation", rotation);
            setField(bone, "cubes", cubes);
            bones.put(name, bone);
        }

        EntityModelData model = new EntityModelData();
        setField(model, "textureWidth", textureWidth);
        setField(model, "textureHeight", textureHeight);
        setField(model, "bones", bones);
        return model;
    }

    /**
     * Infers armor wearability from the entity's bone names. An entity with the standard
     * humanoid bone set (head, body, arms, legs) supports full humanoid armor.
     */
    private static @NotNull String inferArmorType(@NotNull Set<String> boneNames) {
        boolean hasHead = boneNames.contains("head");
        boolean hasBody = boneNames.contains("body");
        boolean hasArms = boneNames.contains("rightArm") || boneNames.contains("right_arm");
        boolean hasLegs = boneNames.contains("rightLeg") || boneNames.contains("right_leg");

        if (hasHead && hasBody && hasArms && hasLegs)
            return "humanoid";

        return "none";
    }

    private static float @NotNull [] readFloatArray(@NotNull JsonObject obj, @NotNull String key, float @NotNull [] fallback) {
        if (!obj.has(key)) return fallback;
        JsonArray arr = obj.getAsJsonArray(key);
        float[] result = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++)
            result[i] = arr.get(i).getAsFloat();
        return result;
    }

    private static int @NotNull [] readIntArray(@NotNull JsonObject obj, @NotNull String key, int @NotNull [] fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        // Bedrock per-face UV is an object, not an array - skip those (use default UV)
        if (!element.isJsonArray()) return fallback;
        JsonArray arr = element.getAsJsonArray();
        int[] result = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++)
            result[i] = arr.get(i).getAsInt();
        return result;
    }

    /**
     * Sets a field on a @GsonType/@NoArgsConstructor object via reflection.
     */
    private static void setField(@NotNull Object instance, @NotNull String fieldName, @NotNull Object value) {
        try {
            java.lang.reflect.Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set field '" + fieldName + "' on " + instance.getClass().getSimpleName(), ex);
        }
    }

}
