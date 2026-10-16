package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.call.result.Substitution;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
     * What a collector that has recorded nothing answers, shared by every one of them.
     */
    private static final @NotNull ConcurrentList<Substitution> NONE = Concurrent.newUnmodifiableList();

    /**
     * Whether this collector keeps what is added to it - false for {@link #DISCARD} alone.
     */
    private final boolean keeps;

    /**
     * The stand-ins recorded so far, each once, created by the first one added - most renders draw none.
     */
    private volatile @Nullable ConcurrentSet<Substitution> recorded;

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
        if (!this.keeps) return;

        ConcurrentSet<Substitution> set = this.recorded;
        if (set == null) {
            synchronized (this) {
                set = this.recorded;
                if (set == null)
                    this.recorded = set = Concurrent.newSet();
            }
        }

        set.add(substitution);
    }

    /**
     * Answers the stand-ins recorded so far, distinct and sorted by their natural order.
     *
     * @return an unmodifiable copy of what this collector holds
     */
    public @NotNull ConcurrentList<Substitution> snapshot() {
        ConcurrentSet<Substitution> set = this.recorded;
        if (set == null) return NONE;

        return set.stream()
            .sorted()
            .collect(Concurrent.toUnmodifiableList());
    }

}
