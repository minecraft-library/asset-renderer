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
import lib.minecraft.renderer.tooling.ToolingBindPoses;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.entity.BindPoseDiscovery;
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
 * Loads bundled entity model definitions from three paired classpath resources:
 * {@code /lib/minecraft/renderer/entity_models.json} holds per-entity metadata
 * (geometry reference, texture reference, armor type, optional {@code variant_of} back-link);
 * {@code /lib/minecraft/renderer/entity_geometry.json} holds the deduplicated bone/cube trees;
 * and {@code /lib/minecraft/renderer/entity_models_overrides.json} carries hand-edited
 * corrections that cannot be auto-derived from the Bedrock vanilla resource pack (e.g.
 * per-entity {@code inventory_y_rotation} for mobs whose Bedrock-authored orientation faces
 * away from the chosen iso camera, or per-bone pose tweaks).
 * <p>
 * A {@code texture_ref} is the Bedrock {@code textures/entity/} sub-path stripped of its prefix
 * ({@code "cow/cow_v2"}, {@code "wither_boss/wither"}) - resolved at render time against the
 * on-disk bedrock cache at
 * {@code <cacheRoot>/bedrock/<bedrockRef>/textures/entity/&lt;ref&gt;.png} via
 * {@link PipelineRendererContext#resolveBedrockEntityTexture(String)}. The PNGs themselves are
 * extracted verbatim from the pinned {@code Mojang/bedrock-samples} pack by
 * {@link lib.minecraft.renderer.pipeline.Pipeline#extractBedrockEntityTextures(java.nio.file.Path,
 * java.nio.file.Path, java.nio.file.Path, boolean) Pipeline.extractBedrockEntityTextures}; the
 * entity pipeline has no dependency on Java's texture atlas.
 * <p>
 * Bedrock ships one geometry file per <i>base</i> model but the Java {@code EntityType} registry
 * has many entities sharing one geometry (e.g. {@code horse}, {@code donkey}, {@code mule},
 * {@code skeleton_horse}, {@code zombie_horse} all reference {@code geometry.horse}). Splitting
 * the data into two files lets each entity metadata row be a few hundred bytes while the
 * potentially-multi-kilobyte bone tree is stored exactly once.
 * <p>
 * At runtime the loader joins them back together - each entity's {@code geometry_ref} is
 * resolved against the geometry file, overlaid with any override row, and packaged into a
 * combined {@link EntityDefinition}, so callers see the same API as before the split.
 *
 * @see ToolingEntityModels.Parser
 * @see PipelineRendererContext
 */
@UtilityClass
public class EntityModelLoader {

    private static final @NotNull String MODELS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry.json";
    private static final @NotNull String OVERRIDES_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models_overrides.json";
    private static final @NotNull String BIND_POSES_RESOURCE_PATH = "/lib/minecraft/renderer/entity_bind_poses.json";

    /** Java-derived models file; produced by {@code ToolingJavaEntityModels} (variant 2a research plan). */
    private static final @NotNull String MODELS_JAVA_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models_java.json";

    /** Java-derived geometry file; bones in Java-native Y-down absolute entity-root frame. */
    private static final @NotNull String GEOMETRY_JAVA_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry_java.json";

    /**
     * Hand-edited geometries merged on top of {@link #GEOMETRY_JAVA_RESOURCE_PATH}. The bytecode
     * tooling can't reach every Java-pipeline geometry (e.g. {@code HumanoidModel.createMesh}'s
     * standalone player-humanoid output, used by {@code SkeletonClothingLayer}-shaped overlays);
     * this file is the escape hatch for those. Entries here survive tooling regenerations.
     */
    private static final @NotNull String GEOMETRY_JAVA_HANDEDITS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry_java_handedits.json";

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * An entity definition loaded from the bundled resources.
     *
     * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
     * @param textureRef the Bedrock-namespace texture sub-path (without the {@code .png} suffix)
     *     resolved at render time against {@code <cacheRoot>/bedrock/<bedrockRef>/textures/entity/}
     *     via {@link PipelineRendererContext#resolveBedrockEntityTexture(String)}, or empty when
     *     the Bedrock client_entity.json did not declare a default texture
     * @param overlays additional geometry/texture pairs rendered on top of the base model in
     *     declared order; populated from the overrides {@code overlays} array for entities that
     *     vanilla composes from multiple layers (charged creeper armor, copper golem holding a
     *     flower)
     * @param forceOpaque when {@code true} every partial-alpha texel in the entity's bundled
     *     texture is bumped to {@code alpha=255} at load time. Used by entities whose Bedrock
     *     texture is authored at low alpha for an additive-blending pass that the static iso
     *     renderer doesn't reproduce (blaze rods at alpha=90, magma cube at variable low
     *     alpha) - additive-over-transparent dims them to ~35%, while bumping to opaque shows
     *     them at the intended baked appearance. Also used for sheep whose ~600 partial-alpha
     *     wool-fluff texels would otherwise render as a translucent speckle. Read from the
     *     overrides {@code force_opaque} top-level field; defaults to {@code false}
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
     * Loads all bundled entity model definitions, joining the per-entity metadata against the
     * deduplicated geometry table.
     *
     * @return a map of entity id to definition
     * @throws PipelineException if either resource is missing or cannot be parsed, or any
     *     entity metadata row references a geometry id absent from the geometry table
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        Map<String, EntityModelData> geometries = loadGeometries();
        JsonObject entities = loadEntitiesBlock();
        JsonObject overrides = loadOverridesBlock();
        JsonObject bindPoses = loadBindPosesBlock();

        HashMap<String, EntityDefinition> definitions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();

            if (!entityJson.has("geometry_ref"))
                throw new PipelineException(
                    "Entity '%s' in '%s' has no geometry_ref", entityId, MODELS_RESOURCE_PATH
                );
            JsonObject override = overrides.has(entityId) && overrides.get(entityId).isJsonObject()
                ? overrides.getAsJsonObject(entityId)
                : null;

            // geometry_ref override lets us reroute an entity to a sibling Bedrock geometry -
            // e.g. the wool-overlay geometry.sheep.v1.8 inherits from geometry.sheep.sheared.v1.8
            // via the `A:B` Bedrock syntax, which the generator's flat-bones parser does not
            // honour; pointing plain sheep at the sheared geometry renders the base body correctly
            // until the parser learns the inheritance rules. Override values that don't resolve
            // here (Java-pipeline-only redirects like {@code geometry.cold_cow}) fall back to the
            // entity's own bedrock-side geometry so the bedrock pipeline keeps loading.
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

