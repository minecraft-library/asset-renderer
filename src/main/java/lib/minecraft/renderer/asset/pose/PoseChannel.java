package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The eleven bone members a pose writes, and the token each is spelled with in the shipped table.
 *
 * <p>Nine of them are the pivot offset, the rotation and the per-axis scale a bone already carries;
 * the other two decide whether the bone draws at all. What separates them here is {@link Kind},
 * which says what a channel accumulates and therefore what it rests at when nothing writes it - a
 * rotation resting at zero and a scale at one are the same statement said two ways, and reading
 * either from the wrong side collapses a limb.
 */
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
    Z_SCALE("z_scale", Kind.SCALE),

    /** Whether the bone and its descendants draw at all. */
    VISIBLE("visible", Kind.FLAG),

    /** Whether the bone's own cubes are skipped while its descendants still draw. */
    SKIP_DRAW("skip_draw", Kind.FLAG);

    /** What a channel accumulates, which decides its rest value and how a clip composes onto it. */
    public enum Kind {

        /** Additive, resting at zero, carried in model pixels. */
        POSITION,

        /** Additive, resting at zero, carried in radians. */
        ROTATION,

        /** Multiplicative, resting at one. */
        SCALE,

        /** A boolean, only ever assigned. */
        FLAG

    }

    private final @NotNull String token;
    private final @NotNull Kind kind;

    PoseChannel(@NotNull String token, @NotNull Kind kind) {
        this.token = token;
        this.kind = kind;
    }

    /**
     * The token this channel is spelled with in the shipped table.
     *
     * @return the snake-case token
     */
    public @NotNull String token() {
        return this.token;
    }

    /**
     * What this channel accumulates.
     *
     * @return the channel kind
     */
    public @NotNull Kind kind() {
        return this.kind;
    }

    /**
     * Whether this channel carries a boolean rather than a number.
     *
     * @return {@code true} for the two flag channels
     */
    public boolean isFlag() {
        return this.kind == Kind.FLAG;
    }

    /**
     * Resolves the channel a token names.
     *
     * @param token the token to resolve
     * @return the channel, or {@code null} when no channel is spelled that way
     */
    public static @Nullable PoseChannel ofToken(@NotNull String token) {
        for (PoseChannel channel : values())
            if (channel.token.equals(token)) return channel;
        return null;
    }

}
