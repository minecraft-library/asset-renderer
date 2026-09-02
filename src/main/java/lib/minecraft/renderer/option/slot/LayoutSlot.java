package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

/**
 * Paint-order slots for the layout's {@link FrameLayer} stack. Every child is a {@link #CHILD}; the
 * single built-in slot exists so callers can splice layers relative to the children via the options'
 * {@code layerDecorator} (for {@code Stack} / overlapping {@code Custom} layouts, child append order
 * is the paint order).
 *
 * <p><b>Parity.</b> Reaches the layout alone, which this store holds no artifact for.
 */
@Parity(subject = Subject.LAYOUT)
public enum LayoutSlot implements LayerSlot {

    /** A single laid-out child render. */
    CHILD
}
