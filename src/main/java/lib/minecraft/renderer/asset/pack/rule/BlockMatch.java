package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * One {@code matchBlocks} entry of a CTM rule - a block id plus its optional block-state property
 * filters, the {@code minecraft:oak_stairs:facing=east,west:half=bottom} grammar.
 * Evaluable headlessly (the renderer knows its rendered state), though CTM renders nothing today.
 *
 * @param block the block id, {@code minecraft:}-defaulted
 * @param properties the state-property filters, keyed by property name to the accepted values
 */
public record BlockMatch(
    @NotNull ResourceId block,
    @NotNull ConcurrentMap<String, ConcurrentList<String>> properties
) {}
