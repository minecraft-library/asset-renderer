package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

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
    SMOOTH;

    /**
     * The clip interpolation a keyframe this curve shapes is emitted with.
     *
     * @return the interpolation the emitted keyframes carry
     */
    public PoseClip.@NotNull Interpolation interpolation() {
        return switch (this) {
            case LINEAR -> PoseClip.Interpolation.LINEAR;
            case SMOOTH -> PoseClip.Interpolation.CATMULLROM;
        };
    }

}
