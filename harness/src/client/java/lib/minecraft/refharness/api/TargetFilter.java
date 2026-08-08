package lib.minecraft.refharness.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed target allowlist - the subject ids a run is scoped to. An empty filter accepts every
 * subject.
 *
 * @param ids the allowed {@code <namespace>:<path>} ids
 */
public record TargetFilter(Set<String> ids) {

    /** Canonicalises the id set so a filter is immutable and comparable by value. */
    public TargetFilter {
        ids = Set.copyOf(ids);
    }

    /**
     * Parses a comma-separated allowlist, tolerating whitespace around each entry.
     *
     * <p>Parsing collects into a {@link LinkedHashSet}, so a list that repeats an id is accepted
     * rather than rejected.
     *
     * @param csv the raw property value; blank means every subject is in scope
     * @return the filter
     */
    public static TargetFilter parse(String csv) {
        if (csv == null || csv.isBlank()) return new TargetFilter(Set.of());
        return new TargetFilter(new LinkedHashSet<>(List.of(csv.split("\\s*,\\s*"))));
    }

    /** Whether this filter is empty, and therefore accepts every subject. */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /**
     * Returns whether a subject is in scope.
     *
     * @param id the subject's {@code <namespace>:<path>} id
     * @return whether the run should render it
     */
    public boolean accepts(String id) {
        return ids.isEmpty() || ids.contains(id);
    }
}
