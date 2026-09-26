package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The two-bone elytra wing mesh vanilla declares in code, at both wearer scales. The bones mirror
 * {@code ElytraModel.createLayer} one for one - left and right wing, each a {@code 10x20x2} box
 * inflated {@code 1.0} on a {@code 64x32} atlas, offset {@code +-5} and rotated {@code +-15deg} -
 * and are authored in vanilla's model frame, shoulders at {@code y 0}.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ElytraMesh {

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
    public static final @NotNull EntityMesh WINGS = buildWingsMesh(false);

    /**
     * The baby wing mesh at half scale (vanilla {@code ElytraModel.BABY_TRANSFORMER} =
     * {@code MeshTransformer.scaling(0.5)}). The vanilla transform re-anchors the shrunk mesh at the
     * feet, but a headless render draws a dedicated baby body mesh whose shoulder height is not the
     * adult feet-anchor value, so the wing bake re-seats the baby wings on the rendered body's actual
     * shoulder bounds instead.
     */
    public static final @NotNull EntityMesh WINGS_BABY = buildWingsMesh(true);

    /**
     * Builds the two-bone wing mesh, transcribed from {@code ElytraModel.createLayer}: left wing box
     * origin {@code (-10,0,0)} size {@code (10,20,2)} texOffs {@code (22,0)} at pivot {@code (5,0,2)}
     * rotated {@code (15,0,-15)}, right wing mirrored. A baby carries the {@code 0.5} per-vertex scale
     * (vanilla {@code BABY_TRANSFORMER}) with its pivot offsets halved to match; the vanilla feet-anchor
     * re-seat is applied at render against the actual body bounds.
     */
    private static @NotNull EntityMesh buildWingsMesh(boolean baby) {
        float scale = baby ? BABY_SCALE : 1f;
        ConcurrentLinkedMap<String, EntityMesh.Bone> bones = Concurrent.newLinkedMap();
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
        return new EntityMesh(new TextureSize(64, 32), bones, false);
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
    private static @NotNull EntityMesh.Bone wingBone(
        @NotNull Vector3f pivot, @NotNull EulerRotation rotation, float scale, @NotNull EntityMesh.Cube cube) {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(cube);
        return new EntityMesh.Bone(pivot, rotation, EulerRotation.NONE, scale, cubes, null);
    }

    /** A wing cube of size {@code 10x20x2} at the given origin, texOffs {@code (22,0)}, inflated {@code 1.0}. */
    private static @NotNull EntityMesh.Cube wingCube(@NotNull Vector3f origin, boolean mirror) {
        return new EntityMesh.Cube(
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
