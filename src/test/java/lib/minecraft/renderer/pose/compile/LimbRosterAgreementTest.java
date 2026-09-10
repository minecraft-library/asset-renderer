package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Side;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster against the four spellings the walker tier resolves by hand.
 *
 * <p>The tier reaches one leg through a fixed cross of two rows and two sides, which is four string
 * literals. A resolved roster has to answer with those same four wherever a mesh carries them, and
 * a disagreement is not a compile error but a missing key at install - so it is asserted here,
 * against every mesh in the corpus that names all four, rather than believed from a probe.
 */
@DisplayName("the roster answers the four leg names the walker tier resolves")
class LimbRosterAgreementTest {

    /** What the walker tier resolves each rank and side to, and what the roster must agree with. */
    private static final @NotNull Map<String, String> SPELLINGS = Map.of(
        "FRONT LEFT", "left_front_leg",
        "FRONT RIGHT", "right_front_leg",
        "HIND LEFT", "left_hind_leg",
        "HIND RIGHT", "right_hind_leg"
    );

    /** The shipped geometry table. */
    private static final @NotNull Map<String, EntityModelData> GEOMETRIES = geometries();

    /** The {@code entity_geometry.json} payload. */
    private record GeometryFile(@NotNull Map<String, EntityModelData> geometries) {}

    /**
     * Reads the shipped geometry table.
     */
    private static @NotNull Map<String, EntityModelData> geometries() {
        ResourceDocument document = BundledResource.read("entity_geometry.json").orElseThrow();
        return new TreeMap<>(document.as(GeometryFile.class).geometries());
    }

    /**
     * The coordinates whose mesh names all four of the tier's leg spellings.
     */
    private static @NotNull List<String> walkerCoordinates() {
        return GEOMETRIES.entrySet().stream()
            .filter(entry -> SPELLINGS.values().stream()
                .allMatch(bone -> entry.getValue().getBones().containsKey(bone)))
            .map(Map.Entry::getKey)
            .toList();
    }

    @Test
    @DisplayName("every mesh naming the four resolves each rank and side to the same bone")
    void theRosterAgreesWithTheTierSpellings() {
        List<String> coordinates = walkerCoordinates();
        assertFalse(coordinates.isEmpty(), "the corpus carries meshes naming all four leg spellings");

        List<String> disagreements = new ArrayList<>();
        for (String coordinate : coordinates) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            for (Rank rank : Rank.values())
                for (Side side : Side.values()) {
                    String expected = SPELLINGS.get(rank + " " + side);
                    Optional<String> resolved = roster.resolve(rank, side);
                    if (!resolved.filter(expected::equals).isPresent())
                        disagreements.add(coordinate + ": " + rank + " " + side
                            + " resolves " + resolved.orElse("nothing") + ", the tier spells " + expected);
                }
        }

        assertEquals(List.of(), disagreements,
            () -> disagreements.size() + " of " + coordinates.size() * 4 + " resolutions disagree");
    }

    @Test
    @DisplayName("a walker mesh carries a front row and a hind row, and the two ranks name different rows")
    void aWalkerCarriesTheRankedRows() {
        for (String coordinate : walkerCoordinates()) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            assertTrue(roster.rows().size() >= 2, coordinate + " carries a front row and a hind row");
            assertEquals(2 * roster.rows().size(), roster.legCount(),
                coordinate + " paints two legs a row");
            assertTrue(roster.row(Rank.FRONT).isPresent() && roster.row(Rank.HIND).isPresent(),
                coordinate + " answers both ranks");
            assertEquals(0, roster.row(Rank.FRONT).orElseThrow().ordinal(),
                coordinate + " reads the front rank as the frontmost row");
            assertTrue(roster.row(Rank.FRONT).orElseThrow().ordinal()
                    != roster.row(Rank.HIND).orElseThrow().ordinal(),
                coordinate + " answers two different rows");
        }
    }

    @Test
    @DisplayName("a walker carrying a row between the two ranks leaves that row unaddressed")
    void aRowBetweenTheRanksIsUnaddressed() {
        List<String> deeper = walkerCoordinates().stream()
            .filter(coordinate -> LimbRoster.of(GEOMETRIES.get(coordinate)).rows().size() > 2)
            .toList();
        assertFalse(deeper.isEmpty(),
            "the corpus carries a mesh naming the four spellings over more than two rows");

        for (String coordinate : deeper) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            List<String> addressable = new ArrayList<>();
            for (Rank rank : Rank.values())
                for (Side side : Side.values())
                    roster.resolve(rank, side).ifPresent(addressable::add);

            List<String> seated = roster.rows().stream()
                .flatMap(row -> row.members().stream())
                .filter(member -> member.depth() == 0)
                .map(LimbRoster.Member::bone)
                .toList();

            assertEquals(4, addressable.size(), coordinate + " answers four of its legs");
            assertTrue(seated.size() > addressable.size(),
                () -> coordinate + " seats legs no rank names: " + seated + " against " + addressable);
        }
    }

    @Test
    @DisplayName("the front row sits ahead of the hind row on every walker mesh")
    void theFrontRowSitsAhead() {
        for (String coordinate : walkerCoordinates()) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            assertEquals(0, roster.row(Rank.FRONT).orElseThrow().ordinal(),
                coordinate + " numbers the front row first");
            assertEquals(roster.rows().size() - 1, roster.row(Rank.HIND).orElseThrow().ordinal(),
                coordinate + " numbers the hind row last");
        }
    }

}
