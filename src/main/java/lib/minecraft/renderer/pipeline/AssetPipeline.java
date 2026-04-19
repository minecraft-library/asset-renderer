package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.pipeline.client.ClientJarDownloader;
import lib.minecraft.renderer.pipeline.client.ClientJarExtractor;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.loader.BlockStateLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTagLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.ItemDefinitionLoader;
import lib.minecraft.renderer.pipeline.loader.ModelResolver;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.loader.TexturePackLoader;
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
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        BlockStateLoader.LoadResult blockStateResult = BlockStateLoader.load(packRoot);
        ConcurrentMap<String, String> itemDefinitions = ItemDefinitionLoader.load(packRoot);
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(packRoot);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(packRoot);

        return new Result(packRoot, vanillaPack, textures, colorMaps, blockTints, blockModels, itemModels,
            blockStateResult.getVariants(), blockStateResult.getMultiparts(), itemDefinitions, blockTags,
            potionEffectColors, bannerPatterns);
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
        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> blockStates;
        private final @NotNull ConcurrentMap<String, Block.Multipart> blockMultiparts;
        private final @NotNull ConcurrentMap<String, String> itemDefinitions;
        private final @NotNull ConcurrentMap<String, BlockTag> blockTags;

        /** Namespaced effect id to ARGB colour, parsed from {@code MobEffects} by the pipeline's potion colour loader. */
        private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;

        /** Namespaced banner pattern id to descriptor, parsed from {@code data/minecraft/banner_pattern/} by the banner pattern loader. */
        private final @NotNull ConcurrentMap<String, BannerPattern> bannerPatterns;

    }

}
