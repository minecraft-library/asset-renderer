package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two derived relationships read over the WHOLE shipped roster - every seat the state
 * silhouettes reveal on every body and baby mesh, and every anatomical name a tier verb lands
 * away from the bone it names - printed as one table and held to what makes anatomical sense:
 * a seat graph is a forest whose leaders are top-level bones, a humanoid's limbs ride nothing
 * however close to the body they stand at bind, and a joint only ever climbs the mesh's own
 * parents.
 */
@DisplayName("the derived relationships over the shipped roster")
class SeatsRosterTest {

    /**
     * The canonical humanoid limb roster - a mesh declaring all six is a biped whose limbs
     * are adjacent to the body at bind and must ride nothing.
     */
    private static final @NotNull Set<String> HUMANOID_LIMBS =
        Set.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg");

    @Test
    @DisplayName("every seat over the roster sits in a forest of top-level bones, and no biped limb rides the body")
    void seatsOverTheRosterMakeSense() {
        ConcurrentMap<String, Entity> definitions = EntityModelLoader.load();
        assumeTrue(!definitions.isEmpty(), "bundled entity tables are present");

        StringBuilder table = new StringBuilder("derived seats over the shipped roster (follower <- leader @ offset in the leader's resting frame)\n");
        int rowsCarrying = 0;
        int seats = 0;
        for (Entity row : new TreeMap<>(definitions).values()) {
            for (Map.Entry<String, Subject> subject : subjectsOf(row).entrySet()) {
                Seats.Derived derived = Seats.derive(subject.getValue().pose(), subject.getValue().mesh());
                if (derived.seats().isEmpty()) continue;
                rowsCarrying++;
                table.append(String.format(Locale.ROOT, "  %-28s %s%n", subject.getKey(), describe(derived)));
                for (Map.Entry<String, Seats.Seat> seat : derived.seats().entrySet()) {
                    seats++;
                    assertTrue(derived.rest().containsKey(seat.getValue().leader()),
                        subject.getKey() + ": leader '" + seat.getValue().leader() + "' is a top-level bone");
                    assertTrue(derived.rest().containsKey(seat.getKey()),
                        subject.getKey() + ": follower '" + seat.getKey() + "' is a top-level bone");
                    assertFalse(seat.getValue().leader().equals(seat.getKey()), subject.getKey() + ": a bone never rides itself");
                    assertTrue(terminates(seat.getKey(), derived), subject.getKey() + ": the chain from '" + seat.getKey() + "' is acyclic");
                }
                if (subject.getValue().mesh().getBones().keySet().containsAll(HUMANOID_LIMBS))
                    assertTrue(derived.seats().isEmpty(), subject.getKey() + ": a biped's limbs ride nothing, yet: " + describe(derived));
            }
        }
        table.append(String.format(Locale.ROOT, "  %d subject(s) carry %d seat(s)%n", rowsCarrying, seats));
        System.out.print(table);
        assertTrue(rowsCarrying > 0, "the roster derives seats somewhere, or the print is vacuous");
    }

