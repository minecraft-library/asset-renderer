package lib.minecraft.renderer.request.slot;

import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.layer.LayerSlot;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Render-order slots for the grid's {@link FrameLayer} stack. Every tile is a {@link #CELL}; the
 * single built-in slot exists so callers can splice layers relative to the cells via the options'
 * {@code layerDecorator}.
 *
 * <p><b>Parity.</b> Reaches the grid alone, which this store holds no artifact for.
 */
@Parity(subject = Subject.GRID)
public enum GridSlot implements LayerSlot {

    /** A single grid cell (tile placement). */
    CELL
}
