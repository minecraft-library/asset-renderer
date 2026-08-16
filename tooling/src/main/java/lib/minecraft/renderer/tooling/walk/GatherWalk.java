package lib.minecraft.renderer.tooling.walk;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.function.Predicate;

/**
 * A walk carrying an ordered pending list - the gather cell with its declared disciplines.
 * Nothing terminates here: the chain continues through a commit, whose event stream carries
 * the buffered values.
 *
 * @param <G> the gathered value type
 */
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class GatherWalk<G> {

    private final @NotNull Descriptor descriptor;

    private @NotNull GatherWalk<G> fold(@NotNull Descriptor.Fold fold) {
        return new GatherWalk<>(this.descriptor.withFold(fold));
    }

    private @NotNull Descriptor.Fold current() {
        Descriptor.Fold fold = this.descriptor.fold();
        if (fold == null) throw new ToolingException("Gather chain lost its fold declaration");
        return fold;
    }

    /**
     * Bounds the buffer: push always, evict the oldest past {@code n}. The push count is the
     * eviction history. {@code keep(2)} read positionally - {@code values().getFirst()} then
     * {@code values().getLast()} - is the two-deep sliding window.
     *
     * @param n the retention capacity
     * @return the bounded walk
     */
    public @NotNull GatherWalk<G> keep(int n) {
        return fold(current().withKeep(n));
    }

    /**
     * Clears the buffer on every fall-through event - an element no stage consumed. Defined
     * over yields, so a raw chain clears on labels too; a strict cell rides a {@code real()}
     * walk.
     *
     * @return the strict walk
     */
    public @NotNull GatherWalk<G> strict() {
        return fold(current().withStrict());
    }

    /**
     * Keeps the buffer across commits - the declared deviation from the unconditional
     * commit-reset. A strict cell's fall-through clear fires regardless.
     *
     * @return the retaining walk
     */
    public @NotNull GatherWalk<G> retain() {
        return fold(current().withRetain());
    }

    /**
     * Gates gathering on a segment: a matching node opens it fresh, clearing an unsealed
     * buffer; nothing gathers while closed; a commit closes it again.
     *
     * @param match the segment opener
     * @return the gated walk
     */
    public @NotNull GatherWalk<G> openAt(@NotNull Match<?> match) {
        return fold(current().withOpenAt(match));
    }

    /**
     * Parks the buffer as the pending payload when a matching node arrives while the segment
     * is open - even an empty buffer parks, so the commit still emits its row. A seal while
     * closed does not fire, and a commit with nothing sealed emits nothing.
     *
     * @param match the segment closer
     * @return the gated walk
     */
    public @NotNull GatherWalk<G> sealAt(@NotNull Match<?> match) {
        return fold(current().withSealAt(match));
    }

    /**
     * Clears the buffer on a matching node, independent of the commit. The stage never claims
     * and fires after the commit on a shared node, so a commit reads before its reset.
     *
     * @param match the reset recognizer
     * @return the walk with the reset installed
     */
    public @NotNull GatherWalk<G> resetAt(@NotNull Match<?> match) {
        return fold(current().withResetAt(match));
    }

    /**
     * Commits at each matching unclaimed node, emitting a commit event carrying the buffered
     * values, then resetting every installed cell.
     *
     * @param match the commit recognizer
     * @return the commit-event stream
     */
    public <C extends AbstractInsnNode> @NotNull CommitWalk<C, G> commitAt(@NotNull Match<C> match) {
        return new CommitWalk<>(this.descriptor.with(new Descriptor.CommitAt(match)));
    }

    /**
     * Commits at each matching unclaimed node of the given class.
     *
     * @param type the commit node class
     * @param test the commit predicate on the narrowed node
     * @return the commit-event stream
     */
    public <C extends AbstractInsnNode> @NotNull CommitWalk<C, G> commitAt(@NotNull Class<C> type, @NotNull Predicate<? super C> test) {
        return commitAt(Insn.of(type, test));
    }

}
