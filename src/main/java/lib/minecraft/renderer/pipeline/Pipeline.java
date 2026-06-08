package lib.minecraft.renderer.pipeline;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.PistonMetadata;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.Lazy;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.Texture;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.loader.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.loader.BlockStateLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTagLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.CitLoader;
import lib.minecraft.renderer.pipeline.loader.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.CtmLoader;
import lib.minecraft.renderer.pipeline.loader.ItemDefinitionLoader;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.loader.TexturePackLoader;
import lib.minecraft.renderer.pipeline.pack.CitRule;
import lib.minecraft.renderer.pipeline.pack.ColorProperties;
import lib.minecraft.renderer.pipeline.pack.CtmRule;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
import lib.minecraft.renderer.pipeline.resolver.PackResolver;
import lib.minecraft.renderer.pipeline.util.PackAcquirer;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
@UtilityClass
public class Pipeline {

    private static final @NotNull Lazy<Proxy<MojangContract>> MOJANG_PROXY = Lazy.of(() ->
        Proxy.builder(
            ClientConfig.builder(MojangContract.class, GsonSettings.defaults())
                .withErrorDecoder(MojangApiException::new)
                .build()
        ).build()
    );

    /**
     * Runs the pipeline with the given options and returns the parsed result.
     *
     * @param options the pipeline options
     * @return the parsed asset result
     */
    public static @NotNull Result run(@NotNull PipelineOptions options) {
        Path packRoot = packRoot(options);
        Path jarPath = downloadJarToCache(options);
        extractClientJar(jarPath, packRoot);

        PackBundle packs = resolvePacks(options, packRoot);

        ConcurrentMap<String, ModelData> blockModels = ModelResolver.loadBlockModels(packs.combinedRoots());
        ConcurrentMap<String, ModelData> itemModels = ModelResolver.loadItemModels(packs.combinedRoots());

        ConcurrentMap<String, Texture> textures = TexturePackLoader.scanTextures(packs.ascending());
        ConcurrentMap<ColorMap.Type, ColorMap> colorMaps = ColorMapLoader.load();
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        BlockStateLoader.LoadResult blockStateResult = BlockStateLoader.load(packs.combinedRoots(), blockModels);
        ConcurrentMap<String, String> itemDefinitions = ItemDefinitionLoader.load(packs.combinedRoots());
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(packs.combinedRoots());
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(packs.combinedRoots());

        ConcurrentMap<String, Integer> colorOverrides = collectColorOverrides(packs.ascending());
        ConcurrentList<CitRule> citRules = collectCitRules(packs.ascending());
        ConcurrentList<CtmRule> ctmRules = collectCtmRules(packs.ascending());

        return new Result(packRoot, packs.vanilla(), packs.packsById(), textures, colorMaps, blockTints, blockModels, itemModels,
            blockStateResult.getVariants(), blockStateResult.getMultiparts(), itemDefinitions, blockTags,
            potionEffectColors, bannerPatterns, colorOverrides, citRules, ctmRules, blockStateResult.getDefaultStateKeys());
    }

