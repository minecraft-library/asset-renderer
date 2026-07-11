package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.exception.PipelineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PackContainer} - content-sniff detection and the three container kinds - against a
 * hand-built CATS archive (stored + GZIP entries), a real directory, and a plain zip. Exercises the
 * {@code .cats.zip} unwrap with inner-over-decoy {@code pack.mcmeta} priority and the hard, named
 * error on an unrecognised source.
 */
@DisplayName("PackContainer detection + byte access")
class PackContainerTest {

    private static final byte[] INNER_MCMETA = "{\"pack\":{\"pack_format\":88}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DECOY_MCMETA = "{\"pack\":{\"description\":\"decoy\"}}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a bare .cats file decodes; stored and GZIP entries read back")
    void bareCats(@TempDir Path dir) throws IOException {
        Path cats = dir.resolve("pack.cats");
        Files.write(cats, buildCats(Optional.of(INNER_MCMETA)));

        PackContainer container = PackContainer.detect(cats);
        assertThat(container, is(org.hamcrest.Matchers.instanceOf(PackContainer.Cats.class)));
        assertThat(((PackContainer.Cats) container).index().size(), is(2));
        assertThat(container.entries("").toList(), hasItem("assets/hello.txt"));
        assertThat(container.exists("assets/hello.txt"), is(true));
        assertThat(new String(container.bytes("assets/hello.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("hi"));
        assertThat(container.bytes("pack.mcmeta").orElseThrow(), equalTo(INNER_MCMETA));
    }

    @Test
    @DisplayName("the CATS decoder rejects bad magic, a wrong version, an unknown compression, and a truncated data region")
    void catsHardErrors() throws IOException {
        assertThrows(PipelineException.class, () -> CatsIndex.decode("NOPEmore".getBytes(StandardCharsets.UTF_8), Optional.empty()));
        assertThrows(PipelineException.class, () -> CatsIndex.decode(oneFileCats(0x02, 0xFF, 0, new byte[0]), Optional.empty()));
        assertThrows(PipelineException.class, () -> CatsIndex.decode(oneFileCats(0x01, 0x00, 3, "abc".getBytes(StandardCharsets.UTF_8)), Optional.empty()));
        assertThrows(PipelineException.class, () -> CatsIndex.decode(oneFileCats(0x01, 0xFF, 100, "abc".getBytes(StandardCharsets.UTF_8)), Optional.empty()));
    }

    @Test
    @DisplayName("entries(prefix) is directory-scoped: a prefix does not swallow a sibling with a shared name")
    void entriesRespectDirectoryBoundary(@TempDir Path dir) throws IOException {
        // plain zip (flat-path container): the underPrefix helper must not over-match assets/minecraft_hd
        Path zip = dir.resolve("plain.zip");
        writeZip(zip, "assets/minecraft/a.txt", "A".getBytes(StandardCharsets.UTF_8),
            "assets/minecraft_hd/b.txt", "B".getBytes(StandardCharsets.UTF_8));
        PackContainer zipped = PackContainer.detect(zip);
        assertThat(zipped.entries("assets/minecraft").toList(), equalTo(java.util.List.of("assets/minecraft/a.txt")));

        // directory (same tree exploded): identical result
        Path exploded = dir.resolve("exploded");
        Files.createDirectories(exploded.resolve("assets/minecraft"));
        Files.createDirectories(exploded.resolve("assets/minecraft_hd"));
        Files.write(exploded.resolve("assets/minecraft/a.txt"), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(exploded.resolve("assets/minecraft_hd/b.txt"), "B".getBytes(StandardCharsets.UTF_8));
        assertThat(PackContainer.detect(exploded).entries("assets/minecraft").toList(), equalTo(java.util.List.of("assets/minecraft/a.txt")));
    }

    @Test
    @DisplayName(".cats.zip unwrap: the inner pack.mcmeta wins over the outer decoy")
    void catsZipInnerWins(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("eureka.cats.zip");
        writeZip(zip, "pack.cats", buildCats(Optional.of(INNER_MCMETA)), "pack.mcmeta", DECOY_MCMETA);

        PackContainer container = PackContainer.detect(zip);
        assertThat(container, is(org.hamcrest.Matchers.instanceOf(PackContainer.Cats.class)));
        assertThat(container.bytes("pack.mcmeta").orElseThrow(), equalTo(INNER_MCMETA));
    }

    @Test
    @DisplayName(".cats.zip unwrap: with no inner pack.mcmeta, the outer decoy is the fallback")
    void catsZipDecoyFallback(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("furry.cats.zip");
        writeZip(zip, "pack.cats", buildCats(Optional.empty()), "pack.mcmeta", DECOY_MCMETA);

        PackContainer container = PackContainer.detect(zip);
        assertThat(container.bytes("pack.mcmeta").orElseThrow(), equalTo(DECOY_MCMETA));
    }

    @Test
    @DisplayName("a directory source becomes a Directory container")
    void directory(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/x.txt"), "X".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("pack.mcmeta"), INNER_MCMETA);

        PackContainer container = PackContainer.detect(dir);
        assertThat(container, is(org.hamcrest.Matchers.instanceOf(PackContainer.Directory.class)));
        assertThat(container.entries("").toList(), hasItem("assets/x.txt"));
        assertThat(container.entries("assets").toList(), equalTo(java.util.List.of("assets/x.txt")));
        assertThat(container.exists("assets/x.txt"), is(true));
        assertThat(container.exists("nope.txt"), is(false));
        assertThat(new String(container.bytes("assets/x.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
    }

    @Test
    @DisplayName("a plain zip with no pack.cats stays a Zip container")
    void plainZip(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("plain.zip");
        writeZip(zip, "assets/y.txt", "Y".getBytes(StandardCharsets.UTF_8), null, null);

        PackContainer container = PackContainer.detect(zip);
        assertThat(container, is(org.hamcrest.Matchers.instanceOf(PackContainer.Zip.class)));
        assertThat(container.exists("assets/y.txt"), is(true));
        assertThat(container.entries("assets").toList(), hasItem("assets/y.txt"));
        assertThat(new String(container.bytes("assets/y.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("Y"));
    }

    @Test
    @DisplayName("an unrecognised file is a hard, named error")
    void unrecognisedSource(@TempDir Path dir) throws IOException {
        Path garbage = dir.resolve("garbage.bin");
        Files.write(garbage, "GARB not a pack".getBytes(StandardCharsets.UTF_8));
        assertThrows(PipelineException.class, () -> PackContainer.detect(garbage));
        assertThrows(PipelineException.class, () -> PackContainer.detect(dir.resolve("does-not-exist")));
    }

    // --- fixtures ---

    /**
     * Builds a minimal CATS blob: an optional stored {@code pack.mcmeta} at the root plus a GZIP
     * {@code assets/hello.txt} = "hi". Layout matches the spec: {@code CATS} magic, version 1,
     * big-endian recursive index, offsets relative to the header end.
     */
    private static byte[] buildCats(Optional<byte[]> innerMcmeta) throws IOException {
        byte[] helloGz = gzip("hi".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int mcSize = 0;
        if (innerMcmeta.isPresent()) {
            mcSize = innerMcmeta.get().length;
            data.write(innerMcmeta.get());       // offset 0
        }
        int helloOffset = data.size();
        data.write(helloGz);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeBytes("CATS");
        h.writeByte(0x01);
        h.writeShort(innerMcmeta.isPresent() ? 2 : 1);
        if (innerMcmeta.isPresent()) writeFileEntry(h, "pack.mcmeta", 0, mcSize, 0xFF);
        h.writeByte(0x01);                        // dir "assets"
        writeName(h, "assets");
        h.writeShort(1);
        writeFileEntry(h, "hello.txt", helloOffset, helloGz.length, 0xFE);

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(header.toByteArray());
        blob.write(data.toByteArray());
        return blob.toByteArray();
    }

    /** A single-file CATS blob with a caller-chosen version, compression marker, and declared size (for error paths). */
    private static byte[] oneFileCats(int version, int compression, int declaredSize, byte[] dataRegion) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(header);
        h.writeBytes("CATS");
        h.writeByte(version);
        h.writeShort(1);
        writeFileEntry(h, "f.bin", 0, declaredSize, compression);

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(header.toByteArray());
        blob.write(dataRegion);
        return blob.toByteArray();
    }

    private static void writeFileEntry(DataOutputStream h, String name, int offset, int size, int compression) throws IOException {
        h.writeByte(0x00);
        writeName(h, name);
        h.writeInt(offset);
        h.writeInt(size);
        h.writeByte(compression);
    }

    private static void writeName(DataOutputStream h, String name) throws IOException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        h.writeByte(n.length);
        h.write(n);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static void writeZip(Path zip, String name1, byte[] content1, String name2, byte[] content2) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(name1));
            out.write(content1);
            out.closeEntry();
            if (name2 != null) {
                out.putNextEntry(new ZipEntry(name2));
                out.write(content2);
                out.closeEntry();
            }
        }
    }

}
