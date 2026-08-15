package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Paint-order slots for the menu's {@link FrameLayer} stack.
 * <p>
 * The full-canvas chrome paints first, then the renders that go in a cell, then the content that
 * fills the cells nothing was put in, then every draw of text. A slot is a position in that order
 * and never a description of a cell, so nothing here answers which cells a caller's slot index
 * reaches - that is a cell's own role.
 */
public enum MenuSlot implements LayerSlot {

    /** The panel, its frame, every cell and every mark, painted as one buffer carrying no ink. */
    CHROME,
    /** What a caller put in a cell. */
    SLOT,
    /** What covers the cells a caller put nothing in, and the icon a mark's face carries. */
    CONTENT,
    /** Both of a container's labels, and the text a screen's own field holds. */
    TEXT
}
