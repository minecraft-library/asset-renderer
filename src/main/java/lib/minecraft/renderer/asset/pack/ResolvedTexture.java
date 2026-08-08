package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The outcome of a texture resolution: the winning pack, the resolved id, read-only byte access to the
 * PNG the caller decodes, and the whole {@code .png.mcmeta} sidecar (not just the animation section) so
 * every downstream consumer reads a texture's metadata without a second lookup. Resolution has already
 * applied the namespace/pack-id dispatch and the within-pack root walk (last existing copy wins), baked
 * at index build time; the caller only decodes and memoises the bytes, keyed by {@code (pack, id)}.
 *
 * <p>Byte access is container-relative, not an absolute {@link java.nio.file.Path}: {@link #container}
 * is the winning pack's container and {@link #path} the root-prefixed, {@code /}-separated entry the
 * root walk landed on. This lets a zip / {@code .cats} pack serve its bytes without extraction to disk;
 * a materialized {@link PackContainer.Directory} answers identically.
 *
 * @param pack the id of the pack that supplied the winning PNG
 * @param id the resolved namespaced texture id
 * @param container the winning pack's container
 * @param path the container-relative, {@code /}-separated entry path of the PNG to decode
 * @param meta the parsed sidecar bound to the same pack+root as the PNG, or empty when absent
 */
public record ResolvedTexture(@NotNull PackId pack, @NotNull ResourceId id, @NotNull PackContainer container, @NotNull String path, @NotNull Optional<MCMeta> meta) {

    /**
     * Reads the winning PNG's bytes from the container.
     *
     * @return the PNG bytes
     * @throws PipelineException if the entry vanished between resolution and decode
     */
    public byte @NotNull [] bytes() {
        return this.container.bytes(this.path)
            .orElseThrow(() -> new PipelineException("Resolved texture '%s' from pack '%s' no longer exists at '%s'", this.id, this.pack, this.path));
    }

}
