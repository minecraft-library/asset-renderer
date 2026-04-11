package dev.sbs.renderer.pipeline.client;

import dev.sbs.renderer.exception.HttpFetchException;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * A direct URL fetcher that wraps {@link HttpClient} for {@code GET} requests, providing both
 * byte-array and streaming-to-file download modes.
 * <p>
 * Every network call in the renderer module goes through this helper. When the project later
 * extracts a proper {@code MojangContract}-style Feign client to a standalone library, this
 * class is the only one that has to change - the two call sites
 * ({@link ClientJarDownloader} and {@link dev.sbs.renderer.EntityRenderer EntityRenderer} skin
 * fetch) can drop in the new client without renaming.
 *
 * @see ClientJarDownloader
 */
@Getter
public final class HttpFetcher {

    private final @NotNull HttpClient client;

    /**
     * Creates a fetcher with sensible defaults: follows redirects, 30-second connect timeout.
     */
    public HttpFetcher() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Downloads the full response body at the given absolute URL.
     *
     * @param url the absolute URL
     * @return the response body bytes
     * @throws HttpFetchException if the request fails or the status is not 2xx
     */
    public byte @NotNull [] get(@NotNull String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();

        try {
            HttpResponse<byte[]> response = this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2)
                throw new HttpFetchException("GET '%s' returned status %d", url, response.statusCode());
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HttpFetchException(ex, "Interrupted while fetching '%s'", url);
        } catch (Exception ex) {
            if (ex instanceof HttpFetchException http) throw http;
            throw new HttpFetchException(ex, "Failed to fetch '%s'", url);
        }
    }

    /**
     * Streams the response body at the given URL directly into a file, creating parent
     * directories as needed.
     *
     * @param url the absolute URL
     * @param destination the file to write to
     * @throws HttpFetchException if the request fails or the status is not 2xx
     */
    public void download(@NotNull String url, @NotNull Path destination) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build();

        try {
            Files.createDirectories(destination.getParent());
            HttpResponse<InputStream> response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2)
                throw new HttpFetchException("GET '%s' returned status %d", url, response.statusCode());

            try (InputStream body = response.body()) {
                Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HttpFetchException(ex, "Interrupted while downloading '%s'", url);
        } catch (Exception ex) {
            if (ex instanceof HttpFetchException http) throw http;
            throw new HttpFetchException(ex, "Failed to download '%s' to '%s'", url, destination);
        }
    }

}
