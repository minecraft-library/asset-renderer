package lib.minecraft.renderer.content.client;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.PistonMetadata;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Client-jar acquisition: downloads the target version's client jar through {@link MojangContract} into
 * the cache and hands back the {@link ClientAssets} - the options plus the vanilla pack - that
 * {@code PackAcquisition} compiles into a {@code PackStack}. The vanilla pack is the jar's
 * {@code assets/minecraft} and {@code data/minecraft} trees, read into memory by default, or extracted
 * to {@link ClientOptions#vanillaPackRoot()} and read from there when the options ask for an
 * extraction.
 * <p>
 * All Mojang network access flows through a single lazily-initialised {@link Client} of
 * {@link MojangContract}, accessible to siblings in this module via {@link #mojang()}. The client
 * carries domain-aware rate limiting from the upstream module so concurrent callers
 * ({@link #acquire}, {@link #downloadJarToCache}, the player skin / cape paths) share the same
 * limiter state.
 */
@UtilityClass
public class ClientAcquisition {

    /**
     * The {@link Gson} used to read {@code version.json} and write the synthesised {@code pack.mcmeta}
     * in {@link #vanillaPackMeta}.
     */
    private static final @NotNull Gson MCMETA_GSON = GsonSettings.defaults().create();

    /** The root entry a pack declares its format in. */
    private static final @NotNull String PACK_MCMETA = "pack.mcmeta";

    /** The root entry a modern client jar declares its pack versions in, in place of a pack.mcmeta. */
    private static final @NotNull String VERSION_JSON = "version.json";

    /**
     * Downloads the client jar for the given options and opens its vanilla pack - read into memory, or
     * extracted to disk and read from there when the options ask for an extraction.
     *
     * @param options the client options (target version, cache root, extraction)
     * @return the client assets - the options plus the vanilla pack
     * @throws ClientException if the version is absent from the Piston manifest, or the client jar
     *     cannot be cached, read or extracted
     * @throws MojangApiException if the Mojang API fails a request for the manifest, the version
     *     metadata or the jar
     */
    public static @NotNull ClientAssets acquire(@NotNull ClientOptions options) {
        Path jarPath = downloadJarToCache(options);
        if (!options.isExtractAssets())
            return new ClientAssets(options, readVanillaPack(jarPath));

        extractClientJar(jarPath, options.vanillaPackRoot());
        return new ClientAssets(options, new PackContainer.Directory(options.vanillaPackRoot()));
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
     * @throws ClientException if the version is absent from the Piston manifest or the jar cannot be
     *     written to the cache
     * @throws MojangApiException if the Mojang API fails a request for the manifest, the version
     *     metadata or the jar
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
            throw new ClientException(ex, "Failed to cache client jar at '%s'", target);
        }

        return target;
    }

    /**
     * The lazily-initialised shared {@link MojangContract}. Single client per JVM via
     * {@link MojangClient}, so concurrent callers ({@link #acquire}, {@link #downloadJarToCache},
     * the player skin / cape paths in {@code PlayerRenderer}) share the same domain-aware
     * rate limiter.
     *
     * @return the shared Mojang contract
     */
    public static @NotNull MojangContract mojang() {
        return MojangClient.INSTANCE.getContract();
    }

    /**
     * Holds the single JVM-wide {@link Client} of {@link MojangContract}. The JVM initialises this
     * class on the first {@link #mojang()} call, so the client is built once, on first access, and
     * concurrent callers share one domain-aware rate limiter. Errors are wrapped through
     * {@link MojangApiException}.
     */
    private static final class MojangClient {

        private static final @NotNull Client<MojangContract> INSTANCE = Client.create(
            ClientConfig.builder(MojangContract.class, GsonSettings.defaults())
                .withErrorDecoder(MojangApiException::new)
                .build()
        );

    }

    /**
     * Resolves the {@code Piston} client-jar entry for the given version, surfacing an
     * {@link ClientException} when the version id is missing from the manifest.
     */
    private static @NotNull PistonMetadata.Downloads.Entry resolveClientEntry(@NotNull MojangContract mojang, @NotNull String version) {
        return mojang.getVersionMetadata(
            mojang.getVersionManifest()
                .getVersions()
                .stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new ClientException("Version '%s' is not in the Piston manifest", version))
            )
            .getDownloads()
            .getClient();
    }

    /**
     * Answers whether a client jar entry belongs to the vanilla pack - the {@code assets/minecraft/}
     * and {@code data/minecraft/} trees and the root {@code pack.mcmeta}, and nothing else the jar
     * carries.
     *
     * @param name the entry's name
     * @return {@code true} when the entry is part of the vanilla pack
     */
    public static boolean isVanillaPackEntry(@NotNull String name) {
        return name.startsWith(VanillaPaths.VANILLA_ASSET_ROOT)
            || name.startsWith(VanillaPaths.VANILLA_DATA_ROOT)
            || name.equals(PACK_MCMETA);
    }

    /**
     * Reads the vanilla pack out of a client jar into memory - every entry {@link #isVanillaPackEntry}
     * keeps, inflated, plus the {@code pack.mcmeta} synthesised from {@code version.json} when the jar
     * ships none. These are the same paths and bytes {@link #extractClientJar} writes to disk.
     *
     * @param jarPath the cached client jar path
     * @return the vanilla pack, held in memory
     * @throws ClientException if the jar cannot be read
     */
    public static @NotNull PackContainer.Live readVanillaPack(@NotNull Path jarPath) {
        Map<String, byte[]> entries = new HashMap<>();
        byte[] versionJson = null;

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                if (name.equals(VERSION_JSON)) versionJson = read(zip, entry);
                else if (isVanillaPackEntry(name)) entries.put(name, read(zip, entry));
            }
        } catch (IOException ex) {
            throw new ClientException(ex, "Failed to read client jar '%s'", jarPath);
        }

        if (!entries.containsKey(PACK_MCMETA) && versionJson != null)
            vanillaPackMeta(versionJson).ifPresent(meta -> entries.put(PACK_MCMETA, meta));

        return PackContainer.Live.of(jarPath, entries);
    }

    /**
     * Streams the {@code assets/minecraft/} and {@code data/minecraft/} subtrees plus the root
     * {@code pack.mcmeta} out of a cached client jar into {@code packRoot}. Skips
     * {@code .class} files, manifests, and other non-resource entries. Idempotent - safe to
     * re-run with the same {@code packRoot}, and cheap to re-run as well: an entry already on disk
     * at the size the jar declares for it is left alone, so a second run over a populated root costs
     * a stat per entry rather than a copy of the whole asset tree.
     * <p>
     * The root mcmeta is included so {@code PackAcquisition} can build the vanilla pack the same
     * way it builds user packs - reading the format and overlay entries from the extracted
     * tree rather than reaching back into the jar. Modern Mojang client jars (verified across
     * 1.21.4 and 26.1) no longer ship a root {@code pack.mcmeta} - the launcher synthesises one
     * from the jar's own {@code version.json} at runtime. The same zip-entry iteration that extracts
     * the asset tree also captures {@code version.json}'s bytes in memory (without writing them to
     * disk); when no root {@code pack.mcmeta} was streamed out, {@link #vanillaPackMeta(byte[])}
     * builds a minimal mcmeta from them, which is written to {@code packRoot} unless the file there
     * already holds those exact bytes. Jars that still ship a real root mcmeta retain it unchanged
     * (the extracted file takes precedence over the synthetic fallback).
     *
     * @param jarPath the cached client jar path
     * @param packRoot the destination pack root
     * @throws ClientException if the jar cannot be read or an extracted entry cannot be written
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

                if (name.equals(VERSION_JSON)) {
                    versionJsonBytes = read(zip, entry);
                    continue;
                }
                if (!isVanillaPackEntry(name)) continue;

                Path destination = packRoot.resolve(name);
                // Before the skip, not after: a warm root whose jar ships a real mcmeta still has to
                // record that it was extracted, or the synthesis below would overwrite the real one
                // with a synthetic one on every run but the first.
                if (name.equals(PACK_MCMETA)) extractedRootMcmeta = true;
                if (alreadyExtracted(entry, destination)) continue;

                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (!extractedRootMcmeta && versionJsonBytes != null)
                writeVanillaPackMeta(versionJsonBytes, packRoot);
        } catch (IOException ex) {
            throw new ClientException(ex, "Failed to extract '%s' into '%s'", jarPath, packRoot);
        }
    }

    /** Reads one entry of an open jar whole. */
    private static byte @NotNull [] read(@NotNull ZipFile zip, @NotNull ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * Answers whether an entry is already on disk whole.
     *
     * <p>The extraction copies each entry byte for byte, so the uncompressed size the jar declares
     * is exactly the size the extracted file carries, and a match is an identity rather than a
     * guess. An entry whose size the jar declines to declare is copied, because nothing was
     * compared; so is one whose size cannot be read, since a file that will not answer is not a file
     * to trust.
     *
     * @param entry the jar entry
     * @param destination where the entry extracts to
     * @return {@code true} when the file already holds that entry's bytes
     */
    private static boolean alreadyExtracted(@NotNull ZipEntry entry, @NotNull Path destination) {
        long declared = entry.getSize();
        if (declared < 0 || !Files.isRegularFile(destination)) return false;

        try {
            return Files.size(destination) == declared;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Writes the synthesised {@code pack.mcmeta} into an extracted root, leaving a file that already
     * holds those exact bytes untouched - so a warm root costs one read rather than a write per run,
     * and a stale file, written by a build whose synthesis differed, is still replaced.
     *
     * @param versionJsonBytes the raw bytes of the jar's root {@code version.json}
     * @param packRoot the destination pack root
     * @throws IOException if the file cannot be read or written
     */
    private static void writeVanillaPackMeta(byte @NotNull [] versionJsonBytes, @NotNull Path packRoot) throws IOException {
        Optional<byte[]> mcmeta = vanillaPackMeta(versionJsonBytes);
        if (mcmeta.isEmpty()) return;

        Path destination = packRoot.resolve(PACK_MCMETA);
        if (Files.isRegularFile(destination) && Arrays.equals(Files.readAllBytes(destination), mcmeta.get())) return;

        Files.createDirectories(packRoot);
        Files.write(destination, mcmeta.get());
    }

    /**
     * Builds a minimal {@code pack.mcmeta} from the supplied {@code version.json} bytes - reads
     * {@code pack_version.resource_major} and {@code resource_minor} into a {@code min_format} /
     * {@code max_format} range mirroring vanilla's builtin pack ({@code PackFormat.minorRange}):
     * {@code min_format} is the exact {@code [major, minor]} so the resolved renderer target carries
     * the full {@code major.minor} (a bare {@code pack_format} int would floor the minor to
     * {@code 0}), and {@code max_format} is the bare major, widening to that major's highest minor.
     * The {@code name} field supplies a human-readable description. Empty when the JSON is malformed
     * or missing the required keys - downstream {@code PackAcquisition.acquire} then reads an empty
     * {@code MCMeta} for the vanilla pack.
     * <p>
     * This mirrors what the Minecraft launcher does at runtime for vanilla resource packs in
     * recent versions, where {@code pack.mcmeta} was dropped as a static jar entry in favour
     * of launcher-side synthesis.
     *
     * @param versionJsonBytes the raw bytes of the jar's root {@code version.json}
     * @return the mcmeta's UTF-8 bytes, or empty when the version file declares no pack format
     */
    private static @NotNull Optional<byte[]> vanillaPackMeta(byte @NotNull [] versionJsonBytes) {
        JsonObject versionJson;
        try {
            versionJson = MCMETA_GSON.fromJson(new String(versionJsonBytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonSyntaxException ex) {
            return Optional.empty();
        }
        if (versionJson == null || !versionJson.has("pack_version") || !versionJson.get("pack_version").isJsonObject())
            return Optional.empty();
        JsonObject packVersion = versionJson.getAsJsonObject("pack_version");

        // Modern jars (26.1+) use {resource_major, resource_minor, data_major, data_minor}; legacy
        // jars (verified on 1.21.4) use the flat {resource, data} shape with no minor. Prefer the
        // newer key when both are present.
        JsonElement majorElement;
        if (packVersion.has("resource_major")) majorElement = packVersion.get("resource_major");
        else if (packVersion.has("resource")) majorElement = packVersion.get("resource");
        else return Optional.empty();

        int major;
        try {
            major = majorElement.getAsInt();
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return Optional.empty();
        }
        int minor = readMinor(packVersion);

        String name = versionJson.has("name") && versionJson.get("name").isJsonPrimitive()
            ? versionJson.get("name").getAsString()
            : "vanilla";

        // Mirror vanilla's builtin pack metadata (PackFormat.minorRange): min_format is the exact
        // current version (major.minor) - the value the renderer target resolves against, matching the
        // client's getPackVersion() used for overlay activation - and max_format is the bare major,
        // widening to that major's highest minor. For 26.1 (minor 0) this is (84.0)..(84.MAX), the same
        // range a bare pack_format: 84 already produced.
        JsonObject pack = new JsonObject();
        pack.add("min_format", formatArray(major, minor));
        pack.addProperty("max_format", major);
        pack.addProperty("description", "Minecraft " + name + " vanilla resources (synthesised by asset-renderer ClientAcquisition)");
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);

        return Optional.of(MCMETA_GSON.toJson(mcmeta).getBytes(StandardCharsets.UTF_8));
    }

    /** Builds a {@code [major, minor]} pack-format array for the {@code min_format} key. */
    private static @NotNull JsonArray formatArray(int major, int minor) {
        JsonArray array = new JsonArray();
        array.add(major);
        array.add(minor);
        return array;
    }

    /** The {@code resource_minor} from a modern {@code pack_version}, or {@code 0} for legacy flat shapes, absent, or non-numeric values. */
    private static int readMinor(@NotNull JsonObject packVersion) {
        JsonElement minor = packVersion.get("resource_minor");
        if (minor == null || !minor.isJsonPrimitive()) return 0;
        try {
            return minor.getAsInt();
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return 0;
        }
    }

}
