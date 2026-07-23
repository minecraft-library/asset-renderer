package lib.minecraft.refharness.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one {@link Sweep} a subject at a time - the work index, the tally, and the completion
 * latch every sweep would otherwise keep for itself.
 *
 * <p>Exactly one subject advances per call, which is exactly one PNG. The frame renderers reuse a
 * single colour texture whose read-back completes on a later frame, so starting the next render
 * before the previous read-back lands would corrupt it.
 *
 * @param <S> the subject descriptor the sweep enumerates
 */
public final class SweepRunner<S> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final Sweep<S> sweep;
    private List<S> work;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private SweepRunner(Sweep<S> sweep) {
        this.sweep = sweep;
    }

    /**
     * Returns a runner for one sweep.
     *
     * @param sweep the sweep to drive
     * @param <S> the subject descriptor it enumerates
     * @return the runner
     */
    public static <S> SweepRunner<S> of(Sweep<S> sweep) {
        return new SweepRunner<>(sweep);
    }

    /** Whether every subject has been consumed and the completion latch has closed. */
    public boolean isDone() {
        return done;
    }

    /**
     * Advances exactly one subject, which is exactly one PNG.
     *
     * @param ctx the sweep context for this tick
     */
    public void step(SweepContext ctx) {
        if (done) return;
        if (ctx.level() == null) return;
        if (work == null) {
            work = sweep.enumerate(ctx);
            sweep.prepare(ctx, work);
        }
        if (index >= work.size()) { finish(ctx); return; }

        S subject = work.get(index);
        sweep.beforeSubject(ctx, subject);
        RefKey key = sweep.key(subject);
        Path out = key.resolve(ctx.outputRoot().resolve(sweep.outputDir()));
        try {
            if (sweep.render(ctx, subject, sweep.canvas(ctx, subject), out)) rendered++;
            else skipped++;
        } catch (IOException ex) {
            LOG.error("{}: PNG write failed for {}", sweep.outputDir(), key.fileName(), ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("{}: render failed for {}", sweep.outputDir(), key.fileName(), ex);
            failed++;
        }

        index++;
        if (index >= work.size()) finish(ctx);
    }

    /** The running rendered / skipped / failed / total counts. */
    public Tally tally() {
        return new Tally(rendered, skipped, failed, work == null ? 0 : work.size());
    }

    private void finish(SweepContext ctx) {
        sweep.afterSweep(ctx);
        LOG.info("{}: done. rendered={}, skipped={}, failed={}, total={}",
            sweep.outputDir(), rendered, skipped, failed, work.size());
        done = true;
    }

    /**
     * Releases nothing.
     *
     * <p>The last subject's read-back callback is still in flight when a sweep finishes, so the
     * frame renderers' GPU textures are deliberately left to the JVM exit rather than closed into a
     * pending copy that would then read a zero-sized image.
     */
    @Override
    public void close() {}

    /**
     * How one sweep finished.
     *
     * @param rendered subjects that wrote a PNG
     * @param skipped subjects the renderer declined
     * @param failed subjects whose render or write threw
     * @param total subjects enumerated
     */
    public record Tally(int rendered, int skipped, int failed, int total) {}
}
