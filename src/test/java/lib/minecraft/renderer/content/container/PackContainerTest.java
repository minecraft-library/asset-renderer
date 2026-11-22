package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.exception.ContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Synthetic coverage of {@link PackContainer} - content-sniff detection and the four container kinds -
 * against a hand-built CATS archive (stored and GZIP entries), a real directory, and a plain zip read both
 * re-opened per call and whole into memory. Covers the {@code .cats.zip} unwrap with inner-over-decoy
 * {@code pack.mcmeta} priority and the hard, named error on an unrecognised source.
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
        assertThat(container, is(instanceOf(PackContainer.Cats.class)));
        assertThat(((PackContainer.Cats) container).index().size(), is(2));
        assertThat(container.entries("").toList(), hasItem("assets/hello.txt"));
        assertThat(container.exists("assets/hello.txt"), is(true));
        assertThat(new String(container.bytes("assets/hello.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("hi"));
        assertThat(container.bytes("pack.mcmeta").orElseThrow(), equalTo(INNER_MCMETA));
    }

    @Test
    @DisplayName("the CATS decoder rejects bad magic, a wrong version, an unknown compression, and a truncated data region")
    void catsHardErrors() throws IOException {
        assertThrows(ContentException.class, () -> CatsIndex.decode("NOPEmore".getBytes(StandardCharsets.UTF_8), Optional.empty()));
        assertThrows(ContentException.class, () -> CatsIndex.decode(oneFileCats(0x02, 0xFF, 0, new byte[0]), Optional.empty()));
        assertThrows(ContentException.class, () -> CatsIndex.decode(oneFileCats(0x01, 0x00, 3, "abc".getBytes(StandardCharsets.UTF_8)), Optional.empty()));
        assertThrows(ContentException.class, () -> CatsIndex.decode(oneFileCats(0x01, 0xFF, 100, "abc".getBytes(StandardCharsets.UTF_8)), Optional.empty()));
    }

    @Test
    @DisplayName("entries(prefix) is directory-scoped: a prefix does not swallow a sibling with a shared name")
    void entriesRespectDirectoryBoundary(@TempDir Path dir) throws IOException {
        // plain zip re-opened per read (flat-path container): the underPrefix helper must not over-match
        // assets/minecraft_hd
        Path zip = dir.resolve("plain.zip");
        writeZip(zip, "assets/minecraft/a.txt", "A".getBytes(StandardCharsets.UTF_8),
            "assets/minecraft_hd/b.txt", "B".getBytes(StandardCharsets.UTF_8));
        PackContainer zipped = new PackContainer.Zip(zip);
        assertThat(zipped.entries("assets/minecraft").toList(), equalTo(List.of("assets/minecraft/a.txt")));

        // the same zip as detected, held in memory: its range stops short of the sibling, with or without
        // the slash
        PackContainer live = PackContainer.detect(zip);
        assertThat(live.entries("assets/minecraft").toList(), equalTo(List.of("assets/minecraft/a.txt")));
        assertThat(live.entries("assets/minecraft/").toList(), equalTo(List.of("assets/minecraft/a.txt")));

        // directory (same tree exploded): identical result
        Path exploded = dir.resolve("exploded");
        Files.createDirectories(exploded.resolve("assets/minecraft"));
        Files.createDirectories(exploded.resolve("assets/minecraft_hd"));
        Files.write(exploded.resolve("assets/minecraft/a.txt"), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(exploded.resolve("assets/minecraft_hd/b.txt"), "B".getBytes(StandardCharsets.UTF_8));
        assertThat(PackContainer.detect(exploded).entries("assets/minecraft").toList(), equalTo(List.of("assets/minecraft/a.txt")));
    }

    @Test
    @DisplayName(".cats.zip unwrap: the inner pack.mcmeta wins over the outer decoy")
    void catsZipInnerWins(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("eureka.cats.zip");
        writeZip(zip, "pack.cats", buildCats(Optional.of(INNER_MCMETA)), "pack.mcmeta", DECOY_MCMETA);

        PackContainer container = PackContainer.detect(zip);
        assertThat(container, is(instanceOf(PackContainer.Cats.class)));
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
        assertThat(container, is(instanceOf(PackContainer.Directory.class)));
        assertThat(container.entries("").toList(), hasItem("assets/x.txt"));
        assertThat(container.entries("assets").toList(), equalTo(List.of("assets/x.txt")));
        assertThat(container.exists("assets/x.txt"), is(true));
        assertThat(container.exists("nope.txt"), is(false));
        assertThat(new String(container.bytes("assets/x.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
    }

    @Test
    @DisplayName("a directory answers a path that is no entry path as absent, and raises nothing")
    void directoryServesOnlyEntryPaths(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("pack");
        Files.createDirectories(pack.resolve("assets"));
        Files.writeString(pack.resolve("assets/x.txt"), "X");
        Files.writeString(pack.resolve("pack.mcmeta"), "{}");
        Files.createDirectories(dir.resolve("beside"));
        Files.writeString(dir.resolve("beside/y.txt"), "Y");
        String rooted = dir.resolve("beside/y.txt").toAbsolutePath().toString().replace('\\', '/');

        PackContainer container = PackContainer.detect(pack);
        List<String> refused = List.of("../beside/y.txt", "assets/../../beside/y.txt", "assets/../pack.mcmeta", "assets/..",
                                       "/", "assets\\x.txt", "assets/x.txt\0");
        for (String path : refused)
            assertThat(path, PackContainer.isEntryPath(path), is(false));
        // A rooted path is refused however the platform spells its root.
        for (String path : Stream.concat(refused.stream(), Stream.of(rooted)).toList()) {
            assertThat(path, container.bytes(path), is(Optional.empty()));
            assertThat(path, container.exists(path), is(false));
        }
        for (String prefix : List.of("..", "../beside", "assets/..", "assets/../..", "/", rooted.substring(0, rooted.lastIndexOf('/')), "assets\0"))
            assertThat(prefix, container.entries(prefix).toList(), is(empty()));

        // A name that only starts or ends with dots is a name like any other, and what the pack holds reads as it did.
        Files.createDirectories(pack.resolve("assets/..hidden"));
        Files.writeString(pack.resolve("assets/..hidden/z..txt"), "Z");
        assertThat(PackContainer.isEntryPath("assets/..hidden/z..txt"), is(true));
        assertThat(container.exists("assets/..hidden/z..txt"), is(true));
        assertThat(container.entries("assets").toList(), containsInAnyOrder("assets/x.txt", "assets/..hidden/z..txt"));
        assertThat(container.entries("").toList(), containsInAnyOrder("pack.mcmeta", "assets/x.txt", "assets/..hidden/z..txt"));
    }

    @Test
    @DisplayName("a directory serves a link that stays under its root, and answers one that leaves it as absent")
    void directoryFollowsNoLinkOutOfItsRoot(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("pack");
        Files.createDirectories(pack.resolve("assets/real/sub"));
        Files.writeString(pack.resolve("assets/real/x.txt"), "X");
        Files.writeString(pack.resolve("assets/real/sub/w.txt"), "W");
        Path beside = dir.resolve("beside");
        Files.createDirectories(beside.resolve("sub"));
        Files.writeString(beside.resolve("y.txt"), "Y");
        Files.writeString(beside.resolve("sub/z.txt"), "Z");
        link(pack.resolve("assets/inside.txt"), pack.resolve("assets/real/x.txt"));
        link(pack.resolve("assets/inside"), pack.resolve("assets/real"));
        link(pack.resolve("assets/outside.txt"), beside.resolve("y.txt"));
        link(pack.resolve("assets/outside"), beside);

        PackContainer container = new PackContainer.Directory(pack);
        assertThat(new String(container.bytes("assets/inside.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
        assertThat(new String(container.bytes("assets/inside/x.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
        assertThat(container.exists("assets/inside/sub/w.txt"), is(true));
        assertThat(container.entries("assets/inside/sub").toList(), equalTo(List.of("assets/inside/sub/w.txt")));

        assertThat(container.bytes("assets/outside.txt"), is(Optional.empty()));
        assertThat(container.exists("assets/outside.txt"), is(false));
        assertThat(container.bytes("assets/outside/y.txt"), is(Optional.empty()));
        assertThat(container.exists("assets/outside/y.txt"), is(false));
        assertThat(container.entries("assets/outside/sub").toList(), is(empty()));
        assertThat("a listing names the link that stays inside and not the one that leaves",
            container.entries("assets").toList(), containsInAnyOrder("assets/real/x.txt", "assets/real/sub/w.txt", "assets/inside.txt"));

        // A root that is itself reached through a link is still the root.
        link(dir.resolve("linked"), pack);
        PackContainer linked = new PackContainer.Directory(dir.resolve("linked"));
        assertThat(new String(linked.bytes("assets/real/x.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
        assertThat(linked.entries("assets/real/sub").toList(), equalTo(List.of("assets/real/sub/w.txt")));
        assertThat(linked.exists("assets/outside.txt"), is(false));
    }

    @Test
    @DisplayName("a plain zip with no pack.cats is read into memory as a Live container")
    void plainZip(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("plain.zip");
        writeZip(zip, "assets/y.txt", "Y".getBytes(StandardCharsets.UTF_8), null, null);

        PackContainer container = PackContainer.detect(zip);
        assertThat(container, is(instanceOf(PackContainer.Live.class)));
        assertThat(((PackContainer.Live) container).source(), equalTo(zip));
        assertThat(container.exists("assets/y.txt"), is(true));
        assertThat(container.entries("assets").toList(), hasItem("assets/y.txt"));
        assertThat(new String(container.bytes("assets/y.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("Y"));
    }

    @Test
    @DisplayName("a zip held in memory answers every read the re-opened zip answers")
    void liveAgreesWithZip(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : List.of("pack.mcmeta", "assets/minecraft/textures/block/stone.png",
                "assets/minecraft/textures/block/stone.png.mcmeta", "assets/minecraft_hd/x.txt", "data/minecraft/tags/a.json")) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        PackContainer.Zip reopened = new PackContainer.Zip(zip);
        PackContainer.Live live = PackContainer.Live.read(zip);
        for (String prefix : List.of("", "assets", "assets/minecraft", "assets/minecraft/textures/block/", "data", "absent"))
            assertThat(prefix, live.entries(prefix).toList(), equalTo(reopened.entries(prefix).sorted().toList()));

        for (String path : reopened.entries("").toList()) {
            assertThat(path, live.exists(path), is(true));
            assertThat(path, live.bytes(path).orElseThrow(), equalTo(reopened.bytes(path).orElseThrow()));
        }
        assertThat(live.exists("assets/minecraft/absent.png"), is(false));
        assertThat(live.bytes("assets/minecraft/absent.png").isPresent(), is(false));
        assertThat(live.source(), equalTo(zip));
    }

    @Test
    @DisplayName("entries held already become a Live container over their source, read as given")
    void liveOfHeldEntries(@TempDir Path dir) {
        Path source = dir.resolve("client.jar");
        PackContainer.Live live = PackContainer.Live.of(source, new HashMap<>(Map.of(
            "pack.mcmeta", INNER_MCMETA, "assets/minecraft/x.txt", "X".getBytes(StandardCharsets.UTF_8))));

        assertThat(live.source(), equalTo(source));
        assertThat(live.entries("").toList(), equalTo(List.of("assets/minecraft/x.txt", "pack.mcmeta")));
        assertThat(live.bytes("pack.mcmeta").orElseThrow(), equalTo(INNER_MCMETA));
    }

    @Test
    @DisplayName("an unrecognised file is a hard, named error")
    void unrecognisedSource(@TempDir Path dir) throws IOException {
        Path garbage = dir.resolve("garbage.bin");
        Files.write(garbage, "GARB not a pack".getBytes(StandardCharsets.UTF_8));
        assertThrows(ContentException.class, () -> PackContainer.detect(garbage));
        assertThrows(ContentException.class, () -> PackContainer.detect(dir.resolve("does-not-exist")));
    }

    // fixtures

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

    /** Makes a symbolic link, abandoning the test on a platform, or for a user, that may not make one. */
    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException ex) {
            throw new TestAbortedException("symbolic links cannot be made here", ex);
        }
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
