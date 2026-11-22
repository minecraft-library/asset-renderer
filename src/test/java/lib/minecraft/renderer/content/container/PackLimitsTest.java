package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.exception.ContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The limits an archive is read under: a zip is held to its entry count and to the bytes it inflates to,
 * in one entry and in all, and a Catharsis archive's compressed file to the bytes one entry may inflate
 * to - each refused as a {@link ContentException}, and each read at the limit left alone. Every archive
 * here is a few kilobytes, read under limits lowered to match.
 */
@DisplayName("PackLimits over an archive")
class PackLimitsTest {

    /** Limits low enough for a kilobyte to cross, with every other ceiling at its default. */
    private static final PackLimits SMALL = PackLimits.DEFAULTS.withEntryBytes(1024).withPackBytes(2048).withEntries(4);

    @Test
    @DisplayName("a zip within every limit reads whole")
    void zipWithinLimits(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", new byte[1024], "b.bin", new byte[1024]));

        LiveIndex index = LiveIndex.read(zip, SMALL);

        assertThat(index.size(), is(2));
        assertThat(index.bytes(), is(2048L));
    }

    @Test
    @DisplayName("an entry that inflates past the entry limit is refused")
    void zipEntryOverLimit(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", new byte[1025]));

        ContentException refused = assertThrows(ContentException.class, () -> LiveIndex.read(zip, SMALL));

        assertThat(refused.getMessage(), containsString("a.bin"));
        assertThat(refused.getMessage(), containsString("limit of 1024 inflated bytes"));
    }

    @Test
    @DisplayName("entries that together inflate past the pack limit are refused")
    void zipTotalOverLimit(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", new byte[1000], "b.bin", new byte[1000], "c.bin", new byte[1000]));

        ContentException refused = assertThrows(ContentException.class, () -> LiveIndex.read(zip, SMALL));

        assertThat(refused.getMessage(), containsString("limit of 2048 inflated bytes"));
    }

    @Test
    @DisplayName("a zip holding more entries than the entry-count limit is refused")
    void zipEntryCountOverLimit(@TempDir Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) files.put(i + ".bin", new byte[1]);
        Path zip = zip(dir, files);

        ContentException refused = assertThrows(ContentException.class, () -> LiveIndex.read(zip, SMALL));

