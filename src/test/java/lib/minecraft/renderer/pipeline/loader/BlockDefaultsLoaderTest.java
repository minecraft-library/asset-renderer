package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Coverage of {@link BlockDefaultsLoader} against the bundled {@code block_defaults.json}: the
 * structured {@code {prop:val}} states load as parsed {@code property -> value} maps (an empty state to
 * the empty map), and {@code unresolved} ids are absent - the empty-vs-absent distinction.
 * <p>
 * The corpus SIZE is not asserted here: it is one key of {@code pin.corpus-count}, whose other key
 * comes from a different loader, so both are captured and asserted in {@link CorpusCountPinTest}.
 */
class BlockDefaultsLoaderTest {

    private static @NotNull ConcurrentMap<String, ConcurrentMap<String, String>> load() {
        return BlockDefaultsLoader.load();
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
