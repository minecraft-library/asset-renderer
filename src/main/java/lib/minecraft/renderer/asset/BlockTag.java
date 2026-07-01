package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A fully resolved vanilla block tag, containing the flattened set of block IDs that belong to
 * it after all {@code #tag} inheritance references have been walked.
 * <p>
 * Vanilla ships ~248 block tags under {@code data/minecraft/tags/block/} defining semantic
 * groups like {@code stairs}, {@code logs}, {@code wool}, {@code candles}, etc. Tags can
 * reference other tags via the {@code #} prefix; this DTO stores the final resolved member
 * list so consumers never need to re-resolve inheritance at query time.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class BlockTag {

    /**
     * The tag's namespaced identifier (e.g. {@code minecraft:stairs}).
     */
    private final @NotNull ResourceId id;

    /**
     * The flattened namespaced block ids belonging to this tag, with all {@code #tag} inheritance
     * already resolved.
     */
    private final @NotNull ConcurrentList<String> values;

}
