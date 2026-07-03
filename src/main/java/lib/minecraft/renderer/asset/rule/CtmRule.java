package lib.minecraft.renderer.asset.rule;

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
     * <p>
     * The face gate passes when the rule targets {@link Face#ALL} or lists the concrete
     * {@code face} directly. The subject gate then passes when the block id is in
     * {@link #matchedBlocks()} or the base texture id is in {@link #matchedTiles()}.
     *
     * @param blockId the block id, e.g. {@code "minecraft:stone_bricks"}
     * @param baseTextureId the base texture id, e.g. {@code "minecraft:block/stone_bricks"}
     * @param face the concrete face being rendered
     * @return true when the rule accepts this block+texture+face combination
     */
    public boolean appliesTo(@NotNull String blockId, @NotNull String baseTextureId, @NotNull Face face) {
        if (!this.faces.contains(Face.ALL) && !this.faces.contains(face)) return false;
        if (this.matchedBlocks.contains(blockId)) return true;
        return this.matchedTiles.contains(baseTextureId);
    }

    /**
     * The six cardinal block faces plus two aliases ({@link #ALL}, {@link #SIDES}) supported by the
     * Optifine CTM grammar. {@link #appliesTo} expands only {@link #ALL}; {@link #SIDES} is
     * retained for parse fidelity but is not yet unfolded into the four side faces.
     */
    public enum Face {

        /**
         * The bottom face.
         */
        DOWN,

        /**
         * The top face.
         */
        UP,

        /**
         * The north face.
         */
        NORTH,

        /**
         * The south face.
         */
        SOUTH,

        /**
         * The west face.
         */
        WEST,

        /**
         * The east face.
         */
        EAST,

        /**
         * Alias for the four horizontal side faces (north / south / west / east).
         */
        SIDES,

        /**
         * Alias matching every block face.
         */
        ALL
    }

}
