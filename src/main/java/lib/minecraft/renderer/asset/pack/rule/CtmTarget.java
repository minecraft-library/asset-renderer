package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * What a CTM rule matches against - vanilla tile textures ({@code matchTiles}) or block ids
 * ({@code matchBlocks}). The two are a partition: the stack merge checks every tile-target rule
 * before any block-target rule.
 */
public sealed interface CtmTarget permits CtmTarget.Tiles, CtmTarget.Blocks {

    /**
     * Whether this target matches the subject being drawn - a {@link Tiles} target tests the drawn
     * base texture id; a {@link Blocks} target tests the block id and its state-property filters. The
     * argument each kind ignores is inert, so a matcher walk can call one uniform method.
     *
     * @param blockId the rendered block's namespaced id
     * @param baseTextureId the concrete resolved texture id of the drawn face
     * @param state the rendered block state
     * @return {@code true} when this target matches the subject
     */
    boolean matches(@NotNull String blockId, @NotNull String baseTextureId, @NotNull Map<String, String> state);

    /**
     * A {@code matchTiles} target - vanilla base-texture names (short, full, or chained).
     *
     * @param names the matched tile names, verbatim
     */
    record Tiles(@NotNull ConcurrentList<String> names) implements CtmTarget {

        @Override
        public boolean matches(@NotNull String blockId, @NotNull String baseTextureId, @NotNull Map<String, String> state) {
            for (String name : this.names)
                if (tileNameMatches(name, baseTextureId)) return true;
            return false;
        }

        /**
         * Whether a {@code matchTiles} token names the drawn base texture - matched as the full
         * namespaced id ({@code minecraft:block/glass}) or exact chained path, the path form
         * ({@code block/glass}), the short name ({@code glass}), or the namespaced short name
         * ({@code minecraft:glass}); a trailing {@code .png} is stripped first.
         */
        private static boolean tileNameMatches(@NotNull String token, @NotNull String baseTextureId) {
            String stripped = token.endsWith(".png") ? token.substring(0, token.length() - 4) : token;
            if (stripped.equals(baseTextureId)) return true;
            ResourceId base = ResourceId.parse(baseTextureId);
            String path = base.name();
            if (stripped.equals(path)) return true;
            String shortName = path.substring(path.lastIndexOf('/') + 1);
            return stripped.equals(shortName) || stripped.equals(base.namespace() + ':' + shortName);
        }

    }

    /**
     * A {@code matchBlocks} target - block ids with optional state filters.
     *
     * @param blocks the matched blocks
     */
    record Blocks(@NotNull ConcurrentList<BlockMatch> blocks) implements CtmTarget {

        @Override
        public boolean matches(@NotNull String blockId, @NotNull String baseTextureId, @NotNull Map<String, String> state) {
            for (BlockMatch block : this.blocks)
                if (block.matches(blockId, state)) return true;
            return false;
        }

    }

}
