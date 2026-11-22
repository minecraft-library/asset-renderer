package lib.minecraft.renderer.content.container;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The entries of a zip held whole in memory - every file entry inflated once, when the index is built,
 * and kept as two parallel arrays in ascending path order.
 *
 * <p>A lookup is a binary search over the paths, and the entries under a directory are the contiguous
 * run of paths between that directory and the first path past it, so listing one is a range rather than
 * a scan. The order is the paths' own, the same on every run whatever order the archive stored them in.
 * A read hands back a copy, so no caller can change what the next one reads.
 *
 * @see PackContainer.Live
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class LiveIndex {

    /** The largest array an entry's declared size is allocated as; a larger declaration is not trusted. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;

    /** The entry paths, in ascending order, each once. */
    private final @NotNull String @NotNull [] paths;

    /** The entries' inflated bytes, the bytes at an index belonging to the path at that index. */
    private final byte @NotNull [] @NotNull [] data;

    /**
     * Reads every file entry of a zip into memory, under {@link PackLimits#DEFAULTS}.
     *
     * @param zip the zip file path
     * @return the index
     * @throws ContentException if the zip cannot be read, or holds more than the limits admit
     */
    static @NotNull LiveIndex read(@NotNull Path zip) {
        return read(zip, PackLimits.DEFAULTS);
    }

    /**
     * Reads every file entry of a zip into memory.
     *
     * <p>A zip can carry two records under one name; the later one is kept, which is the record
     * {@link ZipFile#getEntry} answers for that name.
     *
     * <p>The zip is read under three of the limits: its entry count, tested before any entry is read;
     * the inflated bytes of each entry; and the inflated bytes of every entry read, a record a later
     * one replaces included. An entry is refused on the size it declares where that is already over,
     * and otherwise as soon as it inflates past what is left, so a zip never costs more than the limits
     * to refuse.
     *
     * @param zip the zip file path
     * @param limits the limits the zip is read under
     * @return the index
     * @throws ContentException if the zip cannot be read, or holds more entries or inflates to more
     *     bytes, in one entry or in all, than the limits admit
     */
    static @NotNull LiveIndex read(@NotNull Path zip, @NotNull PackLimits limits) {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            if (archive.size() > limits.entries())
                throw PackLimits.over(String.format("Pack zip '%s'", zip), limits.entries(), "entries");

            long left = limits.packBytes();
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;

                byte[] bytes = readEntry(archive, entry, Math.min(limits.entryBytes(), left));
                if (bytes == null) {
                    throw left < limits.entryBytes()
                        ? PackLimits.over(String.format("Pack zip '%s'", zip), limits.packBytes(), "inflated bytes")
                        : PackLimits.over(String.format("Entry '%s' of pack zip '%s'", entry.getName(), zip), limits.entryBytes(), "inflated bytes");
                }

                left -= bytes.length;
                entries.put(entry.getName(), bytes);
            }
        } catch (IOException ex) {
            throw new ContentException(ex, "Failed to read pack zip '%s'", zip);
        }

        return of(entries);
    }

    /**
     * Reads one zip entry whole - the read every in-memory zip in the library makes, the vanilla pack's
     * included.
     *
     * <p>A pack is mostly small files, and {@link InputStream#readAllBytes} allocates a buffer of its
     * default size for each one before trimming it, so reading a pack that way allocates many times the
     * pack. An entry whose size the zip declares is read into an array of exactly that size instead. One
     * that turns out longer than declared is read on to its end, so a header that understates an entry
     * costs a copy rather than the entry's tail.
     *
     * @param archive the open zip
     * @param entry the entry to read
     * @return the entry's bytes
     * @throws IOException if the entry cannot be read
     */
    public static byte @NotNull [] readEntry(@NotNull ZipFile archive, @NotNull ZipEntry entry) throws IOException {
        return Objects.requireNonNull(readEntry(archive, entry, Long.MAX_VALUE));
    }

    /**
     * Reads one zip entry whole, refusing it where it inflates past a ceiling - on the size it declares
     * where that is already over, before any of it is read, and otherwise once it has yielded one byte
     * too many.
     *
     * @param archive the open zip
     * @param entry the entry to read
     * @param ceiling the most bytes the entry may inflate to
     * @return the entry's bytes, or {@code null} where it inflates to more than the ceiling
     * @throws IOException if the entry cannot be read
     */
    static byte @Nullable [] readEntry(@NotNull ZipFile archive, @NotNull ZipEntry entry, long ceiling) throws IOException {
        long declared = entry.getSize();
        if (declared > ceiling) return null;

        try (InputStream in = archive.getInputStream(entry)) {
            return declared < 0 || declared > MAX_ARRAY
                ? PackLimits.readWithin(in, ceiling)
                : PackLimits.readWithin(in, (int) declared, ceiling);
        }
    }

    /**
     * Holds entries already read, taking ownership of their arrays.
     *
     * @param entries the entries' bytes, by path
     * @return the index
     */
    static @NotNull LiveIndex of(@NotNull Map<String, byte[]> entries) {
        String[] paths = entries.keySet().toArray(String[]::new);
        Arrays.sort(paths);

        byte[][] data = new byte[paths.length][];
        for (int i = 0; i < paths.length; i++)
            data[i] = entries.get(paths[i]);

        return new LiveIndex(paths, data);
    }

    /**
     * Reads a copy of the bytes held for a path.
     *
     * @param path the {@code /}-separated entry path
     * @return a copy of the entry's bytes, or empty when the index holds no such entry
     */
    public @NotNull Optional<byte[]> read(@NotNull String path) {
        int at = Arrays.binarySearch(this.paths, path);
        return at < 0 ? Optional.empty() : Optional.of(this.data[at].clone());
    }

    /**
     * Whether the index holds an entry at a path.
     *
     * @param path the {@code /}-separated entry path
     * @return {@code true} when it does
     */
    public boolean contains(@NotNull String path) {
        return Arrays.binarySearch(this.paths, path) >= 0;
    }

    /**
     * The entry paths under a directory, in ascending order.
     *
     * <p>Every path that starts with {@code x/} sorts at or above {@code x/} and below {@code x0}, the
     * directory with its trailing {@code /} raised to the next character, so the run between the two is
     * exactly the directory's entries - and a sibling such as {@code x_hd/}, which sorts above
     * {@code x0}, is never in it.
     *
     * @param dir the directory, {@code ""} for every entry or a path ending in {@code /}
     * @return the paths in the directory, at any depth
     */
    public @NotNull Stream<String> range(@NotNull String dir) {
        if (dir.isEmpty()) return Arrays.stream(this.paths);

        int from = lowerBound(dir);
        int to = lowerBound(dir.substring(0, dir.length() - 1) + (char) (dir.charAt(dir.length() - 1) + 1));
        return Arrays.stream(this.paths, from, to);
    }

    /**
     * The number of entries the index holds.
     *
     * @return the entry count
     */
    public int size() {
        return this.paths.length;
    }

    /**
     * The total size of the entries' bytes.
     *
     * @return the byte count, summed over every entry
     */
    public long bytes() {
        long total = 0;
        for (byte[] entry : this.data)
            total += entry.length;

        return total;
    }

    /** The index of the first path at or above a key. */
    private int lowerBound(@NotNull String key) {
        int at = Arrays.binarySearch(this.paths, key);
        return at >= 0 ? at : -at - 1;
    }

}
