package lib.minecraft.renderer.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The nine bone members a pose writes, and the token each is spelled with in the shipped table.
 *
 * <p>They are the pivot offset, the rotation and the per-axis scale a bone already carries. What
 * separates them here is {@link Kind}, which says what a channel accumulates and therefore what it
 * rests at when nothing writes it - a rotation resting at zero and a scale at one are the same
 * statement said two ways, and reading either from the wrong side collapses a limb.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
public enum PoseChannel {

    /** Sideways offset of the bone's pivot, in model pixels. */
    X("x", Kind.POSITION),

    /** Vertical offset of the bone's pivot, in model pixels on vanilla's y-down axis. */
    Y("y", Kind.POSITION),

    /** Depth offset of the bone's pivot, in model pixels. */
    Z("z", Kind.POSITION),

    /** Rotation about the bone's x axis, in radians. */
    X_ROT("x_rot", Kind.ROTATION),

    /** Rotation about the bone's y axis, in radians. */
    Y_ROT("y_rot", Kind.ROTATION),

    /** Rotation about the bone's z axis, in radians. */
    Z_ROT("z_rot", Kind.ROTATION),

    /** Multiplier on the bone's x extent, resting at one rather than at zero. */
    X_SCALE("x_scale", Kind.SCALE),

    /** Multiplier on the bone's y extent, resting at one rather than at zero. */
    Y_SCALE("y_scale", Kind.SCALE),

    /** Multiplier on the bone's z extent, resting at one rather than at zero. */
    Z_SCALE("z_scale", Kind.SCALE);

    /**
     * What a channel accumulates, which decides its rest value and how a clip composes onto it -
     * and, read the other way, which of a bone's three members a clip channel displaces.
     *
     * <p>Those are one question rather than two. A clip names the member it adds into and the pose
     * names the member it writes, and the member is the same one; what a channel rests at follows
     * from which it is. So this is what a clip's table is keyed on, and {@link #channel(int)} is
     * where the triple is spelled.
     */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Kind {

        /** Additive, resting at zero, carried in model pixels. */
        POSITION("position"),

        /** Additive, resting at zero, carried in radians. */
        ROTATION("rotation"),

        /** Multiplicative, resting at one. */
        SCALE("scale");

        /** The lower-case token this kind is spelled with in the shipped table. */
        @KeyField
        private final @NotNull String token;

        /**
         * The channel one component of this kind displaces.
         *
         * @param axis the component, zero for x
         * @return the channel it lands on
         * @throws IllegalArgumentException if the axis is not one of three
         */
        public @NotNull PoseChannel channel(int axis) {
            if (axis < 0 || axis >= AXES) throw new IllegalArgumentException(
                "a channel kind has three axes, not " + axis);

            return switch (this) {
                case POSITION -> switch (axis) {
                    case 0 -> X;
                    case 1 -> Y;
                    default -> Z;
                };
                case ROTATION -> switch (axis) {
                    case 0 -> X_ROT;
                    case 1 -> Y_ROT;
                    default -> Z_ROT;
                };
                case SCALE -> switch (axis) {
                    case 0 -> X_SCALE;
                    case 1 -> Y_SCALE;
                    default -> Z_SCALE;
                };
            };
        }

    }

    /** How many components a kind spans - one per axis of the member it names. */
    private static final int AXES = 3;

    /** The snake-case token this channel is spelled with in the shipped table. */
    @KeyField
    private final @NotNull String token;

    /** What this channel accumulates. */
    private final @NotNull Kind kind;

}