            // texture_ref precedence: override > generated entity row > absent. The override
            // exists for mobs whose Bedrock-declared texture path diverges from the bundled set
            // (e.g. pointing a variant entity at a sibling's PNG that the pack already ships).
            Optional<String> textureRef;
            if (override != null && override.has("texture_ref"))
                textureRef = Optional.of(override.get("texture_ref").getAsString());
            else if (entityJson.has("texture_ref"))
                textureRef = Optional.of(entityJson.get("texture_ref").getAsString());
            else
                textureRef = Optional.empty();

            // inventory_y_rotation from the override replaces the geometry's stored value.
            // bone_overrides lets an override layer patch per-bone pivot/rotation when the
            // Bedrock file doesn't encode them. bind_poses is an additional layer - Java
            // client ModelPart rotations scraped via {@link ToolingBindPoses} that stand in
            // for the Bedrock animation system's rest pose on mobs whose 1.21+ .geo.json
            // dropped {@code bind_pose_rotation} in favour of animations. All three layers
            // clone the model so sibling entities sharing a geometry_ref stay isolated.
            JsonObject bindPose = bindPoses.has(entityId) && bindPoses.get(entityId).isJsonObject()
                ? bindPoses.getAsJsonObject(entityId)
                : null;
            EntityModelData model = baseModel;
            boolean boneMutated = override != null && override.has("bone_overrides");
            boolean yMutated = override != null && override.has("inventory_y_rotation");
            boolean bindPoseMutated = bindPose != null && !bindPose.isEmpty();
            boolean hiddenMutated = override != null && override.has("hidden_bones");
            boolean extraBonesMutated = override != null && override.has("extra_bones");
            if (boneMutated || yMutated || bindPoseMutated || hiddenMutated || extraBonesMutated) {
                float yRotation = yMutated
                    ? override.get("inventory_y_rotation").getAsFloat()
                    : baseModel.getInventoryYRotation();
                LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(baseModel.getBones());
                if (bindPoseMutated)
                    applyBindPoses(bones, bindPose, entityId);
                if (boneMutated)
                    applyBoneOverrides(bones, override.getAsJsonObject("bone_overrides"), entityId);
                // extra_bones runs before hidden_bones so a new bone can template from a
                // bone that is itself about to be hidden (blaze: 12 rods all stacked at
                // upperBodyParts0..11; we template 12 distributed copies from upperBodyParts0
                // then hide the originals).
                if (extraBonesMutated)
                    applyExtraBones(bones, override.getAsJsonArray("extra_bones"), entityId);
                if (hiddenMutated)
                    applyHiddenBones(bones, override.getAsJsonArray("hidden_bones"), entityId);
                model = new EntityModelData(
                    baseModel.getTextureWidth(),
                    baseModel.getTextureHeight(),
                    yRotation,
                    Concurrent.adoptLinkedMap(bones)
                );
            }

            // Overlay layers come from two sources, concatenated in this order so manual
            // entries always extend (never replace) auto-generated ones:
            //   1. entity_models.json - auto-generated by ToolingEntityModels' Java-jar
            //      emissive extraction pass; populates emissive eye overlays for entities in
            //      EMISSIVE_PNG_FANOUT (spider, cave_spider, ender_dragon, etc.).
            //   2. entity_models_overrides.json - hand-edited overlays for cases the tooling
            //      can't auto-detect (slime translucent shell, copper golem flower, charged
            //      creeper armor, etc.).
            // Overlays whose geometry_ref matches the base entity's geometry_ref reuse the
            // post-override base model so they inherit any bone_overrides / bind_poses /
            // extra_bones / hidden_bones applied to the base. Critical for emissive eye
            // overlays on entities whose head bone got shifted (enderman head cube_offset
            // [0, 14, 0]) - without this the overlay's eye texture would land on the
            // unshifted head position and miss the rendered head entirely.
            List<OverlayLayer> overlays = new ArrayList<>();
            if (entityJson.has("overlays") && entityJson.get("overlays").isJsonArray())
                overlays.addAll(loadOverlays(entityJson.getAsJsonArray("overlays"), geometries, geometryRef, model, entityId));
            if (override != null && override.has("overlays"))
                overlays.addAll(loadOverlays(override.getAsJsonArray("overlays"), geometries, geometryRef, model, entityId));

            // force_opaque opts an entity into runtime alpha-bumping of its bundled texture.
            // Replaces the legacy ToolingEntityModels.OPAQUE_ALPHA_TEXTURE_REFS hardcoded set:
            // the bump now lives next to the entity's other overrides instead of as a tooling
            // constant, and the bundled PNG stays unmutated (matches what Bedrock ships).
            boolean forceOpaque = override != null && override.has("force_opaque")
                && override.get("force_opaque").getAsBoolean();

