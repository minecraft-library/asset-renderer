package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.kit.PoseEvaluator;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The attachments a model's state silhouettes reveal between its top-level bones - which bone's
 * pivot rides which other bone's frame - derived beside the mesh from the placements vanilla
 * makes by hand.
 *
 * <p>A mesh says which bones are children of which, and a child rides its parent for free. What
 * it does not say is that a wolf's tail is seated on its body: the two are root-level siblings,
 * and only {@code setupAnim}'s sitting branch, which lowers the body and moves the tail to where
 * the lowered body carries it, knows they move together. The shipped silhouette of that branch
 * is the evidence, and this reads it: a bone F rides a bone L when, in every state the model
 * poses, L's frame carries F's resting pivot to where the state actually places F.
 *
 * <p><b>Attachment is told from contact by the ride, never by adjacency.</b> Two bones that
 * touch at bind are not a seat; two bones that MOVE TOGETHER across a hand placement are. The
 * test is quantitative: the residual between where L's frame carries F and where the state puts
 * F must stay within a tenth of how far F travelled, in every state, and F must travel at all -
 * a bone vanilla never moves is evidence of nothing. A humanoid's crouch moves its arms and its
 * body by different amounts, so the arms ride nothing; a sitting wolf's hind legs and tail land
 * within a third of a pixel of where the lowered body carries them, so they ride it.
 *
 * <p><b>A seat is a position, never a rotation.</b> Vanilla's hand placements move a pivot and
 * assign the part's own angle beside it - a sitting wolf's tail keeps its world pitch while the
 * body under it tilts - so a seated bone's pivot follows the leader's frame and its rotation
 * stays its own channels'. Two bones that carry each other exactly are one rigid pair with no
 * leader and seat neither way; a follower carried by several leaders takes the one that carries
 * it closest.
 *
 * <p>The frame arithmetic is the engine's own: a leader's rotation is the {@code rotationZYX}
 * quaternion the bone chain composes with, applied as a direction, so an offset carried here
 * lands where the chain would place a child at that offset.
 */
@Parity(subject = Subject.ENTITY)
public final class Seats {

    /**
     * The share of a pair's travel its ride may miss by and still be the ride - a tenth. Over
     * the shipped roster every seat vanilla places by hand rides within seven hundredths (a
     * wolf's hips at 0.033, its tail at 0.042, a feline's tail at 0.051 and its second segment
     * at 0.066, a polar bear's head at 0.054), and the first shares above a tenth are legs on
     * the part they hang beside - a baby wolf's forepaw on its hind leg (0.125), a polar bear's
     * forelegs on its body (0.140), an equine's on its body (0.143) - a cascade no evidence
     * supports, before the baby wolf's hips at 0.163, which ride the adult's constants on a
     * baby's proportions and are refused with them.
     */
    private static final float TOLERANCE = 0.1f;

    /**
     * The least a bone must travel in a state, in model units, for that state to witness a seat.
     */
    private static final float WITNESS = 1f;

    /**
     * What two placements may differ by and still count as the same place - float rounding
     * across a rotate and its inverse.
     */
    private static final float EPSILON = 1e-3f;

    private Seats() {}

    /**
     * Where one bone stands - its pivot and its rotation, in model units and radians.
     *
     * @param pivot the pivot in the model's own units
     * @param pitch the rotation about x, in radians
     * @param yaw the rotation about y, in radians
     * @param roll the rotation about z, in radians
     */
    public record Placement(@NotNull Vector3f pivot, float pitch, float yaw, float roll) {

        /**
         * Where this placement's frame carries a point given in the frame's own coordinates.
         *
         * @param local the point in this frame
         * @return the point in the parent frame
         */
        @NotNull Vector3f carry(@NotNull Vector3f local) {
            return this.pivot.add(local.transformNormal(this.rotation()));
        }

        /**
         * A point in the parent frame, read in this placement's own coordinates.
         *
         * @param point the point in the parent frame
         * @return the point in this frame
         */
        @NotNull Vector3f localOf(@NotNull Vector3f point) {
            return point.subtract(this.pivot).transformNormal(this.inverse());
        }

        /**
         * Whether this placement's rotation differs from another's on any axis.
         *
         * @param other the placement to compare against
         * @return {@code true} when any Euler angle differs by more than float rounding
         */
        boolean turnedFrom(@NotNull Placement other) {
            return Math.abs(this.pitch - other.pitch) > EPSILON
                || Math.abs(this.yaw - other.yaw) > EPSILON
                || Math.abs(this.roll - other.roll) > EPSILON;
        }

        /**
         * This placement with some channels written over it - a state's placement over the rest.
         *
         * @param written the channels the state writes, positions in model units, rotations in radians
         * @return the placement with those channels replaced
         */
        @NotNull Placement with(@NotNull Map<PoseChannel, Float> written) {
            return new Placement(
                new Vector3f(
                    held(written, PoseChannel.X, this.pivot.x()),
                    held(written, PoseChannel.Y, this.pivot.y()),
                    held(written, PoseChannel.Z, this.pivot.z())),
                held(written, PoseChannel.X_ROT, this.pitch),
                held(written, PoseChannel.Y_ROT, this.yaw),
                held(written, PoseChannel.Z_ROT, this.roll));
        }

