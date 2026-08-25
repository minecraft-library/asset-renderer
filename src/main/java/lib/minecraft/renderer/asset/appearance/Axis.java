package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

/**
 * One selectable option of an appearance axis - the side of a {@code when} comparison a gated row
 * names, answered against the selection an {@link AppearanceOptions} carries. The face the gateable
 * option enums ({@link Age}, {@link Size}, {@link Flag}) share, so a gate holds the option and asks
 * it rather than switching on which axis it came from; which way the answer gates the row is the
 * gate's own {@code expected} polarity, not the option's.
 */
public sealed interface Axis permits Age, Size, Flag {

    /**
     * Reports whether the appearance selects this option.
     *
     * @param appearance the render-axis selections
     * @return {@code true} when this option is the one selected
     */
    boolean selectedIn(@NotNull AppearanceOptions appearance);

}
