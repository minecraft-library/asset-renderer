package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Paint-order slots for the menu's {@link FrameLayer} stack. The full-canvas chrome paints first,
 * then item-slot renders, then remaining content (crafting shapes, anvil decoration, filler panes),
 * then text overlays. {@code TEXT} is reserved: title / label / XP-cost text is currently drawn onto
 * the chrome buffer, so nothing is appended to it yet - it is the named insertion point a caller can
 * splice a text pass into.
 */
public enum MenuSlot implements LayerSlot {

    /** Full-canvas menu chrome (background + borders + baked-in title / labels). */
    CHROME,
    /** Item-icon renders in the functional slots. */
    SLOT,
    /** Remaining decorative content: crafting shapes, anvil decoration, filler panes. */
    CONTENT,
    /** Reserved for text overlays; unused by the built-in renderers (text is baked into the chrome). */
    TEXT;

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
