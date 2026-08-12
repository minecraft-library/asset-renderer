package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.function.Predicate;

/**
 * A typed node recognizer - a node-class token plus a predicate on the narrowed type. One value
 * serves every predicate position: a stop bound ({@code until}), a commit recognizer
 * ({@code commitAt}), and the body the instance match stages delegate to, so each shape has one
 * implementation. Factories live on {@link Insn}; walks narrow through their own instance
 * stages.
 *
 * @param <N> the narrowed node type this recognizer accepts
 */
public interface Match<N extends AbstractInsnNode> {

    /** The node class this recognizer narrows to. */
    @NotNull Class<N> type();

    /**
     * Tests the already-narrowed node.
     *
     * @param node the node, known to be of {@link #type()}
     * @return {@code true} when the node matches
     */
    boolean test(@NotNull N node);

    /**
     * The untyped face - {@code instanceof} plus {@link #test}, the whole recognition in one
     * call for positions holding an arbitrary instruction.
     *
     * @param in the instruction to test
     * @return {@code true} when {@code in} is of {@link #type()} and matches
     */
    default boolean matches(@NotNull AbstractInsnNode in) {
        return type().isInstance(in) && test(type().cast(in));
    }

    /**
     * Refines this recognizer with a further predicate on the narrowed type.
     *
     * @param more the additional condition
     * @return a recognizer matching only where both accept
     */
    default @NotNull Match<N> and(@NotNull Predicate<? super N> more) {
        return Insn.of(type(), node -> test(node) && more.test(node));
    }

}
