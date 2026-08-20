package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What decides between the two arms of a {@link PoseExpr.Select}, and what guards a write that only
 * one arm makes.
 *
 * <p>One grammar serves both, because a branch that assigns different values and a branch that
 * assigns on one side only are the same bytecode shape read two ways. Nothing here can be decided
 * offline by construction: a condition the walk could resolve was resolved while walking, so every
 * predicate that survives names a render-state input.
 */
public sealed interface PosePredicate {

    /** How two numbers are compared. */
    enum Comparison {

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

        private final @NotNull String token;

        Comparison(@NotNull String token) {
            this.token = token;
        }

        /**
         * The token this comparison is spelled with in the shipped table.
         *
         * @return the lower-case token
         */
        public @NotNull String token() {
            return this.token;
        }

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

        /**
         * Resolves the comparison a token names.
         *
         * @param token the token to resolve
         * @return the comparison, or {@code null} when no comparison is spelled that way
         */
        public static @Nullable Comparison ofToken(@NotNull String token) {
            for (Comparison comparison : values())
                if (comparison.token.equals(token)) return comparison;
            return null;
        }

    }

    /**
     * A decided predicate, which exists so a folded branch can collapse rather than being special
     * cased at every site that builds one.
     *
     * @param value what the predicate answers
     */
    record Constant(boolean value) implements PosePredicate {}

    /**
     * A boolean field read off the render state.
     *
     * @param field the vanilla render-state field name
     */
    record Flag(@NotNull String field) implements PosePredicate {}

    /**
     * A numeric comparison between two expressions.
     *
     * @param comparison how the two are compared
     * @param left the left operand
     * @param right the right operand
     */
    record Compare(
        @NotNull Comparison comparison,
        @NotNull PoseExpr left,
        @NotNull PoseExpr right
    ) implements PosePredicate {

        /**
         * Builds a comparison, deciding it when both operands are already literals.
         *
         * @param comparison how the two are compared
         * @param left the left operand
         * @param right the right operand
         * @return the decided constant, or the undecided comparison
         */
        public static @NotNull PosePredicate of(
            @NotNull Comparison comparison, @NotNull PoseExpr left, @NotNull PoseExpr right) {

            if (left instanceof PoseExpr.Const lhs && right instanceof PoseExpr.Const rhs)
                return new Constant(comparison.test(lhs.value(), rhs.value()));
            return new Compare(comparison, left, right);
        }

    }

    /**
     * An equality test against one constant of a render-state enum, which is what a switch over an
     * arm pose or a parrot pose decomposes into.
     *
     * @param field the vanilla render-state field name
     * @param constant the enum constant's own name
     */
    record EnumEq(@NotNull String field, @NotNull String constant) implements PosePredicate {}

    /**
     * A test that a named animation state is running. Nothing offline starts one, so this is carried
     * to record what the model asked rather than because it can answer true.
     *
     * @param field the vanilla render-state field holding the animation state
     */
    record Started(@NotNull String field) implements PosePredicate {}

    /**
     * The negation of a predicate.
     *
     * @param operand what is negated
     */
    record Not(@NotNull PosePredicate operand) implements PosePredicate {}

    /**
     * A conjunction.
     *
     * @param operands what must all hold, in source order
     */
    record All(@NotNull List<PosePredicate> operands) implements PosePredicate {}

    /**
     * A disjunction.
     *
     * @param operands of which one must hold, in source order
     */
    record Any(@NotNull List<PosePredicate> operands) implements PosePredicate {}

    /**
     * The negation of this predicate, collapsing a double negation and a decided constant rather
     * than wrapping them.
     *
     * @return the negated predicate
     */
    default @NotNull PosePredicate negate() {
        if (this instanceof Constant decided) return new Constant(!decided.value());
        if (this instanceof Not negated) return negated.operand();
        return new Not(this);
    }

}
