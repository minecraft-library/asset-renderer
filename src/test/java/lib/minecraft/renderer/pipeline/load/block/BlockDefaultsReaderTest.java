package lib.minecraft.renderer.pipeline.load.block;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies {@link BlockDefaultsReader} against the bundled {@code v2/block_defaults.json}: the
 * structured {@code {prop:val}} states flatten to the property-sorted comma-joined key (empty state to
 * the empty string), and {@code unresolved} ids are absent - the empty-vs-absent distinction.
 */
class BlockDefaultsReaderTest {

    private static @NotNull ConcurrentMap<String, String> load() {
        return BlockDefaultsReader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null));
    }

    @Test
    @DisplayName("loads the 971 resolved default states")
    void loadsResolvedStates() {
        assertEquals(971, load().size(), "every blocks{} entry (unresolved is disjoint) is a resolved key");
    }

    @Test
    @DisplayName("an empty default state flattens to the empty string (resolved, property-less)")
    void emptyStateIsEmptyString() {
        assertEquals("", load().get("minecraft:air"));
    }

    @Test
    @DisplayName("a structured state flattens to the property-sorted comma-joined key")
    void structuredStateJoins() {
        assertEquals("face=wall,facing=north,powered=false", load().get("minecraft:acacia_button"));
    }

    @Test
    @DisplayName("an unresolved block id is absent (not conflated with an empty state)")
    void unresolvedAbsent() {
        assertFalse(load().containsKey("minecraft:acacia_planks"), "acacia_planks is unresolved, so it has no key");
    }
}
