package lib.minecraft.renderer.pipeline.pack.rule;

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
     * negative), a closed range ({@code "5-10"}), or an open-max range ({@code "5-"}). The dash scan
     * skips a leading {@code '-'} so it reads as a sign, not a separator, hence {@code "-5"} is the
     * value {@code -5}, not an open-min range.
     *
     * @param expression the raw token
     * @return the parsed range
     * @throws NumberFormatException if a numeric operand cannot be parsed
     */
    public static @NotNull IntRange parse(@NotNull String expression) {
        String trimmed = expression.trim();
        int dash = trimmed.indexOf('-', trimmed.startsWith("-") ? 1 : 0);
        if (dash < 0) {
            int value = Integer.parseInt(trimmed);
            return new IntRange(value, value);
        }

        String left = trimmed.substring(0, dash);
        String right = trimmed.substring(dash + 1);
        int min = left.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(left);
        int max = right.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(right);
        return new IntRange(min, max);
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
