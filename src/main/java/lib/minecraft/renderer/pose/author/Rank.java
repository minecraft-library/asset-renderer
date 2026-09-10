package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Which transverse row of legs a stance addresses, counted from the front of the subject.
 *
 * <p>A rank names a row by where it sits on the body rather than by what a mesh calls it, so the
 * spellings a mesh uses for its rows are what the compiler reads and never what a caller types.
 * Crossed with {@link Side} it addresses one leg; alone it addresses the whole row.
 */
@Parity(subject = Subject.ENTITY)
public enum Rank {

    /**
     * The frontmost row.
     */
    FRONT,

    /**
     * The rearmost row.
     */
    HIND

}
