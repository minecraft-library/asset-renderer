package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Builds the two-bone elytra wing mesh and its rasterizer triangles. The mesh mirrors vanilla
 * {@code ElytraModel.createLayer} bone for bone (left / right wing, each a {@code 10x20x2} box
 * inflated {@code 1.0} on a {@code 64x32} atlas, offset {@code +-5} and rotated {@code +-15deg}), fed
 * through {@link EntityGeometryKit#buildTriangles} - the same path the entity equipment overlay uses -
 * so no kit change or new schema is needed.
 * <p>
 * The wing texture is the data-driven {@code equipment/elytra.json} {@link LayerType#WINGS} layer
 * (its {@code use_player_texture} flag degrades to the static {@code minecraft:elytra} skin on a
 * headless render, since there is no wearer skin source). A pack that ships no such asset drops the
 * wings entirely (the no-missing-texture-fallback contract).
 */
@UtilityClass
public class ElytraKit {

    /** The elytra equipment asset id whose {@code equipment/elytra.json} supplies the wing texture. */
    private static final @NotNull ResourceId ELYTRA_ASSET = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "elytra");

    /**
     * The whole-model back shift of both wings in model pixels - vanilla {@code WingsLayer.submit}
     * applies {@code PoseStack.translate(0, 0, 0.125)} in the entity's block frame before rendering the
     * elytra, which is {@code 0.125 * 16 = 2} pixels in this mesh's native pixel frame. Baked onto each
     * wing bone's pivot z so the wings seat behind the body rather than clipping into the back.
     */
    private static final float WING_BACK_OFFSET = 2f;

    /** The per-axis {@code CubeDeformation(1.0)} the vanilla wings inflate their box by. */
    private static final @NotNull Vector3f WING_INFLATE = new Vector3f(1f, 1f, 1f);

    /** The half-body scale vanilla {@code ElytraModel.BABY_TRANSFORMER} applies for a baby wearer. */
    private static final float BABY_SCALE = 0.5f;

    /**
     * The model-space feet Y (in the Y-down frame) vanilla {@code MeshTransformer.scaling} anchors its
     * scale about - {@code pose.scaled(F).translated(0, 24.016*(1-F), 0)} - so a shrunk baby model stays
     * planted at the feet rather than shrinking toward the origin (the upper back).
     */
    private static final float MODEL_FEET_Y = 24.016f;

    /** The adult wing mesh at full scale. */
    private static final @NotNull EntityModelData WINGS = buildWingsMesh(false);

    /**
     * The baby wing mesh - vanilla {@code ElytraModel.BABY_TRANSFORMER}
     * ({@code MeshTransformer.scaling(0.5)}) scales the whole wing model to half and re-anchors it at
     * the feet, so the wings seat on the baby's smaller back rather than floating at the adult shoulder.
     */
    private static final @NotNull EntityModelData WINGS_BABY = buildWingsMesh(true);

    /**
     * The elytra wing mesh for an age, for the caller's canvas-bounds fold (so a protruding wing does
     * not crop the fitted canvas).
     *
     * @param baby whether to return the half-scale baby mesh
     * @return the shared wing mesh
     */
    public static @NotNull EntityModelData wingsMesh(boolean baby) {
        return baby ? WINGS_BABY : WINGS;
    }

    /**
     * Builds the elytra wing triangles for an entity, textured from the data-driven
     * {@code equipment/elytra.json} {@link LayerType#WINGS} layer and fed through the shared entity
     * geometry kit at the caller's fit frame. Empty when the pack ships no elytra asset or its wing
     * texture is absent (no fallback).
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param baby whether to render the half-scale baby wings
     * @param modelAnchor the model-space anchor that maps to the canvas centre (the body's fit anchor)
     * @param ndcScale the model-units-to-NDC scale (the body's fit scale)
     * @param modelScale the per-render vertex pre-scale (the body's renderer-scale chain)
     * @param tick the current animation tick
     * @return the wing triangles, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildWings3D(
        @NotNull Textures engine, boolean baby,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale, int tick
    ) {
        List<EquipmentModel.Layer> layers = engine.getContext().resolveEquipmentLayers(ELYTRA_ASSET, LayerType.WINGS);
        if (layers.isEmpty()) return Concurrent.newList();

        EquipmentModel.Layer wing = layers.getFirst();
        Optional<PixelBuffer> texture = engine.tryResolveTextureAtTick(wing.textureLocation(LayerType.WINGS).id(), tick);
        return texture.map(pixelBuffer -> EntityGeometryKit.buildTriangles(
            wingsMesh(baby),
            pixelBuffer,
            modelAnchor,
            false,
            ndcScale,
            modelScale,
            ColorMath.WHITE).triangles()
            )
            .orElseGet(Concurrent::newList);
    }

    /**
     * Builds the two-bone wing mesh, transcribed from {@code ElytraModel.createLayer}: left wing box
     * origin {@code (-10,0,0)} size {@code (10,20,2)} texOffs {@code (22,0)} at pivot {@code (5,0,2)}
     * rotated {@code (15,0,-15)}, right wing mirrored. For a baby the whole model is scaled to half
     * about the feet (vanilla {@code BABY_TRANSFORMER} = {@code MeshTransformer.scaling(0.5)}): each
     * bone carries the {@code 0.5} per-vertex scale and its pivot is folded through
     * {@code p' = F*p + (0, 24.016*(1-F), 0)}, the feet-anchor translate, so the shrunk wings drop to
     * the baby's back rather than floating at the adult shoulder.
     */
    private static @NotNull EntityModelData buildWingsMesh(boolean baby) {
        float scale = baby ? BABY_SCALE : 1f;
        float feetShift = MODEL_FEET_Y * (1f - scale);
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("left_wing", wingBone(
            wingPivot(5f, scale, feetShift),
            new EulerRotation(15f, 0f, -15f),
            scale,
            wingCube(new Vector3f(-10f, 0f, 0f), false)
        ));
        bones.put("right_wing", wingBone(
            wingPivot(-5f, scale, feetShift),
            new EulerRotation(15f, 0f, 15f),
            scale,
            wingCube(new Vector3f(0f, 0f, 0f), true)
        ));
        return new EntityModelData(new TextureSize(64, 32), 0f, bones, false);
    }

    /**
     * The feet-anchored wing pivot: the createLayer pivot {@code (x, 0, WING_BACK_OFFSET)} scaled by
     * {@code scale} about the origin with the feet-anchor Y shift added, so an adult
     * ({@code scale == 1}) keeps its exact createLayer pivot.
     */
    private static @NotNull Vector3f wingPivot(float x, float scale, float feetShift) {
        return new Vector3f(x * scale, feetShift, WING_BACK_OFFSET * scale);
    }

    /** A wing bone owning one cube, at the given pivot, rotation, and per-vertex scale. */
    private static @NotNull EntityModelData.Bone wingBone(
        @NotNull Vector3f pivot, @NotNull EulerRotation rotation, float scale, @NotNull EntityModelData.Cube cube) {
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return new EntityModelData.Bone(pivot, rotation, EulerRotation.NONE, scale, cubes, null);
    }

    /** A wing cube of size {@code 10x20x2} at the given origin, texOffs {@code (22,0)}, inflated {@code 1.0}. */
    private static @NotNull EntityModelData.Cube wingCube(@NotNull Vector3f origin, boolean mirror) {
        return new EntityModelData.Cube(
            origin,
            new Vector3f(10f, 20f, 2f),
            new Vector2f(22f, 0f),
            WING_INFLATE,
            mirror,
            Vector3f.ZERO,
            EulerRotation.NONE,
            Concurrent.newMap()
        );
    }

}
