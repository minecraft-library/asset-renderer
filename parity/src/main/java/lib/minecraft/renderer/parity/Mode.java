package lib.minecraft.renderer.parity;

/**
 * How a claim resolves against every other claim reaching the same path.
 * <p>
 * What a path reaches is unioned across every claim reaching it before either removal runs, so these
 * three describe what a claim does to that union rather than only what it puts into it.
 */
public enum Mode {

    /** Contributes what the claim reaches and removes nothing. */
    SELECT,
    /** Contributes what the claim reaches, then removes what it calls blind from the whole union. */
    DEMOTE,
    /** Removes everything the claim names, its own reach included, whatever put it there. */
    SUPPRESS
}
