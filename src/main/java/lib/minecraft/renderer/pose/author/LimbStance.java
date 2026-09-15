package lib.minecraft.renderer.pose.author;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
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
 * <p>Absolute writes ({@link #pitch}, {@link #yaw}, {@link #roll}, {@link #rotate},
 * {@link #aimAt}, {@link #scale}) state where a channel lands - the statue vocabulary. Additive
 * writes ({@link #pitchBy}, {@link #yawBy}, {@link #rollBy}, {@link #rotateBy}, {@link #offset})
 * add to whatever already drives the channel, so they compose with live stride math. Position is
 * always additive: a pivot is not a human unit, so no verb states one absolutely.
 *
 * <p>The remaining verbs ({@link #sway}, {@link #spin}, {@link #timeline}) are neither, carrying
 * their values as deltas around the stance rather than stating or adding to a rest.
 */
@Parity(subject = Subject.ENTITY)
public final class LimbStance {

    private final @NotNull List<PoseScript.Fragment> fragments = new ArrayList<>();

    LimbStance() {}

    /**
     * States where the limb's pitch lands.
     *
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed angle to land on - where
     * {@link #pitchBy} composes with it instead.
     *
     * @param degrees the absolute pitch in degrees
     * @return this stance
     */
    public @NotNull LimbStance pitch(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.X_ROT, degrees, true));
        return this;
    }

    /**
     * States where the limb's yaw lands.
     *
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed angle to land on - where {@link #yawBy}
     * composes with it instead.
     *
     * @param degrees the absolute yaw in degrees
     * @return this stance
     */
    public @NotNull LimbStance yaw(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.Y_ROT, degrees, true));
        return this;
    }

    /**
     * States where the limb's roll lands.
     *
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed angle to land on - where {@link #rollBy}
     * composes with it instead.
     *
     * @param degrees the absolute roll in degrees
     * @return this stance
     */
    public @NotNull LimbStance roll(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.Z_ROT, degrees, true));
        return this;
    }

    /**
     * States where all three rotation channels land in one stamp - a zero component is an
     * absolute zero write, not an omission.
     *
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed angle to land on - where
     * {@link #rotateBy} composes with it instead.
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
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed extent to land on - and no additive
     * scale spelling composes with it instead.
     *
     * @param factor the scale factor, resting at one
     * @return this stance
     */
    public @NotNull LimbStance scale(double factor) {
        this.fragments.add(new PoseScript.Scale(factor));
        return this;
    }

    /**
     * Displaces the limb from its authored pivot, in model pixels on vanilla's y-down axis.
     *
     * <p>Adds to what the channel holds, so it composes with a live base and rides the stride
     * where an absolute write would refuse - position has no absolute spelling at all.
     *
     * @param xPixels the sideways displacement
     * @param yPixels the vertical displacement, positive downward
     * @param zPixels the depth displacement
     * @return this stance
     */
    public @NotNull LimbStance offset(double xPixels, double yPixels, double zPixels) {
        this.fragments.add(new PoseScript.Write(PoseChannel.X, xPixels, false));
        this.fragments.add(new PoseScript.Write(PoseChannel.Y, yPixels, false));
        this.fragments.add(new PoseScript.Write(PoseChannel.Z, zPixels, false));
        return this;
    }

    /**
     * Adds to the limb's pitch.
     *
     * <p>Adds to what the channel holds, so it composes with a live base and rides the stride
     * where {@link #pitch} refuses.
     *
     * @param degrees the pitch delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance pitchBy(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.X_ROT, degrees, false));
        return this;
    }

    /**
     * Adds to the limb's yaw.
     *
     * <p>Adds to what the channel holds, so it composes with a live base and rides the stride
     * where {@link #yaw} refuses.
     *
     * @param degrees the yaw delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance yawBy(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.Y_ROT, degrees, false));
        return this;
    }

    /**
     * Adds to the limb's roll.
     *
     * <p>Adds to what the channel holds, so it composes with a live base and rides the stride
     * where {@link #roll} refuses.
     *
     * @param degrees the roll delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance rollBy(double degrees) {
        this.fragments.add(new PoseScript.Write(PoseChannel.Z_ROT, degrees, false));
        return this;
    }

    /**
     * Adds to all three rotation channels in one stamp.
     *
     * <p>Adds to what the channel holds, so it composes with a live base and rides the stride
     * where {@link #rotate} refuses.
     *
     * @param pitchDegrees the pitch delta in degrees
     * @param yawDegrees the yaw delta in degrees
     * @param rollDegrees the roll delta in degrees
     * @return this stance
     */
    public @NotNull LimbStance rotateBy(double pitchDegrees, double yawDegrees, double rollDegrees) {
        return this.pitchBy(pitchDegrees).yawBy(yawDegrees).rollBy(rollDegrees);
    }

    /**
     * Aims the limb at a model-space target point - pitch and yaw are solved from the limb's
     * own pivot per target row at compile, roll untouched. The target speaks the space
     * {@link #offset} speaks: model pixels, y-down, origin at the model root.
     *
     * <p>Replaces what the channel holds, so a stance riding the stride refuses it over a channel
     * the shipped pose drives - a live base has no fixed angle to land on - where
     * {@link #pitchBy} and {@link #yawBy} compose with it instead.
     *
     * @param xPixels the target's sideways component
     * @param yPixels the target's vertical component, positive downward
     * @param zPixels the target's depth component
     * @return this stance
     */
    public @NotNull LimbStance aimAt(double xPixels, double yPixels, double zPixels) {
        this.fragments.add(new PoseScript.Aim(xPixels, yPixels, zPixels));
        return this;
    }

    /**
     * Sweeps the limb there and back between two bounds once per period - resting at the first
     * bound at the period's ends, peaking at the second mid-period.
     *
     * <p>Carries its values as deltas around the stance, so it neither states nor adds to a rest
     * and never meets the driven-base refusal.
     *
     * @param axis the rotation axis swept
     * @param fromDegrees the resting bound in degrees
     * @param toDegrees the peak bound in degrees
     * @return this stance
     */
    public @NotNull LimbStance sway(@NotNull Turn axis, double fromDegrees, double toDegrees) {
        this.fragments.add(new PoseScript.Sway(axis, fromDegrees, toDegrees));
        return this;
    }

    /**
     * Turns the limb through a seamless ramp of the given angle once per period, wrapping
     * without a snap when the angle is a full turn.
     *
     * <p>Carries its values as deltas around the stance, so it neither states nor adds to a rest
     * and never meets the driven-base refusal.
     *
     * @param axis the rotation axis turned
     * @param perPeriodDegrees the degrees one period travels
     * @return this stance
     */
    public @NotNull LimbStance spin(@NotNull Turn axis, double perPeriodDegrees) {
        this.fragments.add(new PoseScript.Spin(axis, perPeriodDegrees));
        return this;
    }

    /**
     * Captures a keyframed timeline for this limb - the lambda's verbs land on a fresh
     * {@link Keyframes} that never escapes it.
     *
     * <p>Carries its values as deltas around the stance, so it neither states nor adds to a rest
     * and never meets the driven-base refusal.
     *
     * @param motion the timeline lambda
     * @return this stance
     */
    public @NotNull LimbStance timeline(@NotNull UnaryOperator<Keyframes> motion) {
        Keyframes timeline = new Keyframes();
        motion.apply(timeline);
        this.fragments.add(timeline.captured());
        return this;
    }

    /**
     * Reads this stance out as one captured entry.
     *
     * @param limb the stanced bone and its aim axis, or empty for a container step
     * @return the captured stance
     */
    @NotNull PoseScript.Stance captured(@NotNull Optional<PoseScript.Limb> limb) {
        return new PoseScript.Stance(limb, Concurrent.newUnmodifiableList(this.fragments));
    }

}
