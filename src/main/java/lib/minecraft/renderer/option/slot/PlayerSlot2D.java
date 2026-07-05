package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Paint-order slots for the 2D player {@code ImageLayer} stack.
 */
public enum PlayerSlot2D implements LayerSlot {

    /** Base skin face of every body part. */
    SKIN,
    /** Skin overlay (hat / jacket / sleeve) face of every body part. */
    OVERLAY,
    /** Worn armor + trim composited over every covered body part. */
    ARMOR
}
