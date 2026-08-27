package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Scans a {@link PackStack} into the texture index the renderer resolves against. Every pack is
 * scanned across every namespace under {@code assets/<ns>/textures/**}, the whole
 * {@code .png.mcmeta} sidecar is captured on each row, and packs merge ascending with higher priority
 * winning. Before a pack's rows merge in, its {@code filter.block} patterns erase matching rows from
 * every lower pack.
 *
 * <p>Each row is a fully-resolved {@link ResolvedTexture}: the within-pack root walk is baked at index
 * time (base first, overlays after, last existing copy winning), so its {@link ResolvedTexture#path} is
 * the winning root-prefixed container entry and resolution is a direct index lookup with no second walk.
 *
 * <p>Every read goes through the pack's {@link PackContainer} - byte access, never an absolute path -
 * so a zip / {@code .cats} pack indexes without extraction to disk; a materialized
 * {@link PackContainer.Directory} answers identically. For a vanilla-only stack the extracted tree
 * carries only the {@code minecraft} namespace, so the multi-namespace walk yields exactly the
 * {@code minecraft}-qualified ids and the index is stable.
 *
 * @see ResolvedTexture
 */
@UtilityClass
public class TextureIndexer {

    /** The textures subtree every pack in the stack contributes. */
    private static final @NotNull PackSubtree.Subtree TEXTURES =
        PackSubtree.Subtree.of(VanillaSourcePaths.TEXTURES_SUBDIR, ".png");

    /**
     * Builds the merged texture index across the whole stack.
     *
     * @param stack the resolved pack stack
     * @return the merged index, keyed by namespaced texture id, wrapped unmodifiable
     */
    public static @NotNull ConcurrentMap<ResourceId, ResolvedTexture> index(@NotNull PackStack stack) {
        // The shared walk enumerates and filters serially, then the row build - which reads the PNG's
        // whole .mcmeta sidecar - parallelises across the FJP common pool. map() preserves encounter
        // order, so the sequential merge still sees later roots and later packs last, and winning.
        return PackSubtree.walk(stack, TEXTURES)
            .parallelStream()
            .map(TextureIndexer::buildRow)
            .collect(Concurrent.toUnmodifiableLinkedMap(ResolvedTexture::id, row -> row, (lower, higher) -> higher));
    }

    /** Builds one index row: namespaced id, winning root-prefixed container path, whole sidecar. */
    private static @NotNull ResolvedTexture buildRow(@NotNull PackSubtree.Entry entry) {
        PackContainer container = entry.container();
        ResourceId id = new ResourceId(entry.namespace(), entry.stem());
        return new ResolvedTexture(entry.pack().id(), id, container, entry.entryPath(),
            readSidecar(container, entry.entryPath(), id));
    }

    /** Reads the whole {@code <file>.png.mcmeta} sidecar next to a PNG, bound to the same pack+root. */
    private static @NotNull Optional<MCMeta> readSidecar(@NotNull PackContainer container, @NotNull String pngEntry, @NotNull ResourceId id) {
        return container.bytes(pngEntry + ".mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), id));
    }

}
