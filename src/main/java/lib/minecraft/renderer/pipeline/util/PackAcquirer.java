package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.AssetPipelineException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Materialises a user-supplied texture pack source into a local on-disk pack root. Directories
 * pass through unchanged; zip archives extract once into
 * {@code <cacheRoot>/packs/<sanitised-pack-id>/} and reuse the extracted tree on subsequent runs
 * unless the source zip is newer than the destination directory.
 * <p>
 * Extraction guards against zip-slip by rejecting any entry whose normalised destination escapes
 * the resolved pack root.
 */
@UtilityClass
public class PackAcquirer {

    private static final @NotNull Pattern UNSAFE_ID_CHARS = Pattern.compile("[^A-Za-z0-9._-]+");

    /**
     * Resolves a pack source to a usable extracted pack root.
     *
     * @param source the pack source; either a directory or a zip file
     * @param cacheRoot the renderer's cache root (zip extraction targets
     *     {@code <cacheRoot>/packs/<sanitised-id>/})
     * @return the extracted pack root path
     */
    public static @NotNull Path materialize(@NotNull File source, @NotNull Path cacheRoot) {
        if (source.isDirectory()) return source.toPath();
        if (!source.isFile())
            throw new AssetPipelineException("Pack source '%s' is neither a directory nor a regular file", source);

        String packId = derivePackId(source);
        Path destination = cacheRoot.resolve("packs").resolve(packId);

        if (Files.isDirectory(destination) && !needsReextract(source, destination)) return destination;

        try {
            Files.createDirectories(destination);
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to create pack extraction directory '%s'", destination);
        }

        extractZip(source, destination, packId);
        return destination;
    }

    /**
     * Derives a sanitised pack id from a zip filename, stripping the {@code .zip} extension and
     * replacing any character outside {@code [A-Za-z0-9._-]} with {@code _}.
     */
    public static @NotNull String derivePackId(@NotNull File source) {
        String name = source.getName();
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return UNSAFE_ID_CHARS.matcher(name).replaceAll("_");
    }

    /**
     * Decides whether the destination directory's contents are stale relative to the source zip.
     * Re-extracts when the destination is empty or the zip's last-modified time is newer than the
     * destination directory's last-modified time.
     */
    private static boolean needsReextract(@NotNull File source, @NotNull Path destination) {
        try (var entries = Files.list(destination)) {
            if (entries.findAny().isEmpty()) return true;
        } catch (IOException ex) {
            return true;
        }
        try {
            long sourceMillis = source.lastModified();
            long destMillis = Files.getLastModifiedTime(destination).toMillis();
            return sourceMillis > destMillis;
        } catch (IOException ex) {
            return true;
        }
    }

    /**
     * Streams every entry from a pack zip into the destination directory, mirroring the same
     * loop {@code AssetPipeline.extractClientJar} uses for the client jar. Directory entries are
     * skipped; regular entries are extracted via {@link Files#copy} with REPLACE_EXISTING.
     */
    private static void extractZip(@NotNull File source, @NotNull Path destination, @NotNull String packId) {
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination))
                    throw new AssetPipelineException("Pack '%s' contains a zip-slip entry '%s'", packId, entry.getName());
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to extract pack '%s' from '%s' into '%s'", packId, source, destination);
        }
    }

}
