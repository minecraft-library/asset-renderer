package lib.minecraft.refharness.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One reference sweep - what its subjects are, where they land, and how each one renders.
 *
 * <p>The bookkeeping every sweep shares - the work index, the rendered / skipped / failed tally, the
 * completion latch, and the one-subject-per-tick pacing the asynchronous read-back requires -
 * belongs to {@link SweepRunner} and is not restated here.
 *
 * @param <S> the subject descriptor this sweep enumerates; the live subject is built in
 *            {@link #render}, because most sweeps need level access to build one
 */
public interface Sweep<S> {

    /**
     * Returns the reference-tree directory this sweep writes into, relative to the output root.
     */
    String outputDir();

    /**
     * Enumerates every subject this sweep will render, in render order. One element is one output
     * PNG.
     *
     * @param ctx the sweep context, for registry access and the target filter
     * @return the work list, already filtered and ordered
     */
    List<S> enumerate(SweepContext ctx);

    /**
     * Returns the output name of one subject.
     *
     * @param subject the subject descriptor
     * @return its reference key, resolved against {@link #outputDir} by the runner
     */
    RefKey key(S subject);

    /**
     * Returns the canvas one subject renders onto.
     *
     * @param ctx the sweep context
     * @param subject the subject descriptor
     * @return the canvas, carrying a fit only when this sweep measures one
     */
    Canvas canvas(SweepContext ctx, S subject);

    /**
     * Renders one subject and writes its PNG.
     *
     * @param ctx the sweep context
     * @param subject the subject descriptor
     * @param canvas the canvas from {@link #canvas}
     * @param out the resolved output path
     * @return whether a PNG was written; {@code false} counts the subject as skipped
     * @throws IOException if the PNG write fails
     */
    boolean render(SweepContext ctx, S subject, Canvas canvas, Path out) throws IOException;

    /**
     * Runs once before the first render, after {@link #enumerate}. The place for a sizing pre-pass
     * or a side artifact that has to exist before any frame is captured.
     *
     * @param ctx the sweep context
     * @param subjects the enumerated work list
     */
    default void prepare(SweepContext ctx, List<S> subjects) {}

    /**
     * Runs immediately before each subject renders, inside the same tick. The place for global state
     * a subject's render reads and the next subject's must not inherit.
     *
     * @param ctx the sweep context
     * @param subject the subject about to render
     */
    default void beforeSubject(SweepContext ctx, S subject) {}

    /**
     * Runs once after the last subject, before the completion latch closes. The place to clear
     * whatever {@link #beforeSubject} set.
     *
     * @param ctx the sweep context
     */
    default void afterSweep(SweepContext ctx) {}

    /**
     * Whether the target allowlist applies to this sweep. False for sweeps whose subjects have no
     * registry id to match against - filtering those would silently render nothing.
     */
    default boolean honoursTargetFilter() {
        return true;
    }
}
