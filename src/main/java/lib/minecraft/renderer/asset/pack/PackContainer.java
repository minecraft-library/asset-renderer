package lib.minecraft.renderer.asset.pack;

import lib.minecraft.renderer.asset.pack.cats.CatsIndex;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only byte access over one pack, orthogonal to the pack's content capabilities.
 *
 * <p>Three container kinds answer the same two questions - "what entries exist" and "give me these
 * bytes": an exploded {@link Directory}, a plain {@link Zip}, and a Catharsis {@link Cats} archive
 * (a bare {@code .cats} or a {@code .cats.zip} that wraps one). Paths are always {@code /}-separated
 * and relative to the pack root with no leading slash. {@link #detect} sniffs the source by content -
 * never by filename alone - so a correctly-built pack loads whatever its extension, and an
 * unrecognised file fails loudly rather than degrading to a broken read.
 */
public sealed interface PackContainer permits PackContainer.Directory, PackContainer.Zip, PackContainer.Cats {

    /**
     * Reads the bytes of one entry.
     *
     * @param path the {@code /}-separated entry path
     * @return the entry's bytes, or empty when no such entry exists
     */
    @NotNull Optional<byte[]> bytes(@NotNull String path);

    /**
     * Enumerates every file entry whose path starts with a prefix.
     *
     * @param prefix the {@code /}-separated path prefix ({@code ""} for the whole container)
     * @return the matching entry paths, {@code /}-separated and root-relative
     */
    @NotNull Stream<String> entries(@NotNull String prefix);

    /**
     * Whether a file entry exists at a path.
     *
     * @param path the {@code /}-separated entry path
     * @return {@code true} when an entry exists there
     */
    boolean exists(@NotNull String path);

    /**
     * Detects the container kind of a pack source by content and builds the matching container.
     *
     * <p>A directory becomes {@link Directory}; otherwise the leading bytes decide: {@code "CATS"}
     * magic is a bare {@link Cats}; a {@code PK} zip is probed for a {@code pack.cats} entry - present
     * means a wrapped {@link Cats} (inner {@code pack.mcmeta} authoritative, outer decoy as fallback),
     * absent means a plain {@link Zip}. Anything else is a hard, file-naming error.
     *
     * @param source the pack source path
     * @return the detected container
     * @throws PipelineException if the source is neither a directory nor a recognised archive
     */
    static @NotNull PackContainer detect(@NotNull Path source) {
        if (Files.isDirectory(source)) return new Directory(source);
        if (!Files.isRegularFile(source))
            throw new PipelineException("Pack source '%s' is neither a directory nor a regular file", source);

        byte[] head = readHead(source);
        if (head.length >= 4 && head[0] == 0x43 && head[1] == 0x41 && head[2] == 0x54 && head[3] == 0x53)
            return new Cats(source, CatsIndex.decode(readAll(source), Optional.empty()));
        if (head.length >= 2 && head[0] == 0x50 && head[1] == 0x4B)
            return detectZip(source);
        throw new PipelineException("Pack source '%s' is not a directory, zip, or CATS container", source);
    }

    /** Probes a {@code PK} zip for a {@code pack.cats} entry, unwrapping it to a {@link Cats} or falling back to {@link Zip}. */
    private static @NotNull PackContainer detectZip(@NotNull Path source) {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry cats = zip.getEntry("pack.cats");
            if (cats == null) return new Zip(source);
            byte[] catsBytes = readEntry(zip, cats);
            ZipEntry decoy = zip.getEntry("pack.mcmeta");
            Optional<byte[]> outerMcmeta = decoy == null ? Optional.empty() : Optional.of(readEntry(zip, decoy));
            return new Cats(source, CatsIndex.decode(catsBytes, outerMcmeta));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read pack zip '%s'", source);
        }
    }

    private static byte @NotNull [] readEntry(@NotNull ZipFile zip, @NotNull ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte @NotNull [] readHead(@NotNull Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            return in.readNBytes(4);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read pack source '%s'", source);
        }
    }

    private static byte @NotNull [] readAll(@NotNull Path source) {
        try {
            return Files.readAllBytes(source);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read pack source '%s'", source);
        }
    }

    /**
     * Directory-boundary prefix match shared by the flat-path containers, so a zipped and an exploded
     * copy of the same pack enumerate identically: a non-empty prefix matches only entries strictly
     * under that directory, never a sibling whose name merely starts with the prefix string
     * ({@code assets/minecraft} must not swallow {@code assets/minecraft_hd/...}).
     */
    private static boolean underPrefix(@NotNull String path, @NotNull String prefix) {
        if (prefix.isEmpty()) return true;
        String dir = prefix.endsWith("/") ? prefix : prefix + "/";
        return path.startsWith(dir);
    }

    /**
     * An exploded pack directory on disk.
     *
     * @param root the pack root directory
     */
    record Directory(@NotNull Path root) implements PackContainer {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<byte[]> bytes(@NotNull String path) {
            Path file = this.root.resolve(path);
            if (!Files.isRegularFile(file)) return Optional.empty();
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to read '%s'", file);
            }
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Stream<String> entries(@NotNull String prefix) {
            Path base = this.root.resolve(prefix);
            if (!Files.isDirectory(base)) return Stream.empty();
            try (Stream<Path> walk = Files.walk(base)) {
                return walk.filter(Files::isRegularFile)
                    .map(p -> this.root.relativize(p).toString().replace('\\', '/'))
                    .toList()
                    .stream();
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to enumerate '%s'", base);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            return Files.isRegularFile(this.root.resolve(path));
        }
    }

    /**
     * A plain zip archive pack.
     *
     * @param zip the zip file path
     */
    record Zip(@NotNull Path zip) implements PackContainer {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<byte[]> bytes(@NotNull String path) {
            try (ZipFile archive = new ZipFile(this.zip.toFile())) {
                ZipEntry entry = archive.getEntry(path);
                if (entry == null || entry.isDirectory()) return Optional.empty();
                try (InputStream in = archive.getInputStream(entry)) {
                    return Optional.of(in.readAllBytes());
                }
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to read '%s' from zip '%s'", path, this.zip);
            }
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Stream<String> entries(@NotNull String prefix) {
            try (ZipFile archive = new ZipFile(this.zip.toFile())) {
                List<String> matches = new java.util.ArrayList<>();
                Enumeration<? extends ZipEntry> all = archive.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    if (!entry.isDirectory() && underPrefix(entry.getName(), prefix)) matches.add(entry.getName());
                }
                return matches.stream();
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to enumerate zip '%s'", this.zip);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            try (ZipFile archive = new ZipFile(this.zip.toFile())) {
                ZipEntry entry = archive.getEntry(path);
                return entry != null && !entry.isDirectory();
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to probe zip '%s'", this.zip);
            }
        }
    }

    /**
     * A Catharsis {@code .cats} archive, or a {@code .cats.zip} unwrapped to one.
     *
     * @param source the source path - a {@code .cats} or {@code .cats.zip}
     * @param index the decoded container index
     */
    record Cats(@NotNull Path source, @NotNull CatsIndex index) implements PackContainer {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<byte[]> bytes(@NotNull String path) {
            return this.index.read(path);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Stream<String> entries(@NotNull String prefix) {
            return this.index.paths().filter(p -> underPrefix(p, prefix));
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            return this.index.contains(path);
        }
    }

}
