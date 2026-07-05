package lib.minecraft.renderer.option.slot;

import lib.minecraft.renderer.engine.compose.layer.LayerSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Emission-order slots for the 3D player {@code GeometryLayer} stack. Body stays one contributor
 * because 3D triangle emission order is load-bearing.
 */
public enum PlayerSlot3D implements LayerSlot {

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
