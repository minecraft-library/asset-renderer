package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * One {@code matchBlocks} entry of a CTM rule - a block id plus its optional block-state property
 * filters, the {@code minecraft:oak_stairs:facing=east,west:half=bottom} grammar. Evaluated headlessly
 * by {@link #matches} for isolated-block CTM - the renderer knows the state it is drawing.
 *
 * @param block the block id, {@code minecraft:}-defaulted
 * @param properties the state-property filters, keyed by property name to the accepted values
 */
public record BlockMatch(
    @NotNull ResourceId block,
    @NotNull ConcurrentMap<String, ConcurrentList<String>> properties
) {

    /**
     * Whether the rendered block and state satisfy this filter - the block ids must be equal, and each
     * property clause must hold: the state's value for that property must be one of the accepted values
     * (values within a clause are OR'd, clauses are AND'd). A filter with no properties matches every
     * state of the block.
     *
     * @param blockId the rendered block's namespaced id
     * @param state the rendered block state, keyed by property name to its value
     * @return {@code true} when the block id and every property clause match
     */
    public boolean matches(@NotNull String blockId, @NotNull Map<String, String> state) {
        if (!this.block.id().equals(blockId)) return false;
        for (Map.Entry<String, ConcurrentList<String>> clause : this.properties.entrySet()) {
            String actual = state.get(clause.getKey());
            if (actual == null || !clause.getValue().contains(actual)) return false;
        }
        return true;
    }

}
