package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mesh a subject's own model leaves it holding at one instant - {@link PoseEvaluator}'s channel
 * values written back onto the bones they name.
 *
 * <p><b>Under {@link EntityOptions.PoseMode#BIND} this hands back the very instance it was given</b>,
 * and that identity is the whole of what makes the authored pose cost nothing: no bone is copied, no
 * float is touched, and a render that asks for nothing draws exactly the bytes it drew before. The
 * same holds for a subject whose model poses nothing, one whose pose could not be read, and one that
 * writes only channels a mesh does not carry.
 *
 * <p>Otherwise the bone map is rebuilt <b>in the mesh's own order</b>, because
 * {@link EntityModelData#getBones()} insertion order is the tied-depth priority the depth contract
 * rests on - a coplanar pair is last-drawn-wins, so re-ordering the bones re-decides which face
 * survives.
 *
 * <p><b>A written channel replaces the value it names rather than displacing it.</b> The table's
 * expressions already read the authored value where they build on it, so what comes back is where
 * the bone stands and not how far it moved. A channel the pose leaves alone keeps the mesh's own
 * value untouched, and one written to exactly what the mesh already held keeps it too - which is
 * what makes a pose that resolves to the bind pose a bit-for-bit no-op rather than a round trip
 * through radians.
 *
 * <p>What this poses is the subject's own mesh. A model overlay carries geometry of its own with no
 * pose beside it, so an overlay pass draws where it is authored; the passes that reuse the body's
 * mesh - the collar, the horse marking - move with the body because they are handed the same posed
 * mesh it is.
 *
 * <p>The two flag channels are resolved into the shipped mesh at load rather than here: a bone that
 * rests undrawn is already gone from the map, along with its subtree. So a model that hides a bone
 * on a frame rather than at rest still draws it, which is the open half of bone visibility.
 */
@UtilityClass
public final class PoseKit {

    /** The render-state figure a frame's tick answers - vanilla's free-running age for the subject. */
    private static final @NotNull String AGE_IN_TICKS = "ageInTicks";

    /**
     * The name the container enters the bone map under, when a pose writes one at all - chosen to
     * collide with nothing a vanilla model class declares a field for.
     */
    private static final @NotNull String CONTAINER_BONE = "$container";

    /**
     * The mesh this subject's model leaves it holding at one tick.
     *
     * @param mode whether the subject stands in its authored pose or the one its model evaluates
     * @param subject the resolved subject, supplying the mesh, the pose and what it rests at
     * @param tick the frame's sample tick
     * @return the posed mesh, or the subject's own mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick) {

        EntityModelData model = subject.model();
        if (mode != EntityOptions.PoseMode.ANIMATED) return model;

        EntityPose pose = subject.pose();
        if (!pose.isReadable()) return model;

        PoseEvaluator.ChannelWrites writes =
            PoseEvaluator.evaluate(pose, model, frameAt(pose, subject.restingState(), tick));
        return writes.isEmpty() ? model : rebuild(model, writes);
    }

    // ------------------------------------------------------------------------------------

    /**
     * What the subject answers about itself at one tick - what it rests at, with time run forward.
     *
     * <p>A subject an offline render poses is standing still, so every figure but one is the figure
     * it rests at: it is walking at no speed, swinging at nothing and holding nothing. Elapsed age
     * is the exception and the reason a frame differs from its neighbour at all.
     *
     * <p>Delegating the rest to {@link PoseEvaluator#restingIn} rather than answering it here is
     * what keeps a humanoid's arms off NaN - {@code speedValue} is divided by and is built at one.
     */
    private static @NotNull PoseEvaluator.Frame frameAt(
        @NotNull EntityPose pose, @NotNull Map<String, String> restingState, int tick) {

        PoseEvaluator.Frame rest = PoseEvaluator.restingIn(pose, restingState);
        return new PoseEvaluator.Frame() {
            @Override
            public float input(@NotNull String field) {
                return AGE_IN_TICKS.equals(field) ? tick : rest.input(field);
            }

            @Override
            public float carried(@NotNull String field) {
                return rest.carried(field);
            }

            @Override
            public float question(@NotNull String receiver, @NotNull String question) {
                return rest.question(receiver, question);
            }

            @Override
            public float element(@NotNull String receiver, int index) {
                return rest.element(receiver, index);
            }

            @Override
            public boolean is(@NotNull String member, @NotNull String constant) {
                return rest.is(member, constant);
            }

            @Override
            public boolean has(@NotNull String member) {
                return rest.has(member);
            }
        };
    }

    /** The mesh with every written channel applied, or the mesh itself when none of them moved it. */
    private static @NotNull EntityModelData rebuild(
        @NotNull EntityModelData model, @NotNull PoseEvaluator.ChannelWrites writes) {

        if (writes.container().isEmpty()
            && writes.bones().values().stream().noneMatch(PoseKit::movesGeometry)) return model;

        // Collected into a LinkedHashMap rather than through Map.copyOf: the mesh's own bone order is
        // the tied-depth priority, and copyOf salts its iteration per JVM launch.
        LinkedHashMap<String, EntityModelData.Bone> bones = model.getBones().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                bone -> posedBone(bone.getValue(), bone.getKey(),
                    writes.bones().getOrDefault(bone.getKey(), Map.of())),
                (first, second) -> first, LinkedHashMap::new));
        if (!writes.container().isEmpty()) seatUnderContainer(bones, writes.container());
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * One bone where the pose leaves it, or the bone itself when the pose writes it no geometry.
     *
     * <p>The rotation is assembled as the Euler triplet the bone already carries, one channel at a
     * time, because that triplet is what a chain composition finally reads - a rotation pre-composed
     * as a matrix and multiplied in reaches {@code rotationZYX} through different arithmetic and
     * parts from the authored pose at a delta of zero.
     */
    private static @NotNull EntityModelData.Bone posedBone(
        @NotNull EntityModelData.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written) {

        if (!movesGeometry(written)) return bone;

        Vector3f pivot = bone.getPivot();
        EulerRotation rotation = bone.getRotation();
        return new EntityModelData.Bone(
            new Vector3f(
                held(written, PoseChannel.X, pivot.x()),
                held(written, PoseChannel.Y, pivot.y()),
                held(written, PoseChannel.Z, pivot.z())),
            new EulerRotation(
                degrees(written, PoseChannel.X_ROT, rotation.pitch(), rotation.pitchRadians()),
                degrees(written, PoseChannel.Y_ROT, rotation.yaw(), rotation.yawRadians()),
                degrees(written, PoseChannel.Z_ROT, rotation.roll(), rotation.rollRadians())),
            bone.getBindPoseRotation(),
            scale(written, name, bone.getScale()),
            bone.getCubes(),
            bone.getParent());
    }

    /**
     * Whether any channel here is one a bone carries, so a bone written nothing but a flag is left
     * as the instance it already was.
     */
    private static boolean movesGeometry(@NotNull Map<PoseChannel, Float> written) {
        return written.keySet().stream().anyMatch(channel -> !channel.isFlag());
    }

    /** A pivot component the pose wrote, or the mesh's own where it wrote none. */
    private static float held(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel, float authored) {

        Float value = written.get(channel);
        return value == null ? authored : value;
    }

    /**
     * A rotation channel in the degrees a bone stores it in.
     *
     * <p>The table's arithmetic is in radians throughout, so a written channel converts back. One
     * written to exactly the radians the mesh already read keeps the authored degrees rather than
     * the value a round trip through two conversions lands on, which is what makes a bone the pose
     * puts back where it started bit-identical to one it never touched.
     */
    private static float degrees(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel,
        float authoredDegrees, float authoredRadians) {

        Float value = written.get(channel);
        if (value == null || value == authoredRadians) return authoredDegrees;
        return (float) Math.toDegrees(value);
    }

    /**
     * The one scale a bone holds, folded from the three axes the table writes.
     *
     * <p>Per-axis scale is a shape a bone cannot hold and not a shape the corpus needs: the single
     * model that scales writes one expression to all three axes, so folding them is exact. A
     * divergence is a mesh this cannot express, which is worth failing over rather than picking an
     * axis to believe.
     *
     * @throws RendererException if the three axes do not agree
     */
    private static float scale(
        @NotNull Map<PoseChannel, Float> written, @NotNull String bone, float authored) {

        float x = held(written, PoseChannel.X_SCALE, authored);
        float y = held(written, PoseChannel.Y_SCALE, authored);
        float z = held(written, PoseChannel.Z_SCALE, authored);
        if (x != y || y != z)
            throw new RendererException(
                "entity pose: bone '%s' scales to (%s, %s, %s), which one uniform bone scale cannot hold",
                bone, x, y, z);
        return x;
    }

    /**
     * Seats every top-level bone under the container the pose writes.
     *
     * <p>The container is a parent transform above them all and the mesh names it nowhere, so there
     * is no bone to write it onto - it enters as a cubeless bone every root is re-parented to, which
     * puts it through the same chain composition an authored parent goes through and draws nothing
     * of its own. It starts at rest, the flattening having already put whatever it held into the
     * bones below it, and it enters last so no bone that draws changes the order it is drawn in.
     *
     * <p>Top-level is read the way the chain composition reads it, and that is wider than a null
     * parent: a bone naming a parent this mesh does not declare hangs from the root, and the corpus
     * ships two of them. Reading only the null would seat the container above every bone but those,
     * which would draw them somewhere the rest of the subject is not.
     *
     * @throws RendererException if the container writes a channel a parent bone does not carry
     */
    private static void seatUnderContainer(
        @NotNull LinkedHashMap<String, EntityModelData.Bone> bones,
        @NotNull Map<PoseChannel, Float> written) {

        for (PoseChannel channel : written.keySet())
            if (channel.isFlag() || channel.kind() == PoseChannel.Kind.SCALE)
                throw new RendererException(
                    "entity pose: the container writes '%s', which reaches no bone below it",
                    channel.token());

        String name = containerName(bones.keySet());
        EntityModelData.Bone container = posedBone(new EntityModelData.Bone(), name, written);
        bones.replaceAll((bone, seated) -> !isTopLevel(bones, bone, seated) ? seated
            : new EntityModelData.Bone(seated.getPivot(), seated.getRotation(),
                seated.getBindPoseRotation(), seated.getScale(), seated.getCubes(), name));
        bones.put(name, container);
    }

    /** Whether a bone hangs from the root, by the same three tests the chain composition applies. */
    private static boolean isTopLevel(
        @NotNull Map<String, EntityModelData.Bone> bones, @NotNull String name,
        @NotNull EntityModelData.Bone bone) {

        String parent = bone.getParent();
        return parent == null || parent.equals(name) || !bones.containsKey(parent);
    }

    /** A name for the container that no bone of this mesh already answers to. */
    private static @NotNull String containerName(@NotNull Set<String> taken) {
        StringBuilder name = new StringBuilder(CONTAINER_BONE);
        while (taken.contains(name.toString())) name.append('_');
        return name.toString();
    }

}
