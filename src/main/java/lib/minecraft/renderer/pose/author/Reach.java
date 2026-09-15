package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * How far down a leg's own chain a stance carries.
 *
 * <p>A leg is one bone on most meshes and a chain on a few, so the default reaches the root alone
 * and the rest is asked for. Stancing a whole chain compounds an angle at every link - eight degrees
 * at a dragon's hip, its knee and its toe is twenty-four at the toe - which is a different motion
 * from turning the leg, not a longer one.
 */
@Parity(subject = Subject.ENTITY)
public enum Reach {

    /**
     * The leg's root alone, whose own subtree follows it.
     */
    ROOT,

    /**
     * The root and every leg-named bone below it, nearest first.
     */
    CHAIN,

    /**
     * Every leg-named bone below the root, the root itself untouched.
     */
    SEGMENTS

}
