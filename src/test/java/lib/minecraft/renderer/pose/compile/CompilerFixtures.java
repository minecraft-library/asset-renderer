package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.tensor.EulerRotation;
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
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

    public static @NotNull PoseExpr input(@NotNull String field) {
        return new PoseExpr.Input(field);
    }

    public static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

}
