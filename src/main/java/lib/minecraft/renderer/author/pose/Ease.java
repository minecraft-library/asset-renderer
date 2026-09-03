package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.pose.PoseClip;

/**
 * How a timeline's keyframes reach one another.
 */
public enum Ease {

    /**
     * Straight between bracketing keyframes - {@link PoseClip.Interpolation#LINEAR}.
     */
    LINEAR,

    /**
     * A spline through the bracketing pair and one keyframe either side of them -
     * {@link PoseClip.Interpolation#CATMULLROM}.
     */
    SMOOTH

}
