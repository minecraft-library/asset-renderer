package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What moves a subject, and the preset a caller asking for movement without naming one is given.
 *
 * <p>The corpus walk is the load-bearing test: a subject that holds still under the resting preset
 * and moves under a stride is the largest population here, and nothing before this told a caller
 * which one they were looking at. The rest pin the two ways the answer can be wrong quietly - a gait
 * chosen for a subject that does not need one, which walks an animal vanilla stands still, and one
 * withheld from a subject that does, which is the still render this exists to stop.
 *
 * <p>Counts are floors rather than equalities wherever the roster decides them, because the roster
 * follows the entity registry and moves on a version bump. The named subjects are pinned exactly:
 * each one is the clearest instance of its member, and a subject changing member is a real answer
 * changing rather than a roster drifting.
 */
@DisplayName("what moves a subject, and the gait that reaches it")
class PoseKitMotionTest {

    /** The excursions the reference set is drawn at, which is what an unnamed animation means. */
    private static final @NotNull AnimationOptions ANIMATION = AnimationOptions.defaults();

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("each member's clearest subject is classified as that member")
    void theNamedSubjectsAreClassified() {
        // One subject per member, each chosen because what moves it is not in dispute: a squid's
        // tentacles wave on a written channel, a bat writes no bone outside its clips, a breeze holds
        // still and scrolls, a creeper moves only once something walks it, and an armour stand does
        // nothing at all.
        assertEquals(MotionSource.LIVE, motionOf("minecraft:squid"));
        assertEquals(MotionSource.CLIP, motionOf("minecraft:bat"));
        assertEquals(MotionSource.SCROLL, motionOf("minecraft:breeze"));
        assertEquals(MotionSource.STRIDE, motionOf("minecraft:creeper"));
        assertEquals(MotionSource.INERT, motionOf("minecraft:armor_stand"));
    }

    @Test
    @DisplayName("a pass an appearance dropped takes what it would have moved with it")
    void aDroppedPassMovesNothing() {
        // The creeper is the case that decides whether this reads the subject or the definition it
        // came from: its swirl scrolls, and an uncharged creeper does not draw the swirl. Both are
        // asserted, because only the pair says the gating is what made the difference - read off the
        // definition a caller asking for movement is handed the resting preset, for an animal that
        // does nothing at all until something walks it.
        Entity definition = entities.get("minecraft:creeper");
        assertNotNull(definition, "minecraft:creeper is expected to load");
        assertEquals(MotionSource.SCROLL, PoseKit.motionOf(definition, ANIMATION),
            "the definition carries the swirl, which scrolls");
        assertEquals(MotionSource.STRIDE, motionOf("minecraft:creeper"),
            "an uncharged creeper draws no swirl and so does not scroll");
    }

    @Test
    @DisplayName("every subject is classified, and a stride is what moves the largest still population")
    void theWholeCorpusIsClassified() {
        Map<MotionSource, Integer> tally = new EnumMap<>(MotionSource.class);
        for (Entity raw : entities.values()) {
            Entity subject = raw.resolve(AppearanceOptions.defaults());
            MotionSource motion = PoseKit.motionOf(subject, ANIMATION);
            assertNotNull(motion, subject.id() + " is expected to be classified");
            tally.merge(motion, 1, Integer::sum);
        }
        // A floor rather than a count, the roster following the entity registry. What the floor is
        // for is the direction of the answer: a subject that holds still at rest and moves on a
        // stride is a large share of the corpus, so a derivation that quietly answered LIVE for
        // everything - which is what comparing two instants wrongly would do - fails here rather
        // than in a render nobody looks at.
        assertTrue(tally.getOrDefault(MotionSource.STRIDE, 0) > 25,
            "a stride is expected to be what moves much of the corpus: " + tally);
        assertTrue(tally.getOrDefault(MotionSource.LIVE, 0) > 25,
            "elapsed age is expected to move much of the corpus: " + tally);
        // Nothing is classified by exhausting the alternatives: every member the corpus reaches is
        // reached because something was measured varying, so an empty tally cell is a member no
        // shipped table produces rather than a subject that fell through.
        assertEquals(entities.size(), tally.values().stream().mapToInt(Integer::intValue).sum(),
            "every subject is expected to land in exactly one member: " + tally);
    }