            definitions.put(entityId, new EntityDefinition(model, textureRef, overlays, forceOpaque));
        }

        return Concurrent.adoptMap(definitions).toUnmodifiable();
    }

    /**
     * Resolves the overrides {@code overlays} array into a list of {@link OverlayLayer}s. Each
     * entry is an object with a {@code geometry_ref} (resolved against the geometry table), an
     * optional {@code texture_ref} (the bundled PNG sub-path; absent means the overlay reuses
     * the base entity's texture), and an optional {@code inflate} (additive cube inflate applied
     * to every cube on the overlay model so it surrounds the base mesh instead of z-fighting -
     * the {@link EntityModelData.Cube#getInflate() inflate} field grows the cube outward by N
     * units on each face, matching the Bedrock cube semantics and Java's armor-layer convention).
     * Entries that name an unknown geometry log a warning and drop - matches the lenient
     * handling in {@link #applyBoneOverrides} so a stale override after a geometry regen doesn't
     * abort the whole load.
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
     * Applies per-bone patches from the overrides layer. Keys are bone names matching a bone on
     * the geometry; values are objects carrying optional fields:
     * <ul>
     *   <li>{@code rotation} - three-element pitch/yaw/roll in degrees, overrides the bone's
     *       propagating rotation. When set without an explicit {@code pivot}, the rotation
     *       anchor defaults to the bone's collective cube bounding-box center - "rotate in
     *       place" is the obvious default. Set an explicit {@code pivot} when you want
     *       joint-articulation behavior (rotate a limb about a body joint).</li>
     *   <li>{@code pivot} - three-element array in Bedrock-native Y-up absolute entity-root
     *       space, overrides the bone's rotation anchor. Use this for limb articulation where
     *       the rotation should hinge at a specific point (shoulder, hip, neck) rather than
     *       the bone's center.</li>
     *   <li>{@code cube_offset} - three-element {@code [dx, dy, dz]} translation applied to
     *       every cube's {@code origin} and {@code pivot}. Fills the gap Bedrock leaves for
     *       Molang-driven positional duplicates: {@code geometry.dragon} authors
     *       {@code wingtip1} / {@code rearfoot1} / &amp;c. with cube origins identical to their
     *       base siblings and relies on the animation engine to translate the right-hand
     *       mirror. Without Molang the duplicates stack on top of their sibling - a static
     *       {@code cube_offset} replaces the missing animation translation.</li>
     *   <li>{@code cube_mirror} - boolean that toggles every cube's {@code mirror} flag (the
     *       classic Bedrock cube U-axis flip). Used on the right-side duplicates whose left
     *       siblings are authored with non-mirror UVs: {@code cube_offset} moves the cubes,
     *       {@code cube_mirror} swaps their outward-facing texture so a dragon-wing fin points
     *       outward on both sides instead of toward the body.</li>
     *   <li>{@code cube_overrides} - array indexed by cube position on the bone; each entry
     *       may carry its own {@code origin_offset} (three floats) to move that single cube.
     *       {@code null} / absent entries leave the cube unchanged. Used where one cube on a
     *       multi-cube bone needs a translation the others shouldn't share (ender dragon
     *       wing bone carries a leading-edge bar plus a zero-thickness membrane quad - the
     *       membrane needs to flip to the opposite Z side so its zigzag trailing edge intersects
     *       the bar, while the bar stays in place).</li>
     * </ul>
     * Missing bones are logged on {@code stderr} rather than fatal so a stale override after a
     * geometry regen doesn't break the whole load.
     */
    private static void applyBoneOverrides(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull JsonObject boneOverrides,
        @NotNull String entityId
    ) {
        for (Map.Entry<String, JsonElement> e : boneOverrides.entrySet()) {
            String boneName = e.getKey();
            if (!e.getValue().isJsonObject()) continue;
            JsonObject patch = e.getValue().getAsJsonObject();
            EntityModelData.Bone existing = bones.get(boneName);
            if (existing == null) {
                System.err.printf("  Warning: entity '%s' bone_overrides names bone '%s' which is not on the geometry%n",
                    entityId, boneName);
                continue;
            }
            // Rotation is parsed before pivot so we can detect "rotation set without explicit
            // pivot" and default the pivot to the bone's cube collective bbox center - making
            // `rotation` mean "rotate in place" by default. Joint articulation (rotating a limb
            // about a body joint) requires an explicit `pivot` to opt into bone-pivot semantics.
            EulerRotation rotation = existing.getRotation();
            boolean rotationSet = patch.has("rotation");
            if (rotationSet) {
                JsonElement rot = patch.get("rotation");
                if (rot.isJsonArray() && rot.getAsJsonArray().size() == 3) {
                    rotation = new EulerRotation(
                        rot.getAsJsonArray().get(0).getAsFloat(),
                        rot.getAsJsonArray().get(1).getAsFloat(),
                        rot.getAsJsonArray().get(2).getAsFloat()
                    );
                }
            }

            Vector3f pivot = existing.getPivot();
            boolean pivotExplicit = patch.has("pivot");
            if (pivotExplicit) {
                JsonElement pv = patch.get("pivot");
                if (pv.isJsonArray() && pv.getAsJsonArray().size() == 3) {
                    pivot = new Vector3f(
                        pv.getAsJsonArray().get(0).getAsFloat(),
                        pv.getAsJsonArray().get(1).getAsFloat(),
                        pv.getAsJsonArray().get(2).getAsFloat()
                    );
                }
            } else if (rotationSet && !rotation.equals(EulerRotation.NONE)) {
                pivot = collectiveCubeCenter(existing.getCubes(), existing.getPivot());
            }

            ConcurrentList<EntityModelData.Cube> cubes = existing.getCubes();
            float dx = 0f, dy = 0f, dz = 0f;
            if (patch.has("cube_offset")) {
                JsonElement off = patch.get("cube_offset");
                if (off.isJsonArray() && off.getAsJsonArray().size() == 3) {
                    dx = off.getAsJsonArray().get(0).getAsFloat();
                    dy = off.getAsJsonArray().get(1).getAsFloat();
                    dz = off.getAsJsonArray().get(2).getAsFloat();
                    pivot = pivot.add(new Vector3f(dx, dy, dz));
                }
            }
            boolean mirror = patch.has("cube_mirror") && patch.get("cube_mirror").getAsBoolean();
            if (dx != 0f || dy != 0f || dz != 0f || mirror)
                cubes = rewriteCubes(existing.getCubes(), dx, dy, dz, mirror);
            if (patch.has("cube_overrides") && patch.get("cube_overrides").isJsonArray())
                cubes = applyPerCubeOverrides(cubes, patch.getAsJsonArray("cube_overrides"));
            String parent = existing.getParent();
            if (patch.has("parent"))
                parent = patch.get("parent").isJsonNull() ? null : patch.get("parent").getAsString();

            bones.put(boneName, new EntityModelData.Bone(
                pivot, rotation, existing.getBindPoseRotation(),
                existing.getScale(), cubes, parent
            ));
        }
    }

    /**
     * Applies per-cube override entries to {@code cubes}. Each array entry is either absent,
     * {@code null}, or a JSON object; when an entry is an object it may carry:
     * <ul>
     *   <li>{@code origin_offset} - three-float translation applied to cube's origin and (when
     *       no {@code pivot} replacement is provided) pivot.</li>
     *   <li>{@code pivot} - three-float absolute Bedrock-space pivot that <i>replaces</i> the
     *       cube's pivot (does not stack with {@code origin_offset}). Used to anchor the
     *       cube's rotation at an arbitrary point.</li>
     *   <li>{@code rotation} - three-float pitch/yaw/roll in degrees, replacing the cube's
     *       own rotation. When set without an explicit {@code pivot}, the rotation anchor
     *       defaults to the cube's own (post-{@code origin_offset}) bounding-box center -
     *       "rotate the cube in place" is the obvious default, and the rotation does not
     *       cause a surprise translation.</li>
     *   <li>{@code uv} - two-int {@code [u, v]} that replaces the cube's texture origin on
     *       the strip atlas. Used when an extra-bone clone needs to sample a different
     *       region of the same texture (slime outer shell at uv {@code [0, 0]} cloned from
     *       the inner cube at uv {@code [0, 16]}).</li>
     *   <li>{@code inflate} - float that replaces the cube's inflate value, expanding the
     *       cube outward by N units on every face. Combine with {@code uv} to template a
     *       slightly larger overlay shell from the same source cube.</li>
     *   <li>{@code size} - three-float {@code [sx, sy, sz]} that replaces the cube's size.
     *       Rare; most overlay needs are met by {@code inflate}.</li>
     * </ul>
     * Entries past the cube count are ignored; shorter arrays leave trailing cubes untouched.
     */
    private static @NotNull ConcurrentList<EntityModelData.Cube> applyPerCubeOverrides(
        @NotNull ConcurrentList<EntityModelData.Cube> cubes,
        @NotNull JsonArray overrides
    ) {
        ArrayList<EntityModelData.Cube> out = new ArrayList<>(cubes.size());
        for (int i = 0; i < cubes.size(); i++) {
            EntityModelData.Cube c = cubes.get(i);
            if (i >= overrides.size() || !overrides.get(i).isJsonObject()) {
                out.add(c);
                continue;
            }
            JsonObject o = overrides.get(i).getAsJsonObject();
            float dx = 0f, dy = 0f, dz = 0f;
            if (o.has("origin_offset") && o.get("origin_offset").isJsonArray()
                && o.getAsJsonArray("origin_offset").size() == 3) {
                dx = o.getAsJsonArray("origin_offset").get(0).getAsFloat();
                dy = o.getAsJsonArray("origin_offset").get(1).getAsFloat();
                dz = o.getAsJsonArray("origin_offset").get(2).getAsFloat();
            }
            EulerRotation rot = c.getRotation();
            boolean rotSet = o.has("rotation") && o.get("rotation").isJsonArray()
                && o.getAsJsonArray("rotation").size() == 3;
            if (rotSet) {
                rot = new EulerRotation(
                    o.getAsJsonArray("rotation").get(0).getAsFloat(),
                    o.getAsJsonArray("rotation").get(1).getAsFloat(),
                    o.getAsJsonArray("rotation").get(2).getAsFloat()
                );
            }
            Vector3f origin = c.getOrigin();
            Vector3f size = c.getSize();
            if (o.has("size") && o.get("size").isJsonArray()
                && o.getAsJsonArray("size").size() == 3) {
                size = new Vector3f(
                    o.getAsJsonArray("size").get(0).getAsFloat(),
                    o.getAsJsonArray("size").get(1).getAsFloat(),
                    o.getAsJsonArray("size").get(2).getAsFloat()
                );
            }
            Vector2f uv = c.getUv();
            if (o.has("uv") && o.get("uv").isJsonArray()
                && o.getAsJsonArray("uv").size() == 2) {
                uv = new Vector2f(
                    o.getAsJsonArray("uv").get(0).getAsInt(),
                    o.getAsJsonArray("uv").get(1).getAsInt()
                );
            }
            float inflate = o.has("inflate") ? o.get("inflate").getAsFloat() : c.getInflate();
            Vector3f pivot = c.getPivot();
            // Pivot precedence:
            //   1. Explicit `pivot` field - absolute Bedrock-space coords, used verbatim.
            //   2. Implicit, when `rotation` is set without `pivot`: defaults to the cube's
            //      own (post-origin_offset) bbox center, so a per-cube rotation rotates the
            //      cube in place without a surprise translation.
            //   3. Default: existing cube pivot shifted by origin_offset.
            Vector3f offset = new Vector3f(dx, dy, dz);
            Vector3f newPivot;
            if (o.has("pivot") && o.get("pivot").isJsonArray()
                && o.getAsJsonArray("pivot").size() == 3) {
                newPivot = new Vector3f(
                    o.getAsJsonArray("pivot").get(0).getAsFloat(),
                    o.getAsJsonArray("pivot").get(1).getAsFloat(),
                    o.getAsJsonArray("pivot").get(2).getAsFloat()
                );
            } else if (rotSet && !rot.equals(EulerRotation.NONE)) {
                newPivot = origin.add(offset).add(size.multiply(0.5f));
            } else {
                newPivot = pivot.add(offset);
            }
            out.add(new EntityModelData.Cube(
                origin.add(offset),
                size,
                uv,
                inflate,
                c.isMirror(),
                newPivot,
                rot,
                c.getFaceUv()
            ));
        }
        return Concurrent.adoptList(out);
    }

    /**
     * Computes the collective bounding-box center of {@code cubes} in absolute Bedrock-space.
     * Used as the implicit rotation pivot when {@code rotation} is set on a bone override
     * without an explicit {@code pivot} - rotation defaults to "rotate in place about the
     * cube assembly's own center" rather than the bone's authored pivot, which is often at
     * a joint and would translate the cubes when rotated.
     *
     * @param cubes the bone's cubes, may be empty
     * @param fallback the bone's existing pivot, returned when {@code cubes} is empty
     * @return the collective bbox center, or {@code fallback} when empty
     */
    private static @NotNull Vector3f collectiveCubeCenter(
        @NotNull ConcurrentList<EntityModelData.Cube> cubes,
        @NotNull Vector3f fallback
    ) {
        if (cubes.isEmpty()) return fallback;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (EntityModelData.Cube c : cubes) {
            Vector3f o = c.getOrigin();
            Vector3f s = c.getSize();
            minX = Math.min(minX, o.x());
            minY = Math.min(minY, o.y());
            minZ = Math.min(minZ, o.z());
            maxX = Math.max(maxX, o.x() + s.x());
            maxY = Math.max(maxY, o.y() + s.y());
            maxZ = Math.max(maxZ, o.z() + s.z());
        }
        return new Vector3f((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
    }

    /**
     * Returns a copy of {@code source} with every cube's {@code origin} and {@code pivot}
     * translated by {@code (dx, dy, dz)} and (optionally) with the cube {@code mirror} flag
     * toggled on. Preserves cube {@code size}, {@code uv}, {@code inflate}, {@code rotation},
     * and {@code face_uv}.
     * <p>
     * {@code mirror=true} flips the cube's U texture axis per-face (the standard Bedrock cube
     * mirror flag). Used by right-side duplicates on mobs whose left siblings are authored with
     * non-mirror UVs - the duplicate needs mirrored textures so its outward-facing features
     * (zigzag fins on the ender dragon wing membrane) point outward instead of toward the body.
     */
    private static @NotNull ConcurrentList<EntityModelData.Cube> rewriteCubes(
        @NotNull ConcurrentList<EntityModelData.Cube> source,
        float dx, float dy, float dz, boolean mirror
    ) {
        ConcurrentList<EntityModelData.Cube> out = Concurrent.newList();
        Vector3f offset = new Vector3f(dx, dy, dz);
        for (EntityModelData.Cube c : source) {
            EntityModelData.Cube rewritten = new EntityModelData.Cube(
                c.getOrigin().add(offset),
                c.getSize(),
                c.getUv(),
                c.getInflate(),
                mirror ? !c.isMirror() : c.isMirror(),
                c.getPivot().add(offset),
                c.getRotation(),
                c.getFaceUv()
            );
            out.add(rewritten);
        }
        return out;
    }

    /**
     * Adds new bones to the cloned bone map by templating from an existing bone on the same
     * geometry. Each entry is an object with:
     * <ul>
     *   <li>{@code name} - name of the new bone.</li>
     *   <li>{@code template} - name of an existing bone to clone its cubes/rotation from.</li>
     *   <li>{@code offset} - three-float {@code [dx, dy, dz]} applied to every cube origin,
     *       cube pivot, and the new bone's pivot.</li>
     *   <li>{@code parent} - optional parent bone name; defaults to the template's parent.</li>
     *   <li>{@code rotation} - optional three-float pitch/yaw/roll for the new bone.</li>
     *   <li>{@code cube_overrides} - optional per-cube modifications applied to the templated
     *       and offset cubes via {@link #applyPerCubeOverrides} - same field set as the
     *       bone-level {@code cube_overrides} (origin_offset, rotation, pivot, uv, inflate,
     *       size). Used to repurpose a templated cube into a translucent overlay shell that
     *       samples a different texture region (slime outer cube cloned from the inner cube,
     *       set to {@code uv [0, 0]} and {@code inflate 1.0}).</li>
     * </ul>
     * Fills the gap Bedrock leaves for Molang-driven segmented parts: the ender dragon tail
     * is rendered as 12 repeated neck-bone copies placed at increasing Z via animation; this
     * override materialises them as real bones without the animation engine.
     */
    private static void applyExtraBones(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull JsonArray extra,
        @NotNull String entityId
    ) {
        for (JsonElement el : extra) {
            if (!el.isJsonObject()) continue;
            JsonObject spec = el.getAsJsonObject();
            if (!spec.has("name") || !spec.has("template") || !spec.has("offset")) continue;
            String newName = spec.get("name").getAsString();
            String templateName = spec.get("template").getAsString();
            EntityModelData.Bone template = bones.get(templateName);
            if (template == null) {
                System.err.printf("  Warning: entity '%s' extra_bones '%s' references template '%s' which is not on the geometry%n",
                    entityId, newName, templateName);
                continue;
            }
            JsonArray offArr = spec.getAsJsonArray("offset");
            if (offArr.size() != 3) continue;
            float dx = offArr.get(0).getAsFloat();
            float dy = offArr.get(1).getAsFloat();
            float dz = offArr.get(2).getAsFloat();
            ConcurrentList<EntityModelData.Cube> shifted =
                rewriteCubes(template.getCubes(), dx, dy, dz, false);
            if (spec.has("cube_overrides") && spec.get("cube_overrides").isJsonArray())
                shifted = applyPerCubeOverrides(shifted, spec.getAsJsonArray("cube_overrides"));
            Vector3f newPivot = template.getPivot().add(new Vector3f(dx, dy, dz));
            EulerRotation rotation = template.getRotation();
            if (spec.has("rotation") && spec.get("rotation").isJsonArray()
                && spec.getAsJsonArray("rotation").size() == 3) {
                rotation = new EulerRotation(
                    spec.getAsJsonArray("rotation").get(0).getAsFloat(),
                    spec.getAsJsonArray("rotation").get(1).getAsFloat(),
                    spec.getAsJsonArray("rotation").get(2).getAsFloat()
                );
            }
            String parent = template.getParent();
            if (spec.has("parent") && !spec.get("parent").isJsonNull())
                parent = spec.get("parent").getAsString();
            bones.put(newName, new EntityModelData.Bone(
                newPivot, rotation, template.getBindPoseRotation(),
                template.getScale(), shifted, parent
            ));
        }
    }

    /**
     * Strips bones named in the overrides {@code hidden_bones} array from the cloned bone map.
     * Bedrock geometries pack every optional render target into one tree ({@code geometry.horse}
     * carries {@code Bag1}/{@code Bag2} for donkey/mule chest bags, plus every {@code Saddle*}
     * cube), relying on the Java-side renderer's entity-state flags to gate them. This renderer
     * has no live entity state, so plain horses and the undead variants (skeleton, zombie) would
     * otherwise show saddlebags and a saddle they should not carry. Missing bones log a warning
     * like {@link #applyBoneOverrides}.
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
     * XZ-plane pivot-distance tolerance for positional bone matching, in Bedrock units
     * (pixels of the texture grid). Java and Bedrock use different Y baselines - Java's
     * spider legs sit at Y=15 while Bedrock's sit at Y=9, Java cat body at Y=12 vs
     * Bedrock Y=7 - because the two conventions disagree on where the entity origin sits
     * (feet vs. mid-body). X and Z values stay aligned though, so the primary match runs
     * on the horizontal plane; {@link #PIVOT_MATCH_Y_CAP} adds an orthogonal guard.
     */
    private static final float PIVOT_MATCH_TOLERANCE = 1.5f;

    /**
     * Maximum permitted {@code |dy|} when positionally matching. The Java scraper emits
     * parent-local pivots (see
     * {@link BindPoseDiscovery.Pose}, which calls out
     * the drift on nested bones) - humanoid {@code hat_rim} reports {@code pivot=(0,0,0)}
     * even though its world-space anchor is at the head, and the villager family's
     * {@code hat_rim} was snapping onto the body bone at {@code (0, 0, 0)} and tilting the
     * whole model 90&deg; upward. The cap sits above the spider baseline diff (6 units) so
     * legitimate quadruped matches still land, and below typical humanoid head-to-body gaps
     * (22-26 units) so cross-body mispairs drop.
     */
    private static final float PIVOT_MATCH_Y_CAP = 15.0f;

    /**
     * Epsilon for declaring a positional XZ match ambiguous. When two or more Bedrock
     * bones sit within this much of the best XZ distance to the Java target, positional
     * matching cannot disambiguate them - humanoid spine clusters ({@code head},
     * {@code body}, {@code nose}, {@code arms}) all share XZ=(0,0) so any Java bone
     * targeting XZ=0 with no exact-name match (e.g. {@code hat_rim}) would otherwise
     * snap onto whichever bone happened to iterate first. Returning {@code null} lets
     * those rotations drop rather than land on a wrong bone.
     */
    private static final float PIVOT_MATCH_AMBIGUITY_EPS = 0.25f;

    /**
     * Overlays {@link ToolingBindPoses bind-pose rotations}
     * scraped from Java client Model factories onto each bone's
     * {@link EntityModelData.Bone#getBindPoseRotation() bindPoseRotation}.
     * <p>
     * Three match strategies, tried in order until one wins:
     * <ol>
     *   <li><b>Exact name match</b>. Bedrock and Java agree on common bone names like
     *       {@code body}, {@code head}, {@code tail}.</li>
     *   <li><b>snake_case &rarr; camelCase</b>. Bedrock wolf uses {@code upperBody}; Java
     *       uses {@code upper_body}. Rewrite and retry.</li>
     *   <li><b>Positional match</b>. Find the Bedrock bone whose pivot is closest to the
     *       Java bone's pivot, within {@link #PIVOT_MATCH_TOLERANCE}. Breaks the
     *       spider-leg naming disagreement ({@code leg0}-{@code leg7} vs
     *       {@code left_front_leg} / {@code right_middle_hind_leg} / ...) without a
     *       hand-rolled per-mob dictionary.</li>
     * </ol>
     * Bones are skipped when the Bedrock geometry already encodes any non-zero rotation for
     * this pose: existing {@link EntityModelData.Bone#getBindPoseRotation() bindPoseRotation}
     * (legacy 1.8 Bedrock geometries like {@code sheep.v1.8} win), existing
     * {@link EntityModelData.Bone#getRotation() bone rotation} (modern {@code hoglin} head
     * ships {@code rotation: [50, 0, 0]} already - layering Java's identical 50&deg; on top
     * would double-rotate the head and face the model at the floor), or any cube-level
     * rotation (modern {@code cow.v2} / {@code mooshroom.v2} express the horizontal body via
     * a cube-level {@code rotation: [90, 0, 0]}).
     */
    private static void applyBindPoses(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull JsonObject bindPose,
        @NotNull String entityId
    ) {
        Set<String> consumedBedrockBones = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : bindPose.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject boneEntry = e.getValue().getAsJsonObject();
            JsonElement rotEl = boneEntry.get("rotation");
            if (rotEl == null || !rotEl.isJsonArray()) continue;
            var rotArr = rotEl.getAsJsonArray();
            if (rotArr.size() != 3) continue;

            Vector3f javaPivot = readPivot(boneEntry);
            String poseBone = matchBone(bones, e.getKey(), javaPivot, consumedBedrockBones);
            if (poseBone == null) continue;

            EntityModelData.Bone existing = bones.get(poseBone);
            if (!existing.getBindPoseRotation().equals(EulerRotation.NONE))
                continue;
            if (!existing.getRotation().equals(EulerRotation.NONE))
                continue;
            if (anyCubeHasRotation(existing))
                continue;

            EulerRotation pose = new EulerRotation(
                rotArr.get(0).getAsFloat(),
                rotArr.get(1).getAsFloat(),
                rotArr.get(2).getAsFloat()
            );
            bones.put(poseBone, new EntityModelData.Bone(
                existing.getPivot(),
                existing.getRotation(),
                pose,
                existing.getScale(),
                existing.getCubes(),
                existing.getParent()
            ));
            consumedBedrockBones.add(poseBone);
        }
    }

    /**
     * Extracts the {@code pivot} array from a bind-pose bone entry, returning {@code null}
     * when absent (legacy bind-pose files without pivot data). Pivot-less entries fall through
     * to name-only matching at the call site.
     */
    private static @Nullable Vector3f readPivot(@NotNull JsonObject boneEntry) {
        JsonElement pivotEl = boneEntry.get("pivot");
        if (pivotEl == null || !pivotEl.isJsonArray()) return null;
        var arr = pivotEl.getAsJsonArray();
        if (arr.size() != 3) return null;
        return new Vector3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
    }

    /**
     * Returns {@code true} when any cube on {@code bone} carries a non-zero {@code rotation}.
     * Modern Bedrock geometries express quadruped body poses via cube-level rotations rather
     * than the bone-level {@code bind_pose_rotation} field - the Java bind-pose shouldn't layer
     * on top of that.
     */
    private static boolean anyCubeHasRotation(@NotNull EntityModelData.Bone bone) {
        for (EntityModelData.Cube cube : bone.getCubes()) {
            EulerRotation r = cube.getRotation();
            if (r.pitch() != 0f || r.yaw() != 0f || r.roll() != 0f) return true;
        }
        return false;
    }

    /**
     * Resolves a bind-pose bone key against the Bedrock geometry's actual bone set: exact
     * name first, then snake_case&rarr;camelCase, then positional nearest-pivot match through
     * {@link #nearestBoneByPivot}. {@code consumedBedrockBones} tracks which Bedrock bones
     * have already been paired with a Java bone so a second leg with a similar pivot can't
     * claim the same target.
     */
    private static @Nullable String matchBone(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull String key,
        @Nullable Vector3f javaPivot,
        @NotNull Set<String> consumedBedrockBones
    ) {
        if (bones.containsKey(key) && !consumedBedrockBones.contains(key)) return key;

        String camel = snakeToCamel(key);
        if (!camel.equals(key) && bones.containsKey(camel) && !consumedBedrockBones.contains(camel)) return camel;

        if (javaPivot == null) return null;
        return nearestBoneByPivot(bones, javaPivot, consumedBedrockBones);
    }

    /**
     * Finds the Bedrock bone whose {@link EntityModelData.Bone#getPivot() pivot} is closest to
     * {@code target} on the XZ plane, excluding bones already claimed in
     * {@code consumedBedrockBones}. Candidates beyond {@link #PIVOT_MATCH_TOLERANCE} on XZ or
     * {@link #PIVOT_MATCH_Y_CAP} on Y are rejected; among survivors XZ distance is the primary
     * metric with {@code |dy|} as the tiebreaker.
     * <p>
     * When two or more bones sit within {@link #PIVOT_MATCH_AMBIGUITY_EPS} of the best XZ
     * distance the match is declared ambiguous and {@code null} is returned. Humanoid models
     * cluster {@code head}, {@code body}, {@code arms}, {@code nose} all at XZ=(0,0) so a
     * Java-side pose for {@code hat_rim} (parent-local pivot {@code (0,0,0)}) could otherwise
     * pick any of them and silently rotate the wrong part of the model; rejecting ambiguous
     * matches lets the rotation drop rather than land on the wrong bone. Returns {@code null}
     * when no unique match survives the filters.
     */
    private static @Nullable String nearestBoneByPivot(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull Vector3f target,
        @NotNull Set<String> consumedBedrockBones
    ) {
        String best = null;
        float bestXz = Float.POSITIVE_INFINITY;
        float bestDy = Float.POSITIVE_INFINITY;
        for (Map.Entry<String, EntityModelData.Bone> e : bones.entrySet()) {
            if (consumedBedrockBones.contains(e.getKey())) continue;
            Vector3f p = e.getValue().getPivot();
            float dx = p.x() - target.x();
            float dy = p.y() - target.y();
            float dz = p.z() - target.z();
            float xz = (float) Math.sqrt(dx * dx + dz * dz);
            if (xz > PIVOT_MATCH_TOLERANCE) continue;
            if (Math.abs(dy) > PIVOT_MATCH_Y_CAP) continue;
            float ady = Math.abs(dy);
            if (xz < bestXz - PIVOT_MATCH_AMBIGUITY_EPS
                || (Math.abs(xz - bestXz) <= PIVOT_MATCH_AMBIGUITY_EPS && ady < bestDy)) {
                best = e.getKey();
                bestXz = xz;
                bestDy = ady;
            }
        }
        if (best == null) return null;

        // Ambiguity count ignores the Y cap on purpose: if several Bedrock bones share the
        // target's XZ column (humanoid head/body/nose/arms all at XZ=(0,0)) the target's XZ
        // value alone can't identify any of them, and the Y-cap filter could leave exactly
        // one survivor that still isn't the semantically-right bone. A parent-local Java
        // pivot in that cluster should drop, not anchor to whichever bone happens to pass
        // the Y cap.
        int similar = 0;
        for (Map.Entry<String, EntityModelData.Bone> e : bones.entrySet()) {
            if (consumedBedrockBones.contains(e.getKey())) continue;
            if (e.getKey().equals(best)) continue;
            Vector3f p = e.getValue().getPivot();
            float dx = p.x() - target.x();
            float dz = p.z() - target.z();
            float xz = (float) Math.sqrt(dx * dx + dz * dz);
            if (xz > PIVOT_MATCH_TOLERANCE) continue;
            if (Math.abs(xz - bestXz) <= PIVOT_MATCH_AMBIGUITY_EPS) similar++;
        }
        return similar == 0 ? best : null;
    }

    /** Converts {@code "upper_body"} to {@code "upperBody"}. Idempotent on camelCase input. */
    private static @NotNull String snakeToCamel(@NotNull String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean upperNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') { upperNext = true; continue; }
            out.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return out.toString();
    }

    /**
     * Parses {@code entity_geometry.json} into a map from geometry id to the parsed
     * {@link EntityModelData} bone tree.
     */
    private static @NotNull Map<String, EntityModelData> loadGeometries() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(GEOMETRY_RESOURCE_PATH)) {
            if (stream == null)
                throw new PipelineException("Entity geometry resource '%s' not found on the classpath", GEOMETRY_RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("geometries"))
                throw new PipelineException("Entity geometry resource '%s' has no 'geometries' object", GEOMETRY_RESOURCE_PATH);

            JsonObject geometriesJson = root.getAsJsonObject("geometries");
            Map<String, EntityModelData> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : geometriesJson.entrySet()) {
                EntityModelData model = GSON.fromJson(entry.getValue(), EntityModelData.class);
                out.put(entry.getKey(), model);
            }
            return out;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity geometry resource '%s'", GEOMETRY_RESOURCE_PATH);
        }
    }

    /**
     * Parses {@code entity_models.json} and returns its {@code entities} object for iteration.
     */
    private static @NotNull JsonObject loadEntitiesBlock() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_RESOURCE_PATH)) {
            if (stream == null)
                throw new PipelineException("Entity models resource '%s' not found on the classpath", MODELS_RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities"))
                throw new PipelineException("Entity models resource '%s' has no 'entities' object", MODELS_RESOURCE_PATH);

            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity models resource '%s'", MODELS_RESOURCE_PATH);
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
     * Side-by-side counterpart of {@link #load()} for the Java-derived pipeline. Reads
     * {@code entity_models_java.json} + {@code entity_geometry_java.json} (both produced by
     * {@code ToolingJavaEntityModels}) and emits the same {@link EntityDefinition} shape so the
     * sibling {@code EntityRenderer} can drop in. Skips overrides and bind poses entirely
     * for the first pass - those layers exist in the bedrock pipeline to compensate for Bedrock
     * authoring quirks the Java pipeline doesn't share, and the Java side will get its own
     * {@code entity_models_java_overrides.json} only when residual gaps surface.
     *
     * @return Java-derived definitions keyed by namespaced entity id (empty when files absent)
     * @throws PipelineException when one file is present but unparseable, or when an entity
     *     references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> loadJava() {
        Map<String, EntityModelData> geometries = loadGeometriesJava();
        if (geometries.isEmpty()) return Concurrent.newMap();

        JsonObject entities = loadEntitiesBlockJava();
        JsonObject overrides = loadOverridesBlock();
        HashMap<String, EntityDefinition> definitions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();
            if (!entityJson.has("geometry_ref")) continue;

            JsonObject override = overrides.has(entityId) && overrides.get(entityId).isJsonObject()
                ? overrides.getAsJsonObject(entityId)
                : null;

            // {@code geometry_ref} override redirects an entity to a different geometry,
            // matching the bedrock pipeline's contract. Used by cow_cold / chicken_cold etc.
            // to point variant entities at hand-edited variant geometries
            // ({@code geometry.cold_cow}, etc.) that the bytecode tooling can't reach -
            // ColdCowModel and friends bake separate {@code LayerDefinition}s registered under
            // {@code ModelLayers.COLD_COW} that don't surface as top-level rows in the
            // tooling's per-renderer scan. Overrides whose value doesn't resolve here
            // (bedrock-pipeline-only redirects like {@code geometry.sheep.sheared.v1.8}) fall
            // back to the entity's own Java-side {@code geometry_ref}.
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
                    "Java entity '%s' references geometry '%s' which is not present in '%s'",
                    entityId, geometryRef, GEOMETRY_JAVA_RESOURCE_PATH
                );

            // texture_ref precedence: java-style override > generated entity row > absent.
            // Used by TEXTURE_VARIANT entities whose vanilla renderer's getTextureLocation(state)
            // picks a specific variant texture at zero state (rabbit brown, axolotl lucy, cat
            // black) - the override pins that variant texture in place of the tooling-extracted
            // default. The override's value MUST contain a {@code /} (subdirectory separator)
            // to apply to the Java pipeline: Java's vanilla pack stores entity textures at
            // {@code textures/entity/<subdir>/<file>.png}, while the bedrock cache stores them
            // flat at {@code textures/entity/<file>.png}. Bedrock-style overrides (no slash,
            // e.g. {@code guardian_elder}) are intentionally skipped here so they don't break
            // the Java pipeline's vanilla-pack lookups - the bedrock-pipeline loader still
            // honours them.
            Optional<String> textureRef;
            String overrideTextureRef = override != null && override.has("texture_ref")
                ? override.get("texture_ref").getAsString()
                : null;
            if (overrideTextureRef != null && overrideTextureRef.contains("/"))
                textureRef = Optional.of(overrideTextureRef);
            else if (entityJson.has("texture_ref"))
                textureRef = Optional.of(entityJson.get("texture_ref").getAsString());
            else
                textureRef = Optional.empty();

            // Apply hand-edited overrides shared with the bedrock pipeline. The Java pipeline
            // honours {@code geometry_ref}, {@code texture_ref} (both above), {@code hidden_bones},
            // and {@code overlays}. Other override keys ({@code inventory_y_rotation},
            // {@code bone_overrides}, {@code bind_poses}, {@code extra_bones}) target the
            // bedrock pipeline's Bedrock-sourced geometry quirks and aren't applied here.
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

            // Phase E.4: overlays produced by JavaEntityOverlayResolver (emissive eye layers
            // first; composite layers in later passes). Mirrors the bedrock loader's overlay
            // flow but skips the override layer + bind-pose stack since the Java pipeline
            // ships without those. An overlay sharing the base geometry_ref reuses baseModel
            // verbatim (eye PNGs land on the same UV layout); a distinct geometry_ref resolves
            // freshly from the geometry table. Hand-edited overlays from
            // entity_models_overrides.json are appended after the auto-generated ones so the
            // override list adds to (rather than replaces) the tooling output - matches the
            // bedrock pipeline's order.
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
     * Reads {@link #GEOMETRY_JAVA_RESOURCE_PATH} and merges
     * {@link #GEOMETRY_JAVA_HANDEDITS_RESOURCE_PATH} on top (hand-edits take precedence on key
     * collision). Returns an empty map when the primary file is absent (so {@link #loadJava()}
     * can short-circuit to "no Java pipeline available" without throwing during environments
     * that haven't run {@code ToolingJavaEntityModels} yet).
     */
    private static @NotNull Map<String, EntityModelData> loadGeometriesJava() {
        Map<String, EntityModelData> out = readGeometriesJsonResource(GEOMETRY_JAVA_RESOURCE_PATH, /*required*/ false);
        Map<String, EntityModelData> handedits = readGeometriesJsonResource(GEOMETRY_JAVA_HANDEDITS_RESOURCE_PATH, /*required*/ false);
        out.putAll(handedits);
        return out;
    }

    /**
     * Cross-{@link EntityType}-style family overrides for the Java pipeline's family-fit pre-pass.
     * Mirrors the vanilla harness's {@code EntitySweeper.FAMILY_OVERRIDES} (variant-of-same-entity
     * groupings are derived from {@code variant_of} in entity_models_java.json; this map is for
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
     * membership is derived from {@code variant_of} in entity_models_java.json (variant entities
     * roll up to their declared root) plus {@link #FAMILY_OVERRIDES} (cross-entity groupings).
     * Singletons return a single-element list containing themselves so callers can iterate
     * uniformly without special-casing. The result is cached on first call - the JSON is loaded
     * once for the lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamiliesJava() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            JsonObject entities = loadEntitiesBlockJava();
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

    /** Reads {@link #MODELS_JAVA_RESOURCE_PATH}. Returns an empty {@link JsonObject} when absent. */
    private static @NotNull JsonObject loadEntitiesBlockJava() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_JAVA_RESOURCE_PATH)) {
            if (stream == null) return new JsonObject();
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities")) return new JsonObject();
            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load Java entity models resource '%s'", MODELS_JAVA_RESOURCE_PATH);
        }
    }

    /**
     * Parses {@code entity_bind_poses.json} and returns its {@code entities} object (empty when
     * the file is absent). Generated by
     * {@link ToolingBindPoses} from the Java
     * client jar; stands in for the Bedrock animation system on mobs whose modern
     * {@code .geo.json} dropped {@code bind_pose_rotation}.
     */
    private static @NotNull JsonObject loadBindPosesBlock() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(BIND_POSES_RESOURCE_PATH)) {
            if (stream == null) return new JsonObject();

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("entities")) return new JsonObject();

            return root.getAsJsonObject("entities");
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity bind poses resource '%s'", BIND_POSES_RESOURCE_PATH);
        }
    }

}
