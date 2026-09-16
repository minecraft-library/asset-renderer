package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseNode;
import lib.minecraft.renderer.pose.PosePredicate;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A hash-consed pool of pose expressions and predicates over one target row - what makes every
 * structurally equal subtree of the row's graphs resolve to one shared instance.
 *
 * <p>The evaluator memoizes on node identity, so two value-equal subtrees that are distinct
 * instances evaluate correctly but expand separately - a pose is a graph whose nodes stand for
 * enormously many paths, and instance sharing is what keeps evaluating it linear. The pool
 * reproduces the sharing a loaded table carries constructively for built graphs, and extends it
 * from what an author shared to everything structurally equal: {@link #adopt} seeds the pool with
 * every node the row's shipped pose reaches, and {@link #intern} resolves a node to the pooled
 * instance its structure already names.
 *
 * <p><b>A shipped instance is never swapped or rebuilt.</b> Adoption registers every reachable
 * instance as its own answer - a value-equal duplicate of an earlier registration included - so
 * interning one passes it through as itself. Only a node the pool has never seen canonicalizes,
 * and a splice over a shipped subtree references the shipped instance directly.
 *
 * <p>A key holds a node's local data by value and its children by reference: children resolve
 * before the parent is keyed, so structural equality collapses to identity equality one level
 * deep and a key never re-walks a subtree. Every walk here short-circuits on an instance already
 * seen, because a graph's tree expansion does not terminate in practice.
 */
@Parity(subject = Subject.ENTITY)
public final class GraphInterner {

    private final @NotNull Map<Key, PoseNode> pooled = new HashMap<>();
    private final @NotNull Map<PoseNode, PoseNode> interned = new IdentityHashMap<>();

    /**
     * Seeds the pool from a shipped pose - every expression and predicate reachable through its
     * container steps, bone channels and clip site arguments, in that order, registers bottom-up
     * with the first registration for a key winning.
     *
     * <p>The walk carries its own identity-keyed visited set rather than leaning on the pool:
     * first-registration-wins never registers a non-first value-equal duplicate instance, so a
     * pool lookup answers nothing on every encounter of one and its subtree would re-walk per
     * path.
     *
     * @param shipped the pose whose reachable graph seeds the pool
     */
    void adopt(@NotNull EntityPose shipped) {
        Set<PoseNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map<PoseChannel, PoseExpr> step : shipped.container())
            for (PoseExpr expression : step.values())
                this.adopt(expression, visited);
        for (Map<PoseChannel, PoseExpr> channels : shipped.bones().values())
            for (PoseExpr expression : channels.values())
                this.adopt(expression, visited);
        for (EntityPose.Clip site : shipped.clips())
            for (PoseExpr argument : site.arguments())
                this.adopt(argument, visited);
    }

    /**
     * Registers one shipped node and everything below it, children first.
     */
    private void adopt(@NotNull PoseNode node, @NotNull Set<PoseNode> visited) {
        if (!visited.add(node)) return;
        switch (node) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> this.adopt(operand, visited));
            case PoseExpr.Select select -> {
                this.adopt(select.condition(), visited);
                this.adopt(select.whenTrue(), visited);
                this.adopt(select.whenFalse(), visited);
            }
            case PosePredicate predicate -> {
                this.adopt(predicate.left(), visited);
                this.adopt(predicate.right(), visited);
            }
            case PoseExpr.Const ignored -> { }
            case PoseExpr.Input ignored -> { }
            case PoseExpr.BoneRead ignored -> { }
            case PoseExpr.Answered ignored -> { }
        }
        this.pooled.putIfAbsent(keyOf(node), node);
        this.interned.putIfAbsent(node, node);
    }

    /**
     * Resolves a node to the canonical instance for its structure - a node already pooled answers
     * as itself, a structural duplicate of a pooled node answers the pooled instance, and a
     * genuinely new node registers as the canonical for its key.
     *
     * <p>Children intern before the parent is keyed; a new parent whose children canonicalized
     * away from what it holds is rebuilt over the canonical children, so no pooled node ever
     * carries a duplicate subtree. The walk memoizes per node instance, so a shared instance
     * interns once however many paths reach it.
     *
     * <p>A node answers as its own kind, which is what the type parameter carries: a key names the
     * arm's class among its local data, so two nodes of different arms never share a key and a
     * canonical is always the kind the node handed in was. That is the whole of why the unchecked
     * cast holds, and it is a fact about {@link #keyOf} rather than about any caller.
     *
     * @param node the node to resolve
     * @param <T> the node's own kind, which its canonical shares
     * @return the canonical instance for the node's structure
     */
    @SuppressWarnings("unchecked")
    <T extends PoseNode> @NotNull T intern(@NotNull T node) {
        PoseNode known = this.interned.get(node);
        if (known != null) return (T) known;
        PoseNode candidate = switch (node) {
            case PoseExpr.Const constant -> constant;
            case PoseExpr.Input input -> input;
            case PoseExpr.BoneRead read -> read;
            case PoseExpr.Answered answered -> answered;
            case PoseExpr.Op op -> {
                List<PoseExpr> operands = new ArrayList<>(op.operands().size());
                boolean rebuilt = false;
                for (PoseExpr operand : op.operands()) {
                    PoseExpr interned = this.intern(operand);
                    rebuilt |= interned != operand;
                    operands.add(interned);
                }
                yield rebuilt ? new PoseExpr.Op(op.operator(), Concurrent.newUnmodifiableList(operands)) : op;
            }
            case PoseExpr.Select select -> {
                PosePredicate condition = this.intern(select.condition());
                PoseExpr whenTrue = this.intern(select.whenTrue());
                PoseExpr whenFalse = this.intern(select.whenFalse());
                yield condition == select.condition() && whenTrue == select.whenTrue() && whenFalse == select.whenFalse()
                    ? select
                    : new PoseExpr.Select(condition, whenTrue, whenFalse);
            }
            case PosePredicate predicate -> {
                PoseExpr left = this.intern(predicate.left());
                PoseExpr right = this.intern(predicate.right());
                yield left == predicate.left() && right == predicate.right()
                    ? predicate
                    : new PosePredicate(predicate.comparison(), left, right);
            }
        };
        PoseNode canonical = this.pooled.putIfAbsent(keyOf(candidate), candidate);
        if (canonical == null) canonical = candidate;
        this.interned.put(node, canonical);
        this.interned.putIfAbsent(candidate, canonical);
        return (T) canonical;
    }

    /**
     * The number of pooled entries - expressions and predicates together, one per registered key.
     *
     * @return the pooled entry count
     */
    int size() {
        return this.pooled.size();
    }

    /**
     * Keys one node over its local data and its children's identities.
     *
     * <p>Every arm names its own class among the local data, so a key is unique to one arm and two
     * nodes of different kinds cannot collide however equal the rest of their data reads.
     */
    private static @NotNull Key keyOf(@NotNull PoseNode node) {
        return switch (node) {
            case PoseExpr.Const constant -> new Key(new Object[] {
                PoseExpr.Const.class, Double.doubleToLongBits(constant.value()), constant.width() });
            case PoseExpr.Input input -> new Key(new Object[] { PoseExpr.Input.class, input.field() });
            // Keyed on the record itself, which is what the other arms spell out by hand: these are
            // leaves over strings and an int, so their own equality IS the local data compared by
            // value, and a record of one arm never equals a record of another.
            case PoseExpr.Answered answered -> new Key(new Object[] { answered });
            case PoseExpr.BoneRead read -> new Key(new Object[] { PoseExpr.BoneRead.class, read.bone(), read.channel() });
            case PoseExpr.Op op -> new Key(new Object[] { PoseExpr.Op.class, op.operator() },
                op.operands().toArray(new PoseNode[0]));
            case PoseExpr.Select select -> new Key(new Object[] { PoseExpr.Select.class },
                select.condition(), select.whenTrue(), select.whenFalse());
            case PosePredicate predicate -> new Key(new Object[] { PosePredicate.class, predicate.comparison() },
                predicate.left(), predicate.right());
        };
    }

    /**
     * One pool key - local data compared by value, children compared by reference.
     *
     * <p>Hashing a child by its identity hash and comparing it by reference is what keeps a key
     * constant-time: structural equality already collapsed to identity equality when the children
     * resolved, so a key never re-walks a subtree.
     */
    private static final class Key {

        private final @NotNull Object[] local;
        private final @NotNull PoseNode[] children;
        private final int hash;

        private Key(@NotNull Object[] local, @NotNull PoseNode... children) {
            this.local = local;
            this.children = children;
            int mixed = Arrays.hashCode(local);
            for (PoseNode child : children)
                mixed = 31 * mixed + System.identityHashCode(child);
            this.hash = mixed;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)
                || this.children.length != key.children.length
                || !Arrays.equals(this.local, key.local))
                return false;
            for (int index = 0; index < this.children.length; index++)
                if (this.children[index] != key.children[index]) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

    }

}
