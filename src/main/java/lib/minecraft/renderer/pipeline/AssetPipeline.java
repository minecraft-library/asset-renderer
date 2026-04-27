package lib.minecraft.renderer.pipeline;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.PistonManifest;
import api.simplified.mojang.response.PistonMetadata;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.exception.AssetPipelineException;
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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Orchestrates the end-to-end asset extraction flow: download the client jar through
 * {@link MojangContract}, extract the {@code minecraft/} subtrees, parse every model JSON, read
 * the texture catalogue, and hand the results back to the caller as a {@link Result} record.
 * <p>
 * Actual persistence of the parsed data into the {@code SessionManager} repositories is the
 * caller's responsibility - the pipeline produces pure data and does not touch any database
 * sessions. This keeps the pipeline unit-testable without a live JPA setup.
 * <p>
 * All Mojang network access flows through a single lazily-initialised {@link Proxy} of
 * {@link MojangContract}, accessible to siblings in this module via {@link #mojang()}. The proxy
 * carries domain-aware rate limiting from the upstream module so concurrent callers
 * ({@link #run}, {@link #downloadJarToCache}, the player skin / cape paths) share the same
 * limiter state.
 */
public final class AssetPipeline {

    private static volatile @Nullable Proxy<MojangContract> mojangProxy;

    /**
     * Runs the pipeline with the given options and returns the parsed result.
     *
     * @param options the pipeline options
     * @return the parsed asset result
     */
    public @NotNull Result run(@NotNull AssetPipelineOptions options) {
        Path packRoot = packRoot(options);
        Path jarPath = downloadJarToCache(options);
        extractClientJar(jarPath, packRoot);

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
     * Downloads the client jar for {@code options.getVersion()} into the local cache and returns
     * the {@link Path} - skips the extraction step. Used by tooling generators that ASM-walk the
     * jar bytes directly ({@code BlockColors}, {@code MobEffects}, block-entity classes) without
     * needing the extracted asset tree.
     * <p>
     * The cached file lives at {@code <cacheRoot>/vanilla/<version>/client.jar}. When the file
     * already exists and {@code options.isForceDownload()} is {@code false}, the network round
     * trip is skipped and the cached path is returned.
     *
     * @param options the pipeline options
     * @return the path to the cached client jar
     */
    public static @NotNull Path downloadJarToCache(@NotNull AssetPipelineOptions options) {
        Path target = packRoot(options).resolve("client.jar");
        if (Files.isRegularFile(target) && !options.isForceDownload()) return target;
        try {
            Files.createDirectories(target.getParent());
            MojangContract mojang = mojang();
            PistonMetadata.Downloads.Entry clientEntry = resolveClientEntry(mojang, options.getVersion());
            try (InputStream stream = mojang.downloadClientJar(clientEntry)) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to cache client jar at '%s'", target);
        }
        return target;
    }

    /**
     * The lazily-initialised shared {@link MojangContract}. Single proxy per JVM, double-checked
     * under the class monitor so concurrent callers ({@link #run}, {@link #downloadJarToCache},
     * the player skin / cape paths in
     * {@link lib.minecraft.renderer.PlayerRenderer PlayerRenderer}) share the same domain-aware
     * rate limiter.
     *
     * @return the shared Mojang contract
     */
    public static @NotNull MojangContract mojang() {
        Proxy<MojangContract> local = mojangProxy;
        if (local == null) {
            synchronized (AssetPipeline.class) {
                local = mojangProxy;
                if (local == null) {
                    local = Proxy.builder(
                        ClientConfig.builder(MojangContract.class, GsonSettings.defaults().create())
                            .withErrorDecoder(MojangApiException::new)
                            .build()
                    ).build();
                    mojangProxy = local;
                }
            }
        }
        return local.getContract();
    }

    /**
     * Resolves the {@code Piston} client-jar entry for the given version, surfacing an
     * {@link AssetPipelineException} when the version id is missing from the manifest.
     */
    private static @NotNull PistonMetadata.Downloads.Entry resolveClientEntry(@NotNull MojangContract mojang, @NotNull String version) {
        PistonManifest.Entry entry = mojang.getVersionManifest().getVersions().stream()
            .filter(v -> v.getVersion().equals(version))
            .findFirst()
            .orElseThrow(() -> new AssetPipelineException("Version '%s' is not in the Piston manifest", version));
        return mojang.getVersionMetadata(entry).getDownloads().getClient();
    }

    /**
     * Streams the {@code assets/minecraft/} and {@code data/minecraft/} subtrees out of a cached
     * client jar into {@code packRoot}. Skips {@code .class} files, manifests, and other
     * non-resource entries. Idempotent - safe to re-run with the same {@code packRoot}.
     * <p>
     * Public so tooling generators that need an extracted asset tree without running the parse
     * phases (e.g. {@code ToolingColorMaps} reading the biome colormap PNGs) can pair this with
     * {@link #downloadJarToCache(AssetPipelineOptions)}.
     *
     * @param jarPath the cached client jar path
     * @param packRoot the destination pack root
     */
    public static void extractClientJar(@NotNull Path jarPath, @NotNull Path packRoot) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(VanillaPaths.VANILLA_ASSET_ROOT) && !name.startsWith(VanillaPaths.VANILLA_DATA_ROOT)) continue;
                Path destination = packRoot.resolve(name);
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to extract '%s' into '%s'", jarPath, packRoot);
        }
    }

    /** The standard {@code <cacheRoot>/vanilla/<version>} pack-root path for the given options. */
    private static @NotNull Path packRoot(@NotNull AssetPipelineOptions options) {
        return options.getCacheRoot().toPath().resolve("vanilla").resolve(options.getVersion());
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
