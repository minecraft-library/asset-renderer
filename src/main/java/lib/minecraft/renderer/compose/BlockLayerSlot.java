package lib.minecraft.renderer.compose;

import org.jetbrains.annotations.NotNull;

/**
 * Default render-order slots for the {@link GeometryLayer} stack of the 3D block renderer.
 * <p>
 * Declaration order is emission order and mirrors the historic assembly sequence: the primary block
 * model first, then any additive block-entity geometry, then merged block-entity parts. The shared
 * whole-mesh transforms (variant rotation is folded into the primary; multi-block recenter and the
 * inventory relight run after the stack) are not slots - they post-process the assembled sink.
 */
public enum BlockLayerSlot implements LayerSlot {

    /** The primary block model: multipart assembly or the blockstate-variant elements. */
    PRIMARY,
    /** Additive block-entity geometry overlaid on the primary model (e.g. bell body). */
    ADDITIVE_ENTITY,
    /** Merged block-entity part geometry (bed foot onto head, decorated-pot sides onto base). */
    PARTS;

    @Override
    public int order() {
        return ordinal();
    }

    @Override
    public @NotNull String id() {
        return name();
    }
}
