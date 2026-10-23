package lib.minecraft.renderer.vanilla.appearance;

import lib.minecraft.renderer.call.request.AppearanceOptions;

/**
 * A boolean appearance flag - an axis whose two options are set and unset, so the flag itself is the
 * option a gated row names and the gate's polarity says which side renders. The 26.1 corpus gates on
 * all four: the sheep's un-sheared body layer names {@link #SHEARED} expecting {@code false}, the
 * creeper energy swirl names {@link #CHARGED}, the wolf / cat collar row names {@link #COLLARED}, and
 * the iron golem's style source swaying on the age clock names {@link #CARRIED}.
 */
public enum Flag implements Axis {

    /** Whether the entity renders sheared - the sheep's wool axis. */
    SHEARED,

    /** Whether the entity renders charged (lightning-struck) - the creeper swirl axis. */
    CHARGED,

    /**
     * Whether a collar is worn - the wolf / cat collar row's axis. Set for a tamed subject whether
     * or not a dye is named, which is vanilla's own tie of the collar to tameness - the
     * {@link AppearanceOptions#collarTint() collar tint} being present.
     */
    COLLARED,

    /**
     * Whether a block is carried - the enderman's held block, the iron golem's offered flower. Set
     * while the {@link AppearanceOptions#carriedBlock() carried-block selection} names a block.
     */
    CARRIED

}
