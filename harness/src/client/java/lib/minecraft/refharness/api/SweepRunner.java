package lib.minecraft.refharness.api;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.refharness.Gait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives one {@link Sweep} a subject at a time - the work index, the tally, and the completion
 * latch every sweep would otherwise keep for itself.
 *
 * <p>One {@link #step} is one subject, which is one PNG; {@link #stepBatch} is how many of them a
 * tick carries. The frame renderers reuse a colour texture per canvas size whose read-back completes
 * on a later frame, and {@code PipTarget} retires a replaced texture rather than closing it - which
 * is what makes several renders in flight safe, and what turned the pacing from a fence into a knob.
 *
 * @param <S> the subject descriptor the sweep enumerates
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SweepRunner<S> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final Sweep<S> sweep;
    private List<S> work;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;

    /** Whether every subject has been consumed and the completion latch has closed. */
    @Getter
    private boolean done;

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

    /**
     * The gait this runner's sweep renders at, which the orchestrator arms before stepping it.
     *
     * @return the sweep's gait
     */
    public Gait gait() {
        return sweep.gait();
    }

    /**
     * Advances up to {@code limit} subjects, stopping early when the sweep finishes.
     *
     * <p>The pacing this replaces was one subject per client tick, which is 20 a second whatever the
     * machine can draw - so a 2310-reference run spent nearly two minutes waiting on the tick clock.
     * What made it one was the read-back: {@code PipTarget} reused one colour texture per renderer
     * and <em>closed</em> it whenever the canvas changed size, so a copy still in flight could be
     * handed a released texture. A tick was long enough that it never was. The texture is retired
     * rather than closed now, so several renders can be in flight and the limit is a throughput knob
     * rather than a correctness one.
     *
     * @param ctx the sweep context for this tick
     * @param limit the most subjects to advance
     */
    public void stepBatch(SweepContext ctx, int limit) {
        for (int i = 0; i < limit && !done; i++) step(ctx);
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
