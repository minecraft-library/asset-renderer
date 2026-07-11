package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One texture-index row: the successor of the former {@code asset.Texture}, attributed to the pack
 * that supplied the winning PNG and carrying the whole {@code .png.mcmeta} sidecar (not just the
 * animation section) so every downstream consumer reads a texture's metadata without a second lookup.
 *
 * <p>{@link #relativePath} is the container path of the PNG with the owning root's prefix stripped
 * ({@code assets/<namespace>/textures/<path>.png}), so any of the owning pack's roots can re-prefix it
 * during resolution and the last existing copy wins - the same base-then-overlay precedence the former
 * scanner produced.
 *
 * @param id the namespaced texture id ({@code minecraft:block/stone})
 * @param pack the id of the pack whose root registered this row
 * @param relativePath the owning-root-relative container path of the PNG
 * @param width the texture width in pixels
 * @param height the texture height in pixels
 * @param meta the parsed sidecar bound to the same pack+root as the PNG, or empty when absent
 */
public record IndexedTexture(
    @NotNull ResourceId id,
    @NotNull PackId pack,
    @NotNull String relativePath,
    int width,
    int height,
    @NotNull Optional<MCMeta> meta
) {}
