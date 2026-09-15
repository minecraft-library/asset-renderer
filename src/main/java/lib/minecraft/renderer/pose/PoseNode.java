package lib.minecraft.renderer.pose;

import lib.minecraft.renderer.asset.pose.EntityPose;

import org.jetbrains.annotations.NotNull;

/**
 * One node of a shipped pose graph - a value {@link PoseExpr} computes, or the {@link PosePredicate}
 * a choice turns on.
 *
 * <p>The two are one vocabulary and were one in everything but the type system: a choice holds a
 * predicate the way it holds its arms, a predicate holds expressions as its operands, and every walk
 * over the graph meets both. What separates them is only what they answer with - a number against a
 * yes or no - so a walk that has to name the thing it is standing on names this.
 *
 * <p><b>A pose is a graph and never a tree.</b> One instance stands for as many paths as reach it -
 * a humanoid's arms are nine hundred nodes standing for twenty-two million - so every walk over one
 * memoizes on node identity and short-circuits on an instance already seen. That binds evaluation,
 * interning, every scan the compiler runs, and the text form each arm prints: a node names itself
 * and refers to its children rather than rendering them, which is what {@link #ref} is for.
 * {@link EntityPose} and the records under it print their graphs this way because their components
 * do.
 */
public sealed interface PoseNode permits PoseExpr, PosePredicate {

    /**
     * The reference one node is spelled with wherever it is reached from - what every arm's text
     * form names itself and its children by.
     *
     * <p>It is an identity rather than a value, which is what the two readings of a graph need: two
     * structurally equal nodes are told apart, so an identity assertion's failure says something,
     * and one instance reached down two paths reads the same on both, so sharing is visible without
     * anything walking into it.
     *
     * @param node the node to refer to
     * @return the reference
     */
    static @NotNull String ref(@NotNull PoseNode node) {
        return "@" + Integer.toHexString(System.identityHashCode(node));
    }

}
