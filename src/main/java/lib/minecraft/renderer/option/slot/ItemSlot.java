package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;

/**
 * Render-order slots for the 2D GUI icon {@code ImageLayer} stack: base sprite/banner/shield,
 * then trim, damage bar, and stack-count decorations.
 */
public enum ItemSlot implements LayerSlot {

    /** Base sprite/layer stack, or the shield / banner dispatch. */
    BASE,
    /** Armor-trim overlay composited over the base. */
    TRIM,
    /** Durability damage bar. */
    DAMAGE_BAR,
    /** Stack-count badge. */
    STACK_COUNT
}
