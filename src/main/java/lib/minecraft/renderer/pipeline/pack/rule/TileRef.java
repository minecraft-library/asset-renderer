package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * One entry of a CTM rule's {@code tiles=} list after parse-time expansion - a concrete texture, or
 * one of the two OptiFine sentinels. A {@code 0-46} range unrolls to 47 {@link Texture} entries at
 * parse, so the matcher never re-derives paths.
 */
public sealed interface TileRef permits TileRef.Texture, TileRef.Skip, TileRef.Default {

    /**
     * A concrete tile texture, resolved relative to the {@code .properties} file's directory.
     *
     * @param id the resolved texture id
     */
    record Texture(@NotNull ResourceId id) implements TileRef {}

    /** The {@code <skip>} sentinel - leave the face untouched. */
    record Skip() implements TileRef {}

    /** The {@code <default>} sentinel - use the block's default (base) texture. */
    record Default() implements TileRef {}

}
