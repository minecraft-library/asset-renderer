package lib.minecraft.renderer.options.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Emission-order slots for the block {@code GeometryLayer} stack: primary model, then additive
 * block-entity geometry, then merged block-entity parts.
 */
public enum BlockSlot implements LayerSlot {

    /** The primary block model (multipart assembly or blockstate-variant elements). */
    PRIMARY,
    /** Additive block-entity geometry overlaid on the primary model (e.g. bell body). */
    ADDITIVE_ENTITY,
    /** Merged block-entity part geometry (bed foot onto head, decorated-pot sides onto base). */
    PARTS;

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
