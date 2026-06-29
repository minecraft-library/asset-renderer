package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Default render-order slots for the {@link ImageLayer} stack of the 2D player renderer.
 * <p>
 * Declaration order is paint order: base skin faces for every body part, then the skin overlay
 * (hat / jacket / sleeves), then worn armor and trim. Each built-in layer iterates the body-part
 * layout itself, so the conceptual skin-then-overlay-then-armor ordering lives in the slot order
 * rather than being interleaved per body part.
 */
public enum PlayerLayerSlot implements LayerSlot {

    /** Base skin face of every body part. */
    SKIN,
    /** Skin overlay (hat / jacket / sleeve) face of every body part. */
    OVERLAY,
    /** Worn armor + trim composited over every covered body part. */
    ARMOR;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
