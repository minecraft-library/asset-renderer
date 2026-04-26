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
 * bundled {@code /lib/minecraft/renderer/entity_textures/&lt;ref&gt;.png} classpath resource that
 * {@link ToolingEntityModels} copies out of the Bedrock pack alongside the geometry. The entity
 * pipeline has no dependency on Java's texture atlas.
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
    private static final @NotNull String OVERRIDES_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models_overrides.json";
    private static final @NotNull String BIND_POSES_RESOURCE_PATH = "/lib/minecraft/renderer/entity_bind_poses.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * An entity definition loaded from the bundled resources.
     *
     * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
     * @param textureRef the bundled texture sub-path under
     *     {@code /lib/minecraft/renderer/entity_textures/} (without the {@code .png} suffix), or
     *     empty when the Bedrock client_entity.json did not declare a default texture
     */
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef
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
        JsonObject overrides = loadOverridesBlock();
        JsonObject bindPoses = loadBindPosesBlock();

        ConcurrentMap<String, EntityDefinition> definitions = Concurrent.newMap();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();

            if (!entityJson.has("geometry_ref"))
                throw new AssetPipelineException(
                    "Entity '%s' in '%s' has no geometry_ref", entityId, MODELS_RESOURCE_PATH
                );
            JsonObject override = overrides.has(entityId) && overrides.get(entityId).isJsonObject()
                ? overrides.getAsJsonObject(entityId)
                : null;

            // geometry_ref override lets us reroute an entity to a sibling Bedrock geometry -
            // e.g. the wool-overlay geometry.sheep.v1.8 inherits from geometry.sheep.sheared.v1.8
            // via the `A:B` Bedrock syntax, which the generator's flat-bones parser does not
            // honour; pointing plain sheep at the sheared geometry renders the base body correctly
            // until the parser learns the inheritance rules.
            String geometryRef = override != null && override.has("geometry_ref")
                ? override.get("geometry_ref").getAsString()
                : entityJson.get("geometry_ref").getAsString();
            EntityModelData baseModel = geometries.get(geometryRef);
            if (baseModel == null)
                throw new AssetPipelineException(
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
                dev.simplified.collection.linked.ConcurrentLinkedMap<String, EntityModelData.Bone> bones =
                    dev.simplified.collection.Concurrent.newLinkedMap();
                bones.putAll(baseModel.getBones());
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
                    bones
                );
            }

            definitions.put(entityId, new EntityDefinition(model, textureRef));
        }

        return definitions;
    }

    /**
     * Applies per-bone patches from the overrides layer. Keys are bone names matching a bone on
     * the geometry; values are objects carrying optional fields:
     * <ul>
     *   <li>{@code rotation} - three-element pitch/yaw/roll in degrees, overrides the bone's
     *       propagating rotation.</li>
     *   <li>{@code pivot} - three-element array in Bedrock-native Y-up absolute entity-root
     *       space, overrides the bone's rotation anchor.</li>
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
        @NotNull dev.simplified.collection.linked.ConcurrentLinkedMap<String, EntityModelData.Bone> bones,
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
            float[] pivot = existing.getPivot();
            if (patch.has("pivot")) {
                JsonElement pv = patch.get("pivot");
                if (pv.isJsonArray() && pv.getAsJsonArray().size() == 3) {
                    pivot = new float[]{
                        pv.getAsJsonArray().get(0).getAsFloat(),
                        pv.getAsJsonArray().get(1).getAsFloat(),
                        pv.getAsJsonArray().get(2).getAsFloat()
                    };
                }
            }
            lib.minecraft.renderer.geometry.EulerRotation rotation = existing.getRotation();
            if (patch.has("rotation")) {
                JsonElement rot = patch.get("rotation");
                if (rot.isJsonArray() && rot.getAsJsonArray().size() == 3) {
                    rotation = new lib.minecraft.renderer.geometry.EulerRotation(
                        rot.getAsJsonArray().get(0).getAsFloat(),
                        rot.getAsJsonArray().get(1).getAsFloat(),
                        rot.getAsJsonArray().get(2).getAsFloat()
                    );
                }
            }

            dev.simplified.collection.ConcurrentList<EntityModelData.Cube> cubes = existing.getCubes();
            float dx = 0f, dy = 0f, dz = 0f;
            if (patch.has("cube_offset")) {
                JsonElement off = patch.get("cube_offset");
                if (off.isJsonArray() && off.getAsJsonArray().size() == 3) {
                    dx = off.getAsJsonArray().get(0).getAsFloat();
                    dy = off.getAsJsonArray().get(1).getAsFloat();
                    dz = off.getAsJsonArray().get(2).getAsFloat();
                    pivot = new float[]{ pivot[0] + dx, pivot[1] + dy, pivot[2] + dz };
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
                pivot, rotation, existing.getBindPoseRotation(), cubes, parent
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
     *       cube's own rotation at a point other than the bone pivot - in particular, setting
     *       it to the cube's geometric center makes a 180-deg rotation a pure UV remap (cube
     *       stays in place but the texture mapping rotates). Required when the bone pivot sits
     *       at one edge of a multi-cube assembly (ender dragon wing's bone pivots at the
     *       inner attachment but its zero-thickness membrane needs to spin about its own
     *       center to flip the wing-tip pixel from front-outer to back-outer).</li>
     *   <li>{@code rotation} - three-float pitch/yaw/roll in degrees, replacing the cube's
     *       own rotation. Anchored at the cube's resulting pivot. Lets a single cube on a
     *       multi-cube bone flip in place without disturbing its siblings.</li>
     * </ul>
     * Entries past the cube count are ignored; shorter arrays leave trailing cubes untouched.
     */
    private static @NotNull dev.simplified.collection.ConcurrentList<EntityModelData.Cube> applyPerCubeOverrides(
        @NotNull dev.simplified.collection.ConcurrentList<EntityModelData.Cube> cubes,
        @NotNull com.google.gson.JsonArray overrides
    ) {
        dev.simplified.collection.ConcurrentList<EntityModelData.Cube> out = dev.simplified.collection.Concurrent.newList();
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
            lib.minecraft.renderer.geometry.EulerRotation rot = c.getRotation();
            if (o.has("rotation") && o.get("rotation").isJsonArray()
                && o.getAsJsonArray("rotation").size() == 3) {
                rot = new lib.minecraft.renderer.geometry.EulerRotation(
                    o.getAsJsonArray("rotation").get(0).getAsFloat(),
                    o.getAsJsonArray("rotation").get(1).getAsFloat(),
                    o.getAsJsonArray("rotation").get(2).getAsFloat()
                );
            }
            float[] origin = c.getOrigin();
            float[] pivot = c.getPivot();
            // Default: pivot tracks origin_offset so per-cube rotation spins about the bone
            // attachment point. The optional `pivot` field replaces this entirely, anchoring
            // the rotation at an arbitrary absolute point (e.g. the cube's own center for a
            // pure-UV-remap 180 spin).
            float[] newPivot;
            if (o.has("pivot") && o.get("pivot").isJsonArray()
                && o.getAsJsonArray("pivot").size() == 3) {
                newPivot = new float[]{
                    o.getAsJsonArray("pivot").get(0).getAsFloat(),
                    o.getAsJsonArray("pivot").get(1).getAsFloat(),
                    o.getAsJsonArray("pivot").get(2).getAsFloat()
                };
            } else {
                newPivot = new float[]{ pivot[0] + dx, pivot[1] + dy, pivot[2] + dz };
            }
            out.add(new EntityModelData.Cube(
                new float[]{ origin[0] + dx, origin[1] + dy, origin[2] + dz },
                c.getSize(),
                c.getUv(),
                c.getInflate(),
                c.isMirror(),
                newPivot,
                rot,
                c.getFaceUv()
            ));
        }
        return out;
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
    private static @NotNull dev.simplified.collection.ConcurrentList<EntityModelData.Cube> rewriteCubes(
        @NotNull dev.simplified.collection.ConcurrentList<EntityModelData.Cube> source,
        float dx, float dy, float dz, boolean mirror
    ) {
        dev.simplified.collection.ConcurrentList<EntityModelData.Cube> out = dev.simplified.collection.Concurrent.newList();
        for (EntityModelData.Cube c : source) {
            float[] o = c.getOrigin();
            float[] p = c.getPivot();
            EntityModelData.Cube rewritten = new EntityModelData.Cube(
                new float[]{ o[0] + dx, o[1] + dy, o[2] + dz },
                c.getSize(),
                c.getUv(),
                c.getInflate(),
                mirror ? !c.isMirror() : c.isMirror(),
                new float[]{ p[0] + dx, p[1] + dy, p[2] + dz },
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
     * </ul>
     * Fills the gap Bedrock leaves for Molang-driven segmented parts: the ender dragon tail
     * is rendered as 12 repeated neck-bone copies placed at increasing Z via animation; this
     * override materialises them as real bones without the animation engine.
     */
    private static void applyExtraBones(
        @NotNull dev.simplified.collection.linked.ConcurrentLinkedMap<String, EntityModelData.Bone> bones,
        @NotNull com.google.gson.JsonArray extra,
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
            com.google.gson.JsonArray offArr = spec.getAsJsonArray("offset");
            if (offArr.size() != 3) continue;
            float dx = offArr.get(0).getAsFloat();
            float dy = offArr.get(1).getAsFloat();
            float dz = offArr.get(2).getAsFloat();
            dev.simplified.collection.ConcurrentList<EntityModelData.Cube> shifted =
                rewriteCubes(template.getCubes(), dx, dy, dz, false);
            float[] p = template.getPivot();
            float[] newPivot = new float[]{ p[0] + dx, p[1] + dy, p[2] + dz };
            lib.minecraft.renderer.geometry.EulerRotation rotation = template.getRotation();
            if (spec.has("rotation") && spec.get("rotation").isJsonArray()
                && spec.getAsJsonArray("rotation").size() == 3) {
                rotation = new lib.minecraft.renderer.geometry.EulerRotation(
                    spec.getAsJsonArray("rotation").get(0).getAsFloat(),
                    spec.getAsJsonArray("rotation").get(1).getAsFloat(),
                    spec.getAsJsonArray("rotation").get(2).getAsFloat()
                );
            }
            String parent = template.getParent();
            if (spec.has("parent") && !spec.get("parent").isJsonNull())
                parent = spec.get("parent").getAsString();
            bones.put(newName, new EntityModelData.Bone(
                newPivot, rotation, template.getBindPoseRotation(), shifted, parent
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
        @NotNull dev.simplified.collection.linked.ConcurrentLinkedMap<String, EntityModelData.Bone> bones,
        @NotNull com.google.gson.JsonArray hiddenBones,
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
     * {@link lib.minecraft.renderer.tooling.entity.BindPoseDiscovery.Pose}, which calls out
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
     * Overlays {@link lib.minecraft.renderer.tooling.ToolingBindPoses bind-pose rotations}
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
        @NotNull dev.simplified.collection.linked.ConcurrentLinkedMap<String, EntityModelData.Bone> bones,
        @NotNull JsonObject bindPose,
        @NotNull String entityId
    ) {
        java.util.Set<String> consumedBedrockBones = new java.util.HashSet<>();
        for (Map.Entry<String, JsonElement> e : bindPose.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject boneEntry = e.getValue().getAsJsonObject();
            JsonElement rotEl = boneEntry.get("rotation");
            if (rotEl == null || !rotEl.isJsonArray()) continue;
            var rotArr = rotEl.getAsJsonArray();
            if (rotArr.size() != 3) continue;

            float[] javaPivot = readPivot(boneEntry);
            String poseBone = matchBone(bones, e.getKey(), javaPivot, consumedBedrockBones);
            if (poseBone == null) continue;

            EntityModelData.Bone existing = bones.get(poseBone);
            if (!existing.getBindPoseRotation().equals(lib.minecraft.renderer.geometry.EulerRotation.NONE))
                continue;
            if (!existing.getRotation().equals(lib.minecraft.renderer.geometry.EulerRotation.NONE))
                continue;
            if (anyCubeHasRotation(existing))
                continue;

            lib.minecraft.renderer.geometry.EulerRotation pose = new lib.minecraft.renderer.geometry.EulerRotation(
                rotArr.get(0).getAsFloat(),
                rotArr.get(1).getAsFloat(),
                rotArr.get(2).getAsFloat()
            );
            bones.put(poseBone, new EntityModelData.Bone(
                existing.getPivot(),
                existing.getRotation(),
                pose,
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
    private static float @org.jetbrains.annotations.Nullable [] readPivot(@NotNull JsonObject boneEntry) {
        JsonElement pivotEl = boneEntry.get("pivot");
        if (pivotEl == null || !pivotEl.isJsonArray()) return null;
        var arr = pivotEl.getAsJsonArray();
        if (arr.size() != 3) return null;
        return new float[]{ arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat() };
    }

    /**
     * Returns {@code true} when any cube on {@code bone} carries a non-zero {@code rotation}.
     * Modern Bedrock geometries express quadruped body poses via cube-level rotations rather
     * than the bone-level {@code bind_pose_rotation} field - the Java bind-pose shouldn't layer
     * on top of that.
     */
    private static boolean anyCubeHasRotation(@NotNull EntityModelData.Bone bone) {
        for (EntityModelData.Cube cube : bone.getCubes()) {
            lib.minecraft.renderer.geometry.EulerRotation r = cube.getRotation();
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
    private static @org.jetbrains.annotations.Nullable String matchBone(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull String key,
        float @org.jetbrains.annotations.Nullable [] javaPivot,
        @NotNull java.util.Set<String> consumedBedrockBones
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
    private static @org.jetbrains.annotations.Nullable String nearestBoneByPivot(
        @NotNull Map<String, EntityModelData.Bone> bones,
        float @NotNull [] target,
        @NotNull java.util.Set<String> consumedBedrockBones
    ) {
        String best = null;
        float bestXz = Float.POSITIVE_INFINITY;
        float bestDy = Float.POSITIVE_INFINITY;
        for (Map.Entry<String, EntityModelData.Bone> e : bones.entrySet()) {
            if (consumedBedrockBones.contains(e.getKey())) continue;
            float[] p = e.getValue().getPivot();
            float dx = p[0] - target[0];
            float dy = p[1] - target[1];
            float dz = p[2] - target[2];
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
            float[] p = e.getValue().getPivot();
            float dx = p[0] - target[0];
            float dz = p[2] - target[2];
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
            throw new AssetPipelineException(ex, "Failed to load entity model overrides resource '%s'", OVERRIDES_RESOURCE_PATH);
        }
    }

    /**
     * Parses {@code entity_bind_poses.json} and returns its {@code entities} object (empty when
     * the file is absent). Generated by
     * {@link lib.minecraft.renderer.tooling.ToolingBindPoses ToolingBindPoses} from the Java
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
            throw new AssetPipelineException(ex, "Failed to load entity bind poses resource '%s'", BIND_POSES_RESOURCE_PATH);
        }
    }

}
