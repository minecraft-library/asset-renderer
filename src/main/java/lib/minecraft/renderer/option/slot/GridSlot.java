package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Render-order slots for the grid's {@link FrameLayer} stack. Every tile is a {@link #CELL}; the
 * single built-in slot exists so callers can splice layers relative to the cells via the options'
 * {@code layerDecorator}.
 */
public enum GridSlot implements LayerSlot {

    /** A single grid cell (tile placement). */
    CELL
}
