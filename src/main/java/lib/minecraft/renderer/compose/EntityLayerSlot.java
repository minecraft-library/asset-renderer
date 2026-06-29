package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Default render-order slots for the {@link GeometryLayer} stack of the 3D entity renderer.
 * <p>
 * Declaration order is emission order and mirrors the historic build sequence of
 * {@code EntityRenderer}: base body, then model overlays, then block overlays, then armor. Emission
 * order is load-bearing for the shared depth pass (see {@link GeometryLayer}).
 */
public enum EntityLayerSlot implements LayerSlot {

    /** Base entity body geometry. */
    BASE_BODY,
    /** Cube-tree model overlays sharing the entity frame (eyes, saddles, coats). */
    MODEL_OVERLAY,
    /** Block-model overlays placed on the body (mooshroom mushrooms, copper-golem flower). */
    BLOCK_OVERLAY,
    /** Worn-armor geometry receiving the enchantment foil. */
    ARMOR;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
