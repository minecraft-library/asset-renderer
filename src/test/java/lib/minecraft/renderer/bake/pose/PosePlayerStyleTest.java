package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The resolved catalog row applied through the posing surface.
 *
 * <p>The first test is the one the whole opt-in rests on: under the {@code bind} row both the
 * subject form and the memo hand back the very instance they were given, so a caller that asks for
 * nothing allocates nothing and renders the bytes it always rendered. The rest pin what the memo
 * owes its two passes - one posed instance per tick, and one per member INSTANCE per tick, because
 * variant coats share the family id and an id-keyed memo would answer one coat's mesh for another.
 * The last two hold every form of every shipped row to posing under every style it lists, and the
 * salmon's flattened sizes to placing their container where vanilla's pose stack places it.
 */
@DisplayName("the resolved style row applied to a subject")
class PosePlayerStyleTest {

    /** Ticks a subject is posed at - zero and one odd instant. */
    private static final int @NotNull [] TICKS = {0, 7};

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
    }

    @Test
    @DisplayName("the bind row hands back the very instance it was given, subject for subject")
    void theBindRowIsTheSubjectItself() {
        for (Entity entity : entities.values()) {
            PosePlayer.PosedFrames frames =
                PosePlayer.frames(entity, StyleCatalog.bind(), entity.styles().periodTicks());
            for (int tick : TICKS) {
                assertSame(entity,
                    PosePlayer.posed(entity, StyleCatalog.bind(), entity.styles().periodTicks(), tick),
                    entity.id() + " is its own subject at tick " + tick);
                assertSame(entity, frames.at(tick),
                    entity.id() + " is its own memo answer at tick " + tick);
            }
        }
    }

    @Test
    @DisplayName("a moving row poses the subject somewhere its bind pose is not")
    void aMovingRowPosesTheSubject() {
        Entity squid = subject("minecraft:squid");
        PoseStyle idle = squid.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:squid");
        Entity posed = PosePlayer.posed(squid, idle, squid.styles().periodTicks(), 7);
        assertNotSame(squid, posed, "a driven row answers a new subject");
        assertNotSame(squid.model(), posed.model(), "carrying a posed mesh of its own");
    }

    @Test
    @DisplayName("the memo answers one posed instance per tick")
    void theMemoAnswersOneInstancePerTick() {
        Entity zombie = subject("minecraft:zombie");
        PoseStyle idle = zombie.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:zombie");
        PosePlayer.PosedFrames frames = PosePlayer.frames(zombie, idle, zombie.styles().periodTicks());

        assertSame(frames.at(7), frames.at(7), "one tick asked twice is one posed instance");
        assertNotEquals(frames.at(0).model().getBones(), frames.at(7).model().getBones(),
            "and a subject that moves stands somewhere else seven ticks later");
    }

    @Test
    @DisplayName("the member memo keys by instance, so two subjects sharing an id pose apart")
    void theMemberMemoKeysByInstance() {
        Entity zombie = subject("minecraft:zombie");
        Entity twin = zombie.mutate().build();
        assertNotSame(zombie, twin, "the twin is a distinct instance of the same definition");

        PoseStyle idle = zombie.styles()
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:zombie");
        PosePlayer.PosedFrames frames = PosePlayer.frames(zombie, idle, zombie.styles().periodTicks());
        Entity posed = frames.at(zombie, 7);
        Entity posedTwin = frames.at(twin, 7);

        assertNotSame(posed, posedTwin, "each instance is posed as its own subject");
        assertSame(posed, frames.at(zombie, 7), "and each is posed once per tick");
        assertEquals(frames.at(7).model().getBones(), posed.model().getBones(),
            "the primary asked through the member overload answers the same posed form");
    }

    @Test
    @DisplayName("a union member is measured under its own answer to the style id")
    void aUnionMemberIsMeasuredUnderItsOwnRow() {
        Entity axolotl = subject("minecraft:axolotl");
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:axolotl")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();
        Entity resolved = baby.getAppearance().resolve(axolotl);
        PoseStyle row = resolved.styles().resolve(PoseStyle.IDLE, baby.getAppearance()::applies, baby.getEntityId());
        assertEquals(Set.of("ageInTicks"), Set.copyOf(row.drivers().keySet()),
            "the baby answers the universal row, its family's idle applying to the adult alone");

        PosePlayer.PosedFrames frames = PosePlayer.frames(resolved, row, resolved.styles().periodTicks());
        assertSame(resolved, frames.at(0),
            "nothing the universal row drives moves the baby's meshes");
        assertNotSame(axolotl.model(), frames.at(axolotl, 0).model(),
            "while the adult measured beside it stands in its own idle stance");
    }

    @Test
    @DisplayName("the lone-mesh overload answers the given mesh under bind and an unreadable pose")
    void theLoneMeshOverloadAnswersTheGivenMesh() {
        EntityMesh mesh = new EntityMesh();
        assertSame(mesh, PosePlayer.posed(EntityPose.NONE, mesh,
                StyleCatalog.bind(), StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "the bind row is the mesh itself");

        EntityPose unreadable = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.of("the walk could not read this model"));
        PoseStyle idle = StyleCatalog.BIND_ONLY
            .resolve(PoseStyle.IDLE, AppearanceOptions.defaults()::applies, "minecraft:test");
        assertSame(mesh, PosePlayer.posed(unreadable, mesh, idle,
                StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "and so is a pose that could not be read, under a row that moves");
    }

    @Test
    @DisplayName("every form of every shipped row poses under every style its in-force catalog lists")
    void everyFormPosesUnderEveryListedStyle() {
        List<String> failures = new ArrayList<>();
        Set<String> walked = new LinkedHashSet<>();
        for (Entity entity : entities.values()) {
            List<Optional<String>> coats = new ArrayList<>();
            coats.add(Optional.empty());
            entity.axes().variant().options().keySet().forEach(coat -> coats.add(Optional.of(coat)));
            for (Optional<String> coat : coats) {
                Entity form = coat.flatMap(entity.axes().variant()::select).orElse(entity);
                String label = entity.id().id() + coat.map(key -> " variant=" + key).orElse("");
                Map<String, AppearanceOptions> appearances = new LinkedHashMap<>();
                appearances.put(label, AppearanceOptions.builder().variant(coat).build());
                if (form.axes().baby().isPresent())
                    appearances.put(label + " age=baby", AppearanceOptions.builder().variant(coat).age(Age.BABY).build());
                for (Size size : form.axes().size().options().keySet())
                    appearances.put(label + " size=" + size.name().toLowerCase(Locale.ROOT),
                        AppearanceOptions.builder().variant(coat).size(Optional.of(size)).build());
                if (form.axes().shape().select(Entity.SHAPE_LARGE).isPresent())
                    appearances.put(label + " pattern=flopper", AppearanceOptions.builder()
                        .variant(coat).pattern(Optional.of(TropicalFishPattern.FLOPPER)).build());
                appearances.forEach((formLabel, appearance) -> {
                    walked.add(formLabel);
                    Entity resolved = appearance.resolve(entity);
                    Set<String> ids = new LinkedHashSet<>(resolved.styles().ids());
                    ids.add(PoseStyle.IDLE);
                    ids.add(PoseStyle.STRIDE);
                    for (String id : ids)
                        for (int tick : TICKS) {
                            try {
                                PoseStyle style = resolved.styles().resolve(id, appearance::applies, entity.id().id());
                                PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), tick);
                            } catch (RuntimeException failure) {
                                failures.add(formLabel + " '" + id + "' @" + tick + ": " + failure.getMessage());
                            }
                        }
                });
            }
        }
        assertTrue(walked.size() > 50, "the walk reaches the corpus, " + walked.size() + " forms");
        assertTrue(walked.containsAll(List.of("minecraft:salmon size=small", "minecraft:salmon size=large")),
            "the walk reaches both flattened salmon forms");
        assertEquals(List.of(), failures, "every form poses under every style it lists");
    }

    @Test
    @DisplayName("a salmon of either non-default size poses, its ground frame placed where vanilla places it")
    void aFlattenedSalmonPosesAtVanillasGroundFrame() {
        Entity salmon = subject("minecraft:salmon");
        for (Size size : List.of(Size.SMALL, Size.LARGE)) {
            AppearanceOptions appearance = AppearanceOptions.builder().size(Optional.of(size)).build();
            Entity resolved = appearance.resolve(salmon);
            assertNotEquals(1f, resolved.model().getFlattenedScale(), size + " is a flattened mesh");
            int declared = resolved.model().getBones().size();
            for (String id : List.of(PoseStyle.IDLE, PoseStyle.STRIDE))
                for (int tick : TICKS) {
                    String where = size + " '" + id + "' @" + tick;
                    PoseStyle style = resolved.styles().resolve(id, appearance::applies, "minecraft:salmon");
                    EntityMesh posed = PosePlayer.posed(resolved, style, resolved.styles().periodTicks(), tick).model();
                    List<String> names = List.copyOf(posed.getBones().keySet());
                    assertEquals(declared + 4, names.size(), where + " seats the four container steps");
                    EntityMesh.Bone ground = posed.getBones().get(names.getLast());
                    assertEquals(-EntityMesh.FEET_ANCHOR, ground.getPivot().y(), where + " ground frame");
                    EntityMesh.Bone flop = posed.getBones().get(names.get(declared + 1));
                    assertEquals(0f, flop.getPivot().x(), where + " out-of-water translate x");
                    assertEquals(0f, flop.getPivot().y(), where + " out-of-water translate y");
                    resolved.model().getBones().forEach((name, bone) -> {
                        if (bone.getParent() == null)
                            assertEquals(names.getLast(), posed.getBones().get(name).getParent(),
                                where + " '" + name + "' hangs off the ground frame");
                    });
                }
        }
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

}
