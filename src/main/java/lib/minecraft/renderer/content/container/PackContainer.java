package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only access to one pack's files, whatever the pack is stored as - the two questions every pack
 * reader asks, "what entries exist" and "give me these bytes". Paths are always {@code /}-separated and
 * relative to the pack root with no leading slash.
 *
 * <p>Four kinds of storage answer them: an exploded {@link Directory}, a zip held whole in memory
 * ({@link Live}), a Catharsis {@link Cats} archive (a bare {@code .cats} or a {@code .cats.zip} that
 * wraps one), and a {@link Zip} re-opened for every read, which a caller builds for itself. Each kind
 * owns how it is built and how it reads. {@link #detect} is the one entry point that chooses a kind
 * for a source, and it sniffs the source by content - never by filename alone - so a correctly-built
 * pack loads whatever its extension, and an unrecognised file fails loudly rather than degrading to a
 * broken read.
 */
public sealed interface PackContainer
    permits PackContainer.Directory, PackContainer.Zip, PackContainer.Cats, PackContainer.Live {

    /**
     * Reads the bytes of one entry.
     *
     * @param path the {@code /}-separated entry path
     * @return the entry's bytes, or empty when no such entry exists
     */
    @NotNull Optional<byte[]> bytes(@NotNull String path);

    /**
     * Enumerates every file entry under a directory prefix.
     *
     * <p>A non-empty prefix matches only entries strictly under that directory, never a sibling whose
     * name merely starts with the prefix string - {@code assets/minecraft} does not match
     * {@code assets/minecraft_hd/...} - so a zipped and an exploded copy of the same pack enumerate
     * identically.
     *
     * @param prefix the {@code /}-separated directory prefix ({@code ""} for the whole pack)
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
     * <p>A directory becomes {@link Directory}; otherwise the leading bytes decide. {@code "CATS"} magic
     * is a bare {@link Cats}. A {@code PK} zip is read whole into memory once: one that wraps a
     * {@code pack.cats} entry is unwrapped to a {@link Cats} - the inner {@code pack.mcmeta}
     * authoritative, the outer decoy as fallback - and any other is the {@link Live} it was read as.
     * Anything else is a hard, file-naming error.
     *
     * @param source the pack source path
     * @return the detected container
     * @throws ContentException if the source is neither a directory nor a recognised archive
     */
    static @NotNull PackContainer detect(@NotNull Path source) {
        if (Files.isDirectory(source)) return new Directory(source);
        if (!Files.isRegularFile(source))
            throw new ContentException("Pack source '%s' is neither a directory nor a regular file", source);

        byte[] head = readHead(source);
        if (Cats.isCats(head)) return Cats.read(source);
        if (Live.isZip(head)) {
            Live archive = Live.read(source);
            return Cats.wraps(archive) ? Cats.unwrap(archive) : archive;
        }

        throw new ContentException("Pack source '%s' is not a directory, zip, or CATS container", source);
    }

    /** Reads the leading bytes {@link #detect} decides a source's kind by. */
    private static byte @NotNull [] readHead(@NotNull Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            return in.readNBytes(4);
        } catch (IOException ex) {
            throw new ContentException(ex, "Failed to read pack source '%s'", source);
        }
    }

    /**
     * The directory a prefix names, as every kind matches it: empty for the whole pack, otherwise the
     * prefix with a trailing {@code /}, which is what keeps {@code assets/minecraft} from matching
     * {@code assets/minecraft_hd}.
     */
    private static @NotNull String directoryOf(@NotNull String prefix) {
        if (prefix.isEmpty()) return "";
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** Whether an entry path lies under the directory a prefix names. */
    private static boolean underPrefix(@NotNull String path, @NotNull String prefix) {
        return path.startsWith(directoryOf(prefix));
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
                throw new ContentException(ex, "Failed to read '%s'", file);
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
                throw new ContentException(ex, "Failed to enumerate '%s'", base);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            return Files.isRegularFile(this.root.resolve(path));
        }
    }

    /**
     * A plain zip archive pack, re-opened for every read.
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
                throw new ContentException(ex, "Failed to read '%s' from zip '%s'", path, this.zip);
            }
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Stream<String> entries(@NotNull String prefix) {
            try (ZipFile archive = new ZipFile(this.zip.toFile())) {
                Enumeration<? extends ZipEntry> all = archive.entries();
                return Collections.list(all)
                    .stream()
                    .filter(entry -> !entry.isDirectory() && underPrefix(entry.getName(), prefix))
                    .map(ZipEntry::getName)
                    .toList()
                    .stream();
            } catch (IOException ex) {
                throw new ContentException(ex, "Failed to enumerate zip '%s'", this.zip);
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            try (ZipFile archive = new ZipFile(this.zip.toFile())) {
                ZipEntry entry = archive.getEntry(path);
                return entry != null && !entry.isDirectory();
            } catch (IOException ex) {
                throw new ContentException(ex, "Failed to probe zip '%s'", this.zip);
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

        /** The entry a {@code .cats.zip} carries its archive under. */
        private static final @NotNull String WRAPPED = "pack.cats";

        /**
         * Whether a source's leading bytes are the {@code "CATS"} magic of a bare archive.
         *
         * @param head the source's leading bytes
         * @return {@code true} when the source is a bare {@code .cats}
         */
        static boolean isCats(byte @NotNull [] head) {
            return CatsIndex.startsWithMagic(head);
        }

        /**
         * Reads and decodes a bare {@code .cats} archive.
         *
         * @param cats the archive path
         * @return the container
         * @throws ContentException if the archive cannot be read or is malformed
         */
        public static @NotNull Cats read(@NotNull Path cats) {
            try {
                return new Cats(cats, CatsIndex.decode(Files.readAllBytes(cats), Optional.empty()));
            } catch (IOException ex) {
                throw new ContentException(ex, "Failed to read pack source '%s'", cats);
            }
        }

        /**
         * Whether a zip read into memory is a {@code .cats.zip} - one carrying a {@code pack.cats}.
         *
         * @param archive the zip, held in memory
         * @return {@code true} when it wraps an archive
         */
        static boolean wraps(@NotNull Live archive) {
            return archive.exists(WRAPPED);
        }

        /**
         * Unwraps the archive a {@code .cats.zip} carries, out of the zip already held in memory,
         * taking the zip's own {@code pack.mcmeta} as the decoy the archive falls back to when it ships
         * none.
         *
         * @param archive the {@code .cats.zip}, held in memory
         * @return the container, over the zip's path
         * @throws ContentException if the zip carries no {@code pack.cats} or the archive is malformed
         */
        public static @NotNull Cats unwrap(@NotNull Live archive) {
            byte[] wrapped = archive.bytes(WRAPPED).orElseThrow(() ->
                new ContentException("Pack zip '%s' carries no %s", archive.source(), WRAPPED));
            return new Cats(archive.source(), CatsIndex.decode(wrapped, archive.bytes("pack.mcmeta")));
        }

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

    /**
     * A zip archive held whole in memory - every file entry inflated once, when the container is built,
     * and served from memory from then on.
     *
     * @param source the archive the entries were read from
     * @param index the entries, by path
     */
    record Live(@NotNull Path source, @NotNull LiveIndex index) implements PackContainer {

        /**
         * Whether a source's leading bytes are a zip's {@code PK} signature.
         *
         * @param head the source's leading bytes
         * @return {@code true} when the source is a zip
         */
        static boolean isZip(byte @NotNull [] head) {
            return head.length >= 2 && head[0] == 0x50 && head[1] == 0x4B;
        }

        /**
         * Reads every file entry of a zip into memory.
         *
         * @param zip the zip file path
         * @return the container
         * @throws ContentException if the zip cannot be read
         */
        public static @NotNull Live read(@NotNull Path zip) {
            return new Live(zip, LiveIndex.read(zip));
        }

        /**
         * Holds entries already read out of an archive, for a pack that is not the archive's whole
         * content - the vanilla pack keeps part of the client jar and adds a synthesised
         * {@code pack.mcmeta}.
         *
         * @param source the archive the entries were read from
         * @param entries the entries' bytes, by path; the container takes ownership of the arrays
         * @return the container
         */
        public static @NotNull Live of(@NotNull Path source, @NotNull Map<String, byte[]> entries) {
            return new Live(source, LiveIndex.of(entries));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<byte[]> bytes(@NotNull String path) {
            return this.index.read(path);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Stream<String> entries(@NotNull String prefix) {
            return this.index.range(directoryOf(prefix));
        }

        /** {@inheritDoc} */
        @Override
        public boolean exists(@NotNull String path) {
            return this.index.contains(path);
        }
    }

}