    /**
     * Resolves the vanilla pack and every user-supplied pack into a single {@link PackBundle},
     * eagerly computing the four projections downstream loaders and collectors consume:
     * the vanilla {@link TexturePack} itself, the ascending-priority list, the id-keyed
     * render-priority map (for {@link Result#getPacks()}), and the flat list of every
     * pack's asset roots in ascending priority order (for loaders that don't need pack
     * attribution).
     * <p>
     * Each pack's overlay matching uses its own declared {@code pack_format} (read from the
     * pack's own {@code pack.mcmeta} by {@link PackResolver}) - vanilla matches its own format,
     * each user pack matches its own. A pack missing or with malformed mcmeta throws a
     * {@code PipelineException}; the renderer can't function against a broken jar or a
     * non-conforming user pack, so loud failure is the right call.
     * <p>
     * User packs are materialised through {@link PackAcquirer} (zip extraction with caching),
     * keyed by a sanitised id derived from the source filename, and assigned priorities {@code 1..N}
     * so they all win over vanilla (priority {@code 0}) on overlay merges.
     */
    private static @NotNull PackBundle resolvePacks(
        @NotNull PipelineOptions options,
        @NotNull Path vanillaPackRoot
    ) {
        TexturePack vanilla = PackResolver.resolve(vanillaPackRoot, "vanilla", 0);

        ArrayList<TexturePack> packs = new ArrayList<>();
        packs.add(vanilla);
        for (int i = 0; i < options.getTexturePacks().size(); i++) {
            File source = options.getTexturePacks().get(i);
            String packId = PackAcquirer.derivePackId(source);
            Path userRoot = PackAcquirer.materialize(source, options.getCacheRoot().toPath());
            packs.add(PackResolver.resolve(userRoot, packId, i + 1));
        }

        ConcurrentList<TexturePack> ascending = Concurrent.adoptList(packs).toUnmodifiable();

        // Index the packs by id in render-priority order (highest priority first) so
        // Result#getPacks() is a ready-to-use O(1) lookup: insertion-ordered, first-wins on a
        // duplicate id. Built here once so the renderer context consumes the map directly.
        ConcurrentMap<String, TexturePack> packsById = Concurrent.newLinkedMap();
        ascending.stream()
            .sorted(Comparator.comparingInt(TexturePack::getPriority).reversed())
            .forEachOrdered(pack -> packsById.putIfAbsent(pack.getId(), pack));

        ArrayList<Path> roots = new ArrayList<>();
        for (TexturePack pack : ascending)
            roots.addAll(pack.getAssetRoots());
        ConcurrentList<Path> combinedRoots = Concurrent.adoptList(roots).toUnmodifiable();

        return new PackBundle(vanilla, ascending, packsById.toUnmodifiable(), combinedRoots);
    }

    /**
     * Eager projection of the resolved pack stack into the four views downstream consumers need.
     * {@code ascending} drives per-pack walks (texture scan, CIT / CTM / colour collectors);
     * {@code combinedRoots} is the flattened root list every loader that doesn't need pack
     * attribution consumes; {@code packsById} is the render-priority, id-keyed view passed
     * through to {@link Result#getPacks()}; {@code vanilla} is the bundled minimum every pipeline
     * run carries.
     */
    private record PackBundle(
        @NotNull TexturePack vanilla,
        @NotNull ConcurrentList<TexturePack> ascending,
        @NotNull ConcurrentMap<String, TexturePack> packsById,
        @NotNull ConcurrentList<Path> combinedRoots
    ) {}

    /**
     * Walks every pack's asset roots in ascending-priority order, calling
     * {@link ColorProperties#loadFrom(Path)} per root and merging overrides with later-wins
     * semantics. Packs that ship no {@code optifine/color.properties} or
     * {@code mcpatcher/color.properties} contribute zero entries.
     */
    private static @NotNull ConcurrentMap<String, Integer> collectColorOverrides(@NotNull ConcurrentList<TexturePack> ascending) {
        HashMap<String, Integer> merged = new HashMap<>();

        for (TexturePack pack : ascending) {
            for (Path root : pack.getAssetRoots())
                merged.putAll(ColorProperties.loadFrom(root).overrides());
        }

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

        for (TexturePack pack : ascending) {
            for (Path root : pack.getAssetRoots())
                rules.addAll(CitLoader.load(root));
        }

        rules.sort(Comparator.comparingInt(CitRule::weight).reversed());
        return Concurrent.adoptList(rules).toUnmodifiable();
    }

    /**
     * Walks every pack's asset roots in ascending-priority order, calling
     * {@link CtmLoader#load(Path)} per root and concatenating the resulting rules. The combined
     * list is sorted by descending weight so the highest-priority rules appear first; ties
     * preserve their declaration order, which means later-priority packs naturally win on
     * weight-tied collisions.
     */
    private static @NotNull ConcurrentList<CtmRule> collectCtmRules(@NotNull ConcurrentList<TexturePack> ascending) {
        ArrayList<CtmRule> rules = new ArrayList<>();

        for (TexturePack pack : ascending) {
            for (Path root : pack.getAssetRoots())
                rules.addAll(CtmLoader.load(root));
        }

        rules.sort(Comparator.comparingInt(CtmRule::weight).reversed());
        return Concurrent.adoptList(rules).toUnmodifiable();
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
    public static @NotNull Path downloadJarToCache(@NotNull PipelineOptions options) {
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
            throw new PipelineException(ex, "Failed to cache client jar at '%s'", target);
        }

        return target;
    }

