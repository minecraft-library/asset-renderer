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
 * The kind of drive behind a movement - the one vocabulary a drive is named in.
 *
 * <p>Each clip play site carries one, and it decides what the clip's own time axis is read from: a
 * site nothing drives holds at its first instant, a stride-driven one runs on the walk terms its
 * arguments carry, and a selection plays while the render-state field it names answers non-zero.
 * What moves a whole subject is answered in the same vocabulary, so what drives a clip and what
 * moves a subject are one spelling.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum MotionSource {

    /** Nothing drives it - a clip site held at its first instant, and a subject that holds still. */
    NONE("none"),

    /** Elapsed age drives written channels - a head that bobs, a tail that sways on the clock alone. */
    TICK("tick"),

    /** A swept render-state scalar drives it - a tentacle angle, a flap time, a peek amount. */
    FIGURE("figure"),

    /** A one-hot state selection drives it - a gated clip, or a factor a caller chooses a member of. */
    SELECT("select"),

    /** The walk pair drives it - the stride phase, at the amplitude of whatever is walking. */
    STRIDE("stride"),

    /** Texture offset motion - a pass scrolls its sheet while the geometry holds still. */
    SCROLL("scroll");

    /** The lower-case token this drive is spelled with. */
    @KeyField
    private final @NotNull String token;

}
