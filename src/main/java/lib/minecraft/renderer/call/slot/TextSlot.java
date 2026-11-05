package lib.minecraft.renderer.call.slot;

import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerSlot;

/**
 * Paint-order slots for the text {@link ImageLayer} stack: the background - a tooltip's fill or a book's
 * texture - and a tooltip's border, then the glyph rows.
 */
public enum TextSlot implements LayerSlot {

    /** The tooltip's background fill, or the book's texture. */
    BACKGROUND,
    /** Tooltip gradient border (LORE only). */
    BORDER,
    /** Text glyph rows. */
    TEXT
}
