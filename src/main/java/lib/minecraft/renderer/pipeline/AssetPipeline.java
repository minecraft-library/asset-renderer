package lib.minecraft.renderer.pipeline;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.PistonMetadata;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.collection.Concurrent;
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
import lib.minecraft.renderer.pipeline.loader.CitLoader;
import lib.minecraft.renderer.pipeline.loader.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.ItemDefinitionLoader;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.loader.TexturePackLoader;
import lib.minecraft.renderer.pipeline.pack.CitRule;
import lib.minecraft.renderer.pipeline.pack.ColorProperties;
import lib.minecraft.renderer.pipeline.resolver.PackResolver;
import lib.minecraft.renderer.pipeline.util.PackAcquirer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Orchestrates the end-to-end asset extraction flow: download the client jar through
 * {@link MojangContract}, extract the {@code minecraft/} subtrees, parse every model JSON, read
 * the texture catalogue, and hand the results back to the caller as a {@link Result} record.
 * <p>
 * All Mojang network access flows through a single lazily-initialised {@link Proxy} of
 * {@link MojangContract}, accessible to siblings in this module via {@link #mojang()}. The proxy
 * carries domain-aware rate limiting from the upstream module so concurrent callers
 * ({@link #run}, {@link #downloadJarToCache}, the player skin / cape paths) share the same
 * limiter state.
 */
public final class AssetPipeline {

    private static final @NotNull Gson MCMETA_GSON = GsonSettings.defaults().create();

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

        int targetPackFormat = options.getTargetPackFormat() > 0
            ? options.getTargetPackFormat()
            : readVanillaPackFormat(jarPath);

        TexturePack vanillaPack = PackResolver.resolve(packRoot, "vanilla", 0, targetPackFormat);

        ArrayList<TexturePack> ascendingPacks = new ArrayList<>();
        ascendingPacks.add(vanillaPack);

        for (int i = 0; i < options.getTexturePacks().size(); i++) {
            File source = options.getTexturePacks().get(i);
            int priority = i + 1;
            String packId = PackAcquirer.derivePackId(source);
            Path userRoot = PackAcquirer.materialize(source, options.getCacheRoot().toPath());
            ascendingPacks.add(PackResolver.resolve(userRoot, packId, priority, targetPackFormat));
        }

        ConcurrentList<TexturePack> ascending = Concurrent.adoptList(ascendingPacks).toUnmodifiable();
        ConcurrentList<Path> combinedRoots = combineRoots(ascending);

        ConcurrentMap<String, BlockModelData> blockModels = ModelResolver.loadBlockModels(combinedRoots);
        ConcurrentMap<String, ItemModelData> itemModels = ModelResolver.loadItemModels(combinedRoots);

        ConcurrentMap<String, Texture> textures = TexturePackLoader.scanTextures(ascending);
        ConcurrentMap<ColorMap.Type, ColorMap> colorMaps = ColorMapLoader.load();
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        BlockStateLoader.LoadResult blockStateResult = BlockStateLoader.load(combinedRoots);
        ConcurrentMap<String, String> itemDefinitions = ItemDefinitionLoader.load(combinedRoots);
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(combinedRoots);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(combinedRoots);

        ConcurrentList<TexturePack> packs = ascending.stream()
            .sorted(Comparator.comparingInt(TexturePack::getPriority).reversed())
            .collect(Concurrent.toList())
            .toUnmodifiable();

        ConcurrentMap<String, Integer> colorOverrides = collectColorOverrides(ascending);
        ConcurrentList<CitRule> citRules = collectCitRules(ascending);

        return new Result(packRoot, vanillaPack, packs, textures, colorMaps, blockTints, blockModels, itemModels,
            blockStateResult.getVariants(), blockStateResult.getMultiparts(), itemDefinitions, blockTags,
            potionEffectColors, bannerPatterns, colorOverrides, citRules);
    }

    /**
     * Walks every pack's asset roots in ascending-priority order, calling
     * {@link ColorProperties#loadFrom(Path)} per root and merging overrides with later-wins
     * semantics. Packs that ship no {@code optifine/color.properties} or
     * {@code mcpatcher/color.properties} contribute zero entries.
     */
    private static @NotNull ConcurrentMap<String, Integer> collectColorOverrides(@NotNull ConcurrentList<TexturePack> ascending) {
        HashMap<String, Integer> merged = new HashMap<>();
        for (TexturePack pack : ascending)
            for (Path root : pack.getAssetRoots())
                merged.putAll(ColorProperties.loadFrom(root).overrides());
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Walks every pack's asset roots in ascending-priority order, calling
     * {@link CitLoader#load(Path)} per root and concatenating the resulting rules. The combined
     * list is sorted by descending weight so the highest-priority rules appear first; ties
     * preserve their declaration order, which means later-priority packs naturally win on
     * weight-tied collisions.
     */
    private static @NotNull ConcurrentList<CitRule> collectCitRules(@NotNull ConcurrentList<TexturePack> ascending) {
        ArrayList<CitRule> rules = new ArrayList<>();
        for (TexturePack pack : ascending)
            for (Path root : pack.getAssetRoots())
                rules.addAll(CitLoader.load(root));
        rules.sort(Comparator.comparingInt(CitRule::weight).reversed());
        return Concurrent.adoptList(rules).toUnmodifiable();
    }

    /**
     * Reads the {@code pack_format} declared by the {@code pack.mcmeta} entry inside the cached
     * client jar. {@link #extractClientJar} only extracts the {@code assets/} and {@code data/}
     * subtrees, so the jar's root-level {@code pack.mcmeta} never lands on disk - this opens the
     * jar directly. Used as the default target pack format when callers don't override it,
     * keeping the renderer free of a hardcoded version-to-format mapping table.
     */
    private static int readVanillaPackFormat(@NotNull Path jarPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("pack.mcmeta");
            if (entry == null) return 0;
            try (InputStream in = zip.getInputStream(entry)) {
                String content = new String(in.readAllBytes());
                JsonObject root = MCMETA_GSON.fromJson(content, JsonObject.class);
                if (root == null || !root.has("pack") || !root.get("pack").isJsonObject()) return 0;
                JsonObject pack = root.getAsJsonObject("pack");
                if (pack.has("pack_format")) return pack.get("pack_format").getAsInt();
                if (pack.has("format")) return pack.get("format").getAsInt();
                return 0;
            }
        } catch (IOException | JsonSyntaxException ex) {
            return 0;
        }
    }

    /**
     * Concatenates every pack's asset roots in ascending-priority order. Loaders that don't need
     * pack attribution take this list directly so a higher-priority pack's overlay roots win
     * over lower-priority packs and earlier overlays via the loaders' later-wins merge.
     */
    private static @NotNull ConcurrentList<Path> combineRoots(@NotNull ConcurrentList<TexturePack> ascending) {
        ArrayList<Path> roots = new ArrayList<>();
        for (TexturePack pack : ascending)
            roots.addAll(pack.getAssetRoots());
        return Concurrent.adoptList(roots).toUnmodifiable();
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
        if (Files.isRegularFile(target) && !options.isForceDownload())
            return target;

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
        return mojang.getVersionMetadata(
            mojang.getVersionManifest()
                .getVersions()
                .stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new AssetPipelineException("Version '%s' is not in the Piston manifest", version))
            )
            .getDownloads()
            .getClient();
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
        return options.getCacheRoot()
            .toPath()
            .resolve("vanilla")
            .resolve(options.getVersion());
    }

    /**
     * The result of a single pipeline run.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Result {

        private final @NotNull Path packRoot;
        private final @NotNull TexturePack vanillaPack;

        /**
         * Every registered pack in render priority order - highest priority first. Always
         * contains the vanilla pack at minimum; user packs from
         * {@link AssetPipelineOptions#getTexturePacks()} appear before vanilla when present.
         * Each pack's {@link TexturePack#getAssetRoots()} carries the on-disk directories the
         * renderer walks when reading raw PNG bytes for a texture attributed to that pack.
         */
        private final @NotNull ConcurrentList<TexturePack> packs;

        private final @NotNull ConcurrentMap<String, Texture> textures;
        private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;
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

        /**
         * Pack-supplied colour overrides keyed by raw {@code optifine/color.properties} or
         * {@code mcpatcher/color.properties} property name (e.g. {@code grass.plains},
         * {@code redstone.0}). Higher-priority packs win on key collisions via the per-pack
         * later-wins merge in {@link #collectColorOverrides}.
         */
        private final @NotNull ConcurrentMap<String, Integer> colorOverrides;

        /**
         * Custom Item Texture rules parsed from every pack's
         * {@code optifine/cit/**} and {@code mcpatcher/cit/**} subtrees, sorted by descending
         * weight so the highest-priority rules appear first.
         */
        private final @NotNull ConcurrentList<CitRule> citRules;

    }

}
