package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-built subjects the compiler tests lower against - a canonical seven-bone biped at known
 * rests, its flattened twin, and the small expression helpers the fixtures spell poses with.
 */
public final class CompilerFixtures {

    private CompilerFixtures() {}

    /**
     * The canonical seven-bone biped - arms resting pitched forward fifteen degrees and rolled
     * ten outward, legs and head at zero, every bone top-level.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData humanoid() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_arm", bone(-5f, 2f, 0f, -15f, 0f, 10f, 1f, null));
        mesh.getBones().put("left_arm", bone(5f, 2f, 0f, -15f, 0f, -10f, 1f, null));
        mesh.getBones().put("right_leg", bone(-2f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_leg", bone(2f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * A four-legged walker in two sided rows - the front pair well ahead of the hind, every bone
     * top-level and every leg named for the side it sits on.
     *
     * <p>The rows are far enough apart to cluster as two under the roster's own relative tolerance,
     * and the legs carry no cubes, so the geometric side cross-check reads their pivots and agrees
     * with their names.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData walker() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(0f, 8f, -6f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_front_leg", bone(3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_hind_leg", bone(-3f, 14f, 7f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_hind_leg", bone(3f, 14f, 7f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * A four-legged walker whose every leg is a chain three bones deep - a root, the link below
     * it, and a foot below that - which is the deepest shape the shipped corpus carries.
     *
     * <p>Every bone carries a pivot of its own, so the anatomical climb stops on the bone named
     * rather than redirecting a link onto the one above it.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData chained() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        for (String side : new String[]{"right", "left"}) {
            float x = "right".equals(side) ? -3f : 3f;
            for (String rank : new String[]{"front", "hind"}) {
                float z = "front".equals(rank) ? -5f : 7f;
                String root = side + "_" + rank + "_leg";
                mesh.getBones().put(root, bone(x, 14f, z, 0f, 0f, 0f, 1f, "body"));
                mesh.getBones().put(root + "_tip", bone(x, 18f, z, 0f, 0f, 0f, 1f, root));
                mesh.getBones().put(side + "_" + rank + "_foot",
                    bone(x, 22f, z, 0f, 0f, 0f, 1f, root + "_tip"));
            }
        }
        return mesh;
    }

    /**
     * An eight-legged crawler in four sided rows, evenly spread front to back.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData crawler() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        String[] rows = {"front", "second", "third", "hind"};
        float[] depths = {-6f, -2f, 2f, 6f};
        for (int row = 0; row < rows.length; row++) {
            mesh.getBones().put("right_" + rows[row] + "_leg",
                bone(-4f, 15f, depths[row], 0f, 0f, 0f, 1f, null));
            mesh.getBones().put("left_" + rows[row] + "_leg",
                bone(4f, 15f, depths[row], 0f, 0f, 0f, 1f, null));
        }
        return mesh;
    }

    /**
     * A mesh whose whole leg roster is one midline bone painting both legs of its row - the shape
     * a bat carries, which the roster reads as a fused row carrying no side at all.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData fused() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        EntityModelData.Bone feet = bone(0f, 20f, 0f, 0f, 0f, 0f, 1f, null);
        feet.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("feet", feet);
        return mesh;
    }

    /**
     * A mesh carrying TWO rows of legs, each one midline bone painting both legs of its row.
     *
     * <p>No shipped mesh is shaped this way - the corpus fuses one row or three, never two - so
     * this is what separates a rule stated over every row from one stated over the row count. A
     * mesh answering two rows and no side at all passes a row-count reading and has no second leg
     * for a side term to land on.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData fusedRows() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        EntityModelData.Bone front = bone(0f, 20f, -5f, 0f, 0f, 0f, 1f, null);
        front.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("front_legs", front);
        EntityModelData.Bone back = bone(0f, 20f, 7f, 0f, 0f, 0f, 1f, null);
        back.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("back_legs", back);
        return mesh;
    }

    /**
     * A four-legged walker whose FRONT pair is named against the side it sits on, its hind pair
     * named correctly - the shape vanilla ships on one mesh and one only.
     *
     * <p>One row crossed and one not is what makes it dangerous rather than merely mislabelled: a
     * gait pairing a leg with the one across the body from it pairs the two legs down one flank
     * instead, which is a real cycle and the other one.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData crossedSides() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_front_leg", bone(3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_hind_leg", bone(-3f, 14f, 7f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_hind_leg", bone(3f, 14f, 7f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * A mesh whose front row is a sided pair and whose hind row is one midline bone.
     *
     * <p>No shipped mesh mixes the two, which is what makes this the fixture separating a rule
     * stated over EVERY row from one stated over the mesh: the two readings agree on all 155
     * geometries and part company here.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData halfFused() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_front_leg", bone(3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        EntityModelData.Bone back = bone(0f, 20f, 7f, 0f, 0f, 0f, 1f, null);
        back.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("back_legs", back);
        return mesh;
    }

    /**
     * One unrotated cube at a bone-local corner and extent, with no UV overrides and no mirror.
     */
    public static @NotNull EntityModelData.Cube cube(
        float x, float y, float z, float width, float height, float depth) {

        return new EntityModelData.Cube(new Vector3f(x, y, z),
            new Vector3f(width, height, depth), Vector2f.ZERO, Vector3f.ZERO, false,
            Vector3f.ZERO, EulerRotation.NONE, Concurrent.newMap());
    }

