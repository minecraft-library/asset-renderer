package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.model.BlockTint;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.model.Texture;
import dev.sbs.renderer.model.TexturePack;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Orchestrates the end-to-end asset extraction flow: download the client jar, extract the
 * {@code minecraft/} subtrees, parse every model JSON, read the texture catalogue, and hand the
 * results back to the caller as a {@link Result} record.
 * <p>
 * Actual persistence of the parsed data into the {@code SessionManager} repositories is the
 * caller's responsibility - the pipeline produces pure data and does not touch any database
 * sessions. This keeps the pipeline unit-testable without a live JPA setup.
 */
@RequiredArgsConstructor
public final class AssetPipeline {

    private final @NotNull HttpFetcher fetcher;

    /**
     * Runs the pipeline with the given options and returns the parsed result.
     *
     * @param options the pipeline options
     * @return the parsed asset result
     */
    public @NotNull Result run(@NotNull AssetPipelineOptions options) {
        Path jarPath = ClientJarDownloader.download(options, this.fetcher);

        Path packRoot = options.getCacheRoot().toPath().resolve("vanilla").resolve(options.getVersion());
        ClientJarExtractor.extract(jarPath, packRoot);

        ConcurrentMap<String, BlockModelData> blockModels = ModelResolver.loadBlockModels(packRoot);
        ConcurrentMap<String, ItemModelData> itemModels = ModelResolver.loadItemModels(packRoot);

        TexturePack vanillaPack = TexturePackReader.loadVanilla(packRoot);
        ConcurrentList<Texture> textures = TexturePackReader.scanTextures(packRoot, vanillaPack.getId());
        ConcurrentList<ColorMap> colorMaps = ColorMapReader.load(packRoot, vanillaPack.getId());
        ConcurrentList<BlockTint> blockTints = VanillaTintsLoader.load(vanillaPack.getId());

        return new Result(packRoot, vanillaPack, textures, colorMaps, blockTints, blockModels, itemModels);
    }

    /**
     * The result of a single pipeline run.
     */
    @Getter
    public static final class Result {

        private final @NotNull Path packRoot;
        private final @NotNull TexturePack vanillaPack;
        private final @NotNull ConcurrentList<Texture> textures;
        private final @NotNull ConcurrentList<ColorMap> colorMaps;
        private final @NotNull ConcurrentList<BlockTint> blockTints;
        private final @NotNull ConcurrentMap<String, BlockModelData> blockModels;
        private final @NotNull ConcurrentMap<String, ItemModelData> itemModels;

        public Result(
            @NotNull Path packRoot,
            @NotNull TexturePack vanillaPack,
            @NotNull ConcurrentList<Texture> textures,
            @NotNull ConcurrentList<ColorMap> colorMaps,
            @NotNull ConcurrentList<BlockTint> blockTints,
            @NotNull ConcurrentMap<String, BlockModelData> blockModels,
            @NotNull ConcurrentMap<String, ItemModelData> itemModels
        ) {
            this.packRoot = packRoot;
            this.vanillaPack = vanillaPack;
            this.textures = textures;
            this.colorMaps = colorMaps;
            this.blockTints = blockTints;
            this.blockModels = blockModels;
            this.itemModels = itemModels;
        }

    }

}
