package dev.sbs.renderer.pipeline.pack;

import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/**
 * A parsed Connected Textures rule from an Optifine or MCPatcher {@code .properties} file.
 * <p>
 * Only the subset of fields relevant to the context-free {@link CtmMethod} values is stored;
 * neighbor-based fields ({@code connect}, {@code faces} beyond ALL, dispatch tables) are left
 * out so the record stays narrow.
 *
 * @param source the source file path (debug only)
 * @param weight the rule weight for sort priority; higher values win
 * @param method the CTM method
 * @param matchedBlocks block ids this rule applies to (either this or {@link #matchedTiles()} populated)
 * @param matchedTiles vanilla texture ids this rule applies to
 * @param tiles the output texture id list in method-specific order
 * @param weights per-tile weights for {@link CtmMethod#RANDOM}, or an empty list for equal weighting
 * @param faces the face filter; {@link Face#ALL} matches every block face
 */
public record CtmRule(
    @NotNull String source,
    int weight,
    @NotNull CtmMethod method,
    @NotNull ConcurrentList<String> matchedBlocks,
    @NotNull ConcurrentList<String> matchedTiles,
    @NotNull ConcurrentList<String> tiles,
    @NotNull ConcurrentList<Integer> weights,
    @NotNull EnumSet<Face> faces
) {

    /**
     * Tests whether this rule applies to a given face of a block whose base texture is known.
     *
     * @param blockId the block id, e.g. {@code "minecraft:stone_bricks"}
     * @param baseTextureId the base texture id, e.g. {@code "minecraft:block/stone_bricks"}
     * @param face the face being rendered
     * @return true if the rule accepts this block+texture+face combination
     */
    public boolean appliesTo(@NotNull String blockId, @NotNull String baseTextureId, @NotNull Face face) {
        if (!this.faces.contains(Face.ALL) && !this.faces.contains(face)) return false;
        if (this.matchedBlocks.contains(blockId)) return true;
        return this.matchedTiles.contains(baseTextureId);
    }

    /**
     * The six cardinal block faces plus two aliases ({@code ALL}, {@code SIDES}) supported by the
     * Optifine CTM grammar.
     */
    public enum Face {
        DOWN, UP, NORTH, SOUTH, WEST, EAST, SIDES, ALL
    }

}
