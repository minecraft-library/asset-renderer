package lib.minecraft.renderer.pipeline.pack.rule;

/**
 * CTM-STUB SEAM - neighbor resolution deliberately unimplemented (world-space only; this renderer
 * draws isolated subjects). A future world renderer plugs in here by supplying per-face neighbor
 * occupancy under the rule's connect predicate. Until then NOTHING calls the {@link CtmRule} store
 * from a render path (locked decision 4, 03-rules §5.4) - the store is parse-and-store, zero render
 * callers, by design rather than omission.
 *
 * <p>If icon consultation is ever enabled, the documented no-neighbor resolution per method (D3.6) is:
 * <ul>
 * <li><b>ctm / horizontal / vertical / top</b> - tile 0 ("no connection" in the OptiFine template).</li>
 * <li><b>ctm_compact</b> - tile 0.</li>
 * <li><b>fixed</b> - {@code tiles[0]}.</li>
 * <li><b>random</b> - weighted pick.</li>
 * <li><b>repeat</b> - grid cell {@code (0, 0)}.</li>
 * <li><b>overlay_*</b> - NO output (base renders unmodified; overlays composite on top only when
 * neighbors exist), so an overlay method can never map onto a base-replacing method (07-optifine
 * defect #6 is structurally unreachable).</li>
 * </ul>
 */
public interface CtmNeighborResolver {
    // No methods yet - seam marker only.
}
