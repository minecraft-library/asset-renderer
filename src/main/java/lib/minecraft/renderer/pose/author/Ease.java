package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * How a timeline's keyframes reach one another.
 */
@Parity(subject = Subject.ENTITY)
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
