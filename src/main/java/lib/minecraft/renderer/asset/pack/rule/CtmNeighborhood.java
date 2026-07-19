package lib.minecraft.renderer.asset.pack.rule;

import org.jetbrains.annotations.NotNull;

/**
 * Per-face neighbor occupancy under a CTM rule's connect predicate - the seam by which connectivity
 * enters tile selection ({@link CtmNeighborResolver#select}).
 *
 * <p>This is a headless single-subject renderer: it draws one block in isolation, so no neighbor is
 * ever present and {@link #ISOLATED} is the only occupancy it produces. A future world renderer would
 * add a data-carrying {@code Connected} case; because {@link CtmNeighborResolver#select} switches
 * exhaustively over this sealed type, adding that case is a compile-time-checked change - every
 * selection site that must grow a transition-table branch fails to compile until it does.
 */
public sealed interface CtmNeighborhood permits CtmNeighborhood.Isolated {

    /**
     * The isolated occupancy - no neighbor on any face, the only case this renderer ever produces.
     */
    @NotNull CtmNeighborhood ISOLATED = new Isolated();

    /**
     * The isolated (no-neighbor) occupancy.
     */
    record Isolated() implements CtmNeighborhood {}

}
