package lib.minecraft.renderer.asset.pose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * @param comparison how the two are compared
 * @param left the left operand
 * @param right the right operand
 */
public record PosePredicate(
    @NotNull Comparison comparison,
    @NotNull PoseExpr left,
    @NotNull PoseExpr right
) {

    /** How two numbers are compared. */
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

}
