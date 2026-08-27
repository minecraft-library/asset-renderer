package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

/**
 * One authored keyframe animation - a table of what each bone is displaced by as a clip runs.
 *
 * <p><b>A clip ADDS where a pose REPLACES.</b> The pose table carries the arithmetic a
 * {@code setupAnim} body does, and that body assigns: a channel it writes stands at the value it
 * computed. A clip is applied on top through vanilla's three offset members, every one of which is a
 * {@code +=} on the value already there - position, rotation and scale alike. So the two compose in
 * one direction only, and a clip's value is a displacement rather than a place.
 *
 * <p><b>Scale adds too, and that is not a slip.</b> A bone's scale rests at one and a clip's scale
 * channel rests at zero, so the two are the same statement said from different sides - the delta is
 * what is added to the one, not a factor multiplied into it. Reading it as a multiplier collapses
 * every bone a clip scales to nothing at the instants the clip is at rest.
 *
 * <p>Times are in seconds and rotations in RADIANS, both as vanilla bakes them: a rotation channel
 * is authored in degrees and converted before the table ever sees it, so nothing here converts.
 *
 * @param lengthSeconds how long one run of the clip lasts
 * @param looping whether the clip restarts rather than holding its last frame
 * @param channels what the clip displaces, one entry per bone and target
 */
public record PoseClip(
    float lengthSeconds,
    boolean looping,
    @NotNull ConcurrentList<Channel> channels
) {

    /**
     * Which of a bone's three members a channel displaces.
     *
     * <p>Each names the channel triple it lands on, so nothing downstream re-derives the mapping and
     * a target the table gains has one place to declare it.
     */
    @EnumLookup
    @RequiredArgsConstructor
    public enum Target {

        /** The bone's pivot, in model pixels. */
        POSITION("position", PoseChannel.X, PoseChannel.Y, PoseChannel.Z),

        /** The bone's rotation, in radians. */
        ROTATION("rotation", PoseChannel.X_ROT, PoseChannel.Y_ROT, PoseChannel.Z_ROT),

        /** The bone's per-axis scale, added to the one it rests at. */
        SCALE("scale", PoseChannel.X_SCALE, PoseChannel.Y_SCALE, PoseChannel.Z_SCALE);

        /** The lower-case token this target is spelled with in the shipped table. */
        @KeyField
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull String token;

        private final @NotNull PoseChannel x;
        private final @NotNull PoseChannel y;
        private final @NotNull PoseChannel z;

        /**
         * The channel one component of this target displaces.
         *
         * @param axis the component, zero for x
         * @return the channel it lands on
         * @throws IllegalArgumentException if the axis is not one of three
         */
        public @NotNull PoseChannel channel(int axis) {
            return switch (axis) {
                case 0 -> this.x;
                case 1 -> this.y;
                case 2 -> this.z;
                default -> throw new IllegalArgumentException("a target has three axes, not " + axis);
            };
        }

    }

    /**
     * How a keyframe reaches the one before it.
     *
     * <p>Carried on the keyframe being approached rather than the one being left, which is vanilla's
     * own convention: the curve is a property of the arrival.
     */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Interpolation {

        /** Straight between the two keyframes bracketing the instant. */
        LINEAR("linear"),

        /** A Catmull-Rom spline through the bracketing pair and one keyframe either side of them. */
        CATMULLROM("catmullrom");

        /** The lower-case token this curve is spelled with in the shipped table. */
        @KeyField
        private final @NotNull String token;

    }

    /**
     * What one clip displaces on one bone, in one of its three members.
     *
     * <p>A bone appears once per target rather than once, because the three are separate tables in
     * vanilla and a clip commonly drives one of them and not the others.
     *
     * @param bone the bone this channel displaces, named the way the mesh names it
     * @param target which of the bone's members it displaces
     * @param keyframes the authored frames, in ascending time order
     */
    public record Channel(
        @NotNull String bone,
        @NotNull Target target,
        @NotNull ConcurrentList<Keyframe> keyframes
    ) {}

    /**
     * One authored instant of a channel.
     *
     * @param timeSeconds when in the clip this frame sits
     * @param x the displacement's first component
     * @param y the displacement's second component
     * @param z the displacement's third component
     * @param interpolation how the instants before this one reach it
     */
    public record Keyframe(
        float timeSeconds,
        float x,
        float y,
        float z,
        @NotNull Interpolation interpolation
    ) {

        /**
         * One component of this frame's displacement.
         *
         * @param axis the component, zero for x
         * @return the value
         * @throws IllegalArgumentException if the axis is not one of three
         */
        public float component(int axis) {
            return switch (axis) {
                case 0 -> this.x;
                case 1 -> this.y;
                case 2 -> this.z;
                default -> throw new IllegalArgumentException("a keyframe has three axes, not " + axis);
            };
        }

    }

}
