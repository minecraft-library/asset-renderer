package dev.sbs.renderer.util;

import lombok.experimental.UtilityClass;

/**
 * 1024-entry sine lookup table with cosine derived via the quarter-period phase shift
 * ({@code cos(x) = sin(x + PI/2)}). Indexed by {@code (angle * SIZE / TAU) & (SIZE - 1)}
 * so any real input wraps into the table without a modulo or branch.
 * <p>
 * Precision is ~{@code 0.5} ULP worse than {@link Math#sin} near the half-period midpoints
 * (where adjacent table entries bracket the true value with the largest gap). That is well
 * below per-pixel rounding - block rotations in vanilla resource packs are quantised to
 * {@code 15}-degree steps that land exactly on table entries; fluid flow angles are a free
 * input but 1024 entries give a {@code ~0.35}-degree quantum which rounds indistinguishably
 * when projected through the {@code 128}-pixel tile grid.
 * <p>
 * Not a replacement for {@link Math#sin} / {@link Math#cos} in correctness-critical contexts
 * (geodesic math, physics, anything user-observable beyond rendering). Intentionally scoped
 * to {@code dev.sbs.renderer.util} to keep that boundary visible.
 */
@UtilityClass
public class TrigLUT {

    /** Power-of-two table size so the index wrap is a single bitmask. */
    private static final int SIZE = 1024;

    /** Mask used to wrap any integer index back into {@code [0, SIZE)}. */
    private static final int MASK = SIZE - 1;

    /** Pre-scaled factor that converts radians into a table index. */
    private static final float INDEX_SCALE = SIZE / (float) (Math.PI * 2d);

    /** Quarter-period offset applied to sine lookups to derive cosine. */
    private static final int COS_OFFSET = SIZE / 4;

    private static final float[] SINE = new float[SIZE];

    static {
        for (int i = 0; i < SIZE; i++)
            SINE[i] = (float) Math.sin((i / (double) SIZE) * Math.PI * 2d);
    }

    /**
     * Returns {@code ~sin(radians)} via table lookup.
     *
     * @param radians the angle in radians; any real value is valid (the index wraps)
     * @return the looked-up sine
     */
    public static float sin(float radians) {
        return SINE[(int) (radians * INDEX_SCALE) & MASK];
    }

    /**
     * Returns {@code ~cos(radians)} via table lookup, using the identity
     * {@code cos(x) = sin(x + PI/2)}.
     *
     * @param radians the angle in radians; any real value is valid (the index wraps)
     * @return the looked-up cosine
     */
    public static float cos(float radians) {
        return SINE[((int) (radians * INDEX_SCALE) + COS_OFFSET) & MASK];
    }

}
