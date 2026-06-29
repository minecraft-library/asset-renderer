package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Default render-order slots for the {@link ImageLayer} stack of the 2D item GUI renderer.
 * <p>
 * Declaration order is render order and mirrors the historic pass sequence of
 * {@code ItemRenderer.Gui2D}: base sprite stack, then trim overlay, then damage bar, then stack
 * count. The terminal glint pass is not a slot - it runs in the renderer's finalisation stage.
 */
public enum ItemLayerSlot implements LayerSlot {

    /** Base sprite/layer stack, or the shield / banner dispatch. */
    BASE,
    /** Armor-trim overlay composited over the base. */
    TRIM,
    /** Durability damage bar. */
    DAMAGE_BAR,
    /** Stack-count badge. */
    STACK_COUNT;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
