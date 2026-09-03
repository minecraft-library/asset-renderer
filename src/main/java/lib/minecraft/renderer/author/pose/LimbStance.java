package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The per-limb verb set - what one limb selector's lambda speaks.
 *
 * <p>A stance is always handed to a lambda and never escapes it: every verb returns the stance,
 * so a chain reads as one sentence and no bookkeeping call closes it. Verbs capture fragments in
 * author units - degrees, model pixels, seconds - and capture ONLY: rest rebasing, unit
 * conversion and every refusal happen at compile, against the target row.
 *
 * <p>Absolute writes ({@link #pitch}, {@link #yaw}, {@link #roll}, {@link #rotate}) state where
 * a channel lands - the statue vocabulary. Additive writes ({@link #pitchBy}, {@link #yawBy},
 * {@link #rollBy}, {@link #offset}) add to whatever already drives the channel, so they compose
 * with live stride math. Position is always additive: a pivot is not a human unit, so no verb
 * states one absolutely.
 */
public final class LimbStance {

    private final @NotNull List<PoseScript.Write> writes = new ArrayList<>();
    private final @NotNull List<PoseScript.Scale> scales = new ArrayList<>();
    private final @NotNull List<PoseScript.Aim> aims = new ArrayList<>();
    private final @NotNull List<PoseScript.Sway> sways = new ArrayList<>();
    private final @NotNull List<PoseScript.Spin> spins = new ArrayList<>();
    private final @NotNull List<PoseScript.Track> tracks = new ArrayList<>();

    LimbStance() {}

    /**
     * States where the limb's pitch lands.
     *
     * @param degrees the absolute pitch in degrees
     * @return this stance
     */
    public @NotNull LimbStance pitch(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.X_ROT, degrees, true));
        return this;
    }

    /**
     * States where the limb's yaw lands.
     *
     * @param degrees the absolute yaw in degrees
     * @return this stance
     */
    public @NotNull LimbStance yaw(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.Y_ROT, degrees, true));
        return this;
    }

    /**
     * States where the limb's roll lands.
     *
     * @param degrees the absolute roll in degrees
     * @return this stance
     */
    public @NotNull LimbStance roll(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.Z_ROT, degrees, true));
        return this;
    }

    /**
     * States where all three rotation channels land in one stamp - a zero component is an
     * absolute zero write, not an omission.
     *
     * @param pitchDegrees the absolute pitch in degrees
     * @param yawDegrees the absolute yaw in degrees
     * @param rollDegrees the absolute roll in degrees
     * @return this stance
     */
    public @NotNull LimbStance rotate(double pitchDegrees, double yawDegrees, double rollDegrees) {
        return this.pitch(pitchDegrees).yaw(yawDegrees).roll(rollDegrees);
    }

    /**
     * Scales the limb uniformly - all three scale channels take one factor.
     *
     * @param factor the scale factor, resting at one
     * @return this stance
     */
    public @NotNull LimbStance scale(double factor) {
        this.scales.add(new PoseScript.Scale(factor));
        return this;
    }

    /**
     * Displaces the limb from its authored pivot, in model pixels on vanilla's y-down axis.
     *
     * @param xPixels the sideways displacement
     * @param yPixels the vertical displacement, positive downward
     * @param zPixels the depth displacement
     * @return this stance
     */
    public @NotNull LimbStance offset(double xPixels, double yPixels, double zPixels) {
        this.writes.add(new PoseScript.Write(PoseChannel.X, xPixels, false));
        this.writes.add(new PoseScript.Write(PoseChannel.Y, yPixels, false));
        this.writes.add(new PoseScript.Write(PoseChannel.Z, zPixels, false));
        return this;
    }

    /**
     * Adds to the limb's pitch - stride-safe, composing with whatever already drives the
     * channel.
     *
     * @param degrees the pitch delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance pitchBy(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.X_ROT, degrees, false));
        return this;
    }

    /**
     * Adds to the limb's yaw - stride-safe, composing with whatever already drives the channel.
     *
     * @param degrees the yaw delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance yawBy(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.Y_ROT, degrees, false));
        return this;
    }

    /**
     * Adds to the limb's roll - stride-safe, composing with whatever already drives the
     * channel.
     *
     * @param degrees the roll delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance rollBy(double degrees) {
        this.writes.add(new PoseScript.Write(PoseChannel.Z_ROT, degrees, false));
        return this;
    }

    /**
     * Aims the limb at a model-space target point - pitch and yaw are solved from the limb's
     * own pivot per target row at compile, roll untouched. The target speaks the space
     * {@link #offset} speaks: model pixels, y-down, origin at the model root.
     *
     * @param xPixels the target's sideways component
     * @param yPixels the target's vertical component, positive downward
     * @param zPixels the target's depth component
     * @return this stance
     */
    public @NotNull LimbStance aimAt(double xPixels, double yPixels, double zPixels) {
        this.aims.add(new PoseScript.Aim(xPixels, yPixels, zPixels));
        return this;
    }

    /**
     * Sweeps the limb there and back between two bounds once per period - resting at the first
     * bound at the period's ends, peaking at the second mid-period. Bounds are deltas around
     * the stance.
     *
     * @param axis the rotation axis swept
     * @param fromDegrees the resting bound in degrees
     * @param toDegrees the peak bound in degrees
     * @return this stance
     */
    public @NotNull LimbStance sway(@NotNull Turn axis, double fromDegrees, double toDegrees) {
        this.sways.add(new PoseScript.Sway(axis, fromDegrees, toDegrees));
        return this;
    }

    /**
     * Turns the limb through a seamless ramp of the given angle once per period, wrapping
     * without a snap when the angle is a full turn.
     *
     * @param axis the rotation axis turned
     * @param perPeriodDegrees the degrees one period travels
     * @return this stance
     */
    public @NotNull LimbStance spin(@NotNull Turn axis, double perPeriodDegrees) {
        this.spins.add(new PoseScript.Spin(axis, perPeriodDegrees));
        return this;
    }

    /**
     * Captures a keyframed timeline for this limb - the lambda's verbs land on a fresh
     * {@link Timeline} that never escapes it.
     *
     * @param motion the timeline lambda
     * @return this stance
     */
    public @NotNull LimbStance timeline(@NotNull UnaryOperator<Timeline> motion) {
        Timeline timeline = new Timeline();
        motion.apply(timeline);
        this.tracks.add(timeline.captured());
        return this;
    }

    /**
     * Reads this stance out as one captured entry.
     *
     * @param limb the stanced bone and its aim axis, or empty for a container step
     * @return the captured stance
     */
    @NotNull PoseScript.Stance captured(@NotNull Optional<PoseScript.Limb> limb) {
        return new PoseScript.Stance(
            limb,
            Concurrent.newUnmodifiableList(this.writes),
            Concurrent.newUnmodifiableList(this.scales),
            Concurrent.newUnmodifiableList(this.aims),
            Concurrent.newUnmodifiableList(this.sways),
            Concurrent.newUnmodifiableList(this.spins),
            Concurrent.newUnmodifiableList(this.tracks)
        );
    }

}
