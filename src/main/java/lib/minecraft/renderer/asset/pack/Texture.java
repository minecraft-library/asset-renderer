package lib.minecraft.renderer.asset.pack;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A texture reference within a texture pack. The DTO stores only metadata and a relative file
 * path under the asset cache root - raw PNG bytes stay on disk to keep memory lean.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class Texture {

    /**
     * The namespaced texture id (e.g. {@code minecraft:block/stone}).
     */
    private final @NotNull String id;

    /**
     * The id of the texture pack this texture was sourced from.
     */
    private final @NotNull String packId;

    /**
     * The texture's path relative to the asset cache root.
     */
    private final @NotNull String relativePath;

    /**
     * The texture width in pixels.
     */
    private final int width;

    /**
     * The texture height in pixels.
     */
    private final int height;

    /**
     * The animation metadata when the texture is an animated strip, or empty for a static texture.
     */
    private final @NotNull Optional<AnimationData> animation;

}
