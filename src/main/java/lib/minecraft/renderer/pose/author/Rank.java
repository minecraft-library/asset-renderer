package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Which transverse row of legs a stance addresses, counted from the front of the subject.
 *
 * <p>A rank names a row by where it sits on the body rather than by what a mesh calls it, so the
 * spellings a mesh uses for its rows are what the compiler reads and never what a caller types.
 * Crossed with {@link Side} it addresses one leg; alone it addresses the whole row.
 *
 * <p>{@link #FRONT} and {@link #HIND} are the ends of whatever a mesh carries, so they answer on a
 * one-row biped as readily as on an eight-legged crawler. {@link #SECOND} and {@link #THIRD} name
 * rows BETWEEN those ends and answer only where the mesh carries one, so a chain written for three
 * rows addresses nothing on a two-row walker rather than folding its middle row onto the hind one.
 */
@Parity(subject = Subject.ENTITY)
public enum Rank {

    /**
     * The frontmost row.
     */
    FRONT,

    /**
     * The row behind the frontmost, where the mesh carries a further row behind that.
     */
    SECOND,

    /**
     * The third row from the front, where the mesh carries a further row behind it.
     */
    THIRD,

    /**
     * The rearmost row.
     */
    HIND

}
