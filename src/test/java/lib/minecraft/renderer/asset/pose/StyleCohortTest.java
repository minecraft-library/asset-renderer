package lib.minecraft.renderer.asset.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Holds the shipped catalogs of canvas-group cohorts together: within every {@link Entity#members()}
 * group, members posed through one model class answer identical composed driver maps, row for row.
 *
 * <p>A canvas is a union measured in the pose the render draws, so every member of a group is posed
 * with the same frame answers or the union is measured around a subject its siblings do not match.
 * The catalog answers per entity where the frame oracle answers globally, which makes this the
 * group-bounds tripwire: a member whose rows drifted from its cohort's - a stray answering figures
 * its skeleton does not, a camel husk losing a stance row the camel keeps - renders happily on its
 * own and moves the shared canvas, so the drift is pinned here, where the two catalogs are side by
 * side, rather than read off a sweep.
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
                String poseClass = poseClassOf(models, member);
                assertNotNull(poseClass, member + " of group " + group
                    + " names no adult pose class in " + SHIPPED);
                cohorts.computeIfAbsent(poseClass, key -> new ArrayList<>()).add(member);
            }
            for (Map.Entry<String, List<String>> cohort : cohorts.entrySet()) {
                List<String> members = cohort.getValue();
                if (members.size() < 2) continue;
                Entity reference = entities.get(members.getFirst());
                assertNotNull(reference, members.getFirst() + " is expected to load");
                for (String member : members.subList(1, members.size()))
                    assertOneCatalog(cohort.getKey(), reference, entities.get(member), member);
            }
        }
    }

    /** Two cohort members' catalogs held together, row for row and driver for driver. */
    private static void assertOneCatalog(
        @NotNull String poseClass, @NotNull Entity reference, @Nullable Entity other,
        @NotNull String memberId) {

        assertNotNull(other, memberId + " is expected to load");
        StyleCatalog held = reference.styles();
        StyleCatalog against = other.styles();
        String cohort = "cohort '" + poseClass + "': " + reference.id() + " vs " + memberId;

        assertEquals(held.periodTicks(), against.periodTicks(), cohort + " period");
        assertEquals(
            held.styles().stream().map(row -> row.id() + row.age().map(age -> "@" + age).orElse(""))
                .toList(),
            against.styles().stream().map(row -> row.id() + row.age().map(age -> "@" + age).orElse(""))
                .toList(),
            cohort + " carries a different row set, so the two answer different frames somewhere");
        for (int index = 0; index < held.styles().size(); index++) {
            PoseStyle row = held.styles().get(index);
            PoseStyle twin = against.styles().get(index);
            assertEquals(Map.copyOf(row.drivers()), Map.copyOf(twin.drivers()),
                cohort + " row '" + row.id() + "' composes different drivers, and the shared canvas "
                    + "is measured in the pose those drivers answer");
        }
    }

    /**
     * The model class one member's body is posed through - its explicit adult {@code pose} member
     * where the file states one, else the head of its adult geometry coordinate, the same
     * derivation the index join runs.
     */
    private static @Nullable String poseClassOf(@NotNull JsonObject models, @NotNull String member) {
        JsonObject model = models.getAsJsonObject(member);
        if (model == null) return null;
        JsonObject adult = model.getAsJsonObject("axes")
            .getAsJsonObject("age")
            .getAsJsonObject("options")
            .getAsJsonObject("adult");
        if (adult == null) return null;
        if (adult.has("pose")) return adult.get("pose").getAsString();
        if (!adult.has("geometry")) return null;
        String coordinate = adult.get("geometry").getAsString();
        int head = coordinate.indexOf('#');
        return head < 0 ? coordinate : coordinate.substring(0, head);
    }

}
