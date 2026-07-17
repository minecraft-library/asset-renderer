package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Assignment;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Preferred;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Rung;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    private static PackNameSources file(String name) {
        return PackNameSources.of(Path.of(name));
    }

    @Test
    @DisplayName("rung 1: filename stem drives the id, extension chain stripped")
    void filenameRung() {
        Preferred eureka = PackIdDeriver.preferred(file("eureka.cats.zip"));
        assertThat(eureka.id(), is(new PackId("eureka")));
        assertThat(eureka.rung(), is(Rung.FILENAME));

        Preferred fursky = PackIdDeriver.preferred(file("FurSky Reborn.cats.zip"));
        assertThat(fursky.id(), is(new PackId("fursky-reborn")));
        assertThat(fursky.rung(), is(Rung.FILENAME));
    }

    @Test
    @DisplayName("rung 4: a numbers-only name falls through to the synthetic id")
    void syntheticRung() {
        Preferred p = PackIdDeriver.preferred(file("26.1"));
        assertThat(p.id(), is(new PackId("pack")));
        assertThat(p.rung(), is(Rung.SYNTHETIC));
    }

    @Test
    @DisplayName("rung 2: the LICENSE title line, license tokens dropped")
    void licenseRung() {
        PackNameSources sources = new PackNameSources(
            Path.of("123"), Optional.of("HYPIXEL SKYBLOCK RESOURCE PACK LICENSE"), Optional.empty());
        Preferred p = PackIdDeriver.preferred(sources);
        assertThat(p.id(), is(new PackId("hypixel-skyblock")));
        assertThat(p.rung(), is(Rung.LICENSE));
    }

    @Test
    @DisplayName("rung 3: a short description; a long one is rejected")
    void descriptionRung() {
        PackNameSources usable = new PackNameSources(Path.of("123"), Optional.empty(), Optional.of("Hypixel SkyBlock"));
        Preferred p = PackIdDeriver.preferred(usable);
        assertThat(p.id(), is(new PackId("hypixel-skyblock")));
        assertThat(p.rung(), is(Rung.DESCRIPTION));

        PackNameSources tooLong = new PackNameSources(Path.of("123"), Optional.empty(), Optional.of("one two three four five six"));
        assertThat(PackIdDeriver.preferred(tooLong).rung(), is(Rung.SYNTHETIC));
    }

    @Test
    @DisplayName("colliding ids get -b, -c in supply order with a loud warning")
    void collisionSuffixes() {
        List<PackNameSources> supply = List.of(file("Defrosted.zip"), file("defrosted"), file("DEFROSTED.cats"));
        ConcurrentList<Assignment> assigned = captureErr(() -> PackIdDeriver.assign(supply)).result();

        assertThat(assigned.getFirst().id(), is(new PackId("defrosted")));
        assertThat(assigned.getFirst().collisionBase(), is(Optional.empty()));
        assertThat(assigned.get(1).id(), is(new PackId("defrosted-b")));
        assertThat(assigned.get(1).collisionBase(), is(Optional.of(new PackId("defrosted"))));
        assertThat(assigned.getLast().id(), is(new PackId("defrosted-c")));
    }

    @Test
    @DisplayName("a collision emits a warning naming the id")
    void collisionWarns() {
        Captured<ConcurrentList<Assignment>> captured =
            captureErr(() -> PackIdDeriver.assign(List.of(file("Defrosted.zip"), file("defrosted"))));
        assertThat(captured.err(), containsString("collision"));
        assertThat(captured.err(), containsString("defrosted-b"));
    }

    @Test
    @DisplayName("reserved ids are pre-taken, so the first user collider is suffixed")
    void reservedPreseeded() {
        ConcurrentList<Assignment> assigned = captureErr(() -> PackIdDeriver.assign(List.of(file("Vanilla.zip")))).result();
        assertThat(assigned.getFirst().id(), is(new PackId("vanilla-b")));
        assertThat(assigned.getFirst().collisionBase(), is(Optional.of(PackId.VANILLA)));
    }

    @Test
    @DisplayName("supplying the identical source twice is an error, not a suffix")
    void sameSourceTwiceErrors() {
        PackNameSources s = file("pack.zip");
        assertThrows(PipelineException.class, () -> PackIdDeriver.assign(List.of(s, s)));
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
