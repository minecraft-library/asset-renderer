package lib.minecraft.renderer.content.pack;

import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The outcome of a texture resolution: the winning pack, the resolved id, read-only byte access to the
 * PNG the caller decodes, and the whole {@code .png.mcmeta} sidecar (not just the animation section) so
 * every downstream consumer reads a texture's metadata without a second lookup. Resolution has already
 * applied the namespace/pack-id dispatch and the within-pack root walk (last existing copy wins), baked
 * at index build time; the caller only decodes and memoises the bytes, keyed by {@code (pack, id)}.
 *
 * <p>Byte access is container-relative, not an absolute {@link Path}: {@link #container}
 * is the winning pack's container and {@link #path} the root-prefixed, {@code /}-separated entry the
 * root walk landed on. This lets a zip / {@code .cats} pack serve its bytes without extraction to disk;
 * a materialized directory answers identically.
 *
 * @param pack the id of the pack that supplied the winning PNG
 * @param id the resolved namespaced texture id
 * @param container the winning pack's container
 * @param path the container-relative, {@code /}-separated entry path of the PNG to decode
 * @param meta the sidecar bound to the same pack+root as the PNG; empty when the file is there and does
 *     not parse - blank, not JSON, or carrying a value of the wrong type or a malformed encoding - and
 *     absent when the PNG ships none
 */
public record ResolvedTexture(@NotNull PackId pack, @NotNull ResourceId id, @NotNull PackContainer container, @NotNull String path, @NotNull Possible<MCMeta> meta) {

    /**
     * Resolves the texture a PNG entry holds, reading the {@code <file>.png.mcmeta} sidecar beside it
     * in the same pack and root. A sidecar that does not parse is captured as one that is there and
     * yields nothing, so the texture it annotates is lost alone, as vanilla loses only that sprite, and
     * nothing is raised to fail the lookup or the index build that asked.
     *
     * @param pack the id of the pack that supplies the PNG
     * @param id the resolved namespaced texture id
     * @param container the pack's container
     * @param path the container-relative entry path of the PNG
     * @return the resolved texture, carrying its sidecar
     */
    public static @NotNull ResolvedTexture of(@NotNull PackId pack, @NotNull ResourceId id, @NotNull PackContainer container, @NotNull String path) {
        return new ResolvedTexture(pack, id, container, path, readSidecar(container, path, id));
    }

    /**
     * Reads the winning PNG's bytes from the container.
     *
     * @return the PNG bytes
     * @throws ContentException if the entry vanished between resolution and decode
     */
    public byte @NotNull [] bytes() {
        return this.container.bytes(this.path)
            .orElseThrow(() -> new ContentException("Resolved texture '%s' from pack '%s' no longer exists at '%s'", this.id, this.pack, this.path));
    }

    /**
     * Reads the sidecar next to a PNG. The bytes are read before the parse, so only the parse's own
     * refusal is turned into a state.
     *
     * @param container the pack's container
     * @param pngEntry the container-relative entry path of the PNG
     * @param id the texture id the sidecar annotates
     * @return the parsed sidecar; empty when the file is there and does not parse, absent when there
     *     is none
     */
    private static @NotNull Possible<MCMeta> readSidecar(@NotNull PackContainer container, @NotNull String pngEntry, @NotNull ResourceId id) {
        Optional<byte[]> bytes = container.bytes(pngEntry + ".mcmeta");
        if (bytes.isEmpty()) return Possible.absent();

        try {
            return Possible.of(MCMetaParser.parse(new String(bytes.get(), StandardCharsets.UTF_8), id));
        } catch (ContentException ex) {
            // Reported once, in vanilla's words, by the decode that is first asked for the texture: a
            // pack-prefixed id is probed on every lookup, so a report here would repeat.
            return Possible.empty();
        }
    }

}
