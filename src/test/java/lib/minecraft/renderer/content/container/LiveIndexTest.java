package lib.minecraft.renderer.content.container;

import lib.minecraft.renderer.exception.ContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The in-memory index a {@link PackContainer.Live} reads through - ordering, the directory range, the
 * copy a read hands back, and the duplicate-name and failure cases a zip can present.
 */
@DisplayName("LiveIndex")
class LiveIndexTest {

    @Test
    @DisplayName("entries come back in ascending path order, whatever order the zip stored them in")
    void ascending(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, Map.of("b/2.txt", "2", "a/1.txt", "1", "c.txt", "3", "a/0.txt", "0"));

        assertThat(LiveIndex.read(zip).range("").toList(), equalTo(List.of("a/0.txt", "a/1.txt", "b/2.txt", "c.txt")));
    }

    @Test
    @DisplayName("a directory's range holds its entries at any depth and never a sibling sharing its name")
    void range(@TempDir Path dir) throws IOException {
        LiveIndex index = LiveIndex.read(zip(dir, Map.of(
            "assets/minecraft/a.txt", "A",
            "assets/minecraft/deep/b.txt", "B",
            "assets/minecraft_hd/c.txt", "C",
            "assets/minecraft.txt", "D"
        )));

        assertThat(index.range("assets/minecraft/").toList(), equalTo(List.of("assets/minecraft/a.txt", "assets/minecraft/deep/b.txt")));
        assertThat(index.range("assets/minecraft_hd/").toList(), equalTo(List.of("assets/minecraft_hd/c.txt")));
        assertThat(index.range("absent/").toList(), is(empty()));
    }

    @Test
    @DisplayName("a read hands back a copy, so changing it changes nothing the next read sees")
    void readCopies(@TempDir Path dir) throws IOException {
        LiveIndex index = LiveIndex.read(zip(dir, Map.of("x.txt", "X")));

        byte[] first = index.read("x.txt").orElseThrow();
        first[0] = 'Y';

        assertThat(new String(index.read("x.txt").orElseThrow(), StandardCharsets.UTF_8), equalTo("X"));
    }

    @Test
    @DisplayName("a path the zip does not carry reads empty and is not contained")
    void miss(@TempDir Path dir) throws IOException {
        LiveIndex index = LiveIndex.read(zip(dir, Map.of("x.txt", "X")));

        assertThat(index.read("y.txt").isPresent(), is(false));
        assertThat(index.contains("y.txt"), is(false));
        assertThat(index.contains("x.txt"), is(true));
    }

    @Test
    @DisplayName("a zip's directory records are not entries, and an empty zip holds none")
    void directoriesAndEmpty(@TempDir Path dir) throws IOException {
        Path withDirectory = dir.resolve("dirs.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(withDirectory))) {
            out.putNextEntry(new ZipEntry("assets/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/x.txt"));
            out.write('X');
            out.closeEntry();
        }
        assertThat(LiveIndex.read(withDirectory).range("").toList(), equalTo(List.of("assets/x.txt")));

        LiveIndex none = LiveIndex.read(zip(dir.resolve("none"), Map.of()));
        assertThat(none.size(), is(0));
        assertThat(none.range("").toList(), is(empty()));
    }

    @Test
    @DisplayName("size and bytes count what the index holds")
    void counts(@TempDir Path dir) throws IOException {
        LiveIndex index = LiveIndex.read(zip(dir, Map.of("a.txt", "AB", "b.txt", "CDE")));

        assertThat(index.size(), is(2));
        assertThat(index.bytes(), is(5L));
    }

    @Test
    @DisplayName("two records under one name answer the bytes ZipFile.getEntry answers - the later one")
    void duplicateNames(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("x/a.txt"));
            out.write("FIRST".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("x/b.txt"));
            out.write("SECOND".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        // The second record renamed to the first's name in its local header and the central
        // directory: the same length, so every offset holds. ZipOutputStream refuses to write one.
        Path zip = dir.resolve("duplicate.zip");
        Files.write(zip, bytes.toString(StandardCharsets.ISO_8859_1).replace("x/b.txt", "x/a.txt").getBytes(StandardCharsets.ISO_8859_1));

        byte[] zipAnswers = new PackContainer.Zip(zip).bytes("x/a.txt").orElseThrow();
        assertThat(new String(zipAnswers, StandardCharsets.UTF_8), equalTo("SECOND"));
        assertThat(LiveIndex.read(zip).read("x/a.txt").orElseThrow(), equalTo(zipAnswers));
        assertThat(LiveIndex.read(zip).range("").toList(), equalTo(List.of("x/a.txt")));
    }

    @Test
    @DisplayName("a file that is not a zip is a ContentException")
    void corrupt(@TempDir Path dir) throws IOException {
        Path garbage = dir.resolve("garbage.zip");
        Files.write(garbage, "PK not really a zip".getBytes(StandardCharsets.UTF_8));

        assertThrows(ContentException.class, () -> LiveIndex.read(garbage));
    }

    @Test
    @DisplayName("entries held already are indexed as given, sorted")
    void of() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("z.txt", new byte[] { 'Z' });
        entries.put("a.txt", new byte[] { 'A' });
        LiveIndex index = LiveIndex.of(entries);

        assertThat(index.range("").toList(), equalTo(List.of("a.txt", "z.txt")));
        assertThat(index.read("z.txt").orElseThrow(), equalTo(new byte[] { 'Z' }));
    }

    /** Writes a zip of the given text entries under a directory, creating it. */
    private static Path zip(Path dir, Map<String, String> entries) throws IOException {
        Files.createDirectories(dir);
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

}
