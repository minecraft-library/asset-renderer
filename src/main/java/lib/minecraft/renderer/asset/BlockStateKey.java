package lib.minecraft.renderer.asset;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The single home for the two blockstate-key algorithms: parsing a canonical {@code "prop=val,.."} key
 * into a property map and joining a property map back into that canonical string.
 *
 * <p>{@link #parse(String)} and {@link #join(Map)} are inverses for canonical inputs - sorted, with no
 * comma or equals inside a value - which is all the tooling and vanilla blockstates ever produce. Both
 * default states ({@code block_defaults.json}) and blockstate variant keys parse once at load through
 * {@link #parse}; {@link #join} runs only where a canonical string is still emitted - the parity dump,
 * and {@link Block#defaultStateKey()}.
 *
 * <p>One of those emissions is on the render path after all: the carried-block variant lookup joins a
 * default state per block-overlay row per frame. It stays a join rather than a precomputed component
 * because of what it joins - four of the ninety entity models carry such a row, over default states of
 * at most one property, so the sort has nothing to order and the joiner emits a single token.
 */
@UtilityClass
public final class BlockStateKey {

    /**
     * Parses a canonical blockstate key ({@code "facing=south,lit=false"}) into a {@code property -> value}
     * map. A blank key yields an empty map; a pair without a leading property name (no {@code =}, or a
     * leading {@code =}) is skipped.
     *
     * @param key the canonical {@code "prop=val,.."} key
     * @return the parsed property map
     */
    public static @NotNull ConcurrentMap<String, String> parse(@NotNull String key) {
        ConcurrentMap<String, String> result = Concurrent.newMap();
        if (key.isBlank()) return result;

        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');

            if (eq > 0)
                result.put(pair.substring(0, eq), pair.substring(eq + 1));
        }

        return result;
    }

    /**
     * Joins a {@code property -> value} state into the canonical {@code "prop=val,.."} key, property-name
     * sorted (natural, case-sensitive order); an empty state joins to the empty string.
     *
     * @param state the {@code property -> value} state
     * @return the comma-joined property key
     */
    public static @NotNull String join(@NotNull Map<String, String> state) {
        List<String> names = new ArrayList<>(state.keySet());
        names.sort(null);

        StringJoiner joiner = new StringJoiner(",");
        for (String name : names)
            joiner.add(name + "=" + state.get(name));

        return joiner.toString();
    }

}
