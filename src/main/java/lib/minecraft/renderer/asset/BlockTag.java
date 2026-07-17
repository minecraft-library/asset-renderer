package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

/**
 * A fully resolved vanilla block tag, containing the flattened set of block IDs that belong to
 * it after all {@code #tag} inheritance references have been walked.
 * <p>
 * Vanilla ships ~248 block tags under {@code data/minecraft/tags/block/} defining semantic
 * groups like {@code stairs}, {@code logs}, {@code wool}, {@code candles}, etc. Tags can
 * reference other tags via the {@code #} prefix; this DTO stores the final resolved member
 * list so consumers never need to re-resolve inheritance at query time.
 *
 * @param id the tag's namespaced identifier (e.g. {@code minecraft:stairs})
 * @param values the flattened namespaced block ids belonging to this tag, with all {@code #tag}
 *     inheritance already resolved
 */
public record BlockTag(
    @NotNull ResourceId id,
    @NotNull ConcurrentList<String> values
) {}
