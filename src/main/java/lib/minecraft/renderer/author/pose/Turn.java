package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.pose.PoseChannel;

/**
 * The rotation axis a verb turns a limb about.
 */
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