        /**
         * The rotation the bone chain composes for this placement - {@code rotationZYX} over the
         * roll, yaw and pitch, the same quaternion the engine builds a bone's step from.
         */
        private @NotNull Matrix4f rotation() {
            return Quaternionf.rotationZYX(this.roll, this.yaw, this.pitch).toMatrix4f();
        }

        /**
         * The inverse rotation - the conjugate of the same unit quaternion.
         */
        private @NotNull Matrix4f inverse() {
            Quaternionf turned = Quaternionf.rotationZYX(this.roll, this.yaw, this.pitch);
            return new Quaternionf(-turned.x(), -turned.y(), -turned.z(), turned.w()).toMatrix4f();
        }

    }

    /**
     * One seat - the leader a follower rides, and where the follower's pivot sits in the leader's
     * resting frame.
     *
     * @param leader the top-level bone whose frame carries the follower
     * @param offset the follower's resting pivot in the leader's resting frame, model units
     */
    public record Seat(@NotNull String leader, @NotNull Vector3f offset) {}

    /**
     * What one derivation found - the seats, the resting placement of every top-level bone they
     * were derived over, and the states that witnessed them.
     *
     * @param seats each follower's seat, by follower name, in mesh order
     * @param rest every top-level bone's resting placement, by name, in mesh order
     * @param witnesses the state keys whose silhouette witnesses a seat, in the pose's own order -
     *     the hand placements of parts that move together, which are the only silhouettes that
     *     say how the subject looks seated
     */
    public record Derived(
        @NotNull Map<String, Seat> seats,
        @NotNull Map<String, Placement> rest,
        @NotNull Set<String> witnesses
    ) {

        /** The derivation of a mesh whose pose carries no silhouette, spelled once. */
        static @NotNull Derived none(@NotNull Map<String, Placement> rest) {
            return new Derived(Map.of(), rest, Set.of());
        }

    }

    /**
     * One leader's carry of one follower - how closely the leader's frame carries the follower
     * over the states, and which states witnessed it.
     *
     * @param residual the worst residual over the states, as a share of the wider travel
     * @param witnesses the states that witness the ride, as indices into the states in pose order
     */
    private record Ride(float residual, @NotNull List<Integer> witnesses) {}

    /**
     * Derives the seats a pose's silhouettes reveal over one mesh.
     *
     * @param pose the shipped pose, read for its resting channels and its silhouettes
     * @param mesh the mesh the pose belongs to
     * @return the seats and the resting placements they were derived over
     */
    public static @NotNull Derived derive(@NotNull EntityPose pose, @NotNull EntityModelData mesh) {
        Map<String, Placement> rest = restOf(pose, mesh);
        if (pose.states().isEmpty() || rest.size() < 2) return Derived.none(rest);

        List<String> keys = new ArrayList<>(pose.states().keySet());
        List<Map<String, Placement>> states = new ArrayList<>(keys.size());
        for (String key : keys) states.add(placedBy(pose.states().get(key), rest, mesh));

        Map<String, Seat> seats = new LinkedHashMap<>();
        boolean[] witnessing = new boolean[keys.size()];
        for (String follower : rest.keySet()) {
            String best = null;
            Ride closest = null;
            for (String leader : rest.keySet()) {
                if (leader.equals(follower)) continue;
                Optional<Ride> ride = ride(follower, leader, rest, states);
                if (ride.isEmpty() || (closest != null && ride.get().residual() >= closest.residual())) continue;
                // A pair that carry each other are one rigid piece: neither leads the other.
                if (ride(leader, follower, rest, states).isPresent()) continue;
                best = leader;
                closest = ride.get();
            }
            if (best == null) continue;
            seats.put(follower, new Seat(best, rest.get(best).localOf(rest.get(follower).pivot())));
            for (int witness : closest.witnesses()) witnessing[witness] = true;
        }

        Set<String> witnesses = new LinkedHashSet<>();
        for (int at = 0; at < keys.size(); at++)
            if (witnessing[at]) witnesses.add(keys.get(at));
        return new Derived(Collections.unmodifiableMap(seats), rest, Collections.unmodifiableSet(witnesses));
    }