    @Test
    @DisplayName("every joint a tier verb resolves to climbs the mesh's own parents to a bone the shipped pose turns")
    void jointsOverTheRosterMakeSense() {
        ConcurrentMap<String, Entity> definitions = EntityModelLoader.load();
        assumeTrue(!definitions.isEmpty(), "bundled entity tables are present");

        Map<String, Function<String, BuiltStyle>> verbs = new LinkedHashMap<>();
        verbs.put("head", id -> Poses.quadruped(id).head(head -> head.pitch(37)).build());
        verbs.put("body", id -> Poses.quadruped(id).body(body -> body.pitch(37)).build());
        verbs.put("tail", id -> Poses.quadruped(id).tail(tail -> tail.pitch(37)).build());
        verbs.put("right_arm", id -> Poses.humanoid(id).arm(Side.RIGHT, arm -> arm.pitch(37)).build());
        verbs.put("right_leg", id -> Poses.humanoid(id).leg(Side.RIGHT, leg -> leg.pitch(37)).build());

        StringBuilder table = new StringBuilder("joints a tier verb lands away from the bone it names (row: name -> joint)\n");
        int resolved = 0;
        for (Entity row : new TreeMap<>(definitions).values()) {
            if (!row.pose().isReadable()) continue;
            for (Map.Entry<String, Function<String, BuiltStyle>> verb : verbs.entrySet()) {
                String named = verb.getKey();
                if (!row.model().getBones().containsKey(named)) continue;
                PoseCompiler.Compiled compiled;
                try {
                    compiled = PoseCompiler.compile(verb.getValue().apply("probe"), row);
                } catch (IllegalArgumentException refused) {
                    table.append(String.format(Locale.ROOT, "  %-24s %-10s refused: %s%n", row.id(), named, refused.getMessage()));
                    continue;
                }
                String landed = compiled.diagnostics().entries().stream()
                    .map(StyleDiagnostics.Entry::message)
                    .filter(message -> message.startsWith("joint: '" + named + "' lands on '"))
                    .map(message -> message.substring(("joint: '" + named + "' lands on '").length(), message.indexOf('\'', ("joint: '" + named + "' lands on '").length())))
                    .findFirst()
                    .orElse(null);
                if (landed == null) continue;
                resolved++;
                table.append(String.format(Locale.ROOT, "  %-24s %-10s -> %s%n", row.id(), named, landed));
                assertTrue(ancestorOf(row.model(), named, landed),
                    row.id() + ": '" + landed + "' is an ancestor of '" + named + "' on the mesh");
                assertTrue(compiled.style().drivers().containsKey("style$probe$" + landed + "$x_rot"),
                    row.id() + ": the stance drives the joint's own field");
                assertFalse(compiled.style().drivers().containsKey("style$probe$" + named + "$x_rot"),
                    row.id() + ": and not the named cube's");
            }
        }
        table.append(String.format(Locale.ROOT, "  %d resolution(s)%n", resolved));
        System.out.print(table);
        assertTrue(resolved > 0, "the roster resolves a joint somewhere, or the print is vacuous");
    }

    /**
     * One subject the seats derive over - a body or baby mesh with the pose that belongs to it.
     *
     * @param mesh the mesh
     * @param pose the pose that poses it
     */
    private record Subject(@NotNull EntityModelData mesh, @NotNull EntityPose pose) {}

    /**
     * The subjects of one definition, keyed by a printable label - the body, and the baby form
     * where the definition declares one.
     */
    private static @NotNull Map<String, Subject> subjectsOf(@NotNull Entity row) {
        Map<String, Subject> subjects = new LinkedHashMap<>();
        subjects.put(row.id().toString(), new Subject(row.model(), row.pose()));
        row.axes().babyModel().ifPresent(baby ->
            subjects.put(row.id() + " (baby)", new Subject(baby, row.axes().babyPose().orElse(EntityPose.NONE))));
        return subjects;
    }

    /**
     * The seats of one derivation as one line, in mesh order.
     */
    private static @NotNull String describe(@NotNull Seats.Derived derived) {
        List<String> parts = new ArrayList<>();
        derived.seats().forEach((follower, seat) -> parts.add(String.format(Locale.ROOT,
            "%s <- %s @ (%.2f, %.2f, %.2f)", follower, seat.leader(),
            seat.offset().x(), seat.offset().y(), seat.offset().z())));
        return String.join("; ", parts);
    }

    /**
     * Whether following the seats from one follower reaches a bone seated on nothing.
     */
    private static boolean terminates(@NotNull String follower, @NotNull Seats.Derived derived) {
        Set<String> visited = new LinkedHashSet<>();
        String at = follower;
        while (derived.seats().containsKey(at)) {
            if (!visited.add(at)) return false;
            at = derived.seats().get(at).leader();
        }
        return true;
    }

    /**
     * Whether one bone is a proper ancestor of another on the mesh's own parent chain.
     */
    private static boolean ancestorOf(@NotNull EntityModelData mesh, @NotNull String bone, @NotNull String ancestor) {
        String at = mesh.getBones().get(bone).getParent();
        Set<String> visited = new LinkedHashSet<>();
        while (at != null && mesh.getBones().containsKey(at) && visited.add(at)) {
            if (at.equals(ancestor)) return true;
            at = mesh.getBones().get(at).getParent();
        }
        return false;
    }

}
