package lib.minecraft.renderer.fixture;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Rank;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.bake.mesh.BoneKit;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hand-built subjects the compiler tests lower against - a canonical seven-bone biped at known
 * rests, its flattened twin, its riding-hat and hatless twins, the hipped walker and scaling clip
 * the install's scale scan is measured on, the small expression helpers the fixtures spell poses
 * with, the two readings of a posed bone's drawn transform the scale cases measure, and the bone-map
 * comparison the install pins share.
 */
public final class CompilerFixtures {

    private CompilerFixtures() {}

    /**
     * The canonical seven-bone biped - arms resting pitched forward fifteen degrees and rolled
     * ten outward, legs and head at zero, every bone top-level.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityMesh humanoid() {
        EntityMesh mesh = new EntityMesh();
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
    public static @NotNull EntityMesh walker() {
        EntityMesh mesh = new EntityMesh();
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
    public static @NotNull EntityMesh chained() {
        EntityMesh mesh = new EntityMesh();
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
    public static @NotNull EntityMesh crawler() {
        EntityMesh mesh = new EntityMesh();
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
    public static @NotNull EntityMesh fused() {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        EntityMesh.Bone feet = bone(0f, 20f, 0f, 0f, 0f, 0f, 1f, null);
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
    public static @NotNull EntityMesh fusedRows() {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        EntityMesh.Bone front = bone(0f, 20f, -5f, 0f, 0f, 0f, 1f, null);
        front.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("front_legs", front);
        EntityMesh.Bone back = bone(0f, 20f, 7f, 0f, 0f, 0f, 1f, null);
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
    public static @NotNull EntityMesh crossedSides() {
        EntityMesh mesh = new EntityMesh();
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
    public static @NotNull EntityMesh halfFused() {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_front_leg", bone(3f, 14f, -5f, 0f, 0f, 0f, 1f, null));
        EntityMesh.Bone back = bone(0f, 20f, 7f, 0f, 0f, 0f, 1f, null);
        back.getCubes().add(cube(-3f, 0f, -1f, 6f, 1f, 2f));
        mesh.getBones().put("back_legs", back);
        return mesh;
    }

    /**
     * A four-legged walker whose every leg sits at the pivot of a hip of its own, so a leg the pose
     * never turns climbs to its hip wherever the pose turns that - the front hips ahead of the hind.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityMesh hipped() {
        return hipped(-5f, 7f);
    }

    /**
     * A four-legged walker whose every leg sits at the pivot of a hip of its own, its front-named and
     * hind-named hips at the given depths - so swapping the two puts the legs named for the front
     * behind the ones named for the hind, and a roster ranking by depth answers the other pair.
     *
     * @param frontZ the depth of the two hips named for the front
     * @param hindZ the depth of the two hips named for the hind
     * @return a fresh mesh
     */
    public static @NotNull EntityMesh hipped(float frontZ, float hindZ) {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        for (String side : List.of("right", "left"))
            for (String rank : List.of("front", "hind")) {
                String hip = side + "_" + rank + "_hip";
                mesh.getBones().put(hip, bone("right".equals(side) ? -3f : 3f, 14f,
                    "front".equals(rank) ? frontZ : hindZ, 0f, 0f, 0f, 1f, null));
                mesh.getBones().put(side + "_" + rank + "_leg", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, hip));
            }
        return mesh;
    }

    /**
     * The canonical biped with its hat hung from its head, as every vanilla humanoid hangs it.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityMesh ridingHat() {
        EntityMesh mesh = humanoid();
        mesh.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "head"));
        return mesh;
    }

    /**
     * The canonical biped with no hat at all.
     *
     * @return a fresh mesh
     */
    public static @NotNull EntityMesh hatless() {
        EntityMesh mesh = humanoid();
        mesh.getBones().remove("hat");
        return mesh;
    }

    /**
     * One unrotated cube at a bone-local corner and extent, with no UV overrides and no mirror.
     */
    public static @NotNull EntityMesh.Cube cube(
        float x, float y, float z, float width, float height, float depth) {

        return new EntityMesh.Cube(new Vector3f(x, y, z),
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
    public static @NotNull EntityMesh flattened(float factor) {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("head", bone(0f, 8f, -6f, 0f, 0f, 0f, factor, null));
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, factor, null));
        mesh.getBones().put("tail", bone(0f, 10f, 6f, 30f, 0f, 0f, factor, "body"));
        mesh.getBones().put("right_front_leg", bone(-3f, 14f, -5f, 0f, 0f, 0f, factor, "body"));
        return mesh;
    }

    /**
     * One bone at a pivot and rest rotation, carrying a whole-mesh factor and a parent.
     */
    public static @NotNull EntityMesh.Bone bone(
        float x, float y, float z, float pitch, float yaw, float roll,
        float scale, @Nullable String parent) {

        return new EntityMesh.Bone(new Vector3f(x, y, z),
            new EulerRotation(pitch, yaw, roll), EulerRotation.NONE, scale,
            Concurrent.newList(), parent);
    }

    /**
     * The scale one bone of a posed mesh draws a uniform-scaled vertex at, every ancestor included
     * - the length its chain maps the unit x vector to, times its own rest factor.
     *
     * <p>Read off the drawn transform rather than off the bone, because a pose's scale rides the
     * chain as a ratio over the rest, where neither {@link EntityMesh.Bone#getScale()} nor bone
     * equality sees it.
     *
     * @param posed the posed mesh
     * @param bone the bone measured
     * @return the drawn scale
     */
    public static float drawnScale(@NotNull EntityMesh posed, @NotNull String bone) {
        return drawnScale(posed, bone, 1);
    }

    /**
     * The scale one bone of a posed mesh draws along one of its own axes, every ancestor included
     * - the length its chain maps that axis's unit vector to, times its own rest factor - which
     * tells a per-axis clip scale on the chain apart from a uniform one.
     *
     * @param posed the posed mesh
     * @param bone the bone measured
     * @param axis the bone's own axis, {@code 1} for x, {@code 2} for y and {@code 3} for z
     * @return the drawn scale along that axis
     */
    public static float drawnScale(@NotNull EntityMesh posed, @NotNull String bone, int axis) {
        Matrix4f chain = BoneKit.buildChainTransform(posed.getBones(), bone);
        // Column n of the chain is the image of the unit vector along axis n.
        double x = chain.get(axis, 1);
        double y = chain.get(axis, 2);
        double z = chain.get(axis, 3);
        return (float) Math.sqrt(x * x + y * y + z * z) * posed.getBones().get(bone).getScale();
    }

    /**
     * Where one bone of a posed mesh has its pivot drawn, every ancestor included - its chain's
     * translation.
     *
     * @param posed the posed mesh
     * @param bone the bone measured
     * @return the chain's translation
     */
    public static @NotNull Vector3f chainAt(@NotNull EntityMesh posed, @NotNull String bone) {
        Matrix4f chain = BoneKit.buildChainTransform(posed.getBones(), bone);
        return new Vector3f(chain.get(4, 1), chain.get(4, 2), chain.get(4, 3));
    }

    /**
     * A target row over a mesh and its shipped pose, carrying no option axis, everything else
     * normalised.
     *
     * @param mesh the body mesh
     * @param pose the shipped pose
     * @param periodTicks the ticks one whole excursion spans in the row's empty catalog
     * @return the row
     */
    public static @NotNull Entity row(@NotNull EntityMesh mesh, @NotNull EntityPose pose,
                                      int periodTicks) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .styles(new StyleCatalog(periodTicks, Concurrent.newUnmodifiableList()))
            .axes(noAxes())
            .build();
    }

    /**
     * A bare row over the given mesh and pose, carrying no option axis, at the default catalog
     * period.
     *
     * @param mesh the body mesh
     * @param pose the shipped pose
     * @return the row
     */
    public static @NotNull Entity row(@NotNull EntityMesh mesh, @NotNull EntityPose pose) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(pose)
            .axes(noAxes())
            .build();
    }

    /**
     * The axes of a row carrying no option axis - no baby form, and no shape, state, size or variant
     * option.
     *
     * @return a fresh axes record
     */
    public static @NotNull Entity.Axes noAxes() {
        return new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
            Entity.Variation.none(), Entity.Variation.none());
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

    /**
     * A shipped pose turning one bone alone, beside a clip held at its first instant that grows
     * one bone by a quarter on every axis.
     *
     * @param turned the bone the pose turns
     * @param scaled the bone the clip scales
     * @return the pose
     */
    public static @NotNull EntityPose swelling(@NotNull String turned, @NotNull String scaled) {
        PoseClip swell = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel(scaled, PoseChannel.Kind.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0.25f, 0.25f, 0.25f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0.25f, 0.25f, 0.25f, PoseClip.Interpolation.LINEAR)))));
        return pose(List.of(), Map.of(turned, Map.of(PoseChannel.X_ROT, constant(0d))), List.of(
            new EntityPose.Clip("FixtureAnimation#SWELL", ClipDrive.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), swell)));
    }

    /**
     * A legged front right leg turned and scaled - one selected stance whose turn climbs where its
     * scale does not.
     *
     * @return the style
     */
    public static @NotNull BuiltStyle stomp() {
        return Poses.legged("stomp").leg(Rank.FRONT, Side.RIGHT, leg -> leg.pitchBy(10).scale(1.5)).build();
    }

    /**
     * Holds two posed bone maps equal as bone equality reads them - pivot, rotation, bind-pose
     * rotation, rest scale, cubes, parent, toggle and visibility - and every bone to the same pose
     * scale, which bone equality does not read.
     *
     * @param expected the bones expected
     * @param actual the bones measured
     * @param message what the comparison asserts
     */
    public static void assertSameBones(@NotNull Map<String, EntityMesh.Bone> expected,
                                       @NotNull Map<String, EntityMesh.Bone> actual, @NotNull String message) {
        assertEquals(expected, actual, message);
        expected.forEach((name, bone) -> assertEquals(bone.getPoseScale(), actual.get(name).getPoseScale(),
            message + " - bone '" + name + "' pose scale"));
    }

    public static @NotNull PoseExpr constant(double value) {
        return new PoseExpr.Constant(value, PoseWidth.FLOAT);
    }

    public static @NotNull PoseExpr input(@NotNull String field) {
        return new PoseExpr.Input(field);
    }

    public static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

}
