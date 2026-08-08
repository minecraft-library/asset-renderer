package lib.minecraft.renderer.asset.pack.rule;

import org.jetbrains.annotations.NotNull;

/**
 * An inclusive integer range - the atom of the OptiFine {@code range:}, damage, stack-size, and
 * enchantment-level filters.
 *
 * @param min the minimum value, inclusive
 * @param max the maximum value, inclusive
 */
public record IntRange(int min, int max) {

    /** A range that matches every integer. */
    public static final @NotNull IntRange ANY = new IntRange(Integer.MIN_VALUE, Integer.MAX_VALUE);

    /**
     * A single-value range.
     *
     * @param value the sole accepted value
     * @return a range containing only {@code value}
     */
    public static @NotNull IntRange of(int value) {
        return new IntRange(value, value);
    }

    /**
     * Parses an OptiFine-style range token: a single integer ({@code "5"}, or {@code "-5"} for a
     * negative), a closed range ({@code "5-10"}), or an open-max range ({@code "5-"}), with either
     * bound optionally parenthesised to disambiguate a negative ({@code "(-25)"}, {@code "(-10)-10"},
     * {@code "(-10)-(-5)"}). The separator dash
     * is the one at paren-depth zero that is not a leading sign, so {@code "-5"} reads as the value
     * {@code -5} and {@code "(-10)"} as the value {@code -10}, not open ranges.
     *
     * @param expression the raw token
     * @return the parsed range
     * @throws NumberFormatException if a numeric operand cannot be parsed
     */
    public static @NotNull IntRange parse(@NotNull String expression) {
        String trimmed = expression.trim();
        int separator = rangeSeparator(trimmed);
        if (separator < 0) {
            int value = parseBound(trimmed);
            return new IntRange(value, value);
        }

        String left = trimmed.substring(0, separator);
        String right = trimmed.substring(separator + 1);
        int min = left.isEmpty() ? Integer.MIN_VALUE : parseBound(left);
        int max = right.isEmpty() ? Integer.MAX_VALUE : parseBound(right);
        return new IntRange(min, max);
    }

    /**
     * The index of the range-separating dash - the first {@code '-'} at paren-depth zero that is not a
     * leading sign - or {@code -1} when the token is a single value. A dash inside {@code (...)} (a
     * parenthesised negative bound) or at position zero (a bare negative sign) is not a separator.
     */
    private static int rangeSeparator(@NotNull String token) {
        int depth = 0;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == '-' && depth == 0 && i > 0) return i;
        }
        return -1;
    }

    /** Parses one bound, stripping a surrounding parenthesis that guards a negative value. */
    private static int parseBound(@NotNull String bound) {
        String trimmed = bound.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '(' && trimmed.charAt(trimmed.length() - 1) == ')')
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        return Integer.parseInt(trimmed);
    }

    /**
     * Whether a value falls within this range.
     *
     * @param value the value to test
     * @return {@code true} when {@code min <= value <= max}
     */
    public boolean contains(int value) {
        return value >= this.min && value <= this.max;
    }

}
