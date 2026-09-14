package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.pose.author.LimbSelector;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Reach;
import lib.minecraft.renderer.pose.author.Side;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector run over every shipped geometry, promoted out of a probe and into the suite.
 *
 * <p>What it holds is structure rather than a count. A leg count reproduces on the whole corpus
 * under a classifier that reads both rabbit meshes' row groupers as fused rows and their four legs
 * as segments - same total, every leg addressed on the wrong bone - so the population tallies below
 * are stated per kind, and the three meshes that separate one reading from the other are named
 * bone for bone.
 */
@DisplayName("the leg detector answers the shipped corpus")
class LimbRosterCorpusTest {

    /** The shipped geometry table, keyed by the coordinate an entity's axes name. */
    private static final @NotNull Map<String, EntityModelData> GEOMETRIES = geometries();

    /** The {@code entity_geometry.json} payload, read for the whole table rather than one join. */
    private record GeometryFile(@NotNull Map<String, EntityModelData> geometries) {}

    /**
     * Reads the shipped geometry table.
     */
    private static @NotNull Map<String, EntityModelData> geometries() {
        ResourceDocument document = BundledResource.read("entity_geometry.json").orElseThrow();
        return new TreeMap<>(document.as(GeometryFile.class).geometries());
    }

    /**
     * Every roster the corpus produces, keyed by geometry coordinate.
     */
    private static @NotNull Map<String, LimbRoster> rosters() {
        Map<String, LimbRoster> rosters = new TreeMap<>();
        GEOMETRIES.forEach((coordinate, mesh) -> rosters.put(coordinate, LimbRoster.of(mesh)));
        return rosters;
    }

    @Test
    @DisplayName("every geometry answers, and the kind populations are what the corpus holds")
    void kindPopulationsHold() {
        Map<LimbRoster.Kind, Integer> tally = new EnumMap<>(LimbRoster.Kind.class);
        for (LimbRoster.Kind kind : LimbRoster.Kind.values()) tally.put(kind, 0);
        rosters().values().forEach(roster -> roster.rows()
            .forEach(row -> row.members()
                .forEach(member -> tally.merge(member.kind(), 1, Integer::sum))));

        assertEquals(356, tally.get(LimbRoster.Kind.ROOT), () -> "leg roots, over " + tally);
        assertEquals(7, tally.get(LimbRoster.Kind.FUSED), () -> "fused rows, over " + tally);
        assertEquals(21, tally.get(LimbRoster.Kind.SEGMENT), () -> "segments, over " + tally);
    }

    @Test
    @DisplayName("the row buckets are what the corpus holds")
    void rowBucketsHold() {
        Map<Integer, Integer> buckets = new TreeMap<>();
        rosters().values().forEach(roster -> buckets.merge(roster.rows().size(), 1, Integer::sum));

        assertEquals(42, buckets.getOrDefault(0, 0), () -> "legless geometries, over " + buckets);
        assertEquals(49, buckets.getOrDefault(1, 0), () -> "one-row geometries, over " + buckets);
        assertEquals(58, buckets.getOrDefault(2, 0), () -> "two-row geometries, over " + buckets);
        assertEquals(4, buckets.getOrDefault(3, 0), () -> "three-row geometries, over " + buckets);
        assertEquals(2, buckets.getOrDefault(4, 0), () -> "four-row geometries, over " + buckets);
        assertEquals(155, buckets.values().stream().mapToInt(Integer::intValue).sum(),
            () -> "every geometry answers, over " + buckets);
    }

