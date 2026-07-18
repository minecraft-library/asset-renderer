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
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.Lazy;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.MCMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Client-jar acquisition: downloads the target version's client jar through {@link MojangContract}
 * and extracts the {@code assets/} + {@code data/} subtrees into the cache, handing back the
 * {@link ClientAssets} (options + extracted vanilla root) that {@code PackAcquisition} compiles into a
 * {@code PackStack}. The jar path never escapes {@link #acquire} - callers see only the extracted tree.
 * <p>
 * All Mojang network access flows through a single lazily-initialised {@link Client} of
 * {@link MojangContract}, accessible to siblings in this module via {@link #mojang()}. The client
 * carries domain-aware rate limiting from the upstream module so concurrent callers
 * ({@link #acquire}, {@link #downloadJarToCache}, the player skin / cape paths) share the same
 * limiter state.
 * <p>
 * A plain {@link Client} is used rather than a subnet-rotating {@link Proxy}: the proxy's IPv6
 * subnet rotation only works on hosts that own a routable {@code /64} (specific Linux servers), and
 * {@code Proxy.build()} hard-requires a {@code withSubnetRotation} that would otherwise fail every
 * other environment. The client uses the default single subnet.
 */
@UtilityClass
public class ClientAcquisition {

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
     * The {@link Gson} used to read {@code version.json} and write the synthesised {@code pack.mcmeta}
     * in {@link #synthesiseVanillaPackMeta}.
     */
    private static final @NotNull Gson MCMETA_GSON = GsonSettings.defaults().create();

    /**
     * Downloads and extracts the client jar for the given options, returning the client assets the
     * pack compiler consumes. The cached jar path is a local of this method and never escapes it.
     *
     * @param options the client options (target version + cache root)
     * @return the extracted client assets - the options plus the vanilla pack root
     * @throws PipelineException if the client jar cannot be downloaded or extracted
     */
    public static @NotNull ClientAssets acquire(@NotNull ClientOptions options) {
        Path vanillaRoot = options.vanillaRoot();
        Path jarPath = downloadJarToCache(options);
        extractClientJar(jarPath, vanillaRoot);
        return new ClientAssets(options, vanillaRoot);
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
     * @param options the client options
     * @return the path to the cached client jar
     * @throws PipelineException if the version is absent from the Piston manifest or the download fails
     */
    public static @NotNull Path downloadJarToCache(@NotNull ClientOptions options) {
        Path target = options.vanillaRoot().resolve("client.jar");
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
     * {@link #MOJANG_CLIENT}, so concurrent callers ({@link #acquire}, {@link #downloadJarToCache},
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
     * The root mcmeta is included so {@code PackAcquisition} can build the vanilla pack the same
     * way it builds user packs - reading the format and overlay entries from the extracted
     * tree rather than reaching back into the jar. Modern Mojang client jars (verified across
     * 1.21.4 and 26.1) no longer ship a root {@code pack.mcmeta} - the launcher synthesises one
     * from the jar's own {@code version.json} at runtime. To keep {@link #acquire} working without
     * external scaffolding, the same zip-entry iteration that extracts the asset tree also captures
     * {@code version.json}'s bytes in memory (without writing them to disk); when no root
     * {@code pack.mcmeta} was streamed out, {@link #synthesiseVanillaPackMeta(byte[], Path)} reads
     * {@code pack_version.resource_major} from those bytes and writes a minimal mcmeta to
     * {@code packRoot}. Jars that still ship a real root mcmeta retain it unchanged (the
     * extracted file takes precedence over the synthetic fallback).
     *
     * @param jarPath the cached client jar path
     * @param packRoot the destination pack root
     * @throws PipelineException if the jar cannot be read or an extracted entry cannot be written
     */
    static void extractClientJar(@NotNull Path jarPath, @NotNull Path packRoot) {
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

            if (!extractedRootMcmeta && versionJsonBytes != null)
                synthesiseVanillaPackMeta(versionJsonBytes, packRoot);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to extract '%s' into '%s'", jarPath, packRoot);
        }
    }

    /**
     * Writes a minimal {@code pack.mcmeta} to {@code packRoot} from the supplied
     * {@code version.json} bytes - reads {@code pack_version.resource_major} for the
     * {@code pack_format} and the {@code name} field for a human-readable description.
     * No-op when the JSON is malformed or missing the required keys - downstream
     * {@code PackAcquisition.acquire} will then read an empty {@code MCMeta} for the vanilla pack.
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
    private static void synthesiseVanillaPackMeta(byte @NotNull [] versionJsonBytes, @NotNull Path packRoot) throws IOException {
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

}
