package lib.minecraft.renderer.content.container;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
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
     * Reads every file entry of a zip into memory.
     *
     * <p>A zip can carry two records under one name; the later one is kept, which is the record
     * {@link ZipFile#getEntry} answers for that name.
     *
     * @param zip the zip file path
     * @return the index
     * @throws ContentException if the zip cannot be read
     */
    static @NotNull LiveIndex read(@NotNull Path zip) {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;

                entries.put(entry.getName(), readEntry(archive, entry));
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
        try (InputStream in = archive.getInputStream(entry)) {
            long declared = entry.getSize();
            if (declared < 0 || declared > MAX_ARRAY) return in.readAllBytes();

            byte[] bytes = in.readNBytes((int) declared);
            int next = in.read();
            if (next < 0) return bytes;

            ByteArrayOutputStream whole = new ByteArrayOutputStream(bytes.length + 1);
            whole.write(bytes);
            whole.write(next);
            in.transferTo(whole);
            return whole.toByteArray();
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
