package lib.minecraft.renderer.pipeline.pack;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Pure function that resolves a {@link CtmRule} for a given block id and face into a
 * {@link CtmResolution}.
 * <p>
 * Only context-free CTM methods are currently supported. Neighbor-dependent methods (CTM,
 * CTM_COMPACT, HORIZONTAL, VERTICAL, TOP, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL) are mapped to
 * {@link CtmMethod#UNSUPPORTED} by the loader and here fall back to {@code tiles[0]}.
 */
@UtilityClass
public class CtmMatcher {

    /**
     * Resolves the rule against a block id and face, returning the tile (and optional overlay)
     * that the renderer should sample.
     *
     * @param rule the rule to resolve
     * @param blockId the block id being rendered, used as the random seed
     * @param baseTextureId the vanilla base texture id for the face
     * @return the resolution, or empty when the rule has no tiles
     */
    public static @NotNull Optional<CtmResolution> resolve(
        @NotNull CtmRule rule,
        @NotNull String blockId,
        @NotNull String baseTextureId
    ) {
        if (rule.tiles().isEmpty()) return Optional.empty();

        return switch (rule.method()) {
            case FIXED, REPEAT, UNSUPPORTED -> Optional.of(new CtmResolution(rule.tiles().getFirst(), Optional.empty()));
            case RANDOM -> Optional.of(new CtmResolution(pickRandom(rule, blockId), Optional.empty()));
            case OVERLAY, OVERLAY_FIXED -> Optional.of(new CtmResolution(baseTextureId, Optional.of(rule.tiles().getFirst())));
        };
    }

    private static @NotNull String pickRandom(@NotNull CtmRule rule, @NotNull String blockId) {
        int tileCount = rule.tiles().size();
        int seed = Math.floorMod(blockId.hashCode(), Math.max(1, totalWeight(rule, tileCount)));
        int cursor = 0;
        for (int i = 0; i < tileCount; i++) {
            int weight = i < rule.weights().size() ? rule.weights().get(i) : 1;
            cursor += weight;
            if (seed < cursor) return rule.tiles().get(i);
        }
        return rule.tiles().get(tileCount - 1);
    }

    private static int totalWeight(@NotNull CtmRule rule, int tileCount) {
        int total = 0;
        for (int i = 0; i < tileCount; i++)
            total += i < rule.weights().size() ? rule.weights().get(i) : 1;
        return Math.max(total, 1);
    }

}