        assertThat(refused.getMessage(), containsString("limit of 4 entries"));
    }

    @Test
    @DisplayName("an entry is held to the limit by what it inflates to, whatever size its header declares")
    void zipEntryUnderstatingItsSize(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", new byte[4096]));
        declareSize(zip, 16);

        ContentException refused = assertThrows(ContentException.class, () -> LiveIndex.read(zip, SMALL));

        assertThat(refused.getMessage(), containsString("limit of 1024 inflated bytes"));
    }

    @Test
    @DisplayName("an entry longer than its header declares, and within the limit, is read to its end")
    void zipEntryUnderstatingItsSizeWithinLimit(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", new byte[1024]));
        declareSize(zip, 16);

        assertThat(LiveIndex.read(zip, SMALL).read("a.bin").orElseThrow().length, is(1024));
    }

    @Test
    @DisplayName("detect reads a zip under the limits it is handed")
    void detectTakesLimits(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("pack.mcmeta", new byte[1025]));

        assertThat(PackContainer.detect(zip), instanceOf(PackContainer.Live.class));
        assertThrows(ContentException.class, () -> PackContainer.detect(zip, SMALL));
    }

    @Test
    @DisplayName("a zip re-opened for every read holds each entry to the default entry limit")
    void reopenedZipReadsWithinDefaults(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("a.bin", "A".getBytes(StandardCharsets.UTF_8)));

        assertThat(new PackContainer.Zip(zip).bytes("a.bin").orElseThrow(), equalTo("A".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a compressed archive file within the entry limit inflates whole")
    void catsEntryWithinLimit() throws IOException {
        CatsIndex index = CatsIndex.decode(oneGzipFileCats(new byte[1024]), Optional.empty(), SMALL);

        assertThat(index.read("f.bin").orElseThrow().length, is(1024));
    }

    @Test
    @DisplayName("a compressed archive file that inflates past the entry limit is refused")
    void catsEntryOverLimit() throws IOException {
        CatsIndex index = CatsIndex.decode(oneGzipFileCats(new byte[1025]), Optional.empty(), SMALL);

        ContentException refused = assertThrows(ContentException.class, () -> index.read("f.bin"));

        assertThat(refused.getMessage(), containsString("f.bin"));
        assertThat(refused.getMessage(), containsString("limit of 1024 inflated bytes"));
    }

    @Test
    @DisplayName("a compressed archive file longer than one read block inflates whole and in order, and is refused past the limit")
    void catsEntryAcrossBlocks() throws IOException {
        byte[] raw = new byte[(3 << 20) + 5];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 31 >>> 7);
        byte[] cats = oneGzipFileCats(raw);

        assertThat(CatsIndex.decode(cats, Optional.empty(), PackLimits.DEFAULTS.withEntryBytes(raw.length)).read("f.bin").orElseThrow(), equalTo(raw));
        CatsIndex limited = CatsIndex.decode(cats, Optional.empty(), PackLimits.DEFAULTS.withEntryBytes(raw.length - 1));
        assertThrows(ContentException.class, () -> limited.read("f.bin"));
    }

    @Test
    @DisplayName("a bare archive is read under the limits detect is handed")
    void detectTakesLimitsForCats(@TempDir Path dir) throws IOException {
        Path cats = dir.resolve("pack.cats");
        Files.write(cats, oneGzipFileCats(new byte[1025]));

        assertThat(PackContainer.detect(cats).bytes("f.bin").orElseThrow().length, is(1025));
        PackContainer limited = PackContainer.detect(cats, SMALL);
        assertThrows(ContentException.class, () -> limited.bytes("f.bin"));
    }

    @Test
    @DisplayName("a with method changes its one ceiling and a negative ceiling is refused")
    void withMethods() {
        PackLimits changed = PackLimits.DEFAULTS.withImagePixels(7).withHeldPixels(8).withTiles(9).withPermutations(10).withFrames(11);

        assertThat(changed.imagePixels(), is(7L));
        assertThat(changed.heldPixels(), is(8L));
        assertThat(changed.tiles(), is(9));
        assertThat(changed.permutations(), is(10));
        assertThat(changed.frames(), is(11));
        assertThat(changed.entryBytes(), is(PackLimits.DEFAULTS.entryBytes()));
        assertThat(changed.packBytes(), is(PackLimits.DEFAULTS.packBytes()));
        assertThat(changed.entries(), is(PackLimits.DEFAULTS.entries()));
        assertThrows(IllegalArgumentException.class, () -> PackLimits.DEFAULTS.withEntryBytes(-1));
    }

    /** Writes a deflated zip of the given files. */
    private static Path zip(Path dir, Map<String, byte[]> files) throws IOException {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    /**
     * Rewrites the uncompressed size a one-entry zip's central directory declares for its entry, which
     * is the size a reader is told before it inflates anything.
     */
    private static void declareSize(Path zip, int size) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = bytes.length - 46; at >= 0; at--) {
            if (buffer.getInt(at) != 0x02014B50) continue;

            buffer.putInt(at + 24, size);
            Files.write(zip, bytes);
            return;
        }
        throw new IOException("No central directory record in " + zip);
    }

    /** A CATS blob holding one GZIP-marked file, {@code f.bin}. */
    private static byte[] oneGzipFileCats(byte[] raw) throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzipped)) {
            gz.write(raw);
        }
        byte[] name = "f.bin".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(blob);
        out.writeBytes("CATS");
        out.writeByte(0x01);
        out.writeShort(1);
        out.writeByte(0x00);
        out.writeByte(name.length);
        out.write(name);
        out.writeInt(0);
        out.writeInt(gzipped.size());
        out.writeByte(0xFE);
        out.write(gzipped.toByteArray());
        return blob.toByteArray();
    }

}
