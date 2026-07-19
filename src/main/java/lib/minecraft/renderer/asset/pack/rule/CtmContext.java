package lib.minecraft.renderer.asset.pack.rule;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The per-face query a block render hands the CTM matcher - everything the isolated-subject rules test,
 * all evaluable headlessly from data already in hand for the drawn icon.
 *
 * @param blockId the rendered block's namespaced id, for {@code matchBlocks} equality
 * @param state the rendered block state (e.g. {@code facing=east, half=bottom}), for the property filters
 * @param baseTextureId the concrete resolved texture id of the face, e.g. {@code minecraft:block/glass},
 *     for {@code matchTiles} name equality
 * @param face the CTM grammar face being drawn, already mapped from the render's {@code BlockFace}
 */
public record CtmContext(
    @NotNull String blockId,
    @NotNull Map<String, String> state,
    @NotNull String baseTextureId,
    @NotNull CtmFace face
) {}
