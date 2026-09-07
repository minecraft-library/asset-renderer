package lib.minecraft.renderer.pose.author;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The keyframe authoring verb set - entered through {@link LimbStance#timeline} and
 * lambda-scoped like the stance itself.
 *
 * <p>Every rotation value is a DELTA in degrees around the stance and every position value a
 * delta in model pixels - a clip adds onto what the pose left, and both rest at zero so unused
 * axes are inert. Times are seconds. Verbs capture only: keyframe emission, unit conversion and
 * every refusal happen at compile.
 */
@Parity(subject = Subject.ENTITY)
public final class Keyframes {

    private final @NotNull List<PoseScript.Motion> motions = new ArrayList<>();
    private @NotNull OptionalDouble overSeconds = OptionalDouble.empty();
    private @NotNull Ease ease = Ease.LINEAR;
    private boolean looping = true;

    Keyframes() {}

    /**
     * Swings one rotation axis there and back - the first bound at both ends of the timeline,
     * the second at the middle, zero on the other two axes.
     *
     * @param axis the rotation axis swung
     * @param fromDegrees the delta at the timeline's ends
     * @param toDegrees the delta at the timeline's middle
     * @return this timeline
     */
    public @NotNull Keyframes swing(@NotNull Turn axis, double fromDegrees, double toDegrees) {
        this.motions.add(new PoseScript.Swing(axis, fromDegrees, toDegrees));
        return this;
    }

    /**
     * Bobs the limb vertically - level at both ends of the timeline, lifted by the given pixels
     * at the middle.
     *
     * @param pixels the lift at the peak, in model pixels; positive lifts
     * @return this timeline
     */
    public @NotNull Keyframes bob(double pixels) {
        this.motions.add(new PoseScript.Bob(pixels));
        return this;
    }

    /**
     * Captures an explicit rotation keyframe.
     *
     * @param atSeconds when in the timeline the frame sits
     * @param pitchDegrees the pitch delta at the frame
     * @param yawDegrees the yaw delta at the frame
     * @param rollDegrees the roll delta at the frame
     * @return this timeline
     */
    public @NotNull Keyframes keyframe(double atSeconds, double pitchDegrees, double yawDegrees, double rollDegrees) {
        this.motions.add(new PoseScript.Keyframe(atSeconds, pitchDegrees, yawDegrees, rollDegrees));
        return this;
    }

    /**
     * Captures an explicit position keyframe, in model pixels.
     *
     * @param atSeconds when in the timeline the frame sits
     * @param xPixels the sideways delta at the frame
     * @param yPixels the vertical delta at the frame, positive downward
     * @param zPixels the depth delta at the frame
     * @return this timeline
     */
    public @NotNull Keyframes shift(double atSeconds, double xPixels, double yPixels, double zPixels) {
        this.motions.add(new PoseScript.Shift(atSeconds, xPixels, yPixels, zPixels));
        return this;
    }

    /**
     * Sets the timeline length; unset defaults to the strip window, and a length that divides
     * the window tiles the rendered strip seamlessly.
     *
     * @param seconds the timeline length in seconds
     * @return this timeline
     */
    public @NotNull Keyframes over(double seconds) {
        this.overSeconds = OptionalDouble.of(seconds);
        return this;
    }

    /**
     * Stamps the curve on every keyframe this timeline emits; {@link Ease#LINEAR} is the
     * default.
     *
     * @param ease the curve
     * @return this timeline
     */
    public @NotNull Keyframes ease(@NotNull Ease ease) {
        this.ease = ease;
        return this;
    }

    /**
     * Restarts the timeline when it ends - the default.
     *
     * @return this timeline
     */
    public @NotNull Keyframes loop() {
        this.looping = true;
        return this;
    }

    /**
     * Holds the last frame instead of restarting.
     *
     * @return this timeline
     */
    public @NotNull Keyframes once() {
        this.looping = false;
        return this;
    }

    /**
     * Reads this timeline out as one captured track.
     *
     * @return the captured track
     */
    @NotNull PoseScript.Track captured() {
        return new PoseScript.Track(Concurrent.newUnmodifiableList(this.motions), this.overSeconds, this.ease, this.looping);
    }

}
