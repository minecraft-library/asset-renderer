package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Emission-order slots for the entity {@code GeometryLayer} stack. The base body is built
 * separately and is always emitted first; these are the appended contributors.
 */
public enum EntitySlot implements LayerSlot {

    /** Cube-tree model overlays sharing the entity frame (eyes, saddles, coats). */
    MODEL_OVERLAY,
    /** Block-model overlays placed on the body (mooshroom mushrooms, copper-golem flower). */
    BLOCK_OVERLAY,
    /** Worn-armor geometry receiving the enchantment foil. */
    ARMOR
}
