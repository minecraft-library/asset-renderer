package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Which of a quadruped's four legs a selector addresses.
 */
@Parity(subject = Subject.ENTITY)
public enum Corner {

    /**
     * The left foreleg.
     */
    FRONT_LEFT,

    /**
     * The right foreleg.
     */
    FRONT_RIGHT,

    /**
     * The left hindleg.
     */
    HIND_LEFT,

    /**
     * The right hindleg.
     */
    HIND_RIGHT

}
