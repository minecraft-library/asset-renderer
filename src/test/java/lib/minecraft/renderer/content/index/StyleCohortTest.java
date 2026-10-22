package lib.minecraft.renderer.content.index;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Holds the shipped catalogs of canvas-group cohorts together: within every {@link Entity#members()}
 * group, members posed through one model class list one row set and answer identical composed driver
 * maps at the adult, row for row, but for the pace each walks at - and at the baby as well where both
 * carry a baby form posed through one class, as the two squids and the hoglin and zoglin do.
 *
 * <p>A canvas is a union measured in the pose each member's own render draws, and the adult is what a
 * group measures a member as. The catalog answers per entity where the frame oracle answers globally,
 * which makes this the group-bounds tripwire: a member whose rows drifted from its cohort's - a stray
 * answering figures its skeleton does not, a camel husk losing a stance row the camel keeps - renders
 * happily on its own and moves the shared canvas, so the drift is pinned here, where the two catalogs
 * are side by side, rather than read off a sweep. The walk is the one figure a cohort does not share:
 * its pace is each entity's own, so a villager and a wandering trader amble at different speeds through
 * one model, and the walk pair is held to its fields and waves alone.
 *
 * <p>Cohorts are keyed by the model class the member's body is posed through, read off the shipped
 * file the way the index join reads it, because sharing a pose class is what makes two members one
 * cohort - the piglins share a canvas with the zombified piglin while posing through a class of its
 * own, and that split is legitimate where a driver difference inside one class is not.
 */
@DisplayName("members sharing a pose class share driver maps")
class StyleCohortTest {

    /** The shipped catalog the cohorts are read from - the same file the index assembles. */
    private static final Path SHIPPED =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** The two fields a stride walks on, which each member drives at its own pace. */
    private static final @NotNull Set<String> WALK_PAIR = Set.of("walkAnimationSpeed", "walkAnimationPos");

    @Test
    @DisplayName("every cohort of a members group answers one composed driver map per row")
    void membersSharingAPoseClassShareDriverMaps() throws IOException {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        JsonObject models = JsonParser
            .parseString(Files.readString(SHIPPED, StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("models");

        Set<List<String>> groups = new LinkedHashSet<>();
        for (Entity entity : entities.values())
            if (!entity.members().isEmpty()) groups.add(List.copyOf(entity.members()));
        assertFalse(groups.isEmpty(), "no members group loaded - the corpus always carries some");

        for (List<String> group : groups) {
            Map<String, List<String>> cohorts = new LinkedHashMap<>();
            for (String member : group) {
                String poseClass = poseClassOf(models, member, "adult");
                assertNotNull(poseClass, member + " of group " + group
                    + " names no adult pose class in " + SHIPPED);
                cohorts.computeIfAbsent(poseClass, key -> new ArrayList<>()).add(member);
            }
            for (Map.Entry<String, List<String>> cohort : cohorts.entrySet()) {
                List<String> members = cohort.getValue();
                if (members.size() < 2) continue;
                Entity reference = entities.get(members.getFirst());
                assertNotNull(reference, members.getFirst() + " is expected to load");
                String babyClass = poseClassOf(models, members.getFirst(), "baby");
                for (String member : members.subList(1, members.size())) {
                    boolean babies = babyClass != null && babyClass.equals(poseClassOf(models, member, "baby"));
                    assertOneCatalog(cohort.getKey(), reference, entities.get(member), member, babies);
                }
            }
        }
    }

    /**
     * Two cohort members' catalogs held together, row for row and driver for driver - at the adult,
     * and at the baby where both babies pose through one class.
     */
    private static void assertOneCatalog(
        @NotNull String poseClass, @NotNull Entity reference, @Nullable Entity other,
        @NotNull String memberId, boolean babies) {

        assertNotNull(other, memberId + " is expected to load");
        StyleCatalog held = reference.styles();
        StyleCatalog against = other.styles();
        String cohort = "cohort '" + poseClass + "': " + reference.id() + " vs " + memberId;

        assertEquals(held.periodTicks(), against.periodTicks(), cohort + " period");
        assertEquals(List.copyOf(held.ids()), List.copyOf(against.ids()),
            cohort + " carries a different row set, so the two answer different frames somewhere");
        for (String id : held.ids()) {
            if (id.equals(PoseStyle.BIND)) continue;
            assertEquals(paceless(rowAt(held, id, Age.ADULT)), paceless(rowAt(against, id, Age.ADULT)),
                cohort + " row '" + id + "' composes different drivers, and the shared canvas "
                    + "is measured in the pose those drivers answer");
            if (babies) {
                assertEquals(paceless(rowAt(held, id, Age.BABY)), paceless(rowAt(against, id, Age.BABY)),
                    cohort + " baby row '" + id + "' composes different drivers, where both babies pose "
                        + "through one class");
            }
        }
    }

    /** The row of one id a render of one age draws - the first applying to that age. */
    private static @NotNull Optional<PoseStyle> rowAt(@NotNull StyleCatalog catalog, @NotNull String id,
                                                      @NotNull Age age) {
        return catalog.byId(id, row -> row.age().map(age::equals).orElse(true));
    }

    /**
     * A row's composed drivers, the walk pair's held to its field and wave - each member walking at
     * its own pace - and every other driver whole.
     */
    private static @NotNull Optional<Map<String, Object>> paceless(@NotNull Optional<PoseStyle> row) {
        return row.map(style -> {
            Map<String, Object> drivers = new TreeMap<>();
            style.drivers().forEach((field, driver) ->
                drivers.put(field, WALK_PAIR.contains(field) ? driver.wave() : driver));
            return drivers;
        });
    }

    /**
     * The model class one member's body is posed through at one age - the age option's explicit
     * {@code pose} member where the file states one, else the head of its geometry coordinate, the
     * same derivation the index join runs; null where the member carries no form of that age.
     */
    private static @Nullable String poseClassOf(@NotNull JsonObject models, @NotNull String member,
                                                @NotNull String age) {
        JsonObject model = models.getAsJsonObject(member);
        if (model == null) return null;
        JsonObject form = model.getAsJsonObject("axes")
            .getAsJsonObject("age")
            .getAsJsonObject("options")
            .getAsJsonObject(age);
        if (form == null) return null;
        if (form.has("pose")) return form.get("pose").getAsString();
        if (!form.has("geometry")) return null;
        String coordinate = form.get("geometry").getAsString();
        int head = coordinate.indexOf('#');
        return head < 0 ? coordinate : coordinate.substring(0, head);
    }

}
