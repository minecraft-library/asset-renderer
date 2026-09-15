package lib.minecraft.renderer.pose.author;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;

/**
 * The rotation axis a verb turns a limb about.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum Turn {

    /**
     * Rotation about the sideways axis - a nod; lands on {@link PoseChannel#X_ROT}.
     */
    PITCH(PoseChannel.X_ROT),

    /**
     * Rotation about the vertical axis - a turn of the head; lands on {@link PoseChannel#Y_ROT}.
     */
    YAW(PoseChannel.Y_ROT),

    /**
     * Rotation about the depth axis - a sideways tilt; lands on {@link PoseChannel#Z_ROT}.
     */
    ROLL(PoseChannel.Z_ROT);

    /** The rotation channel a turn about this axis writes. */
    private final @NotNull PoseChannel channel;

}
