package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies {@link BlockDefaultsLoader} against the bundled {@code block_defaults.json}: the
 * structured {@code {prop:val}} states load as parsed {@code property -> value} maps (an empty state to
 * the empty map), and {@code unresolved} ids are absent - the empty-vs-absent distinction.
 */
class BlockDefaultsLoaderTest {

    private static @NotNull ConcurrentMap<String, ConcurrentMap<String, String>> load() {
        return BlockDefaultsLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
    }

    @Test
    @DisplayName("loads the 971 resolved default states")
    void loadsResolvedStates() {
        assertEquals(971, load().size(), "every blocks{} entry (unresolved is disjoint) is a resolved state");
    }

    @Test
    @DisplayName("an empty default state loads as the empty map (resolved, property-less)")
    void emptyStateIsEmptyMap() {
        assertEquals(Map.of(), new HashMap<>(load().get("minecraft:air")));
    }

    @Test
    @DisplayName("a structured state loads as its parsed property map")
    void structuredStateParses() {
        assertEquals(Map.of("face", "wall", "facing", "north", "powered", "false"),
            new HashMap<>(load().get("minecraft:acacia_button")));
    }

    @Test
    @DisplayName("an unresolved block id is absent (not conflated with an empty state)")
    void unresolvedAbsent() {
        assertFalse(load().containsKey("minecraft:acacia_planks"), "acacia_planks is unresolved, so it has no key");
    }
}
