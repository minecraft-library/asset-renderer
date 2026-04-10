package dev.sbs.renderer.pack;

import org.jetbrains.annotations.NotNull;

/**
 * An inclusive integer range, used by CIT and CTM rules to describe damage, stack size, and
 * enchantment level filters.
 *
 * @param min the minimum value, inclusive
 * @param max the maximum value, inclusive
 */
public record IntRange(int min, int max) {

    /** A range that matches every possible integer. */
    public static final @NotNull IntRange ANY = new IntRange(Integer.MIN_VALUE, Integer.MAX_VALUE);

    /**
     * Creates a single-value range.
     *
     * @param value the sole accepted value
     * @return a range containing only {@code value}
     */
    public static @NotNull IntRange of(int value) {
        return new IntRange(value, value);
    }

    /**
     * Parses an Optifine-style range expression into an {@link IntRange}.
     * <p>
     * Accepts: a single integer ({@code "5"}), a dash range ({@code "5-10"}), or an open range
     * ({@code "5-"} or {@code "-10"}).
     *
     * @param expression the raw expression
     * @return the parsed range
     * @throws NumberFormatException if the expression cannot be parsed
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
     * Returns whether {@code value} falls within this range.
     *
     * @param value the value to test
     * @return true if the value is within the range (inclusive on both ends)
     */
    public boolean contains(int value) {
        return value >= this.min && value <= this.max;
    }

}
