package lib.minecraft.renderer.tooling.walk;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A walk carrying a single sticky cell - the latch with its declared disciplines. Nothing
 * terminates here: the chain continues through a commit, whose event carries the latched (or
 * promoted) value, or through the inverted commit that decodes its value at the commit node.
 *
 * @param <G> the latched value type
 */
public final class LatchWalk<G> {

    private final @NotNull Descriptor descriptor;

    LatchWalk(@NotNull Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    private @NotNull LatchWalk<G> fold(@NotNull Descriptor.Fold fold) {
        return new LatchWalk<>(this.descriptor.withFold(fold));
    }

    private @NotNull Descriptor.Fold current() {
        Descriptor.Fold fold = this.descriptor.fold();
        if (fold == null) throw new ToolingException("Latch chain lost its fold declaration");
        return fold;
    }

    /**
     * Clears the latch on every fall-through event - an element no stage consumed. Defined
     * over yields, so a raw chain clears on labels too; a strict cell rides a {@code real()}
     * walk.
     *
     * @return the strict walk
     */
    public @NotNull LatchWalk<G> strict() {
        return fold(current().withStrict());
    }

    /**
     * Latches set-if-absent: while a value is held, the decoder is not consulted and the
     * element falls through to later stages.
     *
     * @return the first-wins walk
     */
    public @NotNull LatchWalk<G> firstWins() {
        return fold(current().withFirstWins());
    }

    /**
     * Keeps the latch across commits - the declared deviation from the unconditional
     * commit-reset.
     *
     * @return the retaining walk
     */
    public @NotNull LatchWalk<G> retain() {
        return fold(current().withRetain());
    }

    /**
     * Clears the latch on a matching node, independent of the commit. The stage never claims
     * and fires after the commit on a shared node.
     *
     * @param match the reset recognizer
     * @return the walk with the reset installed
     */
    public @NotNull LatchWalk<G> resetAt(@NotNull Match<?> match) {
        return fold(current().withResetAt(match));
    }

    /**
     * Promotes on a matching node: an armed latch is taken - read and cleared - into the
     * register the commit reads. A matching node with an empty latch leaves the register
     * untouched, and a later latch write clears it, so a stale promotion never binds forward.
     *
     * @param match the promotion recognizer
     * @return the walk with the promotion installed
     */
    public @NotNull LatchWalk<G> takeAt(@NotNull Match<?> match) {
        return fold(current().withTakeAt(match));
    }

    /**
     * Commits at each matching unclaimed node, emitting a commit event carrying the latched
     * value - or the promoted register where {@link #takeAt} is installed - then resetting
     * every installed cell.
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

    /**
     * The inverted commit: the key latched earlier, the value decoded at the commit node. The
     * decoder is consulted only while the latch is armed - a caller-supplied reader may carry
     * diagnostics, so a keyless invocation would change the log - and a {@code null} decode
     * from an armed reader keeps the latch armed; only a non-null decode commits and takes it.
     *
     * @param valueDecoder the per-node value decoder
     * @return the pair stream
     */
    @SuppressWarnings("unchecked")
    public <V> @NotNull PairWalk<G, V> commitOn(@NotNull Function<AbstractInsnNode, @Nullable V> valueDecoder) {
        return new PairWalk<>(this.descriptor.with(new Descriptor.CommitOn((Function<AbstractInsnNode, Object>) valueDecoder)));
    }

}
