package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * Scans a {@link PackStack} into the texture index the renderer resolves against. Every pack is
 * scanned across every namespace under {@code assets/<ns>/textures/**}, the whole
 * {@code .png.mcmeta} sidecar is captured on each row, and packs merge ascending with higher priority
 * winning. Before a pack's rows merge in, its {@code filter.block} patterns erase matching rows from
 * every lower pack.
 *
 * <p>Every read goes through the pack's {@link PackContainer} - byte access, never an absolute path -
 * so a zip / {@code .cats} pack indexes without extraction to disk; a materialized
 * {@link PackContainer.Directory} answers identically. For a vanilla-only stack the extracted tree
 * carries only the {@code minecraft} namespace, so the multi-namespace walk yields exactly the
 * {@code minecraft}-qualified ids and the index is stable.
 *
 * @see IndexedTexture
 */
@UtilityClass
public class TextureIndexer {

    /**
     * Builds the merged texture index across the whole stack.
     *
     * @param stack the resolved pack stack
     * @return the merged index, keyed by namespaced texture id, wrapped unmodifiable
     */
    public static @NotNull ConcurrentMap<ResourceId, IndexedTexture> index(@NotNull PackStack stack) {
        LinkedHashMap<ResourceId, IndexedTexture> merged = new LinkedHashMap<>();
        for (ResourcePack pack : stack.ascending()) {
            applyFilters(merged, pack);
            merged.putAll(scanPack(pack));
        }
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Erases every accumulated row a pack's {@code filter.block} patterns hide before the pack's own
     * rows merge in, through the shared {@link MCMeta.Pack#hides(ResourceId)} predicate.
     */
    private static void applyFilters(@NotNull Map<ResourceId, IndexedTexture> merged, @NotNull ResourcePack pack) {
        pack.meta().pack().ifPresent(section -> merged.keySet().removeIf(section::hides));
    }

    /** Scans one pack across every root (base first, overlays after) and namespace, later roots winning. */
    private static @NotNull Map<ResourceId, IndexedTexture> scanPack(@NotNull ResourcePack pack) {
        PackContainer container = pack.container();
        LinkedHashMap<ResourceId, IndexedTexture> rows = new LinkedHashMap<>();
        for (PackRoot root : pack.roots()) {
            for (String namespace : pack.namespaces()) {
                String texturesPrefix = root.prefix() + "assets/" + namespace + "/textures";
                rows.putAll(scanTexturesDir(container, texturesPrefix, namespace, pack.id()));
            }
        }
        return rows;
    }

    /** Walks one {@code textures} subtree and decodes every PNG in parallel into index rows. */
    private static @NotNull ConcurrentMap<ResourceId, IndexedTexture> scanTexturesDir(
        @NotNull PackContainer container, @NotNull String texturesPrefix, @NotNull String namespace, @NotNull PackId packId) {
        List<String> pngEntries = container.entries(texturesPrefix).filter(p -> p.endsWith(".png")).toList();

        return pngEntries.parallelStream()
            .map(png -> buildRow(container, png, texturesPrefix, namespace, packId))
            .collect(Concurrent.toMap(IndexedTexture::id, Function.identity()));
    }

    /** Builds one index row: namespaced id, owning-root-relative container path, PNG dimensions, whole sidecar. */
    private static @NotNull IndexedTexture buildRow(@NotNull PackContainer container, @NotNull String pngEntry,
                                                    @NotNull String texturesPrefix, @NotNull String namespace, @NotNull PackId packId) {
        String within = pngEntry.substring(texturesPrefix.length() + 1);
        String withoutExtension = within.endsWith(".png") ? within.substring(0, within.length() - 4) : within;
        ResourceId id = new ResourceId(namespace, withoutExtension);
        String relativePath = "assets/" + namespace + "/textures/" + within;

        byte[] bytes = container.bytes(pngEntry)
            .orElseThrow(() -> new PipelineException("Texture '%s' vanished mid-scan in pack '%s'", pngEntry, packId));
        int width = 0;
        int height = 0;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read texture '%s'", pngEntry);
        }

        return new IndexedTexture(id, packId, relativePath, width, height, readSidecar(container, pngEntry, id));
    }

    /** Reads the whole {@code <file>.png.mcmeta} sidecar next to a PNG, bound to the same pack+root. */
    private static @NotNull Optional<MCMeta> readSidecar(@NotNull PackContainer container, @NotNull String pngEntry, @NotNull ResourceId id) {
        return container.bytes(pngEntry + ".mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), id));
    }

}
