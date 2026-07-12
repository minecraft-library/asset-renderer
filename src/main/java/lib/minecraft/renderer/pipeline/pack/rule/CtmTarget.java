package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

/**
 * What a CTM rule matches against - vanilla tile textures ({@code matchTiles}) or block ids
 * ({@code matchBlocks}). The two are a partition: the stack merge checks every tile-target rule
 * before any block-target rule (07-optifine §5).
 */
public sealed interface CtmTarget permits CtmTarget.Tiles, CtmTarget.Blocks {

    /**
     * A {@code matchTiles} target - vanilla base-texture names (short, full, or chained).
     *
     * @param names the matched tile names, verbatim
     */
    record Tiles(@NotNull ConcurrentList<String> names) implements CtmTarget {}

    /**
     * A {@code matchBlocks} target - block ids with optional state filters.
     *
     * @param blocks the matched blocks
     */
    record Blocks(@NotNull ConcurrentList<BlockMatch> blocks) implements CtmTarget {}

}
