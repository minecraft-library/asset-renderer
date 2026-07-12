package lib.minecraft.renderer.pipeline;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.PistonMetadata;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.Lazy;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.loader.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.loader.BlockStateLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTagLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.GlintItemsLoader;
import lib.minecraft.renderer.pipeline.loader.ItemDefinitionLoader;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.loader.TextureIndexer;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.rule.RuleSet;
import lib.minecraft.renderer.pipeline.resolver.ModelResolver;
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
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Orchestrates the end-to-end asset extraction flow: download the client jar through
 * {@link MojangContract}, extract the {@code minecraft/} subtrees, parse every model JSON, read
 * the texture catalogue, and hand the results back to the caller as a {@link Result} record.
 * <p>
 * All Mojang network access flows through a single lazily-initialised {@link Client} of
 * {@link MojangContract}, accessible to siblings in this module via {@link #mojang()}. The client
 * carries domain-aware rate limiting from the upstream module so concurrent callers
 * ({@link #run}, {@link #downloadJarToCache}, the player skin / cape paths) share the same
 * limiter state.
 * <p>
 * A plain {@link Client} is used rather than a subnet-rotating {@link Proxy}: the proxy's IPv6
 * subnet rotation only works on hosts that own a routable {@code /64} (specific Linux servers), and
 * {@code Proxy.build()} hard-requires a {@code withSubnetRotation} that would otherwise fail every
 * other environment. The client uses the default single subnet.
 */
@UtilityClass
public class Pipeline {

    /**
     * The single JVM-wide {@link Client} of {@link MojangContract}, lazily built on first access so
     * concurrent callers share one domain-aware rate limiter. Wraps errors through
     * {@link MojangApiException}. Exposed to siblings via {@link #mojang()}.
     */
    private static final @NotNull Lazy<Client<MojangContract>> MOJANG_CLIENT = Lazy.of(() ->
        Client.create(
            ClientConfig.builder(MojangContract.class, GsonSettings.defaults())
                .withErrorDecoder(MojangApiException::new)
                .build()
        )
    );

    /**
     * Runs the full extraction flow for the given options: downloads and caches the client jar,
     * extracts the vanilla asset tree, resolves the vanilla plus user pack stack, then fans out to
     * every domain loader (models, blockstates, textures, colormaps, tags, banner patterns, potion
     * colours) and merges the pack rule layer ({@link RuleSet#merge(PackStack)} - CIT, CTM, colour
     * overrides, {@code useGlint}), returning everything as a single {@link Result}.
     *
     * @param options the pipeline options
     * @return the parsed asset result
     * @throws PipelineException if the client jar cannot be downloaded, extracted, or a pack's mcmeta
     *     is missing or malformed
     */
    public static @NotNull Result run(@NotNull PipelineOptions options) {
        Path packRoot = packRoot(options);
        Path jarPath = downloadJarToCache(options);
        extractClientJar(jarPath, packRoot);

        List<Path> userSources = options.getTexturePacks().stream().map(File::toPath).toList();
        PackStack acquired = PackAcquisition.acquire(userSources, options.getCacheRoot().toPath(), packRoot);
        ConcurrentMap<ResourceId, IndexedTexture> textures = TextureIndexer.index(acquired);
        PackStack stack = acquired.withTextureIndex(textures);

        ConcurrentMap<String, ModelData> blockModels = ModelResolver.loadBlockModels(stack);
        ConcurrentMap<String, ModelData> itemModels = ModelResolver.loadItemModels(stack);

        ConcurrentMap<ColorMap.Type, ColorMap> colorMaps = ColorMapLoader.load(stack);
        ConcurrentMap<String, Block.Tint> blockTints = BlockTintsLoader.load();
        BlockStateLoader.LoadResult blockStateResult = BlockStateLoader.load(stack, blockModels);
        ConcurrentMap<String, String> itemDefinitions = ItemDefinitionLoader.load(stack);
        ConcurrentMap<String, List<LayerTint>> itemTints = ItemDefinitionLoader.loadTints(stack);
        ConcurrentSet<String> glintItems = GlintItemsLoader.load();
        ConcurrentMap<String, BlockTag> blockTags = BlockTagLoader.load(stack);
        ConcurrentMap<String, Integer> potionEffectColors = PotionColorLoader.load();
        ConcurrentMap<String, BannerPattern> bannerPatterns = BannerPatternLoader.load(stack);

        RuleSet rules = RuleSet.merge(stack);

        return new Result(packRoot, stack, colorMaps, blockTints, blockModels, itemModels,
            blockStateResult.getVariants(), blockStateResult.getMultiparts(), itemDefinitions, itemTints, glintItems, blockTags,
            potionEffectColors, bannerPatterns, rules, blockStateResult.getDefaultStateKeys());
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
     * @throws PipelineException if the version is absent from the Piston manifest or the download fails
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
     * The lazily-initialised shared {@link MojangContract}. Single client per JVM via
     * {@link #MOJANG_CLIENT}, so concurrent callers ({@link #run}, {@link #downloadJarToCache},
     * the player skin / cape paths in {@link PlayerRenderer}) share the same domain-aware
     * rate limiter.
     *
     * @return the shared Mojang contract
     */
    public static @NotNull MojangContract mojang() {
        return MOJANG_CLIENT.get().getContract();
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
     * The root mcmeta is included so {@link PackAcquisition} can build the vanilla pack the same
     * way it builds user packs - reading the format and overlay entries from the extracted
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
     * @throws PipelineException if the jar cannot be read or an extracted entry cannot be written
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
     * {@link PackAcquisition#acquire} will then read an empty {@code MCMeta} for the vanilla pack.
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

    /**
     * The {@link Gson} used to read {@code version.json} and write the synthesised {@code pack.mcmeta}
     * in {@link #synthesiseVanillaPackMeta}.
     */
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
     * The parsed output of a single {@link #run(PipelineOptions)}: every asset family the renderer
     * consumes, materialised into unmodifiable indexes plus the pack-root paths they were read from.
     * Wrapped into the production {@link RendererContext} by
     * {@link PipelineRendererContext#of(Result)}.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Result {

        /**
         * The {@code <cacheRoot>/vanilla/<version>} directory the vanilla client jar was extracted into.
         */
        private final @NotNull Path packRoot;

        /**
         * The resolved pack stack (vanilla at priority 0, user packs ascending) carrying the scanned
         * texture index. Owns the resolution rule the renderer consults through
         * {@link PipelineRendererContext}; replaces the former vanilla-pack / id-keyed-map / texture-map
         * projections.
         */
        private final @NotNull PackStack stack;

        /**
         * Biome colormap PNGs keyed by {@link ColorMap.Type} (grass, foliage, ...), loaded from the bundled snapshot.
         */
        private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;

        /**
         * Namespaced block id to {@link Block.Tint} descriptor, parsed from {@code BlockColors} by the block-tints loader.
         */
        private final @NotNull ConcurrentMap<String, Block.Tint> blockTints;

        /**
         * Model id to parsed block {@link ModelData}, resolved across the combined pack roots.
         */
        private final @NotNull ConcurrentMap<String, ModelData> blockModels;

        /**
         * Model id to parsed item {@link ModelData}, resolved across the combined pack roots.
         */
        private final @NotNull ConcurrentMap<String, ModelData> itemModels;

        /**
         * Block id to its blockstate variant map (variant selector key to {@link Block.Variant}),
         * from the vanilla blockstate JSON.
         */
        private final @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> blockVariants;

        /**
         * Block id to its {@link Block.Multipart} definition, for blocks whose blockstate JSON uses the multipart form.
         */
        private final @NotNull ConcurrentMap<String, Block.Multipart> blockMultiparts;

        /**
         * Item id to its model id, parsed from {@code assets/minecraft/items/} definition JSON.
         */
        private final @NotNull ConcurrentMap<String, String> itemDefinitions;

        /**
         * Per-item ordered {@link LayerTint} list parsed from {@code assets/minecraft/items/}
         * {@code model.tints[]} (array index = layer {@code tintindex}), for items that declare
         * tints. Read at item-index build time and attached to each {@link lib.minecraft.renderer.asset.Item}.
         */
        private final @NotNull ConcurrentMap<String, List<LayerTint>> itemTints;

        /**
         * Namespaced ids of items vanilla registers with {@code enchantment_glint_override = true}
         * (the always-foil items: enchanted_book, written_book, enchanted_golden_apple,
         * experience_bottle, nether_star, debug_stick, end_crystal), parsed from {@code Items} by
         * the glint-item loader. Read at item-index build time and surfaced as
         * {@link lib.minecraft.renderer.asset.Item#alwaysGlinted()}.
         */
        private final @NotNull ConcurrentSet<String> glintItems;

        /**
         * Namespaced tag id to {@link BlockTag} membership, parsed from {@code data/minecraft/tags/block/} across the pack stack.
         */
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
         * The merged pack rule payload - CIT rules (walked first-match-wins at render), CTM rules
         * (parse-and-store; CTM renders nothing), per-key colour overrides, and the global
         * {@code useGlint} - folded across the pack stack by {@link RuleSet#merge(PackStack)}. Empty of
         * rules for a vanilla-only stack, which ships no {@code optifine/} tree.
         */
        private final @NotNull RuleSet rules;

        /**
         * Per-block canonical default-state key, from the bundled {@code block_defaults.json}
         * snapshot (parsed from the vanilla {@code Blocks} registry by {@code ToolingBlockDefaults}
         * and read by {@link BlockStateLoader}). Lets production callers resolve a block's default
         * variant without a harness sidecar. Empty-property blocks are absent.
         */
        private final @NotNull ConcurrentMap<String, String> blockDefaultStateKeys;

    }

}
