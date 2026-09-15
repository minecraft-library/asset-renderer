package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * Which of a mirrored limb pair a selector addresses.
 *
 * <p>The order the two are declared in is read as a number and not only as a name - a trot sorts a
 * leg into one diagonal pair or the other by its row counted from the front against its index here,
 * and the frontmost right leg is the one the leading pair is defined by. Reordering these two
 * constants swaps the leading pair on every subject and nothing refuses.
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
    RIGHT;

    /**
     * The other of the mirrored pair.
     *
     * <p>Read as a name rather than as a number, so it answers the same whichever order the two
     * are declared in.
     *
     * @return the side this one faces
     */
    public @NotNull Side opposite() {
        return this == RIGHT ? LEFT : RIGHT;
    }

}
