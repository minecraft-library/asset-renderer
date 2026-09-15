package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * How the far side of a pair reads the stance the near side was given.
 *
 * <p>Most pairs are mirror images and a few are not - a mesh may share one expression between its
 * left and its right outright, so deriving the far side under a sign flip would bend it the wrong
 * way. One word says which, where no geometric test can.
 */
@Parity(subject = Subject.ENTITY)
public enum Mirror {

    /**
     * The far side derives under the mirror sign rule - pitch kept, yaw and roll negated, the
     * sideways component of positions and aim targets negated.
     */
    SIGNED,

    /**
     * The far side repeats the stance with every sign as written.
     */
    SHARED

}
