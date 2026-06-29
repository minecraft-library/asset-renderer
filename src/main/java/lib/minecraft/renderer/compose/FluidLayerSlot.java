package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Default render-order slots for the {@link GeometryLayer} stack of the 3D fluid renderer.
 * <p>
 * A fluid render has a single built-in contributor (the cube); the slot exists so callers can splice
 * extra layers relative to it and so fluid uses the same ordering machinery as every other renderer.
 */
public enum FluidLayerSlot implements LayerSlot {

    /** The fluid cube geometry (top + side faces, with optional flow texturing). */
    CUBE;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
