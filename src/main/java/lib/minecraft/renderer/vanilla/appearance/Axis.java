package lib.minecraft.renderer.vanilla.appearance;

import lib.minecraft.renderer.call.request.AppearanceOptions;

/**
 * One selectable option of an appearance axis - the side of a {@code when} comparison a gated row
 * names, answered against the selection an {@link AppearanceOptions} carries by
 * {@link AppearanceOptions#selects}. The face the gateable option enums ({@link Age}, {@link Size},
 * {@link Flag}) share, so a gate holds the option rather than which axis it came from; which way the
 * answer gates the row is the gate's own {@code expected} polarity, not the option's.
 */
public sealed interface Axis permits Age, Size, Flag {}
