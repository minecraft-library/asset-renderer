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
import org.jetbrains.annotations.Nullable;

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

    /** The adult wing mesh at full scale, authored in vanilla's model frame (shoulders at y 0). */
    private static final @NotNull EntityModelData WINGS = buildWingsMesh(false);

    /**
     * The baby wing mesh at half scale (vanilla {@code ElytraModel.BABY_TRANSFORMER} =
     * {@code MeshTransformer.scaling(0.5)}). The vanilla transform re-anchors the shrunk mesh at the
     * feet, but a headless render draws a dedicated baby body mesh whose shoulder height is not the
     * adult feet-anchor value, so {@link #buildWings3D} instead re-seats the baby wings on the rendered
     * body's actual shoulder bounds.
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
     * @param bodyBounds the rendered body bone's {@code [min, max]} bounds (the {@code body} bone), used
     *     to re-seat the baby wings on the actual shoulder height, or {@code null} to leave the wings at
     *     their authored position
     * @param modelAnchor the model-space anchor that maps to the canvas centre (the body's fit anchor)
     * @param ndcScale the model-units-to-NDC scale (the body's fit scale)
     * @param modelScale the per-render vertex pre-scale (the body's renderer-scale chain)
     * @param tick the current animation tick
     * @return the wing triangles, empty when the wings do not resolve
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildWings3D(
        @NotNull Textures engine, boolean baby, @Nullable Vector3f[] bodyBounds,
        @NotNull Vector3f modelAnchor, float ndcScale, float modelScale, int tick
    ) {
        List<EquipmentModel.Layer> layers = engine.getContext().resolveEquipmentLayers(ELYTRA_ASSET, LayerType.WINGS);
        if (layers.isEmpty()) return Concurrent.newList();

        EquipmentModel.Layer wing = layers.getFirst();
        Optional<PixelBuffer> texture = engine.tryResolveTextureAtTick(wing.textureLocation(LayerType.WINGS).id(), tick);
        if (texture.isEmpty()) return Concurrent.newList();

        ConcurrentList<VisibleTriangle> wings = EntityGeometryKit.buildTriangles(
            wingsMesh(baby), texture.get(), modelAnchor, false, ndcScale, modelScale, ColorMath.WHITE).triangles();

        // The adult wing mesh is authored in vanilla's frame (shoulders at y 0), so it already seats on
        // the adult body. The baby draws a dedicated smaller body mesh whose shoulders sit lower, so
        // drop the half-scale wings by the gap between their authored top and the body's actual top (in
        // the Y-down frame the top edge is the minimum y).
        if (baby && bodyBounds != null && !wings.isEmpty())
            return shiftY(wings, bodyBounds[0].y() - minY(wings));

        return wings;
    }

    /** The minimum y across every vertex of the triangles - the top edge in the Y-down model frame. */
    private static float minY(@NotNull ConcurrentList<VisibleTriangle> triangles) {
        float min = Float.POSITIVE_INFINITY;
        for (VisibleTriangle triangle : triangles)
            min = Math.min(min, Math.min(triangle.position0().y(), Math.min(triangle.position1().y(), triangle.position2().y())));
        return min;
    }

    /** Returns a copy of the triangles translated by {@code dy} along Y, leaving normals and UVs intact. */
    private static @NotNull ConcurrentList<VisibleTriangle> shiftY(@NotNull ConcurrentList<VisibleTriangle> triangles, float dy) {
        ConcurrentList<VisibleTriangle> shifted = Concurrent.newList();
        for (VisibleTriangle t : triangles)
            shifted.add(new VisibleTriangle(
                addY(t.position0(), dy), addY(t.position1(), dy), addY(t.position2(), dy),
                t.uv0(), t.uv1(), t.uv2(), t.texture(), t.tintArgb(), t.normal(), t.shading(), t.traits(), t.debugTag()));
        return shifted;
    }

    /** The vector with {@code dy} added to its Y component. */
    private static @NotNull Vector3f addY(@NotNull Vector3f v, float dy) {
        return new Vector3f(v.x(), v.y() + dy, v.z());
    }

    /**
     * Builds the two-bone wing mesh, transcribed from {@code ElytraModel.createLayer}: left wing box
     * origin {@code (-10,0,0)} size {@code (10,20,2)} texOffs {@code (22,0)} at pivot {@code (5,0,2)}
     * rotated {@code (15,0,-15)}, right wing mirrored. A baby carries the {@code 0.5} per-vertex scale
     * (vanilla {@code BABY_TRANSFORMER}) with its pivot offsets halved to match; the vanilla feet-anchor
     * re-seat is applied at render against the actual body bounds ({@link #buildWings3D}).
     */
    private static @NotNull EntityModelData buildWingsMesh(boolean baby) {
        float scale = baby ? BABY_SCALE : 1f;
        ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();
        bones.put("left_wing", wingBone(
            wingPivot(5f, scale),
            new EulerRotation(15f, 0f, -15f),
            scale,
            wingCube(new Vector3f(-10f, 0f, 0f), false)
        ));
        bones.put("right_wing", wingBone(
            wingPivot(-5f, scale),
            new EulerRotation(15f, 0f, 15f),
            scale,
            wingCube(new Vector3f(0f, 0f, 0f), true)
        ));
        return new EntityModelData(new TextureSize(64, 32), 0f, bones, false);
    }

    /**
     * The wing pivot: the createLayer pivot {@code (x, 0, WING_BACK_OFFSET)} with its X and Z offsets
     * scaled by {@code scale} (Y stays at the shoulder line), so an adult ({@code scale == 1}) keeps its
     * exact createLayer pivot and a baby's pivots shrink toward the body centre.
     */
    private static @NotNull Vector3f wingPivot(float x, float scale) {
        return new Vector3f(x * scale, 0f, WING_BACK_OFFSET * scale);
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
