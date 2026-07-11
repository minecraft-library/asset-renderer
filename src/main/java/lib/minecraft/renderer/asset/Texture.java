package lib.minecraft.renderer.asset;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A texture reference within a texture pack. The DTO stores only metadata and a relative file
 * path under the asset cache root - raw PNG bytes stay on disk to keep memory lean.
 *
 * @param id the namespaced texture id (e.g. {@code minecraft:block/stone})
 * @param packId the id of the texture pack this texture was sourced from
 * @param relativePath the texture's path relative to the asset cache root
 * @param width the texture width in pixels
 * @param height the texture height in pixels
 * @param animation the animation metadata when the texture is an animated strip, or empty for a
 *     static texture
 */
public record Texture(
    @NotNull String id,
    @NotNull String packId,
    @NotNull String relativePath,
    int width,
    int height,
    @NotNull Optional<AnimationData> animation
) {}
