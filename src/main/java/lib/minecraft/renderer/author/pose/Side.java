package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Which of a mirrored limb pair a selector addresses.
 */
@Parity(subject = Subject.ENTITY)
public enum Side {

    /**
     * The subject's own left.
     */
    LEFT,

    /**
     * The subject's own right.
     */
    RIGHT

}
