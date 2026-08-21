package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What decides between the two arms of a {@link PoseExpr.Select}.
 *
 * <p>Nothing here can be decided from the table alone: a condition the generator could resolve was
 * resolved while it walked, so every predicate that reaches this side names something a caller
 * supplies. Five arms, and the shipped corpus builds all five.
 *
 * <p>There is deliberately no arm for a bare boolean. A boolean the render state declares as a
 * field and a question asked of something it holds both arrive as numbers, so both are compared
 * against zero and neither needs a shape of its own.
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
     * A decided predicate, which a generator emits where a branch folded on one side of a choice it
     * kept on the other.
     *
     * @param value what the predicate answers
     */
    record Constant(boolean value) implements PosePredicate {}

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
    ) implements PosePredicate {}

    /**
     * A test that a named reference the render state holds is one particular declared constant of
     * its own type - which arm is swinging, which pose a parrot is in, whether a hand is empty.
     *
     * @param member the vanilla render-state member the reference was read from
     * @param constant the constant's own name
     */
    record Is(@NotNull String member, @NotNull String constant) implements PosePredicate {}

    /**
     * A test that a reference the render state is reached through is there at all - whether the
     * item in a named hand carries the named component the pose reads its figures off.
     *
     * <p>The member is a path rather than a single name, so what is being tested for is already
     * part of it: a caller that models no item components answers false to every one of these, and
     * gets the arm vanilla takes when an item carries nothing.
     *
     * @param member the path through the render state the reference is reached by
     */
    record Has(@NotNull String member) implements PosePredicate {}

    /**
     * The negation of a predicate.
     *
     * @param operand what is negated
     */
    record Not(@NotNull PosePredicate operand) implements PosePredicate {}

}
