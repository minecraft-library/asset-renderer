package lib.minecraft.renderer.tooling.walk;

/**
 * How a walk ended, answered by the terminals whose return slot is not the answer
 * ({@code run}, {@code trace}, {@code forEach}). Collector-ended chains carry their answer in
 * the collection and read {@code missing()} for the absent-source case; the find family answers
 * its ordinary miss ({@code null} / {@code false}) silently whatever ended the walk.
 */
public enum Exit {

    /** The source ran out of instructions. A capped walk that yields exactly its budget and then exhausts answers {@code END}, not {@link #BUDGET}. */
    END,

    /** An {@code until} bound fired - the identity sentinel was reached or the stop recognizer accepted a yield. */
    SENTINEL,

    /** The budget prevented a yield that would have occurred - an over-budget yield arrived, not merely the cap being met. */
    BUDGET,

    /** The body asked to stop - a tracer answered {@code null}. The site's own state records why; this value records only that. */
    HALTED,

    /** A traced walk revisited a node and the cycle guard refused it. A linear walk cannot produce this - its advance is monotone in list position. */
    CYCLE,

    /** The looked-up source was absent - the class or the member. {@code missing()} carries which. */
    MISSING

}
