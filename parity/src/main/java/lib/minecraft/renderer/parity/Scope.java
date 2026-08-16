package lib.minecraft.renderer.parity;

/**
 * How far below a package a claim declared on it reaches.
 * <p>
 * The two arms are the two the trigger grammar distinguishes: a package answers for its own
 * compilation units the way {@code *} does, and for its whole tree the way {@code **} does. A leaf
 * package answers for its tree, so a package added below it inherits what its parent claims instead
 * of silently claiming nothing.
 */
public enum Scope {

    /** The package's own compilation units, and nothing in a package below it. */
    PACKAGE,
    /** The package's own compilation units and every one below it. */
    SUBTREE
}
