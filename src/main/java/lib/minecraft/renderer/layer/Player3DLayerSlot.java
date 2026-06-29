package lib.minecraft.renderer.layer;

import org.jetbrains.annotations.NotNull;

/**
 * Default emission-order slots for the {@link GeometryLayer} stack of the 3D player renderer.
 * <p>
 * Declaration order is emission order: the body (all skin parts plus their overlays, kept as one
 * contributor because 3D triangle emission order is load-bearing and must not be split), then worn
 * armor, then the cape. Distinct from the 2D {@link PlayerLayerSlot}, whose skin / overlay passes are
 * pass-major sprite blits.
 */
public enum Player3DLayerSlot implements LayerSlot {

    /** All body-part skin cubes and their overlays, in fixed emission order. */
    BODY,
    /** Worn armor + trim. */
    ARMOR,
    /** Cape geometry behind the torso. */
    CAPE;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
