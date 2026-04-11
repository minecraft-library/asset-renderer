package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.model.Block;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.model.Texture;
import dev.sbs.renderer.model.TexturePack;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.BlockStateMultipart;
import dev.sbs.renderer.model.asset.BlockStateVariant;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.sbs.renderer.pipeline.client.ClientJarDownloader;
import dev.sbs.renderer.pipeline.client.ClientJarExtractor;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.sbs.renderer.pipeline.loader.BlockStateLoader;
import dev.sbs.renderer.pipeline.loader.ColorMapLoader;
import dev.sbs.renderer.pipeline.loader.ItemDefinitionLoader;
import dev.sbs.renderer.pipeline.loader.ModelResolver;
import dev.sbs.renderer.pipeline.loader.TexturePackLoader;
import dev.sbs.renderer.pipeline.loader.VanillaTintsLoader;
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

        TexturePack vanillaPack = TexturePackLoader.loadVanilla(packRoot);
        ConcurrentList<Texture> textures = TexturePackLoader.scanTextures(packRoot, vanillaPack.getId());
        ConcurrentList<ColorMap> colorMaps = ColorMapLoader.load();
        ConcurrentMap<String, Block.Tint> blockTints = VanillaTintsLoader.load();
        BlockStateLoader.LoadResult blockStateResult = BlockStateLoader.load(packRoot);
        ConcurrentMap<String, String> itemDefinitions = ItemDefinitionLoader.load(packRoot);

        return new Result(packRoot, vanillaPack, textures, colorMaps, blockTints, blockModels, itemModels,
            blockStateResult.getVariants(), blockStateResult.getMultiparts(), itemDefinitions);
    }

    /**
     * The result of a single pipeline run.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Result {

        private final @NotNull Path packRoot;
        private final @NotNull TexturePack vanillaPack;
        private final @NotNull ConcurrentList<Texture> textures;
        private final @NotNull ConcurrentList<ColorMap> colorMaps;
        private final @NotNull ConcurrentMap<String, Block.Tint> blockTints;
        private final @NotNull ConcurrentMap<String, BlockModelData> blockModels;
        private final @NotNull ConcurrentMap<String, ItemModelData> itemModels;
        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, BlockStateVariant>> blockStates;
        private final @NotNull ConcurrentMap<String, BlockStateMultipart> blockStateMultiparts;
        private final @NotNull ConcurrentMap<String, String> itemDefinitions;

    }

}
