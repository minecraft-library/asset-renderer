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
import lib.minecraft.renderer.engine.RendererContext;
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
 * {@link RendererContext#resolveTexture(String) resolveTexture}
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

    /**
     * Per-entity metadata file; produced by {@code ToolingEntityModels}.
     */
    private static final @NotNull String MODELS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";

    /**
     * Per-geometry bone tree file; bones in Java-native Y-down absolute entity-root frame.
     */
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
     *     {@link RendererContext#resolveTexture(String)
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
     * @param setupYawAddend yaw rotation in degrees that the vanilla renderer's
     *     {@code setupRotations} override adds to the standard {@code bodyRot} before the super
     *     call. Extracted from the {@code super.setupRotations(state, ps, bodyRot + N, scale)}
     *     bytecode pattern by the tooling-side renderer scan. {@code ShulkerRenderer} is the
     *     canonical case ({@code +180.0F}); every other vanilla renderer leaves {@code bodyRot}
     *     unmodified and lands at {@code 0}. The renderer adds this to the user-supplied yaw
     *     before applying the iso pose - for shulker the addend collapses the default
     *     {@code rotateY(180-bodyRot)} body rotation to identity, exposing the lid's authored
     *     UV orientation unrotated against the viewer
     */
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull List<OverlayLayer> overlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        boolean forceOpaque,
        int baseTintArgb,
        float setupYawAddend,
        float rendererScale
    ) {

        /**
         * Convenience constructor for entities with no overlays and no force-opaque flag - the
         * common case.
         */
        public EntityDefinition(@NotNull EntityModelData model, @NotNull Optional<String> textureRef) {
            this(model, textureRef, List.of(), List.of(), false, 0xFFFFFFFF, 0f, 1f);
        }

        /**
         * Convenience constructor for entities with overlays but no force-opaque flag.
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays
        ) {
            this(model, textureRef, overlays, List.of(), false, 0xFFFFFFFF, 0f, 1f);
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
            this(model, textureRef, overlays, List.of(), forceOpaque, 0xFFFFFFFF, 0f, 1f);
        }

        /**
         * Convenience constructor preserving the historic {@code (model, textureRef, overlays,
         * blockOverlays, forceOpaque)} signature in use before {@link #baseTintArgb} was added.
         * Defaults the tint to {@code 0xFFFFFFFF} (white = no-op multiplicative tint).
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays,
            @NotNull List<BlockOverlayLayer> blockOverlays,
            boolean forceOpaque
        ) {
            this(model, textureRef, overlays, blockOverlays, forceOpaque, 0xFFFFFFFF, 0f, 1f);
        }

        /**
         * Convenience constructor preserving the historic 6-arg signature in use before
         * {@link #setupYawAddend} was added. Defaults the yaw addend to {@code 0f} (no addend).
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays,
            @NotNull List<BlockOverlayLayer> blockOverlays,
            boolean forceOpaque,
            int baseTintArgb
        ) {
            this(model, textureRef, overlays, blockOverlays, forceOpaque, baseTintArgb, 0f, 1f);
        }

        /**
         * Convenience constructor preserving the historic 7-arg signature in use before
         * {@link #rendererScale} was added. Defaults the scale to {@code 1f} (identity).
         */
        public EntityDefinition(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            @NotNull List<OverlayLayer> overlays,
            @NotNull List<BlockOverlayLayer> blockOverlays,
            boolean forceOpaque,
            int baseTintArgb,
            float setupYawAddend
        ) {
            this(model, textureRef, overlays, blockOverlays, forceOpaque, baseTintArgb, setupYawAddend, 1f);
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

    /**
     * Translation by {@code (x, y, z)} in entity-local units.
     */
    public record Translate(float x, float y, float z) implements TransformOp {}

    /**
     * Rotation around the Y axis by {@code degrees}.
     */
    public record RotateY(float degrees) implements TransformOp {}

    /**
     * Per-axis scale {@code (x, y, z)}. Negative components flip the axis.
     */
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
        boolean emissive,
        int tintArgb,
        boolean skipBounds
    ) {

        /**
         * Convenience constructor preserving the historic {@code (model, textureRef, emissive)}
         * signature in use before {@link #tintArgb} was added. Defaults the tint to
         * {@code 0xFFFFFFFF} (white = no-op multiplicative tint) and {@code skipBounds} to
         * {@code false} (overlay contributes to bounds).
         */
        public OverlayLayer(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            boolean emissive
        ) {
            this(model, textureRef, emissive, 0xFFFFFFFF, false);
        }

        /**
         * Convenience constructor preserving the historic {@code (model, textureRef, emissive,
         * tintArgb)} signature in use before {@link #skipBounds} was added. Defaults
         * {@code skipBounds} to {@code false} (overlay contributes to bounds).
         */
        public OverlayLayer(
            @NotNull EntityModelData model,
            @NotNull Optional<String> textureRef,
            boolean emissive,
            int tintArgb
        ) {
            this(model, textureRef, emissive, tintArgb, false);
        }

    }

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
            // Per-overlay multiplicative tint. Vanilla wires per-layer color through
            // {@code coloredCutoutModelRender(model, texture, ..., color, order)} - sheep wool
            // gets {@code state.getWoolColor()}, tropical fish pattern gets {@code state.patternColor},
            // etc. JSON expects a hex string ("0xFFFFFFFF" / "#F9FFFE" / "0xF9FFFE") so it survives
            // round-trip with hand-edits. Defaults to 0xFFFFFFFF (white = no-op MULTIPLY tint).
            int overlayTint = entry.has("tint_color") ? parseTintArgb(entry.get("tint_color").getAsString()) : 0xFFFFFFFF;
            // Some equipment-driven overlays (LlamaDecorLayer carpet, possibly future similar
            // layers) are state-rendered by vanilla but the harness skips them from bounds via
            // NO_RENDER_LAYER_SUFFIXES - the carpet's inflate-0.5 mesh would over-pad the canvas
            // around a body that doesn't actually need that margin. `skip_bounds=true` mirrors
            // that policy here: the overlay still renders, but EntityRenderer.computeUnionScreen
            // Bounds ignores it when sizing the canvas.
            boolean skipBounds = entry.has("skip_bounds") && entry.get("skip_bounds").getAsBoolean();
            out.add(new OverlayLayer(materialised, overlayTexture, emissive, overlayTint, skipBounds));
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
     * Parses a JSON tint string into an ARGB int the rasterizer's {@code MULTIPLY} blend
     * consumes. Accepted forms:
     * <ul>
     * <li>{@code "0xRRGGBB"} / {@code "RRGGBB"} - 6 hex digits, alpha defaults to {@code FF};</li>
     * <li>{@code "0xAARRGGBB"} / {@code "AARRGGBB"} - 8 hex digits, full ARGB;</li>
     * <li>{@code "#RRGGBB"} / {@code "#AARRGGBB"} - CSS-style hash prefix.</li>
     * </ul>
     * Malformed strings log a warning and return {@code 0xFFFFFFFF} (the white = no-op
     * MULTIPLY tint) so a typo in the overrides file doesn't tint the entity entirely black.
     */
    private static int parseTintArgb(@NotNull String spec) {
        String hex = spec.startsWith("0x") || spec.startsWith("0X") ? spec.substring(2)
            : spec.startsWith("#") ? spec.substring(1)
            : spec;
        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() <= 6) value |= 0xFF000000L;
            return (int) value;
        } catch (NumberFormatException ex) {
            System.err.printf("  Warning: malformed tint_color / base_tint value '%s' (expected hex); falling back to 0xFFFFFFFF%n", spec);
            return 0xFFFFFFFF;
        }
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

            // Apply tooling-derived + hand-edited hidden bones. The tooling-emitted
            // entityJson.hidden_bones field carries bones the model class's <init> sets
            // visible=false unconditionally (armor_stand and illager-family hat hides via
            // EntityHiddenBonesResolver). The overrides file extends the list for cases
            // the bytecode walker can't reach (equipment-gated visibility from setupAnim -
            // llama chest, etc.). Both sources are merged into one LinkedHashSet of names.
            boolean hasEntityHidden = entityJson.has("hidden_bones") && entityJson.get("hidden_bones").isJsonArray();
            boolean hasOverrideHidden = override != null && override.has("hidden_bones") && override.get("hidden_bones").isJsonArray();
            if (hasEntityHidden || hasOverrideHidden) {
                LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(baseModel.getBones());
                if (hasEntityHidden) applyHiddenBones(bones, entityJson.getAsJsonArray("hidden_bones"), entityId);
                if (hasOverrideHidden) applyHiddenBones(bones, override.getAsJsonArray("hidden_bones"), entityId);
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

            // Per-entity base-mesh multiplicative tint. Mirrors vanilla
            // {@code LivingEntityRenderer.getModelTint(state)} which returns a per-entity
            // ARGB color the rasterizer multiplies into every sampled texel. Tropical fish use
            // {@code state.baseColor} (DyeColor.WHITE = 0xF9FFFE at zero state); the parser keeps
            // a tunable knob here so other variant-textured entities can adopt the same
            // mechanism without each one needing a new code path. Defaults to 0xFFFFFFFF.
            int baseTint = override != null && override.has("base_tint")
                ? parseTintArgb(override.get("base_tint").getAsString())
                : 0xFFFFFFFF;

            // Bytecode-extracted bodyRot addend from the vanilla renderer's setupRotations
            // override - currently only Shulker uses this (+180F), but the field is generic and
            // any future renderer overriding setupRotations with `super.setupRotations(state, ps,
            // bodyRot + N, scale)` will surface here. Override row wins so authors can hand-edit
            // when the bytecode walk misses a state-dependent case.
            float setupYawAddend = 0f;
            if (entityJson.has("setup_yaw_addend"))
                setupYawAddend = entityJson.get("setup_yaw_addend").getAsFloat();
            if (override != null && override.has("setup_yaw_addend"))
                setupYawAddend = override.get("setup_yaw_addend").getAsFloat();

            // Per-entity render-time scale extracted by EntityRendererScaleResolver from the
            // renderer's scale(state, poseStack) override (wither: literal 2.0; slime: literal
            // 0.999 + state-dependent identity at zero state). Entities with no override or
            // identity-collapsing scale chains omit the field and stay at 1.0.
            float rendererScale = 1f;
            if (entityJson.has("renderer_scale"))
                rendererScale = entityJson.get("renderer_scale").getAsFloat();
            if (override != null && override.has("renderer_scale"))
                rendererScale = override.get("renderer_scale").getAsFloat();

            definitions.put(entityId, new EntityDefinition(baseModel, textureRef, overlays, blockOverlays, false, baseTint, setupYawAddend, rendererScale));
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

    private static volatile Map<String, List<String>> FAMILIES_CACHE;

    /**
     * Loads the top-level {@code families} table from entity_models.json. Cross-entity family
     * groupings are emitted there by ToolingEntityModels.deriveCrossEntityFamilies based on
     * shared geometry_ref (mooshroom and cow both bake CowModel.createBodyLayer -> both end
     * up at geometry.cow -> family mapping mooshroom -> cow).
     */
    private static @NotNull Map<String, String> loadFamiliesTable() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_RESOURCE_PATH)) {
            if (stream == null) return Map.of();
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("families")) return Map.of();
            JsonObject families = root.getAsJsonObject("families");
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : families.entrySet())
                if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
            return out;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load family table from '%s'", MODELS_RESOURCE_PATH);
        }
    }

    /**
     * Returns {@code entityId -> familyMembers} keyed by every Java-pipeline entity id. Family
     * membership is derived from {@code variant_of} in entity_models.json (variant entities
     * roll up to their declared root) plus the top-level {@code families} table emitted by
     * ToolingEntityModels (cross-entity groupings like mooshroom -> cow). Singletons return a
     * single-element list containing themselves so callers can iterate uniformly without
     * special-casing. The result is cached on first call - the JSON is loaded once for the
     * lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamilies() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            JsonObject entities = loadEntitiesBlock();
            Map<String, String> familiesTable = loadFamiliesTable();
            // Two-pass: first pass assigns each entity to its family root via variant_of /
            // families table; second pass inverts the map to family -> [members] so any member
            // looking up by its own id sees the whole family.
            Map<String, String> entityToFamily = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                String entityId = entry.getKey();
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                String family = familiesTable.get(entityId);
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

    /**
     * Reads {@link #MODELS_RESOURCE_PATH}. Returns an empty {@link JsonObject} when absent.
     */
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
