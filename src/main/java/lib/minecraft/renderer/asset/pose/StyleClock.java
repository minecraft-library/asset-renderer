package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The clock a style row's movement is carried on - the one vocabulary a shipped table's mechanism
 * inventory is spelled in.
 *
 * <p>A row's inventory names one entry per mechanism, and the entry's clock is what its written
 * channels are read against: elapsed age, a swept render-state scalar, a one-hot selection, the
 * walk pair, a scrolling sheet, or nothing at all.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum StyleClock {

    /** Nothing drives it - a subject that holds still. */
    NONE("none"),

    /** Elapsed age drives written channels - a head that bobs, a tail that sways on the clock alone. */
    TICK("tick"),

    /** A swept render-state scalar drives it - a tentacle angle, a flap time, a peek amount. */
    FIGURE("figure"),

    /** A one-hot state selection drives it - a factor a caller chooses a member of. */
    SELECT("select"),

    /** The walk pair drives it - the stride phase, at the amplitude of whatever is walking. */
    STRIDE("stride"),

    /** Texture offset motion - a pass scrolls its sheet while the geometry holds still. */
    SCROLL("scroll");

    /** The lower-case token this clock is spelled with. */
    @KeyField
    private final @NotNull String token;

}