    @Test
    @DisplayName("the resolved gait is a stride exactly where a stride is what moves the subject")
    void theGaitFollowsWhatMoves() {
        for (Entity raw : entities.values()) {
            Entity subject = raw.resolve(AppearanceOptions.defaults());
            MotionSource motion = PoseKit.motionOf(subject, ANIMATION);
            EntityOptions.PoseMode gait =
                PoseKit.gaitOf(EntityOptions.PoseMode.ANIMATED, subject, ANIMATION);
            EntityOptions.PoseMode expected = motion == MotionSource.STRIDE
                ? EntityOptions.PoseMode.WALK
                : EntityOptions.PoseMode.IDLE;
            assertEquals(expected, gait, subject.id() + " asked for movement");
        }
    }

    @Test
    @DisplayName("a named preset is handed back untouched, so nothing that named one changes")
    void aNamedPresetPassesThrough() {
        // The whole byte-neutrality of this addition: a caller that names a preset must reach the
        // very same path it always did, and the resolution must not run at all.
        Entity subject = resolved("minecraft:creeper");
        for (EntityOptions.PoseMode named : new EntityOptions.PoseMode[]{
            EntityOptions.PoseMode.BIND, EntityOptions.PoseMode.IDLE, EntityOptions.PoseMode.WALK})
            assertSame(named, PoseKit.gaitOf(named, subject, ANIMATION), named + " is expected to pass through");
    }

    @Test
    @DisplayName("asking for movement moves a subject the resting preset leaves standing")
    void askingForMovementMovesAStrideSubject() {
        // The point of the whole member, asserted where it is visible: the creeper stands in the same
        // place at two ticks of the resting preset and in two different ones once movement is asked
        // for without naming a gait.
        //
        // Compared on where the bones POINT rather than on mesh identity. A pose whose channels are
        // all constants still rebuilds the mesh - the creeper writes its head flat at zero - so the
        // instance differs at every tick and says nothing about whether the subject moved.
        Entity subject = resolved("minecraft:creeper");
        assertEquals(
            rotations(PoseKit.posed(EntityOptions.PoseMode.IDLE, subject, 0, ANIMATION)),
            rotations(PoseKit.posed(EntityOptions.PoseMode.IDLE, subject, 7, ANIMATION)),
            "a resting creeper is expected to hold still");
        assertNotEquals(
            rotations(PoseKit.posed(EntityOptions.PoseMode.ANIMATED, subject, 0, ANIMATION)),
            rotations(PoseKit.posed(EntityOptions.PoseMode.ANIMATED, subject, 7, ANIMATION)),
            "a creeper asked to move is expected to move");
    }

    @Test
    @DisplayName("a table reading a figure nothing answers is named rather than left quietly still")
    void anUnansweredFigureIsNamed() {
        // No shipped table reaches this today - every figure the corpus reads is one a render
        // answers - so it is pinned on a pose written here. What it guards is the day a table starts
        // reading a figure this side does not drive: the subject holds still either way, and the
        // difference between a gap and a subject that stands still by nature is the whole of what a
        // reader has to go on.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", cubed());
        EntityPose reads = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body",
                Map.of(PoseChannel.X_ROT, new PoseExpr.Input("swellAmount")))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(reads)
            .overlays(Concurrent.newUnmodifiableList())
            .build();
        assertEquals(MotionSource.TICKED, PoseKit.motionOf(subject, ANIMATION));
        // And a pose reading nothing is the other answer, so the two are told apart by what the table
        // asked for rather than by both being still.
        EntityPose asksNothing = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body",
                Map.of(PoseChannel.X_ROT, new PoseExpr.Const(0.5d, PoseOperator.Width.FLOAT)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        assertEquals(MotionSource.INERT, PoseKit.motionOf(subject.mutate().pose(asksNothing).build(), ANIMATION));
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull MotionSource motionOf(@NotNull String id) {
        return PoseKit.motionOf(resolved(id), ANIMATION);
    }

    private static @NotNull Entity resolved(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.resolve(AppearanceOptions.defaults());
    }

    /** Where every bone of a mesh points, which is what two instants of one subject are compared on. */
    private static @NotNull java.util.List<EulerRotation> rotations(@NotNull EntityModelData mesh) {
        return mesh.getBones().values().stream().map(EntityModelData.Bone::getRotation).toList();
    }

    /** A bone carrying one cube, so a pose that moves the mesh is tellable from one that does not. */
    private static @NotNull EntityModelData.Bone cubed() {
        var cubes = Concurrent.<EntityModelData.Cube>newList();
        cubes.add(new EntityModelData.Cube());
        return new EntityModelData.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes, null);
    }

}
