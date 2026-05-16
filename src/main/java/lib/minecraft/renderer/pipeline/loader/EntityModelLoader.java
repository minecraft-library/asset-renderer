package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads bundled entity model definitions from three paired classpath resources produced by
 * {@code ToolingEntityModels}: {@link #MODELS_RESOURCE_PATH} holds per-entity metadata
 * (geometry reference, texture reference, optional {@code variant_of} back-link, overlays);
 * {@link #GEOMETRY_RESOURCE_PATH} holds the deduplicated bone/cube trees; and
 * {@link #OVERRIDES_RESOURCE_PATH} carries hand-edited corrections that cannot be auto-derived
 * from bytecode (variant texture defaults, hidden bones, per-entity overlay layers).
 * <p>
 * {@link #GEOMETRY_HANDEDITS_RESOURCE_PATH} merges into the geometry table on top of the
 * generated entries for geometries the bytecode tooling can't reach (e.g.
 * {@code HumanoidModel.createMesh}'s standalone player-humanoid output, used by
 * {@code SkeletonClothingLayer}-shaped overlays).
 * <p>
 * A {@code texture_ref} is the vanilla {@code textures/entity/} sub-path (e.g.
 * {@code "cow/cow"}, {@code "wither/wither"}); resolved at render time against the active pack
 * stack via
 * {@link lib.minecraft.renderer.engine.RendererContext#resolveTexture(String) resolveTexture}
 * as {@code minecraft:entity/<ref>}.
 * <p>
 * Many Java {@code EntityType} registry rows share one geometry (e.g. {@code horse},
 * {@code donkey}, {@code mule}, {@code skeleton_horse}, {@code zombie_horse} all reference
 * {@code geometry.horse}). Splitting the data into two files lets each entity metadata row stay
 * small while the potentially-multi-kilobyte bone tree is stored exactly once.
 *
 * @see PipelineRendererContext
 */
@UtilityClass
public class EntityModelLoader {

    /** Per-entity metadata file; produced by {@code ToolingEntityModels}. */
    private static final @NotNull String MODELS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";

    /** Per-geometry bone tree file; bones in Java-native Y-down absolute entity-root frame. */
    private static final @NotNull String GEOMETRY_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry.json";

    /**
     * Hand-edited geometries merged on top of {@link #GEOMETRY_RESOURCE_PATH}. The bytecode
     * tooling can't reach every Java-pipeline geometry (e.g. {@code HumanoidModel.createMesh}'s
     * standalone player-humanoid output, used by {@code SkeletonClothingLayer}-shaped overlays);
     * this file is the escape hatch for those. Entries here survive tooling regenerations.
     */
    private static final @NotNull String GEOMETRY_HANDEDITS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry_handedits.json";

    private static final @NotNull String OVERRIDES_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models_overrides.json";

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * An entity definition loaded from the bundled resources.
     *
     * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
     * @param textureRef the vanilla {@code textures/entity/} sub-path (without the {@code .png}
     *     suffix), resolved at render time via
     *     {@link lib.minecraft.renderer.engine.RendererContext#resolveTexture(String)
     *     resolveTexture} as {@code minecraft:entity/<ref>}, or empty when no default texture
     * @param overlays additional geometry/texture pairs rendered on top of the base model in
     *     declared order; populated by the auto-generated emissive eye scan plus hand-edited
     *     entries for layered entities (charged creeper armor, copper golem holding a flower)
     * @param blockOverlays vanilla-block-shaped overlays rendered on top of the entity body
     *     (mooshroom mushrooms, iron golem poppy) at a transform-stack-applied position
     * @param forceOpaque when {@code true} every partial-alpha texel in the entity's bundled
     *     texture is bumped to {@code alpha=255} at load time. Used by entities whose vanilla
     *     texture is authored at low alpha for an additive-blending pass that the static iso
     *     renderer doesn't reproduce (blaze rods, magma cube), or for aesthetic partial alpha
     *     (sheep wool fluff). Opt-in via the {@code force_opaque} field on the overrides row;
     *     defaults to {@code false}
     */
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull List<OverlayLayer> overlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        boolean forceOpaque
    ) {

        /**
         * Convenience constructor for entities with no overlays and no force-opaque flag - the
         * common case.
         */
        public EntityDefinition(@NotNull EntityModelData model, @NotNull Optional<String> textureRef) {
            this(model, textureRef, List.of(), List.of(), false);
        }

        /**
         * Convenience constructor for entities with overlays but no force-opaque flag.
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays
        ) {
            this(model, textureRef, overlays, List.of(), false);
        }

        /**
         * Convenience constructor for entities with overlays and force-opaque - drops block overlays.
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays,
            boolean forceOpaque
        ) {
            this(model, textureRef, overlays, List.of(), forceOpaque);
        }

    }

    /**
     * One block-model overlay attached to an entity: a vanilla block (e.g. red mushroom block)
     * rendered at a specific transform on top of the entity body. Used by mooshroom (mushrooms
     * on back / between horns), enderman (carried block), iron golem (poppy), etc.
     *
     * <p>The {@code transforms} list is applied in order at render time, one push/pop scope per
     * block-overlay row (each row = one mushroom/flower/etc). Transforms operate in entity-local
     * coordinates - the block model's 0..1 unit cube is placed in the entity's frame after the
     * transform chain. Optionally pre-pended by an entity-bone pose ({@code attachedBone}) so
     * head-attached overlays (mooshroom's third mushroom between the horns) follow the head's
     * runtime / bind-pose rotation.
     *
     * @param blockId the block id to render (e.g. {@code "minecraft:red_mushroom_block"})
     * @param attachedBone optional entity-bone whose pose stack pre-multiplies the transforms
     *     (e.g. {@code "head"} for the mooshroom horn-mushroom). {@code null} when the overlay
     *     is positioned in the entity's root frame
     * @param transforms ordered list of {@code translate} / {@code rotate_y} / {@code scale} ops
     *     applied to the block model after the optional bone pose
     */
    public record BlockOverlayLayer(
        @NotNull String blockId,
        @Nullable String attachedBone,
        @NotNull List<TransformOp> transforms
    ) {}

    /**
     * One transform operation in a {@link BlockOverlayLayer}'s chain. Mirrors the vanilla
     * {@code PoseStack} ops a render layer issues between {@code pushPose} / {@code popPose}:
     * {@code translate(F, F, F)} -> {@link Translate}, {@code mulPose(rotationDegrees(deg))} on
     * the Y axis -> {@link RotateY}, {@code scale(F, F, F)} -> {@link Scale}.
     *
     * <p>Sealed so the renderer can pattern-match without a default branch. Add a new op kind
     * (e.g. {@code RotateX}) by extending the seal and updating both the JSON serialiser and
     * the renderer dispatch.
     */
    public sealed interface TransformOp permits Translate, RotateY, Scale {}

    /** Translation by {@code (x, y, z)} in entity-local units. */
    public record Translate(float x, float y, float z) implements TransformOp {}

    /** Rotation around the Y axis by {@code degrees}. */
    public record RotateY(float degrees) implements TransformOp {}

    /** Per-axis scale {@code (x, y, z)}. Negative components flip the axis. */
    public record Scale(float x, float y, float z) implements TransformOp {}

    /**
     * One overlay layer on an {@link EntityDefinition}: an independent geometry plus its own
     * bundled texture sub-path. Resolved from the overrides {@code overlays} array at load time.
     *
     * @param model the overlay's bone/cube tree, sharing the base model's coordinate frame so
     *     they co-register under the renderer's shared auto-fit transform
     * @param textureRef the bundled texture sub-path (without {@code .png}), or empty when the
     *     overlay should reuse the base entity's texture
     * @param emissive when {@code true} the overlay renders full-bright + additive (vanilla
     *     Java's {@code RenderType.eyes} pattern) instead of shaded src-over. Tagged onto every
     *     triangle the overlay produces; the rasterizer keys off the per-triangle flag
     */
    public record OverlayLayer(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        boolean emissive
    ) {}

    /**
     * Resolves an overlays JSON array into a list of {@link OverlayLayer}s. Each entry is an
     * object with a {@code geometry_ref} (resolved against the geometry table), an optional
     * {@code texture_ref} (the vanilla {@code textures/entity/} sub-path; absent means the
     * overlay reuses the base entity's texture), and an optional {@code inflate} (additive cube
     * inflate applied to every cube on the overlay so it surrounds the base mesh instead of
     * z-fighting - matches Java's armor-layer convention). Entries that name an unknown geometry
     * log a warning and drop so a stale override after a geometry regen doesn't abort the load.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull JsonArray overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String baseGeometryRef,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId
    ) {
        List<OverlayLayer> out = new ArrayList<>();
        for (JsonElement el : overlays) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (!entry.has("geometry_ref")) {
                System.err.printf("  Warning: entity '%s' overlay missing 'geometry_ref'%n", entityId);
                continue;
            }
            String geometryRef = entry.get("geometry_ref").getAsString();
            // When the overlay shares the base entity's geometry, reuse the post-override
            // base model so overlay cubes inherit bone_overrides / bind_poses / extra_bones
            // applied to the base. Otherwise (different geometry, e.g. slime outer shell or
            // creeper power overlay) resolve fresh from the geometry table.
            EntityModelData overlayModel;
            if (geometryRef.equals(baseGeometryRef)) {
                overlayModel = baseModel;
            } else {
                overlayModel = geometries.get(geometryRef);
                if (overlayModel == null) {
                    System.err.printf("  Warning: entity '%s' overlay references geometry '%s' which is not present in '%s'%n",
                        entityId, geometryRef, GEOMETRY_RESOURCE_PATH);
                    continue;
                }
            }
            Optional<String> overlayTexture = entry.has("texture_ref")
                ? Optional.of(entry.get("texture_ref").getAsString())
                : Optional.empty();
            float inflate = entry.has("inflate") ? entry.get("inflate").getAsFloat() : 0f;
            EntityModelData materialised = inflate != 0f ? inflateModel(overlayModel, inflate) : overlayModel;
            boolean emissive = entry.has("emissive") && entry.get("emissive").getAsBoolean();
            out.add(new OverlayLayer(materialised, overlayTexture, emissive));
        }
        return out;
    }

    /**
     * Returns a deep-cloned copy of {@code model} with every cube's {@link
     * EntityModelData.Cube#getInflate() inflate} field bumped by {@code delta}. Used by the
     * overlay loader to surround the base mesh with an inflated overlay (creeper armor mesh
     * needs ~2 units of inflate so its translucent lightning grid sits around the base creeper
     * instead of z-fighting with it). Bones, pivots, rotations, UVs, and parent links are
     * preserved verbatim - only the per-cube inflate changes.
     */
    private static @NotNull EntityModelData inflateModel(@NotNull EntityModelData source, float delta) {
        LinkedHashMap<String, EntityModelData.Bone> inflated = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : source.getBones().entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            ArrayList<EntityModelData.Cube> cubes = new ArrayList<>(bone.getCubes().size());
            for (EntityModelData.Cube cube : bone.getCubes())
                cubes.add(new EntityModelData.Cube(
                    cube.getOrigin(), cube.getSize(), cube.getUv(),
                    cube.getInflate() + delta, cube.isMirror(),
                    cube.getPivot(), cube.getRotation(), cube.getFaceUv()
                ));
            inflated.put(e.getKey(), new EntityModelData.Bone(
                bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                bone.getScale(), Concurrent.adoptList(cubes), bone.getParent()
            ));
        }
        return new EntityModelData(
            source.getTextureWidth(), source.getTextureHeight(),
            source.getInventoryYRotation(), Concurrent.adoptLinkedMap(inflated)
        );
    }

    /**
     * Strips bones named in the overrides {@code hidden_bones} array from the cloned bone map.
     * The Java pipeline's geometries pack every optional render target into one tree (humanoid
     * outer-layer over inner, equine saddle bones over base body) and rely on entity-state flags
     * at render time to gate them. The static renderer has no live entity state, so unwanted
     * bones must be hidden via this override list. Missing bones log a warning.
     */
    private static void applyHiddenBones(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull JsonArray hiddenBones,
        @NotNull String entityId
    ) {
        for (JsonElement el : hiddenBones) {
            if (!el.isJsonPrimitive()) continue;
            String name = el.getAsString();
            if (bones.remove(name) == null)
                System.err.printf("  Warning: entity '%s' hidden_bones names bone '%s' which is not on the geometry%n",
                    entityId, name);
        }
    }


    /**
     * Parses {@code entity_models_overrides.json} and returns its {@code entities} object (empty
     * when the file is absent). The overrides file is optional - when missing, the loader emits
     * definitions straight from the generated entity_models.json without any corrections.
     */
    private static @NotNull JsonObject loadOverridesBlock() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(OVERRIDES_RESOURCE_PATH)) {
            if (stream == null) return new JsonObject();

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities")) return new JsonObject();

            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity model overrides resource '%s'", OVERRIDES_RESOURCE_PATH);
        }
    }

    /**
     * Loads bundled entity definitions, joining per-entity metadata against the deduplicated
     * geometry table and overlaying any hand-edited corrections from {@link #OVERRIDES_RESOURCE_PATH}.
     *
     * @return definitions keyed by namespaced entity id (empty when geometry resource absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an
     *     entity references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        Map<String, EntityModelData> geometries = loadGeometries();
        if (geometries.isEmpty()) return Concurrent.newMap();

        JsonObject entities = loadEntitiesBlock();
        JsonObject overrides = loadOverridesBlock();
        HashMap<String, EntityDefinition> definitions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();
            if (!entityJson.has("geometry_ref")) continue;

            JsonObject override = overrides.has(entityId) && overrides.get(entityId).isJsonObject()
                ? overrides.getAsJsonObject(entityId)
                : null;

            // {@code geometry_ref} override redirects an entity to a different geometry. Used by
            // cow_cold / chicken_cold etc. to point variant entities at hand-edited variant
            // geometries ({@code geometry.cold_cow}, etc.) that the bytecode tooling can't reach -
            // ColdCowModel and friends bake separate {@code LayerDefinition}s registered under
            // {@code ModelLayers.COLD_COW} that don't surface as top-level rows in the tooling's
            // per-renderer scan. Override values that don't resolve here fall back to the
            // entity's own {@code geometry_ref}.
            String overrideGeometryRef = override != null && override.has("geometry_ref")
                ? override.get("geometry_ref").getAsString()
                : null;
            String entityGeometryRef = entityJson.get("geometry_ref").getAsString();
            String geometryRef = overrideGeometryRef != null && geometries.containsKey(overrideGeometryRef)
                ? overrideGeometryRef
                : entityGeometryRef;
            EntityModelData baseModel = geometries.get(geometryRef);
            if (baseModel == null)
                throw new PipelineException(
                    "Entity '%s' references geometry '%s' which is not present in '%s'",
                    entityId, geometryRef, GEOMETRY_RESOURCE_PATH
                );

            // texture_ref precedence: override > generated entity row > absent. Used by
            // TEXTURE_VARIANT entities whose vanilla renderer's getTextureLocation(state) picks
            // a specific variant texture at zero state (rabbit brown, axolotl lucy, cat black) -
            // the override pins that variant texture in place of the tooling-extracted default.
            Optional<String> textureRef;
            if (override != null && override.has("texture_ref"))
                textureRef = Optional.of(override.get("texture_ref").getAsString());
            else if (entityJson.has("texture_ref"))
                textureRef = Optional.of(entityJson.get("texture_ref").getAsString());
            else
                textureRef = Optional.empty();

            // Apply hand-edited overrides. The loader honours {@code geometry_ref},
            // {@code texture_ref} (both above), {@code hidden_bones}, and {@code overlays}.
            if (override != null && override.has("hidden_bones") && override.get("hidden_bones").isJsonArray()) {
                LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(baseModel.getBones());
                applyHiddenBones(bones, override.getAsJsonArray("hidden_bones"), entityId);
                baseModel = new EntityModelData(
                    baseModel.getTextureWidth(),
                    baseModel.getTextureHeight(),
                    baseModel.getInventoryYRotation(),
                    Concurrent.adoptLinkedMap(bones)
                );
            }

            // Overlays come from two sources, concatenated in this order so hand-edited entries
            // always extend (never replace) the auto-generated ones:
            //   1. entity_models.json - emissive eye layers + composite layers emitted by
            //      EntityOverlayResolver during tooling.
            //   2. entity_models_overrides.json - hand-edited overlays for cases the tooling
            //      can't auto-detect (slime translucent shell, copper golem flower).
            // An overlay sharing the base geometry_ref reuses baseModel verbatim (eye PNGs land
            // on the same UV layout); a distinct geometry_ref resolves freshly from the geometry
            // table.
            List<OverlayLayer> overlays = new ArrayList<>();
            if (entityJson.has("overlays") && entityJson.get("overlays").isJsonArray())
                overlays.addAll(loadOverlays(entityJson.getAsJsonArray("overlays"), geometries, geometryRef, baseModel, entityId));
            if (override != null && override.has("overlays") && override.get("overlays").isJsonArray())
                overlays.addAll(loadOverlays(override.getAsJsonArray("overlays"), geometries, geometryRef, baseModel, entityId));

            List<BlockOverlayLayer> blockOverlays = entityJson.has("block_overlays") && entityJson.get("block_overlays").isJsonArray()
                ? loadBlockOverlays(entityJson.getAsJsonArray("block_overlays"))
                : List.of();

            definitions.put(entityId, new EntityDefinition(baseModel, textureRef, overlays, blockOverlays, false));
        }
        return Concurrent.adoptMap(definitions);
    }

    /**
     * Resolves the Java pipeline's {@code block_overlays} JSON array into {@link BlockOverlayLayer}
     * rows. Each entry has a {@code block_id}, optional {@code attached_bone}, and a
     * {@code transforms} array whose entries are tagged objects: {@code {"op":"translate","x":...,"y":...,"z":...}}
     * / {@code {"op":"rotate_y","degrees":...}} / {@code {"op":"scale","x":...,"y":...,"z":...}}.
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull JsonArray array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("block_id")) continue;
            String blockId = row.get("block_id").getAsString();
            String attachedBone = row.has("attached_bone") && !row.get("attached_bone").isJsonNull()
                ? row.get("attached_bone").getAsString()
                : null;
            List<TransformOp> ops = new ArrayList<>();
            if (row.has("transforms") && row.get("transforms").isJsonArray()) {
                for (JsonElement opElement : row.getAsJsonArray("transforms")) {
                    if (!opElement.isJsonObject()) continue;
                    JsonObject opObj = opElement.getAsJsonObject();
                    String kind = opObj.has("op") ? opObj.get("op").getAsString() : "";
                    switch (kind) {
                        case "translate" -> ops.add(new Translate(
                            opObj.get("x").getAsFloat(),
                            opObj.get("y").getAsFloat(),
                            opObj.get("z").getAsFloat()));
                        case "rotate_y" -> ops.add(new RotateY(opObj.get("degrees").getAsFloat()));
                        case "scale" -> ops.add(new Scale(
                            opObj.get("x").getAsFloat(),
                            opObj.get("y").getAsFloat(),
                            opObj.get("z").getAsFloat()));
                        default -> { }
                    }
                }
            }
            out.add(new BlockOverlayLayer(blockId, attachedBone, List.copyOf(ops)));
        }
        return List.copyOf(out);
    }

    /**
     * Reads {@link #GEOMETRY_RESOURCE_PATH} and merges
     * {@link #GEOMETRY_HANDEDITS_RESOURCE_PATH} on top (hand-edits take precedence on key
     * collision). Returns an empty map when the primary file is absent (so {@link #load()}
     * can short-circuit to "no Java pipeline available" without throwing during environments
     * that haven't run {@code ToolingEntityModels} yet).
     */
    private static @NotNull Map<String, EntityModelData> loadGeometries() {
        Map<String, EntityModelData> out = readGeometriesJsonResource(GEOMETRY_RESOURCE_PATH, /*required*/ false);
        Map<String, EntityModelData> handedits = readGeometriesJsonResource(GEOMETRY_HANDEDITS_RESOURCE_PATH, /*required*/ false);
        out.putAll(handedits);
        return out;
    }

    /**
     * Cross-entity family overrides for the family-fit pre-pass.
     * Mirrors the vanilla harness's {@code EntitySweeper.FAMILY_OVERRIDES} (variant-of-same-entity
     * groupings are derived from {@code variant_of} in entity_models.json; this map is for
     * distinct entities that should share a family canvas). Mooshroom uses the cow body geometry
     * + mushroom block overlays - vanilla family-fits it under cow so the mushrooms protrude into
     * the cold-cow-sized canvas's empty top space instead of squishing the body to fit a mooshroom-
     * tight canvas. Other candidates if vanilla adds them: zombified_piglin -> piglin,
     * wither_skeleton -> skeleton, husk -> zombie.
     */
    private static final @NotNull Map<String, String> FAMILY_OVERRIDES = Map.of(
        "minecraft:mooshroom", "minecraft:cow"
    );

    private static volatile Map<String, List<String>> FAMILIES_CACHE;

    /**
     * Returns {@code entityId -> familyMembers} keyed by every Java-pipeline entity id. Family
     * membership is derived from {@code variant_of} in entity_models.json (variant entities
     * roll up to their declared root) plus {@link #FAMILY_OVERRIDES} (cross-entity groupings).
     * Singletons return a single-element list containing themselves so callers can iterate
     * uniformly without special-casing. The result is cached on first call - the JSON is loaded
     * once for the lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamilies() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            JsonObject entities = loadEntitiesBlock();
            // Two-pass: first pass assigns each entity to its family root via variant_of /
            // FAMILY_OVERRIDES; second pass inverts the map to family -> [members] so any member
            // looking up by its own id sees the whole family.
            Map<String, String> entityToFamily = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                String entityId = entry.getKey();
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                String family = FAMILY_OVERRIDES.get(entityId);
                if (family == null && obj.has("variant_of"))
                    family = obj.get("variant_of").getAsString();
                if (family == null) family = entityId;
                entityToFamily.put(entityId, family);
            }
            Map<String, List<String>> familyToMembers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : entityToFamily.entrySet())
                familyToMembers.computeIfAbsent(e.getValue(), k -> new java.util.ArrayList<>()).add(e.getKey());
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : entityToFamily.entrySet())
                result.put(e.getKey(), List.copyOf(familyToMembers.get(e.getValue())));
            FAMILIES_CACHE = result;
            return result;
        }
    }

    private static @NotNull Map<String, EntityModelData> readGeometriesJsonResource(@NotNull String path, boolean required) {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(path)) {
            if (stream == null) {
                if (required)
                    throw new PipelineException("Entity geometry resource '%s' not found on the classpath", path);
                return new LinkedHashMap<>();
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("geometries")) return new LinkedHashMap<>();
            JsonObject geometriesJson = root.getAsJsonObject("geometries");
            Map<String, EntityModelData> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : geometriesJson.entrySet()) {
                if (entry.getKey().startsWith("//")) continue;
                out.put(entry.getKey(), GSON.fromJson(entry.getValue(), EntityModelData.class));
            }
            return out;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity geometry resource '%s'", path);
        }
    }

    /** Reads {@link #MODELS_RESOURCE_PATH}. Returns an empty {@link JsonObject} when absent. */
    private static @NotNull JsonObject loadEntitiesBlock() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_RESOURCE_PATH)) {
            if (stream == null) return new JsonObject();
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities")) return new JsonObject();
            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load Java entity models resource '%s'", MODELS_RESOURCE_PATH);
        }
    }

}
