package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

/**
 * A boolean appearance flag - an axis whose two options are set and unset, so the flag itself is the
 * option a gated row names and the gate's polarity says which side renders. The 26.1 corpus gates on
 * both: the sheep's un-sheared body layer names {@link #SHEARED} expecting {@code false}, and the
 * creeper energy swirl names {@link #CHARGED}.
 */
public enum Flag implements Axis {

    /** Whether the entity renders sheared - the sheep's wool axis. */
    SHEARED {
        @Override
        public boolean selectedIn(@NotNull AppearanceOptions appearance) {
            return appearance.isSheared();
        }
    },

    /** Whether the entity renders charged (lightning-struck) - the creeper swirl axis. */
    CHARGED {
        @Override
        public boolean selectedIn(@NotNull AppearanceOptions appearance) {
            return appearance.isCharged();
        }
    }

}
