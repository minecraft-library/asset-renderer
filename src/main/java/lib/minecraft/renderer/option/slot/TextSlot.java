package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Paint-order slots for the text {@link ImageLayer} stack: tooltip background and border (LORE
 * only), then the glyph rows.
 */
public enum TextSlot implements LayerSlot {

    /** Tooltip background fill (LORE only). */
    BACKGROUND,
    /** Tooltip gradient border (LORE only). */
    BORDER,
    /** Text glyph rows. */
    TEXT
}
