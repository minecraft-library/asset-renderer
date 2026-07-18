package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Candidate;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Naming;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Rung;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PackIdDeriver} - the four-rung id ladder and the cross-pack collision resolution.
 * Covers the filename/license/description/synthetic rungs, the reserved-id pre-seeding, letter-ordinal
 * collision suffixes with their loud warning, and the same-source-twice error.
 */
@DisplayName("PackIdDeriver ladder + collision resolution")
class PackIdDeriverTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("rung 1: filename stem drives the id, extension chain stripped")
    void filenameRung() {
        Candidate eureka = PackIdDeriver.preferred(file("eureka.cats.zip"));
        assertThat(eureka.id(), is(new PackId("eureka")));
        assertThat(eureka.rung(), is(Rung.FILENAME));

        Candidate fursky = PackIdDeriver.preferred(file("FurSky Reborn.cats.zip"));
        assertThat(fursky.id(), is(new PackId("fursky-reborn")));
        assertThat(fursky.rung(), is(Rung.FILENAME));
    }

    @Test
    @DisplayName("rung 4: a numbers-only name falls through to the synthetic id")
    void syntheticRung() {
        Candidate p = PackIdDeriver.preferred(file("26.1"));
        assertThat(p.id(), is(new PackId("pack")));
        assertThat(p.rung(), is(Rung.SYNTHETIC));
    }

    @Test
    @DisplayName("rung 2: the LICENSE title line, license tokens dropped")
    void licenseRung() {
        Candidate p = PackIdDeriver.preferred(withLicense("123", "HYPIXEL SKYBLOCK RESOURCE PACK LICENSE"));
        assertThat(p.id(), is(new PackId("hypixel-skyblock")));
        assertThat(p.rung(), is(Rung.LICENSE));
    }

    @Test
    @DisplayName("rung 3: a short description; a long one is rejected")
    void descriptionRung() {
        Candidate p = PackIdDeriver.preferred(withDescription("123", "Hypixel SkyBlock"));
        assertThat(p.id(), is(new PackId("hypixel-skyblock")));
        assertThat(p.rung(), is(Rung.DESCRIPTION));

        assertThat(PackIdDeriver.preferred(withDescription("123", "one two three four five six")).rung(), is(Rung.SYNTHETIC));
    }

    @Test
    @DisplayName("colliding ids get -b, -c in supply order with a loud warning")
    void collisionSuffixes() {
        List<Naming> supply = List.of(file("Defrosted.zip"), file("defrosted"), file("DEFROSTED.cats"));
        ConcurrentList<PackId> assigned = captureErr(() -> PackIdDeriver.assign(supply)).result();

        assertThat(assigned.getFirst(), is(new PackId("defrosted")));
        assertThat(assigned.get(1), is(new PackId("defrosted-b")));
        assertThat(assigned.getLast(), is(new PackId("defrosted-c")));
    }

    @Test
    @DisplayName("a collision emits a warning naming the id")
    void collisionWarns() {
        Captured<ConcurrentList<PackId>> captured =
            captureErr(() -> PackIdDeriver.assign(List.of(file("Defrosted.zip"), file("defrosted"))));
        assertThat(captured.err(), containsString("collision"));
        assertThat(captured.err(), containsString("defrosted-b"));
    }

    @Test
    @DisplayName("reserved ids are pre-taken, so the first user collider is suffixed")
    void reservedPreseeded() {
        ConcurrentList<PackId> assigned = captureErr(() -> PackIdDeriver.assign(List.of(file("Vanilla.zip")))).result();
        assertThat(assigned.getFirst(), is(new PackId("vanilla-b")));
    }

    @Test
    @DisplayName("supplying the identical source twice is an error, not a suffix")
    void sameSourceTwiceErrors() {
        Naming s = file("pack.zip");
        assertThrows(PipelineException.class, () -> PackIdDeriver.assign(List.of(s, s)));
    }

    // --- naming fixtures (a Directory container serves the LICENSE / mcmeta a rung reads) ---

    /** A naming handle over a source filename whose container ships nothing (only filename/synthetic fire). */
    private Naming file(String name) {
        return new Naming(Path.of(name), new PackContainer.Directory(freshDir()));
    }

    /** A naming handle whose container ships a root {@code LICENSE} with the given first line. */
    private Naming withLicense(String name, String licenseText) {
        Path dir = freshDir();
        write(dir.resolve("LICENSE"), licenseText);
        return new Naming(Path.of(name), new PackContainer.Directory(dir));
    }

    /** A naming handle whose container ships a {@code pack.mcmeta} carrying the given description. */
    private Naming withDescription(String name, String description) {
        Path dir = freshDir();
        write(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"" + description + "\"}}");
        return new Naming(Path.of(name), new PackContainer.Directory(dir));
    }

    private Path freshDir() {
        try {
            return Files.createTempDirectory(this.tmp, "pack");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // --- System.err capture helper ---

    private record Captured<T>(T result, String err) {}

    private static <T> Captured<T> captureErr(java.util.function.Supplier<T> body) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            T result = body.get();
            return new Captured<>(result, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }

}
