package lib.minecraft.renderer.asset.rule;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The outcome of a {@link CtmMatcher} lookup for a block face.
 * <p>
 * For every non-overlay method (the context-free {@link CtmMethod#FIXED FIXED} /
 * {@link CtmMethod#RANDOM RANDOM} / {@link CtmMethod#REPEAT REPEAT} plus the neighbor-based
 * CTM-family methods) {@code textureId} holds the replacement tile the renderer should sample and
 * {@code overlayTextureId} is empty. For the {@link CtmMethod#OVERLAY OVERLAY} /
 * {@link CtmMethod#OVERLAY_FIXED OVERLAY_FIXED} methods {@code textureId} holds the original base
 * texture unchanged and {@code overlayTextureId} holds the tile to composite on top.
 *
 * @param textureId the replacement tile, or the original base texture for overlay methods
 * @param overlayTextureId the overlay tile to composite, present only for overlay methods
 */
public record CtmResolution(
    @NotNull String textureId,
    @NotNull Optional<String> overlayTextureId
) {}
