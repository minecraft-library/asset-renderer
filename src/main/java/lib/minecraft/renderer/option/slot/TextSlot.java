package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.ImageLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Paint-order slots for the text {@link ImageLayer} stack: tooltip background and border (LORE
 * only), then the glyph rows.
 */
public enum TextSlot implements LayerSlot {

    /** Tooltip background fill (LORE only). */
    BACKGROUND,
    /** Tooltip gradient border (LORE only). */
    BORDER,
    /** Text glyph rows. */
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
