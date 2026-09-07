package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;

/**
 * The rotation axis a verb turns a limb about.
 */
@Parity(subject = Subject.ENTITY)
public enum Turn {

    /**
     * Rotation about the sideways axis - a nod; lands on {@link PoseChannel#X_ROT}.
     */
    PITCH,

    /**
     * Rotation about the vertical axis - a turn of the head; lands on {@link PoseChannel#Y_ROT}.
     */
    YAW,

    /**
     * Rotation about the depth axis - a sideways tilt; lands on {@link PoseChannel#Z_ROT}.
     */
    ROLL

}
