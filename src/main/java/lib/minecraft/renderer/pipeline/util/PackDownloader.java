package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.PipelineException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Downloads resource pack archives from a remote URL into a local cache path. Skips the network
 * round trip when the destination file already exists and the caller has not requested a forced
 * refresh, mirroring {@code Pipeline.downloadJarToCache}'s caching semantics.
 */
@UtilityClass
public class PackDownloader {

    /**
     * Downloads {@code url} into {@code destination}, creating parent directories as needed.
     * Returns {@code destination} unchanged when it already exists as a regular file and
     * {@code force} is false.
     *
     * @param url the remote URL
     * @param destination the target file path on disk
     * @param force when true, re-download even if the destination already exists
     * @return the destination path
     */
    public static @NotNull Path download(@NotNull URL url, @NotNull Path destination, boolean force) {
        if (Files.isRegularFile(destination) && !force) return destination;

        try {
            Files.createDirectories(destination.getParent());
            try (InputStream stream = url.openStream()) {
                Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to download '%s' to '%s'", url, destination);
        }

        return destination;
    }

    /**
     * Convenience overload accepting a string URL. The string is parsed via {@link URI#create} and
     * converted to {@link URL} so callers don't need to handle the deprecated {@link URL} string
     * constructor.
     *
     * @param url the remote URL string
     * @param destination the target file path on disk
     * @param force when true, re-download even if the destination already exists
     * @return the destination path
     */
    public static @NotNull Path download(@NotNull String url, @NotNull Path destination, boolean force) {
        try {
            return download(URI.create(url).toURL(), destination, force);
        } catch (IOException | IllegalArgumentException ex) {
            throw new PipelineException(ex, "Invalid pack URL '%s'", url);
        }
    }

}
