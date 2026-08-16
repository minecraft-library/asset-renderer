package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.LinkedHashMap;

/**
 * A loader that reads the bundled vanilla block tint table from the {@code block_tints.json}
 * classpath resource and produces a lookup map of block id to {@link Block.Tint}.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.client.color.block.BlockColors.createDefault()} as parsed by
 * {@code ToolingBlockTints.Parser}. To refresh it on a Minecraft version bump, run the
 * {@code blockTints} Gradle task; the runtime pipeline never invokes the ASM walker directly. Older
 * Minecraft versions reuse the same 26.1 tint set - blocks that don't exist in their era simply never
 * match a lookup, which the renderer treats as untinted.
 * <p>
 * Each value is a block-keyed {@code {target, constant?}} object that reflects straight into a
 * {@link Block.Tint}: {@code target} resolves by name and {@code constant} decodes through the shared
 * {@link Color} codec. The {@code source} provenance and the top-level {@code dropped} list stay in
 * the file for humans and are ignored at load.
 *
 * @see Block.Tint
 */
@UtilityClass
public class BlockTintsLoader {

    /**
     * Bundled tint snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "block_tints.json";

    /**
     * Reads the tint table from {@code block_tints.json} natively through the shared read layer, each
     * value reflecting straight into a {@link Block.Tint}. Iteration order mirrors the on-disk JSON.
     *
     * @return a map keyed by namespaced block id
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull ConcurrentMap<String, Block.Tint> load() {
        ResourceDocument document = BundledResource.require(RESOURCE_NAME);
        return Concurrent.adoptLinkedMap(document.as(TintTable.class).tints()).toUnmodifiable();
    }

    /** The {@code block_tints.json} payload: the block-keyed tint map ({@code dropped} is ignored). */
    record TintTable(@NotNull LinkedHashMap<String, Block.Tint> tints) {}

}
