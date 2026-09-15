package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;

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
    ROLL;

    /**
     * The channel a turn about this axis writes.
     *
     * @return the rotation channel this axis lands on
     */
    public @NotNull PoseChannel channel() {
        return switch (this) {
            case PITCH -> PoseChannel.X_ROT;
            case YAW -> PoseChannel.Y_ROT;
            case ROLL -> PoseChannel.Z_ROT;
        };
    }

}
