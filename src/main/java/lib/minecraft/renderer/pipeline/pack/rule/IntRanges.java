package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of {@link IntRange}s - the OptiFine grammar's list-of-values-or-ranges form
 * ({@code damage=1,3,5-7}, {@code enchantmentLevels=1-3 5}). A value matches when any entry contains
 * it.
 *
 * @param entries the range entries, in declaration order
 */
public record IntRanges(@NotNull ConcurrentList<IntRange> entries) {

    /** The empty set - matches nothing. */
    public static final @NotNull IntRanges EMPTY = new IntRanges(Concurrent.newUnmodifiableList());

    /**
     * Parses a whitespace- or comma-separated list of range tokens.
     *
     * @param expression the raw list expression
     * @return the parsed ranges
     * @throws NumberFormatException if a token's numeric operand cannot be parsed
     */
    public static @NotNull IntRanges parse(@NotNull String expression) {
        List<IntRange> ranges = new ArrayList<>();
        for (String token : expression.trim().split("[,\\s]+"))
            if (!token.isEmpty()) ranges.add(IntRange.parse(token));
        return new IntRanges(Concurrent.adoptList(ranges).toUnmodifiable());
    }

    /**
     * Whether any entry contains a value.
     *
     * @param value the value to test
     * @return {@code true} when at least one range admits {@code value}
     */
    public boolean contains(int value) {
        return this.entries.stream().anyMatch(range -> range.contains(value));
    }

    /**
     * Whether this set carries no ranges.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

}