    /**
     * The lazily-initialised shared {@link MojangContract}. Single proxy per JVM via
     * {@link #MOJANG_PROXY}, so concurrent callers ({@link #run}, {@link #downloadJarToCache},
     * the player skin / cape paths in
     * {@link PlayerRenderer PlayerRenderer}) share the same domain-aware
     * rate limiter.
     *
     * @return the shared Mojang contract
     */
    public static @NotNull MojangContract mojang() {
        return MOJANG_PROXY.get().getContract();
    }

    /**
     * Resolves the {@code Piston} client-jar entry for the given version, surfacing an
     * {@link PipelineException} when the version id is missing from the manifest.
     */
    private static @NotNull PistonMetadata.Downloads.Entry resolveClientEntry(@NotNull MojangContract mojang, @NotNull String version) {
        return mojang.getVersionMetadata(
            mojang.getVersionManifest()
                .getVersions()
                .stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new PipelineException("Version '%s' is not in the Piston manifest", version))
            )
            .getDownloads()
            .getClient();
    }

    /**
     * Streams the {@code assets/minecraft/} and {@code data/minecraft/} subtrees plus the root
     * {@code pack.mcmeta} out of a cached client jar into {@code packRoot}. Skips
     * {@code .class} files, manifests, and other non-resource entries. Idempotent - safe to
     * re-run with the same {@code packRoot}.
     * <p>
     * The root mcmeta is included so {@link PackResolver} can resolve the vanilla pack the same
     * way it resolves user packs - reading the format and overlay entries from the extracted
     * tree rather than reaching back into the jar. Modern Mojang client jars (verified across
     * 1.21.4 and 26.1) no longer ship a root {@code pack.mcmeta} - the launcher synthesises one
     * from the jar's own {@code version.json} at runtime. To keep
     * {@link #run(PipelineOptions) Pipeline.run} working without external scaffolding, the same
     * zip-entry iteration that extracts the asset tree also captures {@code version.json}'s
     * bytes in memory (without writing them to disk); when no root {@code pack.mcmeta} was
     * streamed out, {@link #synthesiseVanillaPackMeta(byte[], Path)} reads
     * {@code pack_version.resource_major} from those bytes and writes a minimal mcmeta to
     * {@code packRoot}. Jars that still ship a real root mcmeta retain it unchanged (the
     * extracted file takes precedence over the synthetic fallback).
     * <p>
     * Public so tooling generators that need an extracted asset tree without running the parse
     * phases (e.g. {@code ToolingColorMaps} reading the biome colormap PNGs) can pair this with
     * {@link #downloadJarToCache(PipelineOptions)}.
     *
     * @param jarPath the cached client jar path
     * @param packRoot the destination pack root
     */
    public static void extractClientJar(@NotNull Path jarPath, @NotNull Path packRoot) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            boolean extractedRootMcmeta = false;
            byte[] versionJsonBytes = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                boolean isAssetTree = name.startsWith(VanillaSourcePaths.VANILLA_ASSET_ROOT)
                    || name.startsWith(VanillaSourcePaths.VANILLA_DATA_ROOT);
                boolean isRootMcmeta = name.equals("pack.mcmeta");
                boolean isVersionJson = name.equals("version.json");

                if (isVersionJson) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        versionJsonBytes = in.readAllBytes();
                    }
                    continue;
                }
                if (!isAssetTree && !isRootMcmeta) continue;

                Path destination = packRoot.resolve(name);
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                if (isRootMcmeta) extractedRootMcmeta = true;
            }

            if (!extractedRootMcmeta && versionJsonBytes != null) {
                synthesiseVanillaPackMeta(versionJsonBytes, packRoot);
            }
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to extract '%s' into '%s'", jarPath, packRoot);
        }
    }

    /**
     * Writes a minimal {@code pack.mcmeta} to {@code packRoot} from the supplied
     * {@code version.json} bytes - reads {@code pack_version.resource_major} for the
     * {@code pack_format} and the {@code name} field for a human-readable description.
     * No-op when the JSON is malformed or missing the required keys - downstream
     * {@link PackResolver#resolve} will then throw the usual missing-mcmeta error.
     * <p>
     * This mirrors what the Minecraft launcher does at runtime for vanilla resource packs in
     * recent versions, where {@code pack.mcmeta} was dropped as a static jar entry in favour
     * of launcher-side synthesis.
     *
     * @param versionJsonBytes the raw bytes of the jar's root {@code version.json}, captured
     *     during the single zip-entry iteration in {@link #extractClientJar}
     * @param packRoot the destination pack root
     * @throws IOException if writing the synthesised file fails
     */
    private static void synthesiseVanillaPackMeta(@NotNull byte[] versionJsonBytes, @NotNull Path packRoot) throws IOException {
        JsonObject versionJson;
        try {
            versionJson = MCMETA_GSON.fromJson(new String(versionJsonBytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonSyntaxException ex) {
            return;
        }
        if (versionJson == null || !versionJson.has("pack_version") || !versionJson.get("pack_version").isJsonObject()) return;
        JsonObject packVersion = versionJson.getAsJsonObject("pack_version");

        // Modern jars (26.1+) use {resource_major, resource_minor, data_major, data_minor};
        // legacy jars (verified on 1.21.4) use the flat {resource, data} shape. Prefer the
        // newer key when both are present.
        JsonElement formatElement;
        if (packVersion.has("resource_major")) formatElement = packVersion.get("resource_major");
        else if (packVersion.has("resource")) formatElement = packVersion.get("resource");
        else return;

        int packFormat;
        try {
            packFormat = formatElement.getAsInt();
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return;
        }

        String name = versionJson.has("name") && versionJson.get("name").isJsonPrimitive()
            ? versionJson.get("name").getAsString()
            : "vanilla";

        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("description", "Minecraft " + name + " vanilla resources (synthesised by asset-renderer Pipeline.extractClientJar)");
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);

        Path destination = packRoot.resolve("pack.mcmeta");
        Files.createDirectories(packRoot);
        Files.writeString(destination, MCMETA_GSON.toJson(mcmeta));
    }

    private static final @NotNull Gson MCMETA_GSON = GsonSettings.defaults().create();

    /**
     * The standard {@code <cacheRoot>/vanilla/<version>} pack-root path for the given options.
     */
    private static @NotNull Path packRoot(@NotNull PipelineOptions options) {
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
         * Every registered pack keyed by its {@link TexturePack#getId() id} for O(1) lookup, in
         * render priority order - {@code values()} iterate highest priority first. Always contains
         * the vanilla pack at minimum; user packs from {@link PipelineOptions#getTexturePacks()}
         * appear before vanilla when present; on a duplicate id the first (highest priority) pack
         * wins. Each pack's {@link TexturePack#getAssetRoots()} carries the on-disk directories the
         * renderer walks when reading raw PNG bytes for a texture attributed to that pack.
         */
        private final @NotNull ConcurrentMap<String, TexturePack> packs;

        private final @NotNull ConcurrentMap<String, Texture> textures;
        private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;
        private final @NotNull ConcurrentMap<String, Block.Tint> blockTints;
        private final @NotNull ConcurrentMap<String, ModelData> blockModels;
        private final @NotNull ConcurrentMap<String, ModelData> itemModels;
        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> blockVariants;
        private final @NotNull ConcurrentMap<String, Block.Multipart> blockMultiparts;
        private final @NotNull ConcurrentMap<String, String> itemDefinitions;
        private final @NotNull ConcurrentMap<String, BlockTag> blockTags;

        /**
         * Namespaced effect id to ARGB colour, parsed from {@code MobEffects} by the pipeline's potion colour loader.
         */
        private final @NotNull ConcurrentMap<String, Integer> potionEffectColors;

        /**
         * Namespaced banner pattern id to descriptor, parsed from {@code data/minecraft/banner_pattern/} by the banner pattern loader.
         */
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

        /**
         * Connected Textures rules parsed from every pack's
         * {@code optifine/ctm/**} and {@code mcpatcher/ctm/**} subtrees, sorted by descending
         * weight. Currently consumable via the renderer context's {@code resolveCtm} for tooling
         * and external callers; the renderer itself doesn't yet apply them.
         */
        private final @NotNull ConcurrentList<CtmRule> ctmRules;

        /**
         * Per-block canonical default-state key, from the bundled {@code block_defaults.json}
         * snapshot (parsed from the vanilla {@code Blocks} registry by {@code ToolingBlockDefaults}
         * and read by {@link BlockStateLoader}). Lets production callers resolve a block's default
         * variant without a harness sidecar. Empty-property blocks are absent.
         */
        private final @NotNull ConcurrentMap<String, String> blockDefaultStateKeys;

    }

}
