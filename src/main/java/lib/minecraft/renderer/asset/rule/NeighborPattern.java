package lib.minecraft.renderer.asset.rule;

import org.jetbrains.annotations.NotNull;

/**
 * An 8-bit bitmask describing which of a face's surrounding neighbors connect for CTM purposes.
 * <p>
 * Face-local axes are oriented so that {@link #N} points "up the face" and {@link #E} points
 * "right along the face" when the face is viewed head-on. The eight cardinal + diagonal positions
 * are:
 *
 * <pre>{@code
 *    NW   N   NE
 *      \  |  /
 *    W - *  - E
 *      /  |  \
 *    SW   S   SE
 * }</pre>
 *
 * The bit values walk clockwise from {@link #N}: N=0x01, NE=0x02, E=0x04, SE=0x08, S=0x10,
 * SW=0x20, W=0x40, NW=0x80. A bit being set means "this neighbor matches" by whatever predicate
 * the caller used to build the pattern (block id, base texture id, etc.). The matcher itself is
 * agnostic to the predicate; it only consumes the pattern.
 * <p>
 * Per-method tile selection then folds the 256 possible patterns into the rule's tile list. The
 * full 47-tile {@code CTM} layout, the reduced 5-tile {@code CTM_COMPACT}, and the smaller
 * {@code HORIZONTAL} / {@code VERTICAL} / {@code TOP} variants all read the same bits but apply
 * different lookup tables.
 *
 * @param bits the 8-bit neighbor mask, low byte significant
 * @see CtmMatcher#resolveWithPattern
 */
public record NeighborPattern(int bits) {

    /**
     * North neighbor (up the face) - matches the {@code N} cell.
     */
    public static final int N = 0x01;

    /**
     * Northeast neighbor (up-right).
     */
    public static final int NE = 0x02;

    /**
     * East neighbor (right).
     */
    public static final int E = 0x04;

    /**
     * Southeast neighbor (down-right).
     */
    public static final int SE = 0x08;

    /**
     * South neighbor (down).
     */
    public static final int S = 0x10;

    /**
     * Southwest neighbor (down-left).
     */
    public static final int SW = 0x20;

    /**
     * West neighbor (left).
     */
    public static final int W = 0x40;

    /**
     * Northwest neighbor (up-left).
     */
    public static final int NW = 0x80;

    /**
     * The pattern with no matching neighbors.
     */
    public static final @NotNull NeighborPattern EMPTY = new NeighborPattern(0);

    /**
     * Tests whether all bits in {@code mask} are set.
     *
     * @param mask one or more position constants OR'd together (e.g. {@code N | E})
     * @return {@code true} iff every bit in {@code mask} is set on this pattern
     */
    public boolean has(int mask) {
        return (this.bits & mask) == mask;
    }

    /**
     * Returns a new pattern with the additional bits set.
     *
     * @param mask the bits to add
     * @return a new pattern with {@code bits | mask}
     */
    public @NotNull NeighborPattern with(int mask) {
        return new NeighborPattern(this.bits | mask);
    }

    /**
     * Builds a pattern from individual cardinal flags. Diagonals stay clear; useful when only the
     * 4 cardinal neighbors are known to a caller.
     *
     * @param north whether the north neighbor matches
     * @param east whether the east neighbor matches
     * @param south whether the south neighbor matches
     * @param west whether the west neighbor matches
     * @return the cardinal-only pattern
     */
    public static @NotNull NeighborPattern cardinals(boolean north, boolean east, boolean south, boolean west) {
        int bits = 0;
        if (north) bits |= N;
        if (east) bits |= E;
        if (south) bits |= S;
        if (west) bits |= W;
        return new NeighborPattern(bits);
    }

}
