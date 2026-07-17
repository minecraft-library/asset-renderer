package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.exception.PipelineException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The decoded index of a Catharsis {@code pack.cats} container.
 *
 * <p>The format (magic {@code "CATS"} + version byte {@code 0x01}) carries a big-endian, recursive,
 * depth-first directory index inline in its header, followed by a data region of the files' bytes
 * back-to-back. Each directory is a {@code u16} entry count then that many entries; each entry is a
 * {@code u8} type ({@code 0x00} file, {@code 0x01} directory), a {@code u8} name length, the ASCII
 * name, then either a nested directory or a file record ({@code i32} offset, {@code i32} size,
 * {@code u8} compression: {@code 0xFF} stored, {@code 0xFE} GZIP). File offsets are relative to the
 * <b>end of the header</b>, so this index slices the data region off at that boundary and reads each
 * file by its offset within it. The container is read-only; the decoder never writes {@code .cats}.
 *
 * @see PackContainer.Cats
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CatsIndex {

    private static final int MAGIC = 0x43415453; // "CATS"
    private static final int VERSION = 0x01;

    private final @NotNull LinkedHashMap<String, CatsEntry> entries;
    private final byte @NotNull [] data;
    private final @NotNull Optional<byte[]> outerMcmeta;

    /**
     * Decodes a {@code pack.cats} blob into an index over its files.
     *
     * @param blob the whole {@code pack.cats} byte content
     * @param outerMcmeta the outer {@code .cats.zip} decoy {@code pack.mcmeta}, used only as a
     *     fallback when the container carries no inner {@code pack.mcmeta}; empty for a bare
     *     {@code .cats} source
     * @return the decoded index
     * @throws PipelineException if the magic, version, or structure is malformed
     */
    public static @NotNull CatsIndex decode(byte @NotNull [] blob, @NotNull Optional<byte[]> outerMcmeta) {
        if (blob.length < 5 || readInt(blob, 0) != MAGIC)
            throw new PipelineException("Not a CATS container: bad magic");
        int version = blob[4] & 0xFF;
        if (version != VERSION)
            throw new PipelineException("Unsupported CATS version %d (expected 1)", version);

        LinkedHashMap<String, CatsEntry> entries = new LinkedHashMap<>();
        int[] pos = {5};
        parseDirectory(blob, pos, "", entries);
        byte[] data = Arrays.copyOfRange(blob, pos[0], blob.length);
        for (CatsEntry entry : entries.values())
            if (entry.offset() < 0 || entry.size() < 0 || (long) entry.offset() + entry.size() > data.length)
                throw new PipelineException("Truncated CATS container: entry '%s' spans [%d,+%d) beyond the %d-byte data region",
                    entry.path(), entry.offset(), entry.size(), data.length);
        return new CatsIndex(entries, data, outerMcmeta);
    }

    /**
     * Recursively parses one directory - a {@code u16} entry count then that many entries - appending
     * every file it reaches to {@code out} under its full {@code /}-separated path, and advancing the
     * cursor past the whole subtree.
     */
    private static void parseDirectory(byte @NotNull [] blob, int @NotNull [] pos, @NotNull String prefix, @NotNull Map<String, CatsEntry> out) {
        int count = readShort(blob, pos);
        for (int i = 0; i < count; i++) {
            int type = readByte(blob, pos);
            int nameLen = readByte(blob, pos);
            String name = readName(blob, pos, nameLen);
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (type == 0x01) {
                parseDirectory(blob, pos, path, out);
            } else if (type == 0x00) {
                int offset = readInt(blob, advance(pos, 4));
                int size = readInt(blob, advance(pos, 4));
                Compression compression = Compression.fromByte(readByte(blob, pos));
                out.put(path, new CatsEntry(path, offset, size, compression));
            } else {
                throw new PipelineException("CATS entry '%s' has unknown type byte 0x%02X", path, type);
            }
        }
    }

    /**
     * Reads and decompresses the file at a path, applying the inner-over-decoy priority for
     * {@code pack.mcmeta}: the inner file wins, and the outer {@code .cats.zip} decoy is returned only
     * when the container carries no inner {@code pack.mcmeta}.
     *
     * @param path the {@code /}-separated file path
     * @return the file's decompressed bytes, or empty when absent
     * @throws PipelineException if a GZIP file cannot be inflated
     */
    public @NotNull Optional<byte[]> read(@NotNull String path) {
        CatsEntry entry = this.entries.get(path);
        if (entry == null)
            return "pack.mcmeta".equals(path) ? this.outerMcmeta.map(byte[]::clone) : Optional.empty();

        byte[] slice = Arrays.copyOfRange(this.data, entry.offset(), entry.offset() + entry.size());
        return switch (entry.compression()) {
            case STORED -> Optional.of(slice);
            case GZIP -> Optional.of(gunzip(slice, path));
        };
    }

    /**
     * Whether the container exposes a file at the path, counting the {@code pack.mcmeta} decoy
     * fallback.
     *
     * @param path the {@code /}-separated file path
     * @return {@code true} when {@link #read} would yield bytes
     */
    public boolean contains(@NotNull String path) {
        return this.entries.containsKey(path) || ("pack.mcmeta".equals(path) && this.outerMcmeta.isPresent());
    }

    /**
     * The file paths carried by the container, in decode order (the outer decoy is not enumerated).
     *
     * @return a stream of {@code /}-separated file paths
     */
    public @NotNull Stream<String> paths() {
        return this.entries.keySet().stream();
    }

    /**
     * The number of files carried by the container.
     *
     * @return the file count
     */
    public int size() {
        return this.entries.size();
    }

    private static byte @NotNull [] gunzip(byte @NotNull [] slice, @NotNull String path) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(slice))) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to inflate GZIP CATS entry '%s'", path);
        }
    }

    private static int readByte(byte @NotNull [] blob, int @NotNull [] pos) {
        require(blob, pos[0], 1);
        return blob[pos[0]++] & 0xFF;
    }

    private static int readShort(byte @NotNull [] blob, int @NotNull [] pos) {
        require(blob, pos[0], 2);
        int v = ((blob[pos[0]] & 0xFF) << 8) | (blob[pos[0] + 1] & 0xFF);
        pos[0] += 2;
        return v;
    }

    private static @NotNull String readName(byte @NotNull [] blob, int @NotNull [] pos, int len) {
        require(blob, pos[0], len);
        String name = new String(blob, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return name;
    }

    /** Advances the cursor by {@code n} and returns the pre-advance position (for a fixed-width read). */
    private static int advance(int @NotNull [] pos, int n) {
        int at = pos[0];
        pos[0] += n;
        return at;
    }

    private static int readInt(byte @NotNull [] blob, int at) {
        require(blob, at, 4);
        return ((blob[at] & 0xFF) << 24) | ((blob[at + 1] & 0xFF) << 16) | ((blob[at + 2] & 0xFF) << 8) | (blob[at + 3] & 0xFF);
    }

    private static void require(byte @NotNull [] blob, int at, int n) {
        if (at < 0 || at + n > blob.length)
            throw new PipelineException("Truncated CATS container: need %d bytes at offset %d of %d", n, at, blob.length);
    }

    /**
     * One file record in a {@link CatsIndex}.
     *
     * @param path the full {@code /}-separated path
     * @param offset the byte offset within the data region (relative to the header end)
     * @param size the stored byte length within the data region
     * @param compression how the stored bytes are compressed
     */
    public record CatsEntry(@NotNull String path, int offset, int size, @NotNull Compression compression) {}

    /** Per-file compression of a {@link CatsEntry}. */
    public enum Compression {

        /** Uncompressed, stored verbatim ({@code 0xFF}). */
        STORED(0xFF),
        /** GZIP-compressed ({@code 0xFE}). */
        GZIP(0xFE);

        private final int marker;

        Compression(int marker) {
            this.marker = marker;
        }

        /**
         * Maps a compression marker byte to its enum.
         *
         * @param marker the marker byte value ({@code 0..255})
         * @return the compression
         * @throws PipelineException if the marker is neither {@code 0xFF} nor {@code 0xFE}
         */
        public static @NotNull Compression fromByte(int marker) {
            for (Compression c : values())
                if (c.marker == marker) return c;
            throw new PipelineException("Unknown CATS compression marker 0x%02X", marker);
        }
    }

}
