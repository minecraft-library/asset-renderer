package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.linked.ConcurrentLinkedMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Entry point invoked by the {@code generateEntityModels} Gradle task.
 * <p>
 * Downloads the Bedrock Edition vanilla resource pack from GitHub, parses every
 * {@code .geo.json} entity model file via {@link Parser}, maps each to a Java
 * Edition entity id and default texture, and writes the result to
 * {@code src/main/resources/renderer/entity_models.json}. The runtime pipeline reads the JSON
 * via {@link EntityModelLoader}.
 * <p>
 * Bedrock entity models are geometrically identical to their Java Edition counterparts for all
 * vanilla entities - both editions share the same design specifications. The Bedrock format is
 * used as the source because it ships structured JSON while Java Edition hardcodes entity
 * geometry in compiled class files.
 */
@UtilityClass
public final class ToolingEntityModels {

    /** Source URL for the Bedrock Edition vanilla resource pack zip (branch-archive download). */
    private static final @NotNull String BEDROCK_PACK_URL = "https://github.com/ZtechNetwork/MCBVanillaResourcePack/archive/refs/heads/master.zip";

    /** Fixed output path for the bundled entity-models resource. */
    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/renderer/entity_models.json");

    /** In-zip directory prefix where the Bedrock pack stores entity geometry files. */
    private static final @NotNull String MODELS_PREFIX = "models/entity/";

    /**
     * Geometry identifiers to skip - these are non-entity models, armor layers, or duplicates
     * that don't correspond to renderable entities.
     */
    private static final @NotNull Set<String> SKIP_IDENTIFIERS = Set.of(
        "geometry.player.armor.base", "geometry.player.armor1", "geometry.player.armor2",
        "geometry.player.armor.helmet", "geometry.player.armor.chestplate",
        "geometry.player.armor.leggings", "geometry.player.armor.boots",
        "geometry.item_sprite", "geometry.trident", "geometry.bow", "geometry.crossbow",
        "geometry.shield", "geometry.spyglass", "geometry.minecart",
        "geometry.leash_knot", "geometry.fishing_hook", "geometry.fireworks_rocket",
        "geometry.fireball", "geometry.experience_orb", "geometry.arrow",
        "geometry.ender_crystal", "geometry.wither_skull", "geometry.shulker_bullet",
        "geometry.tripod_camera"
    );

    /**
     * Maps Bedrock geometry identifier stems to Java Edition entity texture paths. Entries
     * not in this map fall back to the convention {@code minecraft:entity/{name}/{name}}.
     */
    private static final @NotNull ConcurrentMap<String, String> TEXTURE_OVERRIDES = buildTextureOverrides();

