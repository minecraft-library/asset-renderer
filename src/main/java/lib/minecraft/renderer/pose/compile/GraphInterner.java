package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
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

    private final @NotNull Map<Key, PoseExpr> exprs = new HashMap<>();
    private final @NotNull Map<Key, PosePredicate> predicates = new HashMap<>();
    private final @NotNull Map<PoseExpr, PoseExpr> internedExprs = new IdentityHashMap<>();
    private final @NotNull Map<PosePredicate, PosePredicate> internedPredicates = new IdentityHashMap<>();

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
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
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
     * Registers one shipped expression and everything below it, children first.
     */
    private void adopt(@NotNull PoseExpr node, @NotNull Set<Object> visited) {
        if (!visited.add(node)) return;
        switch (node) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> this.adopt(operand, visited));
            case PoseExpr.Select select -> {
                this.adopt(select.condition(), visited);
                this.adopt(select.whenTrue(), visited);
                this.adopt(select.whenFalse(), visited);
            }
            case PoseExpr.Const ignored -> { }
            case PoseExpr.Input ignored -> { }
            case PoseExpr.BoneRead ignored -> { }
        }
        this.exprs.putIfAbsent(keyOf(node), node);
        this.internedExprs.putIfAbsent(node, node);
    }

    /**
     * Registers one shipped predicate and its operands, children first.
     */
    private void adopt(@NotNull PosePredicate node, @NotNull Set<Object> visited) {
        if (!visited.add(node)) return;
        this.adopt(node.left(), visited);
        this.adopt(node.right(), visited);
        this.predicates.putIfAbsent(keyOf(node), node);
        this.internedPredicates.putIfAbsent(node, node);
    }

    /**
     * Resolves an expression to the canonical instance for its structure - a node already pooled
     * answers as itself, a structural duplicate of a pooled node answers the pooled instance, and
     * a genuinely new node registers as the canonical for its key.
     *
     * <p>Children intern before the parent is keyed; a new parent whose children canonicalized
     * away from what it holds is rebuilt over the canonical children, so no pooled node ever
     * carries a duplicate subtree. The walk memoizes per node instance, so a shared instance
     * interns once however many paths reach it.
     *
     * @param node the expression to resolve
     * @return the canonical instance for the expression's structure
     */
    @NotNull PoseExpr intern(@NotNull PoseExpr node) {
        PoseExpr known = this.internedExprs.get(node);
        if (known != null) return known;
        PoseExpr candidate = switch (node) {
            case PoseExpr.Const constant -> constant;
            case PoseExpr.Input input -> input;
            case PoseExpr.BoneRead read -> read;
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
        };
        PoseExpr canonical = this.exprs.putIfAbsent(keyOf(candidate), candidate);
        if (canonical == null) canonical = candidate;
        this.internedExprs.put(node, canonical);
        this.internedExprs.putIfAbsent(candidate, canonical);
        return canonical;
    }

    /**
     * Resolves a predicate to the canonical instance for its structure, under the same contract
     * as the expression overload.
     *
     * @param node the predicate to resolve
     * @return the canonical instance for the predicate's structure
     */
    @NotNull PosePredicate intern(@NotNull PosePredicate node) {
        PosePredicate known = this.internedPredicates.get(node);
        if (known != null) return known;
        PoseExpr left = this.intern(node.left());
        PoseExpr right = this.intern(node.right());
        PosePredicate candidate = left == node.left() && right == node.right()
            ? node
            : new PosePredicate(node.comparison(), left, right);
        PosePredicate canonical = this.predicates.putIfAbsent(keyOf(candidate), candidate);
        if (canonical == null) canonical = candidate;
        this.internedPredicates.put(node, canonical);
        this.internedPredicates.putIfAbsent(candidate, canonical);
        return canonical;
    }

    /**
     * The number of pooled entries - expressions and predicates together, one per registered key.
     *
     * @return the pooled entry count
     */
    int size() {
        return this.exprs.size() + this.predicates.size();
    }

    /**
     * Keys one expression over its local data and its children's identities.
     */
    private static @NotNull Key keyOf(@NotNull PoseExpr node) {
        return switch (node) {
            case PoseExpr.Const constant -> new Key(new Object[] {
                PoseExpr.Const.class, Double.doubleToLongBits(constant.value()), constant.width() });
            case PoseExpr.Input input -> new Key(new Object[] { PoseExpr.Input.class, input.field() });
            case PoseExpr.BoneRead read -> new Key(new Object[] { PoseExpr.BoneRead.class, read.bone(), read.channel() });
            case PoseExpr.Op op -> new Key(new Object[] { PoseExpr.Op.class, op.operator() }, op.operands().toArray());
            case PoseExpr.Select select -> new Key(new Object[] { PoseExpr.Select.class },
                select.condition(), select.whenTrue(), select.whenFalse());
        };
    }

    /**
     * Keys one predicate over its comparison and its operands' identities.
     */
    private static @NotNull Key keyOf(@NotNull PosePredicate node) {
        return new Key(new Object[] { PosePredicate.class, node.comparison() }, node.left(), node.right());
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
        private final @NotNull Object[] children;
        private final int hash;

        private Key(@NotNull Object[] local, @NotNull Object... children) {
            this.local = local;
            this.children = children;
            int mixed = Arrays.hashCode(local);
            for (Object child : children)
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
