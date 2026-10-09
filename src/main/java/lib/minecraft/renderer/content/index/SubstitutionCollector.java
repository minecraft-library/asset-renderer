package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.call.result.Substitution;
import org.jetbrains.annotations.NotNull;

/**
 * The stand-ins one render has drawn so far, safe to add to from any frame worker.
 * <p>
 * A {@link RendererContext} carries one, and {@link RendererContext#report} adds every stand-in reported
 * through that context to it. It keeps each substitution once, however many faces or frames draw it, and
 * answers what it holds sorted, so a render's record reads the same whichever worker reported first.
 */
public final class SubstitutionCollector {

    /**
     * A collector that keeps nothing: the one a context answers unless one is derived with
     * {@link RendererContext#collecting}, and the one a {@link RendererContext#measuring measuring}
     * context answers whatever it is derived over.
     */
    public static final @NotNull SubstitutionCollector DISCARD = new SubstitutionCollector(false);

    /**
     * Whether this collector keeps what is added to it - false for {@link #DISCARD} alone.
     */
    private final boolean keeps;

    /**
     * The stand-ins recorded so far, each once.
     */
    private final @NotNull ConcurrentSet<Substitution> recorded = Concurrent.newSet();

    /**
     * Constructs a new empty {@code SubstitutionCollector} that keeps every stand-in added to it.
     */
    public SubstitutionCollector() {
        this(true);
    }

    /**
     * Constructs a new empty {@code SubstitutionCollector}.
     *
     * @param keeps whether it keeps what is added to it
     */
    private SubstitutionCollector(boolean keeps) {
        this.keeps = keeps;
    }

    /**
     * Records one stand-in; a repeat changes nothing.
     *
     * @param substitution the stand-in drawn
     */
    public void add(@NotNull Substitution substitution) {
        if (this.keeps)
            this.recorded.add(substitution);
    }

    /**
     * Answers the stand-ins recorded so far, distinct and sorted by their natural order.
     *
     * @return an unmodifiable copy of what this collector holds
     */
    public @NotNull ConcurrentList<Substitution> snapshot() {
        return this.recorded.stream()
            .sorted()
            .collect(Concurrent.toUnmodifiableList());
    }

}
