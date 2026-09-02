package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

/**
 * A boolean appearance flag - an axis whose two options are set and unset, so the flag itself is the
 * option a gated row names and the gate's polarity says which side renders. The 26.1 corpus gates on
 * all three: the sheep's un-sheared body layer names {@link #SHEARED} expecting {@code false}, the
 * creeper energy swirl names {@link #CHARGED}, and the wolf / cat collar row names {@link #COLLARED}.
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
    },

    /**
     * Whether a collar is worn - the wolf / cat collar row's axis. Set for a tamed subject whether
     * or not a dye is named, which is vanilla's own tie of the collar to tameness.
     */
    COLLARED {
        @Override
        public boolean selectedIn(@NotNull AppearanceOptions appearance) {
            return appearance.collarTint().isPresent();
        }
    }

}
