package lib.minecraft.renderer.pipeline.resolver;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.TexturePack;
import lib.minecraft.renderer.asset.rule.PackMeta;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Parses a pack's {@code pack.mcmeta} via {@link PackMeta} and produces a {@link TexturePack}
 * whose {@link TexturePack#getAssetRoots()} carries the base pack root plus every overlay subtree
 * whose declared {@code formats} range matches the pack's own declared {@code pack_format}.
 * Overlays are appended in declaration order so loaders apply them with later-wins semantics.
 *
 * @see TexturePack
 * @see PackMeta
 */
@UtilityClass
public class PackResolver {

    /**
     * Resolves a pack root into a {@link TexturePack} carrying the resolved overlay roots.
     * <p>
     * The pack must ship a {@code pack.mcmeta} at its root - {@link PackMeta#parse} throws a
     * {@code PipelineException} when it's missing or malformed, on the assumption that callers
     * expect the pack to load. The pack's own declared {@code pack_format} drives overlay
     * matching: each {@code overlays.entries[i]} contributes a directory to the resolved roots
     * iff its {@code formats} predicate accepts the pack's format and the directory exists on
     * disk.
     *
     * @param packRoot the extracted pack root containing {@code pack.mcmeta}
     * @param packId the pack identifier
     * @param priority the pack priority across packs
     * @return the resolved pack with base + matching overlays
     */
    public static @NotNull TexturePack resolve(
        @NotNull Path packRoot,
        @NotNull String packId,
        int priority
    ) {
        if (!Files.isDirectory(packRoot))
            throw new lib.minecraft.renderer.exception.PipelineException(
                "Pack root '%s' does not exist or is not a directory", packRoot);

        PackMeta meta = PackMeta.parse(packRoot.resolve("pack.mcmeta"), packId);

        ArrayList<Path> assetRoots = new ArrayList<>();
        assetRoots.add(packRoot);
        for (PackMeta.Overlay overlay : meta.overlays()) {
            if (!overlay.formats().matches(meta.packFormat())) continue;
            Path overlayRoot = packRoot.resolve(overlay.directory());
            if (Files.isDirectory(overlayRoot)) assetRoots.add(overlayRoot);
        }

        ConcurrentList<Path> roots = Concurrent.adoptList(assetRoots).toUnmodifiable();
        return new TexturePack(packId, "minecraft", meta, roots, priority);
    }

}