    /**
     * Runs the generator.
     *
     * @param args ignored - the output path is fixed to keep the resource location stable
     * @throws IOException if the Bedrock pack cannot be downloaded or the JSON file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        System.out.println("Downloading Bedrock vanilla resource pack...");
        HttpFetcher fetcher = new HttpFetcher();
        byte[] zipBytes = fetcher.get(BEDROCK_PACK_URL);
        System.out.println("Downloaded " + zipBytes.length + " bytes");

        ConcurrentMap<String, JsonObject> entities = Concurrent.newMap();
        int fileCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // Find .geo.json and .json files under models/entity/ inside the zip
                int modelsIdx = name.indexOf(MODELS_PREFIX);
                if (modelsIdx < 0) continue;
                String fileName = name.substring(modelsIdx + MODELS_PREFIX.length());
                if (fileName.contains("/") || fileName.isEmpty()) continue;
                if (!fileName.endsWith(".json")) continue;

                String json = new String(zis.readAllBytes());
                fileCount++;

                ConcurrentList<Parser.ParsedEntity> parsed = Parser.parse(json);
                for (Parser.ParsedEntity pe : parsed) {
                    String identifier = pe.identifier();
                    if (SKIP_IDENTIFIERS.contains(identifier)) continue;

                    String entityId = identifierToEntityId(identifier);
                    if (entityId == null) continue;
                    // Skip duplicates - prefer the first occurrence (non-versioned)
                    if (entities.containsKey(entityId)) continue;

                    String textureId = resolveTextureId(entityId, identifier);
                    JsonObject entityJson = serializeEntity(pe, textureId);
                    entities.put(entityId, entityJson);
                }
            }
        }

        System.out.println("Parsed " + fileCount + " .geo.json files, produced " + entities.size() + " entity definitions");

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH, buildJson(entities));
        System.out.println("Wrote " + OUTPUT_PATH.toAbsolutePath());
    }

    /**
     * Converts a Bedrock geometry identifier to a namespaced Java Edition entity id.
     * Returns null for identifiers that don't map to a known entity.
     */
    private static String identifierToEntityId(@NotNull String identifier) {
        // Strip "geometry." prefix
        String stem = identifier.startsWith("geometry.") ? identifier.substring(9) : identifier;
        // Strip version suffixes like ".v1.8", ".v1.0"
        stem = stem.replaceAll("\\.v\\d+(\\.\\d+)?$", "");
        // Skip armor variants, baby variants, and other non-base models
        if (stem.contains("armor") || stem.contains("baby") || stem.contains("_v1")
            || stem.contains("_v2") || stem.contains("_v3") || stem.contains("saddle"))
            return null;
        // Normalize separators
        stem = stem.replace(".", "_");
        return "minecraft:" + stem;
    }

    /**
     * Resolves the default Java Edition texture path for an entity.
     */
    private static @NotNull String resolveTextureId(@NotNull String entityId, @NotNull String identifier) {
        String name = entityId.substring("minecraft:".length());
        String override = TEXTURE_OVERRIDES.get(name);
        if (override != null) return override;
        return "minecraft:entity/" + name + "/" + name;
    }

    /**
     * Packs a {@link Parser.ParsedEntity} into its JSON form, tagging on the texture id and
     * armor-type fields that the runtime loader reads.
     */
    private static @NotNull JsonObject serializeEntity(
        @NotNull ToolingEntityModels.Parser.ParsedEntity pe,
        @NotNull String textureId
    ) {
        JsonObject entityJson = GsonSettings.defaults().create().toJsonTree(pe.model()).getAsJsonObject();
        entityJson.addProperty("texture_id", textureId);
        entityJson.addProperty("armor_type", pe.armorType());
        return entityJson;
    }

    /**
     * Serialises the collected entities into the bundled JSON format with a stable alphabetical
     * key ordering so regenerating the file produces byte-identical output.
     */
    private static @NotNull String buildJson(@NotNull ConcurrentMap<String, JsonObject> entities) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. From Bedrock Edition vanilla resource pack. Run the tooling/generateEntityModels Gradle task to refresh.");