    /**
     * How closely a leader's frame carries a follower across every state, and which states
     * witnessed it, or empty where it does not carry it - the worst residual over the states,
     * as a share of the wider of the two bones' travel in that state.
     *
     * <p><b>A state witnesses a seat only where the leader TURNS.</b> A leader that merely
     * translates carries every bone that translates with it, and a whole figure shifted by
     * hand - a parrot sat lower, every part down by the same amount - would read as every
     * part seated on every other. Only a turned frame tells a ride from a shared shift, so
     * the follower must travel at least a pixel in some state where the leader's rotation
     * changed.
     *
     * <p><b>A state that places neither pivot says nothing about a seat.</b> A seat is a
     * position, and a state that turns parts each about its own pivot - a wolf's shake rolling
     * its body, mane, head and tail where they stand - is a turn of each part rather than a
     * placement of any, so it neither witnesses a ride nor contradicts one.
     */
    private static @NotNull Optional<Ride> ride(
        @NotNull String follower, @NotNull String leader,
        @NotNull Map<String, Placement> rest, @NotNull List<Map<String, Placement>> states) {

        Placement followerRest = rest.get(follower);
        Placement leaderRest = rest.get(leader);
        Vector3f local = leaderRest.localOf(followerRest.pivot());
        List<Integer> witnesses = new ArrayList<>();
        float worst = 0f;
        for (int at = 0; at < states.size(); at++) {
            Map<String, Placement> state = states.get(at);
            Placement followerNow = state.get(follower);
            Placement leaderNow = state.get(leader);
            float travelled = followerNow.pivot().subtract(followerRest.pivot()).length();
            float carried = leaderNow.pivot().subtract(leaderRest.pivot()).length();
            if (travelled <= EPSILON && carried <= EPSILON) continue;
            float residual = followerNow.pivot().subtract(leaderNow.carry(local)).length();
            float scale = Math.max(travelled, carried);
            if (residual > Math.max(EPSILON, TOLERANCE * scale)) return Optional.empty();
            if (travelled >= WITNESS && leaderNow.turnedFrom(leaderRest)) witnesses.add(at);
            if (scale > 0f) worst = Math.max(worst, residual / scale);
        }
        return witnesses.isEmpty() ? Optional.empty() : Optional.of(new Ride(worst, List.copyOf(witnesses)));
    }

    /**
     * The resting placement of every top-level bone - the shipped pose evaluated with every
     * figure resting, the mesh's own values where the pose writes nothing.
     */
    static @NotNull Map<String, Placement> restOf(@NotNull EntityPose pose, @NotNull EntityModelData mesh) {
        PoseEvaluator.ChannelWrites written = PoseEvaluator.evaluate(pose, mesh, PoseEvaluator.AT_REST);
        Map<String, Placement> rest = new LinkedHashMap<>();
        mesh.getBones().forEach((name, bone) -> {
            if (!isTopLevel(mesh, name, bone)) return;
            rest.put(name, placement(bone, mesh.getFlattenedScale(),
                written.bones().getOrDefault(name, Map.of())));
        });
        return Collections.unmodifiableMap(rest);
    }

    /**
     * Where every top-level bone stands in one state - the resting placement with the state's
     * channels evaluated over it, every figure resting.
     */
    private static @NotNull Map<String, Placement> placedBy(
        @NotNull EntityPose.Silhouette silhouette, @NotNull Map<String, Placement> rest,
        @NotNull EntityModelData mesh) {

        Map<String, Placement> placed = new LinkedHashMap<>(rest);
        silhouette.bones().forEach((name, channels) -> {
            EntityModelData.Bone bone = mesh.getBones().get(name);
            if (bone == null || !rest.containsKey(name)) return;
            List<PoseChannel> keys = new ArrayList<>(channels.keySet());
            List<PoseExpr> expressions = new ArrayList<>(keys.size());
            for (PoseChannel key : keys) expressions.add(channels.get(key));
            ConcurrentList<Float> values = PoseEvaluator.values(
                Concurrent.newUnmodifiableList(expressions), mesh, PoseEvaluator.AT_REST);
            Map<PoseChannel, Float> written = new LinkedHashMap<>();
            for (int at = 0; at < keys.size(); at++) written.put(keys.get(at), values.get(at));
            placed.put(name, rest.get(name).with(written));
        });
        return placed;
    }

    /**
     * One bone's placement - a written channel where the pose writes it, the mesh's own value
     * where it does not, positions in the model's own units: the kit's read, so a top-level pivot
     * of a flattened mesh rests where a written channel would evaluate it, factor and feet anchor
     * both taken off.
     */
    private static @NotNull Placement placement(
        @NotNull EntityModelData.Bone bone, float flattened, @NotNull Map<PoseChannel, Float> written) {

        return new Placement(
            new Vector3f(
                held(written, PoseChannel.X, PoseKit.authored(bone, PoseChannel.X, flattened)),
                held(written, PoseChannel.Y, PoseKit.authored(bone, PoseChannel.Y, flattened)),
                held(written, PoseChannel.Z, PoseKit.authored(bone, PoseChannel.Z, flattened))),
            held(written, PoseChannel.X_ROT, bone.getRotation().pitchRadians()),
            held(written, PoseChannel.Y_ROT, bone.getRotation().yawRadians()),
            held(written, PoseChannel.Z_ROT, bone.getRotation().rollRadians()));
    }

    private static float held(@NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel, float authored) {
        Float value = written.get(channel);
        return value == null ? authored : value;
    }

    /** Whether a bone hangs from the root, by the tests the chain composition applies. */
    private static boolean isTopLevel(
        @NotNull EntityModelData mesh, @NotNull String name, @NotNull EntityModelData.Bone bone) {

        String parent = bone.getParent();
        return parent == null || parent.equals(name) || !mesh.getBones().containsKey(parent);
    }

}
