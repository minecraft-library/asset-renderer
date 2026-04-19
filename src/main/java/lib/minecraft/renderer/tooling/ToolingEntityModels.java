package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.client.ClientJarDownloader;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.entity.BedrockEntityManifest;
import lib.minecraft.renderer.tooling.entity.MobRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.VariantReconciler;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.linked.ConcurrentLinkedMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Entry point invoked by the {@code generateEntityModels} Gradle task.
 * <p>
 * Downloads the Bedrock Edition vanilla resource pack from GitHub, parses every
 * {@code .geo.json} entity model file via {@link Parser}, maps each to a Java
 * Edition entity id and default texture, and writes the result to
 * {@code src/main/resources/lib/minecraft/renderer/entity_models.json}. The runtime pipeline reads the JSON
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

    /** Output path for per-entity metadata (canonical id -&gt; geometry ref, texture, variant_of). */
    private static final @NotNull Path MODELS_OUTPUT_PATH = Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** Output path for deduplicated bone/cube trees keyed by Bedrock geometry id. */
    private static final @NotNull Path GEOMETRY_OUTPUT_PATH = Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /** In-zip directory prefix where the Bedrock pack stores entity geometry files. */
    private static final @NotNull String MODELS_PREFIX = "models/entity/";

    /** Namespace prefix applied to emitted entity ids. */
    private static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    /** Bedrock geometry identifiers are prefixed with this string. */
    private static final @NotNull String GEOMETRY_PREFIX = "geometry.";

    /**
     * Runs the generator.
     *
     * @param args ignored - the output path is fixed to keep the resource location stable
     * @throws IOException if the Bedrock pack cannot be downloaded or the JSON file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        HttpFetcher fetcher = new HttpFetcher();

        // Step 1: walk the Java client jar for the authoritative living-mob set. Bedrock .entity.json
        // provides the name/geometry/texture mapping, but cross-checking against this set ensures we
        // only emit entries that correspond to real Java EntityType registrations (keeps armor_stand,
        // drops Bedrock-only agent/npc/villager_v2).
        System.out.println("Downloading Minecraft client jar for mob registry scan...");
        Path clientJar = ClientJarDownloader.download(AssetPipelineOptions.defaults(), fetcher);
        Set<String> knownMobIds = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            Diagnostics diagnostics = new Diagnostics();
            for (MobRegistryDiscovery.MobEntry entry : MobRegistryDiscovery.discover(zip, diagnostics))
                knownMobIds.add(entry.entityId());
            System.out.println("Discovered " + knownMobIds.size() + " living-mob entity ids from the client jar");
            diagnostics.entries().forEach(System.out::println);
        }

        // Step 2: download Bedrock pack.
        System.out.println("Downloading Bedrock vanilla resource pack...");
        byte[] zipBytes = fetcher.get(BEDROCK_PACK_URL);
        System.out.println("Downloaded " + zipBytes.length + " bytes");

        // Step 3: parse the client-entity manifest (entity/*.entity.json) for the
        // (identifier -> {geometry, texture}) and (geometry -> [identifier]) mappings. This
        // replaces the previously hand-curated TEXTURE_OVERRIDES and most of the
        // BEDROCK_NAME_ALIASES tables.
        Diagnostics manifestDiagnostics = new Diagnostics();
        BedrockEntityManifest.Manifest manifest = BedrockEntityManifest.parse(zipBytes, manifestDiagnostics);
        manifestDiagnostics.entries().forEach(System.out::println);
        System.out.println("Parsed " + manifest.byIdentifier().size() + " entity.json entries from the Bedrock pack");

        // Step 4: parse every geometry file and collect the unique bone/cube trees keyed by
        // Bedrock geometry id. Same file can contain multiple geometries; first occurrence of
        // each id wins via LinkedHashMap insertion semantics.
        Map<String, Parser.ParsedEntity> geometriesById = new LinkedHashMap<>();
        int geoFileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                int idx = name.indexOf(MODELS_PREFIX);
                if (idx < 0) continue;
                String suffix = name.substring(idx + MODELS_PREFIX.length());
                if (suffix.contains("/") || !suffix.endsWith(".json")) continue;

                geoFileCount++;
                String json = new String(zis.readAllBytes());
                ConcurrentList<Parser.ParsedEntity> parsed = Parser.parse(json);
                for (Parser.ParsedEntity pe : parsed)
                    geometriesById.putIfAbsent(pe.identifier(), pe);
            }
        }
        System.out.println("Parsed " + geoFileCount + " .geo.json files, found "
            + geometriesById.size() + " unique geometries");

        // Step 5: fan out (one geometry -> N canonical entities) via the manifest. Each
        // canonical identifier is reconciled with knownMobIds and tagged as base/variant/drop.
        // Geometries not referenced by any .entity.json get a fallback pass where the
        // geometry stem itself is classified (for Bedrock-only variants like cow_cold whose
        // .geo.json file ships without a companion entity.json).
        Map<String, JsonObject> emittedEntities = new LinkedHashMap<>();
        Map<String, Parser.ParsedEntity> emittedGeometries = new LinkedHashMap<>();
        int droppedNonMob = 0;
        int keptBase = 0;
        int keptVariant = 0;

        for (Map.Entry<String, Parser.ParsedEntity> geoEntry : geometriesById.entrySet()) {
            String geometryId = geoEntry.getKey();
            Parser.ParsedEntity pe = geoEntry.getValue();
            List<String> manifestIdentifiers = manifest.fanOutByGeometry().getOrDefault(geometryId, List.of());

            if (manifestIdentifiers.isEmpty()) {
                // Fallback: no .entity.json references this geometry. Try classifying the
                // geometry's own stem as a potential variant of a known mob (e.g. cow_cold).
                String stem = bedrockStem(geometryId);
                if (stem == null) continue;
                VariantReconciler.Classification classification =
                    VariantReconciler.classify(stem, knownMobIds);
                if (!classification.isMob()) {
                    droppedNonMob++;
                    continue;
                }
                boolean added = addEmission(
                    emittedEntities, emittedGeometries, classification, pe, geometryId,
                    /* manifestTexture */ null
                );
                if (added) {
                    if (classification.baseMobId() != null) keptVariant++; else keptBase++;
                }
                continue;
            }

            for (String identifier : manifestIdentifiers) {
                VariantReconciler.Classification classification =
                    VariantReconciler.classify(identifier, knownMobIds);
                if (!classification.isMob()) {
                    droppedNonMob++;
                    continue;
                }
                BedrockEntityManifest.Entry manifestEntry = manifest.byIdentifier().get(identifier);
                String manifestTexture = manifestEntry != null ? manifestEntry.textureId() : null;
                boolean added = addEmission(
                    emittedEntities, emittedGeometries, classification, pe, geometryId, manifestTexture
                );
                if (added) {
                    if (classification.baseMobId() != null) keptVariant++; else keptBase++;
                }
            }
        }

        System.out.printf(
            "Emitted %d entities (%d base + %d variant) referencing %d unique geometries, dropped %d non-mob%n",
            emittedEntities.size(), keptBase, keptVariant, emittedGeometries.size(), droppedNonMob
        );

        Files.createDirectories(MODELS_OUTPUT_PATH.getParent());
        Files.writeString(MODELS_OUTPUT_PATH, buildModelsJson(emittedEntities));
        Files.writeString(GEOMETRY_OUTPUT_PATH, buildGeometryJson(emittedGeometries));
        System.out.println("Wrote " + MODELS_OUTPUT_PATH.toAbsolutePath());
        System.out.println("Wrote " + GEOMETRY_OUTPUT_PATH.toAbsolutePath());
    }

    /**
     * Adds one emission entry - an entity metadata record pointing at a shared geometry - if the
     * canonical entity id is not already emitted. The geometry is deduplicated across all
     * entities that share it. Returns {@code true} when a new entity row was emitted.
     */
    private static boolean addEmission(
        @NotNull Map<String, JsonObject> emittedEntities,
        @NotNull Map<String, Parser.ParsedEntity> emittedGeometries,
        @NotNull VariantReconciler.Classification classification,
        @NotNull Parser.ParsedEntity geometry,
        @NotNull String geometryId,
        @Nullable String manifestTexture
    ) {
        String canonicalEntityId = MINECRAFT_NAMESPACE + classification.canonicalId();
        if (emittedEntities.containsKey(canonicalEntityId)) return false;

        JsonObject entityJson = new JsonObject();
        entityJson.addProperty("geometry_ref", geometryId);
        entityJson.addProperty("texture_id", resolveTextureId(classification, manifestTexture));
        entityJson.addProperty("armor_type", geometry.armorType());
        if (classification.baseMobId() != null)
            entityJson.addProperty("variant_of", MINECRAFT_NAMESPACE + classification.baseMobId());

        emittedEntities.put(canonicalEntityId, entityJson);
        emittedGeometries.putIfAbsent(geometryId, geometry);
        return true;
    }

    /**
     * Resolves the namespaced texture id for one entity emission, in this precedence:
     * <ol>
     *   <li>Bedrock manifest's {@code textures.default} for the identifier (after alias
     *       resolution, the manifest entry is looked up under the <i>Bedrock</i> identifier so
     *       aliased canonicals like {@code zombified_piglin} still get the
     *       {@code zombie_pigman.entity.json}'s texture).</li>
     *   <li>Convention fallback {@code minecraft:entity/<base>/<canonical>} when the entry is a
     *       variant - base-mob folder plus variant id keeps cow_cold under entity/cow/cow_cold.</li>
     *   <li>Convention fallback {@code minecraft:entity/<canonical>/<canonical>} for bases.</li>
     * </ol>
     */
    private static @NotNull String resolveTextureId(
        @NotNull VariantReconciler.Classification classification,
        @Nullable String manifestTexture
    ) {
        if (manifestTexture != null) return manifestTexture;

        String canonical = classification.canonicalId();
        String base = classification.baseMobId();
        if (base != null) return MINECRAFT_NAMESPACE + "entity/" + base + "/" + canonical;
        return MINECRAFT_NAMESPACE + "entity/" + canonical + "/" + canonical;
    }

    /**
     * Strips the {@code "geometry."} prefix from a Bedrock geometry identifier and collapses
     * dotted segments into underscores so the result is comparable to Java registry ids.
     * Returns {@code null} when the identifier looks like an armor/baby/saddle/versioned variant
     * we know to skip at the fallback level.
     */
    private static @Nullable String bedrockStem(@NotNull String geometryId) {
        String stem = geometryId.startsWith(GEOMETRY_PREFIX) ? geometryId.substring(GEOMETRY_PREFIX.length()) : geometryId;
        stem = stem.replaceAll("\\.v\\d+(\\.\\d+)?$", "");
        if (stem.contains("armor") || stem.contains("baby") || stem.contains("_v1")
            || stem.contains("_v2") || stem.contains("_v3") || stem.contains("saddle"))
            return null;
        return stem.replace(".", "_");
    }

    /**
     * Serialises the entities map into the bundled {@code entity_models.json} with stable
     * alphabetical key ordering so regenerating produces byte-identical output.
     */
    private static @NotNull String buildModelsJson(@NotNull Map<String, JsonObject> entities) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. One entry per Java EntityType living mob; geometry_ref points into entity_geometry.json. Run ./gradlew :asset-renderer:entityModels to refresh.");

        JsonObject entitiesObj = new JsonObject();
        entities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entitiesObj.add(entry.getKey(), entry.getValue()));
        root.add("entities", entitiesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    /**
     * Serialises the deduplicated geometries map into the bundled {@code entity_geometry.json}.
     * Keys are Bedrock geometry ids; values are the parsed {@link EntityModelData} bone trees.
     */
    private static @NotNull String buildGeometryJson(@NotNull Map<String, Parser.ParsedEntity> geometries) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Deduplicated bone/cube trees keyed by Bedrock geometry id; referenced from entity_models.json.");

        JsonObject geometriesObj = new JsonObject();
        Gson gson = GsonSettings.defaults().create();
        geometries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry ->
                geometriesObj.add(entry.getKey(), gson.toJsonTree(entry.getValue().model()))
            );
        root.add("geometries", geometriesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
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
     * per-cube. The parsed output feeds into the bundled {@code /lib/minecraft/renderer/entity_models.json}
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

                // Legacy Bedrock keys can use "<id>:<parent_id>" to declare geometry inheritance.
                // The part before the colon is the geometry's own id - used by .entity.json and
                // by every consumer of this parser. The parent reference after the colon is not
                // resolved here (the bone tree is already flattened), so we strip it.
                int colon = key.indexOf(':');
                String identifier = colon >= 0 ? key.substring(0, colon) : key;

                JsonObject geometry = entry.getValue().getAsJsonObject();
                int textureWidth = geometry.has("texturewidth")
                    ? geometry.get("texturewidth").getAsInt() : 64;
                int textureHeight = geometry.has("textureheight")
                    ? geometry.get("textureheight").getAsInt() : 64;

                JsonArray bonesArray = geometry.has("bones") ? geometry.getAsJsonArray("bones") : null;
                if (bonesArray == null) continue;

                EntityModelData model = buildModel(bonesArray, textureWidth, textureHeight);
                String armorType = inferArmorType(model.getBones().keySet());
                results.add(new ParsedEntity(identifier, model, armorType));
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
