package dev.sbs.renderer.pipeline.client;

import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A downloader that fetches the Minecraft client jar to the local cache root, mapping a version
 * id to a direct Mojang piston-data URL.
 * <p>
 * The initial build supports a single hardcoded version (26.1, the first deobfuscated build).
 * When the project extracts a proper {@code MojangContract} Feign client later, this class will
 * delegate to that client for version-manifest-based resolution and the hardcoded URL table
 * will be removed. Downloads are delegated to the shared {@link HttpFetcher} and cached locally
 * so subsequent pipeline runs skip the network round-trip.
 *
 * @see HttpFetcher
 * @see ClientJarExtractor
 */
@UtilityClass
public class ClientJarDownloader {

    private static final @NotNull Map<String, String> KNOWN_URLS = Map.of(
        "26.1", "https://piston-data.mojang.com/v1/objects/191771837687b766537a8c4607cb6fad79c533a1/client.jar"
    );

    /**
     * Resolves the client jar path for the given options, downloading it through the shared
     * {@link HttpFetcher} when the cached copy is absent or the caller explicitly asked for a
     * fresh download.
     *
     * @param options the pipeline options
     * @param fetcher the HTTP fetcher instance
     * @return the path to the cached client jar
     * @throws AssetPipelineException if the version is unknown or the download fails
     */
    public static @NotNull Path download(@NotNull AssetPipelineOptions options, @NotNull HttpFetcher fetcher) {
        String url = KNOWN_URLS.get(options.getVersion());
        if (url == null)
            throw new AssetPipelineException("Unknown Minecraft version '%s' - add it to the KNOWN_URLS table or wait for the MojangContract integration", options.getVersion());

        Path destination = options.getCacheRoot().toPath().resolve("vanilla").resolve(options.getVersion()).resolve("client.jar");
        if (!options.isForceDownload() && Files.isRegularFile(destination))
            return destination;

        fetcher.download(url, destination);
        return destination;
    }

}