    /**
     * A four-bone quadruped flattened at one whole-mesh factor - a parentless body carrying a
     * tail child, so a displacement crosses the factor and the feet anchor on the one and the
     * factor alone on the other.
     *
     * @param factor the whole-mesh factor every bone carries
     * @return a fresh mesh
     */
    public static @NotNull EntityModelData flattened(float factor) {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(0f, 8f, -6f, 0f, 0f, 0f, factor, null));
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, factor, null));
        mesh.getBones().put("tail", bone(0f, 10f, 6f, 30f, 0f, 0f, factor, "body"));
        mesh.getBones().put("right_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, factor, "body"));
        return mesh;
    }

    /**
     * One bone at a pivot and rest rotation, carrying a whole-mesh factor and a parent.
     */
    public static @NotNull EntityModelData.Bone bone(
        float x, float y, float z, float pitch, float yaw, float roll,
        float scale, @Nullable String parent) {

        return new EntityModelData.Bone(new Vector3f(x, y, z),
            new EulerRotation(pitch, yaw, roll), EulerRotation.NONE, scale,
            Concurrent.newList(), parent);
    }

    /**
     * A target row over a mesh and its shipped pose, everything else normalised.
     */
    public static @NotNull Entity row(@NotNull EntityModelData mesh, @NotNull EntityPose pose,
                                      int periodTicks) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .styles(new StyleCatalog(periodTicks, Concurrent.newUnmodifiableList()))
            .build();
    }

    /**
     * A bare row over the given mesh and pose, at the default catalog period.
     *
     * @param mesh the body mesh
     * @param pose the shipped pose
     * @return the row
     */
    public static @NotNull Entity row(@NotNull EntityModelData mesh, @NotNull EntityPose pose) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .build();
    }

    /**
     * A readable pose over the given regions.
     */
    public static @NotNull EntityPose pose(
        @NotNull List<Map<PoseChannel, PoseExpr>> container,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
        @NotNull List<EntityPose.Clip> clips) {

        return new EntityPose(
            Concurrent.newUnmodifiableList(container),
            Concurrent.newUnmodifiableMap(bones),
            Concurrent.newUnmodifiableList(clips),
            Optional.empty());
    }

    /**
     * A pose writing one bone channel with the given expression.
     */
    public static @NotNull EntityPose boneWrite(
        @NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr expression) {

        return pose(List.of(), Map.of(bone, Map.of(channel, expression)), List.of());
    }

    public static @NotNull PoseExpr constant(double value) {
        return new PoseExpr.Constant(value, PoseOperator.Width.FLOAT);
    }

    public static @NotNull PoseExpr input(@NotNull String field) {
        return new PoseExpr.Input(field);
    }

    public static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

}
