package lib.minecraft.renderer.tooling.animation;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The eleven {@code ModelPart} members a {@code setupAnim} body can write, and the token each is
 * spelled with in the shipped table.
 *
 * <p>This is the whole sink vocabulary, measured rather than anticipated: every write in the
 * reachable corpus is a {@code putfield} on one of these, and the four mutating methods vanilla
 * declares beside them - {@code setRotation}, {@code offsetPos}, {@code offsetRotation} and
 * {@code translateAndRotate} - are called from nowhere a pose walk reaches. So a member outside
 * this set is a finding rather than a channel to add quietly.
 *
 * <p>The vanilla field name and the emitted token are held separately because they disagree. The
 * field is what a {@code putfield} names and the token is what the table spells, and the tables
 * here have always written multi-word names in snake case.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
public enum PoseChannel {

    /** Sideways offset of the bone's pivot, in model pixels. */
    X("x", "x", Kind.POSITION),

    /** Vertical offset of the bone's pivot, in model pixels on vanilla's y-down axis. */
    Y("y", "y", Kind.POSITION),

    /** Depth offset of the bone's pivot, in model pixels. */
    Z("z", "z", Kind.POSITION),

    /** Rotation about the bone's x axis, in radians. */
    X_ROT("xRot", "x_rot", Kind.ROTATION),

    /** Rotation about the bone's y axis, in radians. */
    Y_ROT("yRot", "y_rot", Kind.ROTATION),

    /** Rotation about the bone's z axis, in radians. */
    Z_ROT("zRot", "z_rot", Kind.ROTATION),

    /** Multiplier on the bone's x extent, defaulting to one rather than to zero. */
    X_SCALE("xScale", "x_scale", Kind.SCALE),

    /** Multiplier on the bone's y extent, defaulting to one rather than to zero. */
    Y_SCALE("yScale", "y_scale", Kind.SCALE),

    /** Multiplier on the bone's z extent, defaulting to one rather than to zero. */
    Z_SCALE("zScale", "z_scale", Kind.SCALE),

    /** Whether the bone and its descendants draw at all. */
    VISIBLE("visible", "visible", Kind.FLAG),

    /** Whether the bone's own cubes are skipped while its descendants still draw. */
    SKIP_DRAW("skipDraw", "skip_draw", Kind.FLAG);

    /** What a channel accumulates, which decides its rest value and how a clip composes onto it. */
    public enum Kind {

        /** Additive, resting at zero, carried in model pixels. */
        POSITION,

        /** Additive, resting at zero, carried in radians. */
        ROTATION,

        /** Multiplicative, resting at one - vanilla stores an offset of {@code v - 1}. */
        SCALE,

        /** A boolean, only ever assigned. */
        FLAG

    }

    /** The vanilla {@code ModelPart} field name a {@code putfield} on this channel names. */
    @KeyField
    private final @NotNull String field;

    /** The token this channel is spelled with in the shipped table. */
    private final @NotNull String token;

    /** What this channel accumulates. */
    private final @NotNull Kind kind;

    /**
     * Whether this channel carries a boolean rather than a float.
     *
     * @return {@code true} for the two flag channels
     */
    public boolean isFlag() {
        return this.kind == Kind.FLAG;
    }

}
