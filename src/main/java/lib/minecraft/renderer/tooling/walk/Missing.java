package lib.minecraft.renderer.tooling.walk;

/**
 * Which half of a looked-up source was absent, readable before any run via
 * {@code missing()} in constant time. A site that must report or memoise the miss reads this
 * and emits its own distinct messages - the walk itself stays silent about it.
 */
public enum Missing {

    /** The jar holds no class of the requested internal name. */
    CLASS,

    /** The class loaded but declares no member of the requested name. */
    MEMBER

}
