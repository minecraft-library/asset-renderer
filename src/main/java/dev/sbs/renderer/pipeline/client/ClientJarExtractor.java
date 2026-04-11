package dev.sbs.renderer.pipeline.client;

import dev.sbs.renderer.exception.AssetPipelineException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An extractor that unzips the {@code assets/minecraft/**} and {@code data/minecraft/**}
 * subtrees of a Minecraft client jar into a cache directory, skipping classes and other
 * non-resource entries.
 * <p>
 * The extractor is idempotent: if the destination tree already contains files, the caller can
 * either delete them first or trust the extractor to overwrite them via
 * {@link StandardCopyOption#REPLACE_EXISTING}. Only resource entries under the two known
 * subtrees are extracted - all {@code .class} files, manifests, and other jar metadata are
 * ignored.
 *
 * @see ClientJarDownloader
 */
@UtilityClass
public class ClientJarExtractor {

    private static final @NotNull String[] SUBTREES = { "assets/minecraft/", "data/minecraft/" };

    /**
     * Extracts the relevant subtrees of the client jar into the pack root directory.
     *
     * @param jarPath the client jar file path
     * @param packRoot the destination pack root (typically {@code cache/vanilla/<version>})
     * @throws AssetPipelineException if the extraction fails for any I/O reason
     */
    public static void extract(@NotNull Path jarPath, @NotNull Path packRoot) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                boolean keep = false;
                for (String subtree : SUBTREES) {
                    if (name.startsWith(subtree)) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) continue;

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

}
