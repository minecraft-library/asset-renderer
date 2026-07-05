package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

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
    STACK_COUNT;

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
