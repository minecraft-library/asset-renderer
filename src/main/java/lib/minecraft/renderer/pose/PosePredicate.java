package lib.minecraft.renderer.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * What decides between the two arms of a {@link PoseExpr.Select} - a numeric comparison, and only
 * that.
 *
 * <p>Nothing here can be decided from the table alone: a condition the generator could resolve was
 * resolved while it walked, so every comparison that reaches this side has an operand a caller
 * supplies.
 *
 * <p>There is no arm for a bare boolean and none for a test of which constant a reference holds. A
 * boolean the render state declares as a field arrives as a number and is compared against zero; a
 * question about which constant something holds is a question about a subject standing still, which
 * the generator answers where it knows the subject rather than leaving to whoever draws it.
 *
 * <p>It prints as its operands' references rather than as its operands, for the reason
 * {@link PoseExpr} does: a condition sits inside a graph whose nodes stand for enormously many
 * paths, and rendering one is rendering the tree.
 *
 * @param comparison how the two are compared
 * @param left the left operand
 * @param right the right operand
 */
public record PosePredicate(
    @NotNull Comparison comparison,
    @NotNull PoseExpr left,
    @NotNull PoseExpr right
) implements PoseNode {

    /**
     * Builds a comparison, deciding it where both operands are already literals.
     *
     * <p>A decided comparison comes back as {@link #settled}, so a caller reading the answer back
     * through {@link #answered} gets it whichever way the predicate was built.
     *
     * <p><b>Only a generator writing a table may decide one, and a reader must not</b>, for the
     * reason {@link PoseExpr#operation} may not fold: the shared table's numbering describes the
     * graph that was emitted. That is why this is a named factory rather than anything the record
     * does on construction.
     *
     * @param comparison how the two are compared
     * @param left the left operand
     * @param right the right operand
     * @return the decided predicate, or the undecided comparison
     */
    public static @NotNull PosePredicate comparing(
        @NotNull Comparison comparison, @NotNull PoseExpr left, @NotNull PoseExpr right) {

        if (left instanceof PoseExpr.Constant lhs && right instanceof PoseExpr.Constant rhs)
            return settled(comparison.test(lhs.value(), rhs.value()));
        return new PosePredicate(comparison, left, right);
    }

    /**
     * A predicate that is already decided, spelled as the comparison that says so.
     *
     * <p>This compares two numbers and has no arm for an answer already known, so a decision is
     * carried as a comparison of one literal against itself - equal for true, unequal for false. A
     * reader evaluates it correctly without knowing it was a decision, and {@link #answered} reads it
     * back.
     *
     * @param value what the predicate answers
     * @return the predicate answering it
     */
    public static @NotNull PosePredicate settled(boolean value) {
        return new PosePredicate(value ? Comparison.EQ : Comparison.NE,
            new PoseExpr.Constant(0f), new PoseExpr.Constant(0f));
    }

    /**
     * What this predicate answers where both its operands are already literals.
     *
     * @return the answer, or empty where the tick still reaches one of the operands
     */
    public @NotNull Optional<Boolean> answered() {
        if (this.left instanceof PoseExpr.Constant lhs && this.right instanceof PoseExpr.Constant rhs)
            return Optional.of(this.comparison.test(lhs.value(), rhs.value()));
        return Optional.empty();
    }

    /**
     * The negation of this predicate, taken on the comparison rather than wrapped around it.
     *
     * <p>Every comparison has its complement in the same roster - equal against unequal, less
     * against greater-or-equal, less-or-equal against greater - so a negation is a different
     * comparison of the same two operands and never a shape of its own.
     *
     * @return the negated predicate
     */
    public @NotNull PosePredicate negate() {
        Comparison flipped = switch (this.comparison) {
            case EQ -> Comparison.NE;
            case NE -> Comparison.EQ;
            case LT -> Comparison.GE;
            case GE -> Comparison.LT;
            case LE -> Comparison.GT;
            case GT -> Comparison.LE;
        };
        return new PosePredicate(flipped, this.left, this.right);
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String toString() {
        return this.comparison.token() + PoseNode.ref(this)
            + "(" + PoseNode.ref(this.left) + ", " + PoseNode.ref(this.right) + ")";
    }

    /** How two numbers are compared. */
    @EnumLookup
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    public enum Comparison {

        /** Equal. */
        EQ("eq"),

        /** Not equal. */
        NE("ne"),

        /** Strictly less. */
        LT("lt"),

        /** Less or equal. */
        LE("le"),

        /** Strictly greater. */
        GT("gt"),

        /** Greater or equal. */
        GE("ge");

        /** The lower-case token this comparison is spelled with in the shipped table. */
        @KeyField
        private final @NotNull String token;

        /**
         * Applies this comparison.
         *
         * @param left the left operand
         * @param right the right operand
         * @return whether the comparison holds
         */
        public boolean test(double left, double right) {
            return switch (this) {
                case EQ -> left == right;
                case NE -> left != right;
                case LT -> left < right;
                case LE -> left <= right;
                case GT -> left > right;
                case GE -> left >= right;
            };
        }

    }

}
