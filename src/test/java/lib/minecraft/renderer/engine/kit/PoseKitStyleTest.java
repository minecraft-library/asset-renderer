package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The resolved catalog row applied through the posing surface.
 *
 * <p>The first test is the one the whole opt-in rests on: under the {@code bind} row both the
 * subject form and the memo hand back the very instance they were given, so a caller that asks for
 * nothing allocates nothing and renders the bytes it always rendered. The rest pin what the memo
 * owes its two passes - one posed instance per tick, and one per member INSTANCE per tick, because
 * variant coats share the family id and an id-keyed memo would answer one coat's mesh for another.
 */
@DisplayName("the resolved style row applied to a subject")
class PoseKitStyleTest {

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
            PoseKit.PosedFrames frames =
                PoseKit.frames(entity, entity.styles().bind(), entity.styles().periodTicks());
            for (int tick : TICKS) {
                assertSame(entity,
                    PoseKit.posed(entity, entity.styles().bind(), entity.styles().periodTicks(), tick),
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
        PoseStyle idle = squid.styles().resolve(PoseStyle.IDLE, EntityOptions.of("minecraft:squid"));
        Entity posed = PoseKit.posed(squid, idle, squid.styles().periodTicks(), 7);
        assertNotSame(squid, posed, "a driven row answers a new subject");
        assertNotSame(squid.model(), posed.model(), "carrying a posed mesh of its own");
    }

    @Test
    @DisplayName("the memo answers one posed instance per tick")
    void theMemoAnswersOneInstancePerTick() {
        Entity zombie = subject("minecraft:zombie");
        PoseStyle idle = zombie.styles().resolve(PoseStyle.IDLE, EntityOptions.of("minecraft:zombie"));
        PoseKit.PosedFrames frames = PoseKit.frames(zombie, idle, zombie.styles().periodTicks());

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

        PoseStyle idle = zombie.styles().resolve(PoseStyle.IDLE, EntityOptions.of("minecraft:zombie"));
        PoseKit.PosedFrames frames = PoseKit.frames(zombie, idle, zombie.styles().periodTicks());
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
        Entity resolved = axolotl.resolve(baby.getAppearance());
        PoseStyle row = resolved.styles().resolve(PoseStyle.IDLE, baby);
        assertEquals(Set.of("ageInTicks"), Set.copyOf(row.drivers().keySet()),
            "the baby answers the universal row, its family's idle applying to the adult alone");

        PoseKit.PosedFrames frames = PoseKit.frames(resolved, row, resolved.styles().periodTicks());
        assertSame(resolved, frames.at(0),
            "nothing the universal row drives moves the baby's meshes");
        assertNotSame(axolotl.model(), frames.at(axolotl, 0).model(),
            "while the adult measured beside it stands in its own idle stance");
    }

    @Test
    @DisplayName("the lone-mesh overload answers the given mesh under bind and an unreadable pose")
    void theLoneMeshOverloadAnswersTheGivenMesh() {
        EntityModelData mesh = new EntityModelData();
        assertSame(mesh, PoseKit.posed(EntityPose.NONE, mesh,
                StyleCatalog.BIND_ONLY.bind(), StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "the bind row is the mesh itself");

        EntityPose unreadable = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.of("the walk could not read this model"));
        PoseStyle idle = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, EntityOptions.of("minecraft:test"));
        assertSame(mesh, PoseKit.posed(unreadable, mesh, idle,
                StyleCatalog.BIND_ONLY.periodTicks(), 7),
            "and so is a pose that could not be read, under a row that moves");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

}
