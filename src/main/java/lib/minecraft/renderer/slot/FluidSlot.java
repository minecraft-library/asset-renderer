package lib.minecraft.renderer.slot;

import lib.minecraft.renderer.engine.layer.LayerSlot;

/**
 * Render-order slots for the fluid {@code GeometryLayer} stack. The cube is the single built-in
 * contributor; the slot lets callers splice extra layers relative to it.
 */
public enum FluidSlot implements LayerSlot {

    /** The fluid cube geometry. */
    CUBE
}
