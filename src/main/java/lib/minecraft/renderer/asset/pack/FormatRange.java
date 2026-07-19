package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.node.JsonTree;
import org.jetbrains.annotations.NotNull;

/**
 * One normalized encoding of the three pack-format generations. Every vanilla format declaration -
 * the legacy {@code pack_format} int, the
 * {@code supported_formats} int / {@code [min,max]} / {@code {min_inclusive,max_inclusive}} forms,
 * and the modern {@code min_format} / {@code max_format} int / {@code [major,minor]} pair - collapses
 * to an inclusive {@link FormatVersion} span so callers test a single {@link #contains} predicate.
 *
 * <p>Two normalization rules are load-bearing. A bare integer on the <b>max</b> side widens to that
 * major's highest minor ({@code (M, }{@link FormatVersion#MAX_MINOR}{@code )}) so a
 * {@code max_format: 255} still admits {@code 255.1} - the author's "any modern version" intent;
 * a bare integer on the <b>min</b> side floors to minor {@code 0}. Explicit {@code [major,minor]}
 * arrays are taken exactly. When several generations coexist in one {@code pack.mcmeta}, the newest
 * wins: {@code min_format}/{@code max_format} over {@code supported_formats} over {@code pack_format};
 * the losing keys are advisory only.
 *
 * @param min the inclusive lower bound
 * @param max the inclusive upper bound
 */
public record FormatRange(@NotNull FormatVersion min, @NotNull FormatVersion max) {

    /** The all-accepting range, used when a pack declares no format of any generation. */
    public static final @NotNull FormatRange ANY = new FormatRange(
        new FormatVersion(0, 0),
        new FormatVersion(FormatVersion.MAX_MINOR, FormatVersion.MAX_MINOR)
    );

    /**
     * Tests whether this range admits the given format, inclusive on both ends.
     *
     * @param version the target format version
     * @return {@code true} when {@code min <= version <= max}
     */
    public boolean contains(@NotNull FormatVersion version) {
        return version.compareTo(this.min) >= 0 && version.compareTo(this.max) <= 0;
    }

    /**
     * Normalizes a pack root's format declaration, applying newest-generation-wins across the three
     * encodings.
     *
     * @param pack the {@code pack} object node from a {@code pack.mcmeta}
     * @param packId the pack id, for error messages
     * @return the winning normalized range, or {@link #ANY} when no format key is present
     * @throws PipelineException if a present format key carries a malformed encoding
     */
    public static @NotNull FormatRange fromPackObject(@NotNull JsonTree pack, @NotNull String packId) {
        if (pack.has("min_format") || pack.has("max_format")) {
            FormatVersion min = pack.has("min_format") ? minBound(pack.get("min_format"), packId) : new FormatVersion(0, 0);
            FormatVersion max = pack.has("max_format")
                ? maxBound(pack.get("max_format"), packId)
                : new FormatVersion(FormatVersion.MAX_MINOR, FormatVersion.MAX_MINOR);
            return new FormatRange(min, max);
        }
        if (pack.has("supported_formats"))
            return fromFormatsValue(pack.get("supported_formats"), packId);
        if (pack.has("pack_format"))
            return fromFormatsValue(pack.get("pack_format"), packId);
        return ANY;
    }

    /**
     * Normalizes a single {@code formats}-style value - the gen-2 encoding shared by
     * {@code supported_formats}, legacy {@code pack_format}, and {@code overlays.entries[].formats}.
     * Accepts a bare integer, a length-2 {@code [min,max]} array, and a
     * {@code {min_inclusive,max_inclusive}} object.
     *
     * @param value the format value node
     * @param packId the pack id, for error messages
     * @return the normalized range
     * @throws PipelineException if the encoding is unrecognised or malformed
     */
    public static @NotNull FormatRange fromFormatsValue(@NotNull JsonTree value, @NotNull String packId) {
        if (value.intValue().isPresent())
            return new FormatRange(minBound(value, packId), maxBound(value, packId));

        if (value.isArray()) {
            if (value.size() != 2)
                throw new PipelineException("Pack '%s' has a 'formats' array of size %d (expected 2)", packId, value.size());
            return new FormatRange(minBound(value.at(0), packId), maxBound(value.at(1), packId));
        }

        if (value.isObject()) {
            if (!value.has("min_inclusive") || !value.has("max_inclusive"))
                throw new PipelineException("Pack '%s' 'formats' object missing min_inclusive/max_inclusive", packId);
            return new FormatRange(minBound(value.get("min_inclusive"), packId), maxBound(value.get("max_inclusive"), packId));
        }

        throw new PipelineException("Pack '%s' has an unrecognised 'formats' encoding", packId);
    }

    /** Lower bound of a value: a bare int floors to minor {@code 0}; a {@code [major,minor]} array is exact. */
    private static @NotNull FormatVersion minBound(@NotNull JsonTree value, @NotNull String packId) {
        if (value.intValue().isPresent()) return new FormatVersion(value.intValue().get(), 0);
        return exactArray(value, packId);
    }

    /** Upper bound of a value: a bare int widens to {@link FormatVersion#MAX_MINOR}; an array is exact. */
    private static @NotNull FormatVersion maxBound(@NotNull JsonTree value, @NotNull String packId) {
        if (value.intValue().isPresent()) return new FormatVersion(value.intValue().get(), FormatVersion.MAX_MINOR);
        return exactArray(value, packId);
    }

    /** Reads an exact {@code [major,minor]} array. */
    private static @NotNull FormatVersion exactArray(@NotNull JsonTree value, @NotNull String packId) {
        if (!value.isArray())
            throw new PipelineException("Pack '%s' format bound '%s' is neither an int nor a [major,minor] array", packId, value.toGson());
        if (value.size() != 2)
            throw new PipelineException("Pack '%s' has a [major,minor] array of size %d (expected 2)", packId, value.size());
        return new FormatVersion(value.at(0).toGson().getAsInt(), value.at(1).toGson().getAsInt());
    }

    /**
     * A {@code major.minor} pack-format version, ordered by major then minor.
     *
     * @param major the major format number
     * @param minor the minor format number ({@code 0} floor, {@link #MAX_MINOR} widened ceiling)
     */
    public record FormatVersion(int major, int minor) implements Comparable<FormatVersion> {

        /** The widened minor ceiling standing in for "any minor of this major". */
        public static final int MAX_MINOR = Integer.MAX_VALUE;

        /** {@inheritDoc} */
        @Override
        public int compareTo(@NotNull FormatVersion other) {
            int byMajor = Integer.compare(this.major, other.major);
            return byMajor != 0 ? byMajor : Integer.compare(this.minor, other.minor);
        }

    }

}
