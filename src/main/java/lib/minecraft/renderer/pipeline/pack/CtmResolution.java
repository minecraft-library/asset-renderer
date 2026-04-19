package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The outcome of a {@link CtmMatcher} lookup for a block face.
 * <p>
 * When a context-free rule method (FIXED/RANDOM/REPEAT) applies, {@code textureId} holds the
 * replacement base texture and {@code overlayTextureId} is empty. When an overlay method applies,
 * {@code textureId} holds the original base texture unchanged and {@code overlayTextureId} holds
 * the overlay tile to composite on top.
 *
 * @param textureId the replacement or original base texture id
 * @param overlayTextureId the optional overlay texture id
 */
public record CtmResolution(
    @NotNull String textureId,
    @NotNull Optional<String> overlayTextureId
) {}