        JsonObject entitiesObj = new JsonObject();
        entities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entitiesObj.add(entry.getKey(), entry.getValue()));
        root.add("entities", entitiesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    /**
     * Builds the {@link #TEXTURE_OVERRIDES} table. Entries exist only for entities whose Java
     * texture path does not match the {@code minecraft:entity/{name}/{name}} convention.
     */
    private static @NotNull ConcurrentMap<String, String> buildTextureOverrides() {
        ConcurrentMap<String, String> map = Concurrent.newMap();
        map.put("zombie", "minecraft:entity/zombie/zombie");
        map.put("skeleton", "minecraft:entity/skeleton/skeleton");
        map.put("creeper", "minecraft:entity/creeper/creeper");
        map.put("spider", "minecraft:entity/spider/spider");
        map.put("enderman", "minecraft:entity/enderman/enderman");
        map.put("zombie_pigman", "minecraft:entity/piglin/zombified_piglin");
        map.put("zombie_villager", "minecraft:entity/zombie_villager/zombie_villager");
        map.put("wither_skeleton", "minecraft:entity/skeleton/wither_skeleton");
        map.put("stray", "minecraft:entity/skeleton/stray");
        map.put("husk", "minecraft:entity/zombie/husk");
        map.put("drowned", "minecraft:entity/zombie/drowned");
        map.put("snow_golem", "minecraft:entity/snow_golem/snow_golem");
        map.put("iron_golem", "minecraft:entity/iron_golem/iron_golem");
        map.put("wolf", "minecraft:entity/wolf/wolf");
        map.put("cat", "minecraft:entity/cat/siamese");
        map.put("ocelot", "minecraft:entity/cat/ocelot");
        map.put("horse", "minecraft:entity/horse/horse_brown");
        map.put("pig", "minecraft:entity/pig/pig");
        map.put("cow", "minecraft:entity/cow/cow");
        map.put("sheep", "minecraft:entity/sheep/sheep");
        map.put("chicken", "minecraft:entity/chicken/chicken");
        map.put("squid", "minecraft:entity/squid/squid");
        map.put("bat", "minecraft:entity/bat/bat");
        map.put("villager", "minecraft:entity/villager/villager");
        map.put("witch", "minecraft:entity/witch/witch");
        map.put("ghast", "minecraft:entity/ghast/ghast");
        map.put("blaze", "minecraft:entity/blaze/blaze");
        map.put("slime", "minecraft:entity/slime/slime");
        map.put("magma_cube", "minecraft:entity/slime/magmacube");
        map.put("silverfish", "minecraft:entity/silverfish/silverfish");
        map.put("endermite", "minecraft:entity/endermite/endermite");
        map.put("guardian", "minecraft:entity/guardian/guardian");
        map.put("shulker", "minecraft:entity/shulker/shulker");
        map.put("phantom", "minecraft:entity/phantom/phantom");
        map.put("ravager", "minecraft:entity/illager/ravager");
        map.put("pillager", "minecraft:entity/illager/pillager");
        map.put("vindicator", "minecraft:entity/illager/vindicator");
        map.put("evoker", "minecraft:entity/illager/evoker");
        map.put("vex", "minecraft:entity/vex/vex");
        map.put("dolphin", "minecraft:entity/dolphin/dolphin");
        map.put("turtle", "minecraft:entity/turtle/big_sea_turtle");
        map.put("panda", "minecraft:entity/panda/panda");
        map.put("fox", "minecraft:entity/fox/fox");
        map.put("bee", "minecraft:entity/bee/bee");
        map.put("hoglin", "minecraft:entity/hoglin/hoglin");
        map.put("piglin", "minecraft:entity/piglin/piglin");
        map.put("strider", "minecraft:entity/strider/strider");
        map.put("axolotl", "minecraft:entity/axolotl/axolotl_lucy");
        map.put("glow_squid", "minecraft:entity/squid/glow_squid");
        map.put("goat", "minecraft:entity/goat/goat");
        map.put("frog", "minecraft:entity/frog/temperate_frog");
        map.put("tadpole", "minecraft:entity/tadpole/tadpole");
        map.put("allay", "minecraft:entity/allay/allay");
        map.put("warden", "minecraft:entity/warden/warden");
        map.put("camel", "minecraft:entity/camel/camel");
        map.put("sniffer", "minecraft:entity/sniffer/sniffer");
        map.put("armadillo", "minecraft:entity/armadillo/armadillo");
        map.put("breeze", "minecraft:entity/breeze/breeze");
        map.put("bogged", "minecraft:entity/skeleton/bogged");
        map.put("creaking", "minecraft:entity/creaking/creaking");
        map.put("armor_stand", "minecraft:entity/armorstand/armorstand");
        map.put("rabbit", "minecraft:entity/rabbit/brown");
        map.put("polar_bear", "minecraft:entity/bear/polarbear");
        map.put("llama", "minecraft:entity/llama/llama");
        map.put("parrot", "minecraft:entity/parrot/parrot_red_blue");
        map.put("cod", "minecraft:entity/fish/cod");
        map.put("salmon", "minecraft:entity/fish/salmon");
        map.put("pufferfish", "minecraft:entity/fish/pufferfish");
        map.put("tropical_fish", "minecraft:entity/fish/tropical");
        map.put("ender_dragon", "minecraft:entity/dragon/dragon");
        map.put("wither_boss", "minecraft:entity/wither_boss/wither");
        return map;
    }

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
    static class Parser {

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
         * <p>
         * Bedrock geo.json is Y-up with cube origins pre-baked to entity-root space; the runtime
         * expects Java {@code ModelPart} conventions (Y-down, cube origins bone-local). The
         * conversion happens here so every downstream consumer sees a single coordinate
         * convention:
         * <ul>
         * <li><b>Pivot:</b> relativise to its own bone (a no-op since Bedrock pivots already live
         * in entity-root space) and negate {@code y} to flip Y-up into Y-down.</li>
         * <li><b>Cube origin:</b> subtract the pivot to move the corner into bone-local space,
         * then <i>mirror</i> it about the pivot's XZ plane. Because {@code origin} is the
         * <b>min</b> corner and {@code size} is an unsigned extent, flipping Y swaps min/max on
         * the Y axis - so the converted {@code origin.y} is {@code -(origin.y - pivot.y) -
         * size.y}, i.e. the former max corner negated.</li>
         * </ul>
         * Sizes remain unsigned extents and rotation angles carry over unchanged; no vanilla
         * Bedrock entity needs a rotation adjustment because the per-axis rotation conventions
         * match between {@code .geo.json} and Java entity models.
         */
        private static @NotNull EntityModelData buildModel(
            @NotNull JsonArray bonesArray,
            int textureWidth,
            int textureHeight
        ) {
            // Insertion-ordered so the generated JSON preserves bone order from the Bedrock pack,
            // and so runtime rendering assigns per-bone render priorities deterministically.
            ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();

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
                float[] r = readFloatArray(boneJson, "rotation", new float[]{ 0, 0, 0 });
                EulerRotation rotation = new EulerRotation(r[0], r[1], r[2]);

                // Bedrock Y-up -> Java Y-down: mirror the pivot about the XZ plane.
                float[] javaPivot = new float[]{ pivot[0], -pivot[1], pivot[2] };

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

                    // World-space (Y-up) -> bone-local (Y-down). The cube's min corner in Y-up
                    // becomes its max corner after Y is flipped, so the new min Y is the negated
                    // former max: -(origin.y - pivot.y + size.y). X and Z are unaffected by the
                    // mirror and only need to be relativised to the bone.
                    float[] javaOrigin = new float[]{
                        origin[0] - pivot[0],
                        -(origin[1] - pivot[1]) - size[1],
                        origin[2] - pivot[2]
                    };

                    cubes.add(new EntityModelData.Cube(javaOrigin, size, uv, inflate, mirror, Concurrent.newMap()));
                }

                bones.put(name, new EntityModelData.Bone(javaPivot, rotation, cubes));
            }

            return new EntityModelData(textureWidth, textureHeight, EntityModelData.YAxis.DOWN, 0f, bones);
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

        /**
         * Reads a float array field from {@code obj}, returning {@code fallback} when the key is
         * absent. Used for {@code pivot}, {@code rotation}, {@code origin}, and {@code size}.
         */
        private static float @NotNull [] readFloatArray(@NotNull JsonObject obj, @NotNull String key, float @NotNull [] fallback) {
            if (!obj.has(key)) return fallback;
            JsonArray arr = obj.getAsJsonArray(key);
            float[] result = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++)
                result[i] = arr.get(i).getAsFloat();
            return result;
        }

        /**
         * Reads an int array field from {@code obj}, returning {@code fallback} when the key is
         * absent or the value is not an array. The non-array branch guards against Bedrock
         * per-face UV blocks which store an object instead of an array.
         */
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

    }

}
