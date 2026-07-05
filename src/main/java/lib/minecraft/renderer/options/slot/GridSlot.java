package lib.minecraft.renderer.options.slot;

import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Render-order slots for the grid's {@link FrameLayer} stack. Every tile is a {@link #CELL}; the
 * single built-in slot exists so callers can splice layers relative to the cells via the options'
 * {@code layerDecorator}.
 */
public enum GridSlot implements LayerSlot {

    /** A single grid cell (tile placement). */
    CELL;

    /** {@inheritDoc} */
    @Override
    public int order() {
        return ordinal();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String id() {
        return name();
    }
}