    @Test
    @DisplayName("a rabbit's row groupers hold four sided legs, which no leg count can tell from two fused rows")
    void rabbitGroupersHoldFourSidedLegs() {
        for (String coordinate : coordinatesNaming("frontlegs")) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            assertEquals(2, roster.rows().size(), coordinate + " carries two rows");
            assertEquals(4, roster.legCount(), coordinate + " paints four legs");

            List<LimbRoster.Member> seats = roster.rows().stream()
                .flatMap(row -> row.members().stream())
                .filter(member -> member.depth() == 0)
                .toList();
            assertEquals(4, seats.size(), () -> coordinate + " seats four legs, not two fused rows: " + seats);
            seats.forEach(seat -> {
                assertEquals(LimbRoster.Kind.ROOT, seat.kind(),
                    () -> coordinate + " seats '" + seat.bone() + "' as a leg of its own");
                assertTrue(seat.side().isPresent(),
                    () -> coordinate + " reads a side for '" + seat.bone() + "'");
            });
            assertTrue(seats.stream().noneMatch(seat -> LimbRoster.legish(seat.bone())
                    && seat.bone().equals("frontlegs")),
                coordinate + " never seats the grouper itself");
        }
    }

    @Test
    @DisplayName("a bee's three fused rows each count two legs and carry no side")
    void beeFusedRowsCarryNoSide() {
        for (String coordinate : coordinatesNaming("middle_legs")) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            assertEquals(3, roster.rows().size(), coordinate + " carries three rows");
            assertEquals(6, roster.legCount(), coordinate + " paints six legs");
            roster.rows().forEach(row -> {
                assertEquals(1, row.members().size(),
                    () -> coordinate + " row " + row.ordinal() + " is one bone");
                LimbRoster.Member fused = row.members().getFirst();
                assertEquals(LimbRoster.Kind.FUSED, fused.kind(),
                    () -> coordinate + " reads '" + fused.bone() + "' as one bone painting two legs");
                assertEquals(Optional.empty(), fused.side(),
                    () -> coordinate + " reads no side for '" + fused.bone() + "'");
            });
        }
    }

    @Test
    @DisplayName("a dragon's legs are four roots over eight segments, two deep")
    void dragonChainsRunTwoDeep() {
        for (String coordinate : coordinatesNaming("left_front_leg_tip")) {
            LimbRoster roster = LimbRoster.of(GEOMETRIES.get(coordinate));
            List<LimbRoster.Member> members = roster.rows().stream()
                .flatMap(row -> row.members().stream())
                .toList();

            assertEquals(4, members.stream().filter(member -> member.depth() == 0).count(),
                () -> coordinate + " seats four legs: " + members);
            assertEquals(8, members.stream().filter(member -> member.depth() > 0).count(),
                () -> coordinate + " carries eight segments: " + members);
            assertEquals(2, members.stream().mapToInt(LimbRoster.Member::depth).max().orElse(0),
                coordinate + " runs two segments deep");
        }
    }

    @Test
    @DisplayName("every member is a bone its mesh declares, and no bone is a member twice")
    void membersAreDeclaredExactlyOnce() {
        rosters().forEach((coordinate, roster) -> {
            Set<String> seen = new LinkedHashSet<>();
            roster.rows().forEach(row -> row.members().forEach(member -> {
                assertTrue(GEOMETRIES.get(coordinate).getBones().containsKey(member.bone()),
                    () -> coordinate + " names '" + member.bone() + "', which the mesh declares");
                assertTrue(seen.add(member.bone()),
                    () -> coordinate + " holds '" + member.bone() + "' once");
            }));
        });
    }

    @Test
    @DisplayName("rows run front to back and a sided row reads right before left")
    void rowsAndSidesAreOrdered() {
        rosters().forEach((coordinate, roster) -> {
            for (int ordinal = 0; ordinal < roster.rows().size(); ordinal++)
                assertEquals(ordinal, roster.rows().get(ordinal).ordinal(),
                    coordinate + " numbers its rows from the front");
            roster.rows().forEach(row -> {
                List<Side> sides = row.members().stream()
                    .filter(member -> member.depth() == 0)
                    .map(LimbRoster.Member::side)
                    .flatMap(Optional::stream)
                    .toList();
                if (sides.size() == 2)
                    assertEquals(List.of(Side.RIGHT, Side.LEFT), sides,
                        () -> coordinate + " row " + row.ordinal() + " reads right before left");
            });
        });
    }

    @Test
    @DisplayName("no geometry resolves ambiguously, so a hit is a mesh the detector has not met")
    void theCorpusIsUnambiguous() {
        List<String> ambiguous = new ArrayList<>();
        rosters().forEach((coordinate, roster) -> roster.ambiguities()
            .forEach(note -> ambiguous.add(coordinate + ": " + note)));
        assertTrue(ambiguous.stream().noneMatch(note -> note.contains("neither a side token")),
            () -> "every leg root resolves a side: " + ambiguous);
        assertTrue(ambiguous.stream().noneMatch(note -> note.contains("fewer than two legs")),
            () -> "every row grouper holds a row: " + ambiguous);
    }

    @Test
    @DisplayName("exactly one mesh names a leg against the side it sits on, and the detector says so")
    void onlyOneMeshNamesALegAgainstItsPosition() {
        List<String> crossed = new ArrayList<>();
        rosters().forEach((coordinate, roster) -> roster.ambiguities().stream()
            .filter(note -> note.contains("is named"))
            .forEach(note -> crossed.add(coordinate + ": " + note)));

        assertEquals(List.of(
                "BabyArmadilloModel#createBodyLayer: leg 'right_front_leg' is named RIGHT and sits LEFT",
                "BabyArmadilloModel#createBodyLayer: leg 'left_front_leg' is named LEFT and sits RIGHT"),
            crossed,
            "the one mesh vanilla names against its own geometry, and the only one");
    }

    @Test
    @DisplayName("a segment takes the side of the leg it hangs off, never the side its own name claims")
    void aSegmentInheritsTheSeatsSide() {
        List<String> misread = new ArrayList<>();
        rosters().forEach((coordinate, roster) -> roster.rows().forEach(row -> {
            for (LimbRoster.Member seat : row.members()) {
                if (seat.depth() != 0) continue;
                row.members().stream()
                    .filter(member -> member.depth() > 0)
                    .filter(member -> member.side().equals(seat.side()))
                    .filter(member -> member.bone().contains("foot"))
                    .forEach(member -> {
                        String claims = member.bone().startsWith("left") ? "LEFT" : "RIGHT";
                        String holds = seat.side().map(Enum::name).orElse("none");
                        if (!claims.equals(holds))
                            misread.add(coordinate + ": '" + member.bone() + "' claims " + claims
                                + " and is held at " + holds);
                    });
            }
        }));

        assertEquals(List.of(
                "HumanoidModel#createBabyArmorMesh: 'left_foot' claims LEFT and is held at RIGHT",
                "HumanoidModel#createBabyArmorMesh: 'right_foot' claims RIGHT and is held at LEFT",
                "HumanoidModel#createBabyArmorMesh@pose=0.5,-0.5,0.0: 'left_foot' claims LEFT and is held at RIGHT",
                "HumanoidModel#createBabyArmorMesh@pose=0.5,-0.5,0.0: 'right_foot' claims RIGHT and is held at LEFT"),
            misread,
            "vanilla cross-parents the baby boots, so the chain edge decides the side and the name does not");
    }

    @Test
    @DisplayName("a mesh naming no leg carries no row rather than an empty one")
    void alegSlessMeshCarriesNoRow() {
        LimbRoster roster = LimbRoster.of(new EntityModelData());
        assertTrue(roster.rows().isEmpty(), "no mesh, no rows");
        assertEquals(0, roster.legCount(), "no mesh, no legs");
        assertFalse(roster.row(Rank.FRONT).isPresent(),
            "a rank addresses nothing where the mesh carries no row");
    }

    @Test
    @DisplayName("every member answers where it sits, and a bone no row holds answers nowhere")
    void everyMemberIsPlaced() {
        rosters().forEach((coordinate, roster) -> {
            roster.rows().forEach(row -> row.members().forEach(member ->
                assertEquals(Optional.of(new LimbRoster.Placement(row.ordinal(), member)),
                    roster.placementOf(member.bone()),
                    () -> coordinate + " places '" + member.bone() + "' in the row holding it")));

            Set<String> held = new LinkedHashSet<>();
            roster.rows().forEach(row -> row.members().forEach(member -> held.add(member.bone())));
            GEOMETRIES.get(coordinate).getBones().keySet().stream()
                .filter(bone -> !held.contains(bone))
                .forEach(bone -> assertEquals(Optional.empty(), roster.placementOf(bone),
                    () -> coordinate + " holds '" + bone + "' in no row of legs"));
        });
    }

    @Test
    @DisplayName("a rank's own bones are placed in the row that rank addresses")
    void aRanksBonesArePlacedInItsRow() {
        for (Rank rank : Rank.values())
            rosters().forEach((coordinate, roster) -> {
                int addressed = roster.row(rank).map(LimbRoster.Row::ordinal).orElse(-1);
                for (String bone : roster.members(new LimbSelector.Legs(
                    Optional.of(rank), Optional.empty(), Reach.CHAIN, LimbSelector.Stamp.LONE)))
                    assertEquals(addressed,
                        roster.placementOf(bone).map(LimbRoster.Placement::row).orElse(-1),
                        () -> coordinate + " answers '" + bone + "' for " + rank
                            + " out of the row that rank addresses");
            });
    }

    /**
     * The geometry coordinates whose mesh declares the given bone.
     *
     * @param bone the bone name a family is recognised by
     * @return the coordinates, never empty
     */
    private static @NotNull List<String> coordinatesNaming(@NotNull String bone) {
        List<String> found = GEOMETRIES.entrySet().stream()
            .filter(entry -> entry.getValue().getBones().containsKey(bone))
            .map(Map.Entry::getKey)
            .toList();
        assertFalse(found.isEmpty(), "the corpus declares a mesh naming '" + bone + "'");
        return found;
    }

}
