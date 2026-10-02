package lib.minecraft.renderer.engine.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The kind of drive behind a clip that plays - the one vocabulary a play site is named in.
 *
 * <p>Each clip play site carries one, and it decides what the clip's own time axis is read from: a
 * site nothing drives holds at its first instant, a stride-driven one runs on the walk terms its
 * arguments carry, and a selection plays while the render-state field it names answers non-zero.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum ClipDrive {

    /** Nothing drives it - a clip site held at its first instant. */
    NONE("none"),

    /** A one-hot state selection drives it - a gated clip. */
    SELECT("select"),

    /** The walk pair drives it - the stride phase, at the amplitude of whatever is walking. */
    STRIDE("stride");

    /** The lower-case token this drive is spelled with. */
    @KeyField
    private final @NotNull String token;

}
