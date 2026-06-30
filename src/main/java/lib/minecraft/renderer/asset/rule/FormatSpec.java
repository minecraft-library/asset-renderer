package lib.minecraft.renderer.asset.rule;

/**
 * The pack-format predicate carried by a single {@code overlays.entries[].formats} entry. Vanilla
 * supports three encodings - a bare integer ({@code 46}), a length-2 integer array
 * ({@code [40, 50]}), and an inclusive-range object ({@code {min_inclusive, max_inclusive}}) -
 * each implemented as a record below. The {@link #matches(int)} contract collapses all three into
 * a single predicate so callers don't pattern-match.
 */
public sealed interface FormatSpec {

    /**
     * Tests whether this predicate accepts the given target pack format.
     *
     * @param target the renderer's target pack format
     * @return {@code true} when the spec accepts the target
     */
    boolean matches(int target);

    /**
     * Single-value predicate matching iff {@code target == value}.
     *
     * @param value the only accepted pack format
     */
    record IntSpec(int value) implements FormatSpec {
        @Override
        public boolean matches(int target) {
            return target == value;
        }
    }

    /**
     * Inclusive integer range predicate matching iff {@code min <= target <= max}.
     *
     * @param min the inclusive lower bound
     * @param max the inclusive upper bound
     */
    record RangeSpec(int min, int max) implements FormatSpec {
        @Override
        public boolean matches(int target) {
            return target >= min && target <= max;
        }
    }

}
