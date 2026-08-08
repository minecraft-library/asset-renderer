package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.DepthQuantumFrameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic depth-quantum probe. Renders two overlapping quads a swept distance apart and lets the
 * depth test say how finely it can tell them apart - see {@link DepthQuantumFrameRenderer} for what
 * one frame means and which hardware model each answer implies.
 *
 * <p>Nothing here is a reference. The frames go to a directory outside the reference tree, no
 * existing renderer is touched, and no reference is re-rendered - the whole point is to settle an
 * assumption the reference set already rests on without disturbing the set itself.
 *
 * <h2>The grid</h2>
 *
 * Two depth ranges by two base depths by the exponent ladder. The ranges are the harness's own
 * {@code 1000} and the {@code 10000} it used to record entities at, which in window units every
 * candidate model says should make no difference - so a transition that moves with the range
 * falsifies all of them. The base depths are the middle of the range and a tenth of the way out,
 * which is the axis that separates the candidates.
 *
 * <p>The ladder carries two controls at its ends. {@link #FAR_CONTROL} is a separation no model can
 * quantise away, so its band must come out red; {@link #NEAR_CONTROL} is one every model must
 * quantise away, so its band must come out green. A run where either control disagrees is measuring
 * something other than the depth test.
 */
public final class DepthQuantumSweep implements Sweep<DepthQuantumFrameRenderer.Cell> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Frame edge, in pixels. Small on purpose - the readout is three bands, not a silhouette. */
    private static final int FRAME_SIZE = 128;

    /** The depth ranges swept: the harness's own, and the tenfold one the entity set used to use. */
    private static final float[] DEPTH_RANGES = {1000f, 10000f};

    /**
     * The base depths swept, as a fraction of the range: the middle, where window depth is
     * {@code 0.5}, and a tenth of the way out. A pair at the middle is the one whose separation stays
     * representable in a {@code float32} vertex all the way down.
     */
    private static final float[] BASE_DEPTHS = {0f, 0.1f};

    /** Coarsest separation probed - far above every candidate quantum, so its band must be red. */
    private static final int FAR_CONTROL = 8;

    /** Finest separation probed - far below every candidate quantum, so its band must be green. */
    private static final int NEAR_CONTROL = 40;

    /** First exponent of the contiguous ladder between the two controls. */
    private static final int LADDER_FIRST = 18;

    /** Last exponent of the contiguous ladder between the two controls. */
    private static final int LADDER_LAST = 36;

    private final DepthQuantumFrameRenderer frameRenderer = new DepthQuantumFrameRenderer();

    /**
     * Returns a directory outside the reference tree - these frames are a diagnostic, and the
     * manifest that gates the tree must not acquire them.
     */
    @Override
    public String outputDir() {
        return "depth-quantum-probe";
    }

    @Override
    public List<DepthQuantumFrameRenderer.Cell> enumerate(SweepContext ctx) {
        List<DepthQuantumFrameRenderer.Cell> cells = new ArrayList<>();
        for (float range : DEPTH_RANGES)
            for (float base : BASE_DEPTHS)
                for (int exponent : exponents())
                    cells.add(new DepthQuantumFrameRenderer.Cell(range, base, exponent));
        LOG.info("DepthQuantumSweep built: {} frames over {} ranges x {} base depths",
            cells.size(), DEPTH_RANGES.length, BASE_DEPTHS.length);
        return cells;
    }

    /** The exponent ladder, both controls included. */
    private static List<Integer> exponents() {
        List<Integer> ladder = new ArrayList<>();
        ladder.add(FAR_CONTROL);
        for (int exponent = LADDER_FIRST; exponent <= LADDER_LAST; exponent++) ladder.add(exponent);
        ladder.add(NEAR_CONTROL);
        return ladder;
    }

    @Override
    public RefKey key(DepthQuantumFrameRenderer.Cell cell) {
        // Named so a name-sorted listing walks the ladder in order within each range and base depth,
        // which is what makes the transition readable by eye as well as by the analysis.
        return RefKey.named(String.format("range%05d", (int) cell.depthRange()))
            .with(String.format("base%03d", (int) (cell.baseDepth() * 1000f)))
            .with(String.format("k%02d", cell.exponent()));
    }

    @Override
    public Canvas canvas(SweepContext ctx, DepthQuantumFrameRenderer.Cell cell) {
        return Canvas.square(FRAME_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, DepthQuantumFrameRenderer.Cell cell, Canvas canvas, Path out)
        throws IOException {
        return this.frameRenderer.render(ctx.client(), cell, canvas, out);
    }

    @Override
    public void afterSweep(SweepContext ctx) {
        this.frameRenderer.close();
    }

    /**
     * Returns {@code false} - a probe cell has no registry id for the allowlist to match against, so
     * honouring the filter would make any scoped run write no frame at all.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }
}
