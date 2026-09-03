package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

/**
 * The unit-agnostic intermediate form of an authored pose - everything a builder captured, in
 * author order and author units.
 *
 * <p>A script holds what was SPELLED, never what it lowers to: rotations stay in degrees,
 * positions in model pixels, times in seconds. Compilation against a target row owns every unit
 * conversion, every rest rebase and every refusal, so one script installs on many rows and each
 * row lowers it against its own mesh and rest values.
 *
 * <p>The one runtime type a script may hold is a {@link PoseExpr} captured raw ({@link Raw}) -
 * held by reference, never copied and never walked at capture, because a captured expression can
 * be a graph whose nodes many paths reach and whose tree expansion does not terminate in
 * practice.
 *
 * @param stances the captured selector calls, in author order - a bone stance carries its limb,
 *     a container step carries none
 * @param raws the raw expression captures, spliced verbatim at compile
 * @param keepStride whether the universal stride drivers ride the built style
 * @param hover the container lift-and-bob idiom, when authored
 * @param periodSeconds the declared excursion period in seconds; empty rides the catalog period
 */
public record PoseScript(
    @NotNull ConcurrentList<Stance> stances,
    @NotNull ConcurrentList<Raw> raws,
    boolean keepStride,
    @NotNull Optional<Hover> hover,
    @NotNull OptionalDouble periodSeconds
) {

    /**
     * Which model-space direction a limb's rest posture points along, for aim solves.
     */
    public enum AimAxis {

        /**
         * The limb aims its facing direction - a head turns to look at the target.
         */
        FACING,

        /**
         * The limb rests pointing down and aims along its length - an arm raises toward the target.
         */
        DOWN

    }

    /**
     * The bone a stance addresses, with the aim axis stamped for it.
     *
     * @param bone the bone name, as the mesh names it
     * @param axis the direction the limb's rest posture points along
     */
    public record Limb(@NotNull String bone, @NotNull AimAxis axis) {}

    /**
     * One captured selector call - a limb's (or a container step's) verbs, each fragment list in
     * call order.
     *
     * @param limb the stanced bone and its aim axis; empty for a container step
     * @param writes the captured channel writes
     * @param scales the captured uniform scales
     * @param aims the captured aim targets
     * @param sways the captured sweeps
     * @param spins the captured full turns
     * @param tracks the captured timelines
     */
    public record Stance(
        @NotNull Optional<Limb> limb,
        @NotNull ConcurrentList<Write> writes,
        @NotNull ConcurrentList<Scale> scales,
        @NotNull ConcurrentList<Aim> aims,
        @NotNull ConcurrentList<Sway> sways,
        @NotNull ConcurrentList<Spin> spins,
        @NotNull ConcurrentList<Track> tracks
    ) {}

    /**
     * One captured channel write.
     *
     * @param channel the channel written
     * @param value the authored value - degrees on a rotation channel, model pixels on a
     *     position one
     * @param absolute whether the value states where the channel lands; an additive write adds
     *     to whatever already drives the channel
     */
    public record Write(@NotNull PoseChannel channel, double value, boolean absolute) {}

    /**
     * One captured uniform scale over all three scale channels.
     *
     * @param factor the scale factor, resting at one
     */
    public record Scale(double factor) {}

    /**
     * One captured aim target - a model-space point the stanced limb points at, solved into
     * pitch and yaw at compile with roll untouched.
     *
     * @param xPixels the target's sideways component, in model pixels from the model root
     * @param yPixels the target's vertical component, in model pixels on the y-down axis
     * @param zPixels the target's depth component, in model pixels
     */
    public record Aim(double xPixels, double yPixels, double zPixels) {}

    /**
     * One captured sway - a there-and-back sweep between two bounds once per period.
     *
     * @param axis the rotation axis swept
     * @param fromDegrees the bound the sweep rests at, at both ends of the period
     * @param toDegrees the bound the sweep peaks at mid-period
     */
    public record Sway(@NotNull Turn axis, double fromDegrees, double toDegrees) {}

    /**
     * One captured spin - a seamless ramp through a full angle once per period.
     *
     * @param axis the rotation axis turned
     * @param perPeriodDegrees the degrees one period travels
     */
    public record Spin(@NotNull Turn axis, double perPeriodDegrees) {}

    /**
     * One captured timeline - a limb's keyframed motion fragments plus the settings shaping
     * every keyframe they emit.
     *
     * @param motions the captured motion fragments, in call order
     * @param overSeconds the timeline length in seconds; empty defaults to the strip window
     * @param ease the curve stamped on every emitted keyframe
     * @param looping whether the timeline restarts rather than holding its last frame
     */
    public record Track(
        @NotNull ConcurrentList<Motion> motions,
        @NotNull OptionalDouble overSeconds,
        @NotNull Ease ease,
        boolean looping
    ) {}

    /**
     * One captured timeline motion fragment - all values are deltas around the stance.
     */
    public sealed interface Motion permits Swing, Bob, Keyframe, Shift {}

    /**
     * A rotation triangle on one axis - the first bound at both ends of the timeline, the
     * second at the middle, zero on the other two axes.
     *
     * @param axis the rotation axis swung
     * @param fromDegrees the delta at the timeline's ends
     * @param toDegrees the delta at the timeline's middle
     */
    public record Swing(@NotNull Turn axis, double fromDegrees, double toDegrees) implements Motion {}

    /**
     * A vertical position triangle - level at both ends of the timeline, lifted at the middle.
     *
     * @param pixels the lift at the triangle's peak, in model pixels; positive lifts, the
     *     lowering supplies the y-down sign
     */
    public record Bob(double pixels) implements Motion {}

    /**
     * An explicit rotation keyframe.
     *
     * @param atSeconds when in the timeline the frame sits
     * @param pitchDegrees the pitch delta at the frame
     * @param yawDegrees the yaw delta at the frame
     * @param rollDegrees the roll delta at the frame
     */
    public record Keyframe(double atSeconds, double pitchDegrees, double yawDegrees, double rollDegrees) implements Motion {}

    /**
     * An explicit position keyframe.
     *
     * @param atSeconds when in the timeline the frame sits
     * @param xPixels the sideways delta at the frame, in model pixels
     * @param yPixels the vertical delta at the frame, in model pixels on the y-down axis
     * @param zPixels the depth delta at the frame, in model pixels
     */
    public record Shift(double atSeconds, double xPixels, double yPixels, double zPixels) implements Motion {}

    /**
     * The container lift-and-bob idiom - the whole figure raised and gently dipped.
     *
     * @param liftPixels how far the whole figure lifts, in model pixels; positive lifts, the
     *     lowering supplies the y-down sign
     * @param bobPixels the dip-and-return excursion around the lift, once per period; zero
     *     keeps the style still
     */
    public record Hover(double liftPixels, double bobPixels) {}

    /**
     * One raw expression capture - an authored graph replacing a channel verbatim at compile.
     *
     * @param bone the bone the expression writes
     * @param channel the channel it replaces
     * @param expr the expression graph, held by reference and never walked at capture
     */
    public record Raw(@NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr expr) {}

    /**
     * The accumulator behind the tier builders - runs stance lambdas, collects their captured
     * fragments in call order and snapshots the script.
     */
    static final class Capture {

        private final @NotNull List<Stance> stances = new ArrayList<>();
        private final @NotNull List<Raw> raws = new ArrayList<>();
        private boolean keepStride;
        private @Nullable Hover hover;
        private @NotNull OptionalDouble periodSeconds = OptionalDouble.empty();

        /**
         * Captures one limb stance - the lambda's verbs land on a fresh stance whose fragments
         * append as one ordered entry.
         *
         * @param bone the stanced bone, as the mesh names it
         * @param axis the aim axis stamped for this limb - heads aim their facing direction,
         *     hanging limbs aim down their length
         * @param verbs the stance lambda
         * @return this capture
         */
        @NotNull Capture stance(@NotNull String bone, @NotNull AimAxis axis, @NotNull UnaryOperator<LimbStance> verbs) {
            LimbStance stance = new LimbStance();
            verbs.apply(stance);
            this.stances.add(stance.captured(Optional.of(new Limb(bone, axis))));
            return this;
        }

        /**
         * Captures one container step - the same verb surface addressed at the whole figure's
         * seat rather than at a bone.
         *
         * @param verbs the step lambda
         * @return this capture
         */
        @NotNull Capture step(@NotNull UnaryOperator<LimbStance> verbs) {
            LimbStance stance = new LimbStance();
            verbs.apply(stance);
            this.stances.add(stance.captured(Optional.empty()));
            return this;
        }

        /**
         * Captures one limb stance and stamps its mirror onto the paired limb - the authored
         * side as given, the paired side under the mirror sign rule: pitch kept, yaw and roll
         * negated, the sideways component of positions and aim targets negated, scale factors
         * and times untouched.
         *
         * @param authored the stanced bone the lambda addresses, as the mesh names it
         * @param paired the opposite bone the mirrored copy lands on
         * @param axis the aim axis stamped for both limbs
         * @param verbs the stance lambda, run once - the paired stance is derived from the
         *     captured one, never re-captured
         * @return this capture
         */
        @NotNull Capture pair(@NotNull String authored, @NotNull String paired, @NotNull AimAxis axis, @NotNull UnaryOperator<LimbStance> verbs) {
            LimbStance stance = new LimbStance();
            verbs.apply(stance);
            Stance captured = stance.captured(Optional.of(new Limb(authored, axis)));
            this.stances.add(captured);
            this.stances.add(mirrored(captured, Optional.of(new Limb(paired, axis))));
            return this;
        }

        /**
         * Whether any captured stance addresses the given bone.
         *
         * @param bone the bone name to look for
         * @return whether a stance addresses it
         */
        boolean stanced(@NotNull String bone) {
            return this.stances.stream().anyMatch(stance -> stance.limb()
                .map(limb -> limb.bone().equals(bone))
                .orElse(false));
        }

        /**
         * Appends mirrored copies of every stance the source bone has captured so far onto the
         * target bone, under the mirror sign rule; the source stances are untouched.
         *
         * @param source the bone whose stances are mirrored
         * @param target the bone the mirrored copies land on
         * @return this capture
         */
        @NotNull Capture mirror(@NotNull String source, @NotNull String target) {
            List<Stance> copies = new ArrayList<>();
            for (Stance stance : this.stances)
                stance.limb()
                    .filter(limb -> limb.bone().equals(source))
                    .ifPresent(limb -> copies.add(mirrored(stance, Optional.of(new Limb(target, limb.axis())))));
            this.stances.addAll(copies);
            return this;
        }

        /**
         * Appends verbatim copies of every stance the source bone has captured so far onto the
         * target bone - fragment lists shared by reference, values untouched.
         *
         * @param source the bone whose stances are copied
         * @param target the bone the copies land on
         * @return this capture
         */
        @NotNull Capture copy(@NotNull String source, @NotNull String target) {
            List<Stance> copies = new ArrayList<>();
            for (Stance stance : this.stances)
                stance.limb()
                    .filter(limb -> limb.bone().equals(source))
                    .ifPresent(limb -> copies.add(new Stance(Optional.of(new Limb(target, limb.axis())),
                        stance.writes(), stance.scales(), stance.aims(),
                        stance.sways(), stance.spins(), stance.tracks())));
            this.stances.addAll(copies);
            return this;
        }

        /**
         * Rewrites every captured stance into its mirror image: a stance on a paired bone
         * crosses to its opposite under the mirror sign rule, and every other stance - a
         * centred bone or a container step - mirrors in place.
         *
         * @param pairs the paired bones, each direction its own entry
         * @return this capture
         */
        @NotNull Capture flip(@NotNull Map<String, String> pairs) {
            this.stances.replaceAll(stance -> mirrored(stance, stance.limb()
                .map(limb -> new Limb(pairs.getOrDefault(limb.bone(), limb.bone()), limb.axis()))));
            return this;
        }

        /**
         * Captures one raw expression, held by reference.
         *
         * @param bone the bone the expression writes
         * @param channel the channel it replaces
         * @param expr the expression graph
         * @return this capture
         */
        @NotNull Capture raw(@NotNull String bone, @NotNull PoseChannel channel, @NotNull PoseExpr expr) {
            this.raws.add(new Raw(bone, channel, expr));
            return this;
        }

        /**
         * Marks the script as riding the universal stride drivers.
         *
         * @return this capture
         */
        @NotNull Capture keepStride() {
            this.keepStride = true;
            return this;
        }

        /**
         * Captures the container lift-and-bob idiom; a later call replaces an earlier one.
         *
         * @param liftPixels how far the whole figure lifts, in model pixels
         * @param bobPixels the dip-and-return excursion around the lift
         * @return this capture
         */
        @NotNull Capture hover(double liftPixels, double bobPixels) {
            this.hover = new Hover(liftPixels, bobPixels);
            return this;
        }

        /**
         * Captures a declared excursion period; a later call replaces an earlier one.
         *
         * @param seconds the period in seconds
         * @return this capture
         */
        @NotNull Capture period(double seconds) {
            this.periodSeconds = OptionalDouble.of(seconds);
            return this;
        }

        /**
         * Snapshots the captured script - the fragment lists are unmodifiable copies, so a
         * snapshot is unaffected by later captures.
         *
         * @return the captured script
         */
        @NotNull PoseScript script() {
            return new PoseScript(
                Concurrent.newUnmodifiableList(this.stances),
                Concurrent.newUnmodifiableList(this.raws),
                this.keepStride,
                Optional.ofNullable(this.hover),
                this.periodSeconds
            );
        }

        /**
         * Derives one stance's mirror image under the mirror sign rule, re-addressed at the
         * given limb.
         *
         * @param stance the stance to mirror
         * @param limb the limb the mirror lands on; empty for a container step
         * @return the mirrored stance
         */
        private static @NotNull Stance mirrored(@NotNull Stance stance, @NotNull Optional<Limb> limb) {
            return new Stance(
                limb,
                Concurrent.newUnmodifiableList(stance.writes().stream().map(Capture::mirrored).toList()),
                stance.scales(),
                Concurrent.newUnmodifiableList(stance.aims().stream().map(Capture::mirrored).toList()),
                Concurrent.newUnmodifiableList(stance.sways().stream().map(Capture::mirrored).toList()),
                Concurrent.newUnmodifiableList(stance.spins().stream().map(Capture::mirrored).toList()),
                Concurrent.newUnmodifiableList(stance.tracks().stream().map(Capture::mirrored).toList())
            );
        }

        /**
         * Mirrors one channel write - yaw, roll and the sideways position negate, all else holds.
         */
        private static @NotNull Write mirrored(@NotNull Write write) {
            return switch (write.channel()) {
                case Y_ROT, Z_ROT, X -> new Write(write.channel(), negated(write.value()), write.absolute());
                default -> write;
            };
        }

        /**
         * Mirrors one aim target - the sideways component crosses the centre plane.
         */
        private static @NotNull Aim mirrored(@NotNull Aim aim) {
            return new Aim(negated(aim.xPixels()), aim.yPixels(), aim.zPixels());
        }

        /**
         * Mirrors one sway - yaw and roll bounds negate, pitch holds.
         */
        private static @NotNull Sway mirrored(@NotNull Sway sway) {
            return sway.axis() == Turn.PITCH
                ? sway
                : new Sway(sway.axis(), negated(sway.fromDegrees()), negated(sway.toDegrees()));
        }

        /**
         * Mirrors one spin - yaw and roll travel negates, pitch holds.
         */
        private static @NotNull Spin mirrored(@NotNull Spin spin) {
            return spin.axis() == Turn.PITCH
                ? spin
                : new Spin(spin.axis(), negated(spin.perPeriodDegrees()));
        }

        /**
         * Mirrors one timeline - every motion fragment mirrored, the settings untouched.
         */
        private static @NotNull Track mirrored(@NotNull Track track) {
            return new Track(
                Concurrent.newUnmodifiableList(track.motions().stream().map(Capture::mirrored).toList()),
                track.overSeconds(),
                track.ease(),
                track.looping()
            );
        }

        /**
         * Mirrors one motion fragment under the same sign rule the stance verbs follow.
         */
        private static @NotNull Motion mirrored(@NotNull Motion motion) {
            return switch (motion) {
                case Swing swing -> swing.axis() == Turn.PITCH
                    ? swing
                    : new Swing(swing.axis(), negated(swing.fromDegrees()), negated(swing.toDegrees()));
                case Bob bob -> bob;
                case Keyframe frame -> new Keyframe(frame.atSeconds(), frame.pitchDegrees(),
                    negated(frame.yawDegrees()), negated(frame.rollDegrees()));
                case Shift shift -> new Shift(shift.atSeconds(), negated(shift.xPixels()), shift.yPixels(), shift.zPixels());
            };
        }

        /**
         * Negates a value, keeping an authored zero as authored - the mirror of no travel is no travel.
         */
        private static double negated(double value) {
            return value == 0 ? value : -value;
        }

    }

}
