package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Assignment;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Rung;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PackProvenance} - the sidecar record - stamps the current heuristic version and
 * round-trips through its JSON form, including the optional collision trail.
 */
@DisplayName("PackProvenance sidecar round-trip")
class PackProvenanceTest {

    @Test
    @DisplayName("a bare (uncollided) assignment round-trips with the heuristic version stamped")
    void bareRoundTrip() {
        Assignment assignment = new Assignment(new PackId("eureka"), Rung.FILENAME, "eureka", Optional.empty());
        PackProvenance provenance = PackProvenance.of(assignment, Path.of("packs/eureka.cats.zip"), 1_720_000_000_000L);

        assertThat(provenance.heuristicVersion(), is(PackIdDeriver.HEURISTIC_VERSION));
        assertThat(PackProvenance.parse(provenance.toJson()), is(provenance));
    }

    @Test
    @DisplayName("a collided assignment round-trips its collision base")
    void collidedRoundTrip() {
        Assignment assignment = new Assignment(new PackId("defrosted-b"), Rung.FILENAME, "defrosted", Optional.of(new PackId("defrosted")));
        PackProvenance provenance = PackProvenance.of(assignment, Path.of("packs/Defrosted.zip"), 42L);

        PackProvenance restored = PackProvenance.parse(provenance.toJson());
        assertThat(restored, is(provenance));
        assertThat(restored.collisionBase(), is(Optional.of(new PackId("defrosted"))));
    }

    @Test
    @DisplayName("parse fails with a PipelineException on malformed JSON, a missing field, or a bad rung")
    void parseErrors() {
        assertThrows(PipelineException.class, () -> PackProvenance.parse("{ broken"));
        // missing the numeric source_modified_millis field (a require guard, not a raw NPE)
        assertThrows(PipelineException.class, () -> PackProvenance.parse(
            "{\"id\":\"eureka\",\"source_path\":\"p\",\"heuristic_version\":1,\"raw_name\":\"eureka\",\"chosen_rung\":\"FILENAME\"}"));
        // an unrecognised chosen_rung enum value
        assertThrows(PipelineException.class, () -> PackProvenance.parse(
            "{\"id\":\"eureka\",\"source_path\":\"p\",\"source_modified_millis\":1,\"heuristic_version\":1,\"raw_name\":\"eureka\",\"chosen_rung\":\"BOGUS\"}"));
    }

}
