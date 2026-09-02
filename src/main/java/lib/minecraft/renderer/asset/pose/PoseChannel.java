package lib.minecraft.renderer.asset.pose;

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

    /** What a channel accumulates, which decides its rest value and how a clip composes onto it. */
    public enum Kind {

        /** Additive, resting at zero, carried in model pixels. */
        POSITION,

        /** Additive, resting at zero, carried in radians. */
        ROTATION,

        /** Multiplicative, resting at one. */
        SCALE

    }

    /** The snake-case token this channel is spelled with in the shipped table. */
    @KeyField
    private final @NotNull String token;

    /** What this channel accumulates. */
    private final @NotNull Kind kind;

}
