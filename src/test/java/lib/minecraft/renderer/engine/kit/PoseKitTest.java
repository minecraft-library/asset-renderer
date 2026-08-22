package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table written back onto the mesh it poses.
 *
 * <p>The first test is the one the whole opt-in rests on: under the authored pose this hands back
 * the very instance it was given, so a caller that asks for nothing renders the bytes it always
 * rendered. Nothing weaker will do - an equal copy would still be a copy, and the arithmetic that
 * built it would be arithmetic that could drift.
 *
 * <p>The rest pin what posing must not cost: bones stay in the order the mesh declares them, because
 * that order is the tied-depth priority; a channel put back where it started stays bit-identical
 * rather than making the trip out to radians and back; and every subject in the corpus poses to a
 * number at every tick, which is what says the table and the shipped meshes agree about what a bone
 * is called.
 *
 * <p>The flag half is pinned twice over, because its two halves fail differently. On the corpus, no
 * subject may gain or lose a bone to a posed frame - not one flag in the shipped table reads elapsed
 * time, so a frame decides visibility exactly as the load-time strip did, and the day that stops
 * being true a limb starts appearing partway through an animation with nothing to say why. On a mesh
 * built here, hiding and skipping have to drop different things, which is the only reason there are
 * two channels.
 */
@DisplayName("the shipped pose table applied to a mesh")
class PoseKitTest {

    /** Ticks a subject is posed at - zero, a couple of odd instants, and one before the start. */
    private static final int @NotNull [] TICKS = {0, 7, 41, -5};

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("the authored pose hands back the very mesh it was given, subject for subject")
    void theAuthoredPoseIsTheMeshItself() {
        // The byte-neutrality of the default, asserted as identity rather than as equality: an equal
        // copy is still a copy, and every float in it is one the authored path never computed.
        for (Entity entity : entities.values())
            for (int tick : TICKS)
                assertSame(entity.model(), PoseKit.posed(EntityOptions.PoseMode.BIND, entity, tick),
                    entity.id() + " draws its own mesh at tick " + tick);
    }

    @Test
    @DisplayName("every subject in the corpus poses to numbers, at every tick asked")
    void theWholeCorpusPoses() {
        int posed = 0;
        for (Entity entity : entities.values()) {
            for (int tick : TICKS) {
                EntityModelData mesh = PoseKit.posed(EntityOptions.PoseMode.ANIMATED, entity, tick);
                String where = entity.id() + " at tick " + tick;
                mesh.getBones().forEach((name, bone) -> {
                    assertTrue(finite(bone.getPivot()), where + ": " + name + " stands somewhere");
                    assertTrue(finite(bone.getRotation()), where + ": " + name + " points somewhere");
                    assertTrue(Float.isFinite(bone.getScale()), where + ": " + name + " is some size");
                });
            }
            if (PoseKit.posed(EntityOptions.PoseMode.ANIMATED, entity, 0) != entity.model()) posed++;
        }
        // A floor rather than a count, for the reason the evaluator's own corpus walk carries one:
        // the roster follows the entity registry and moves on a version bump.
        assertTrue(posed > 50, "the corpus is expected to be posed, not skipped past: " + posed);
    }

    @Test
    @DisplayName("a posed mesh keeps its bones in the order its mesh declares them")
    void theMeshsOwnOrderSurvives() {
        // The order is the tied-depth priority a coplanar pair is decided by, so a rebuild that
        // reordered the map would re-decide which face survives on subjects nothing else touched.
        for (Entity entity : entities.values()) {
            EntityModelData mesh = PoseKit.posed(EntityOptions.PoseMode.ANIMATED, entity, 13);
            if (mesh == entity.model()) continue;
            List<String> declared = List.copyOf(entity.model().getBones().keySet());
            List<String> posed = List.copyOf(mesh.getBones().keySet());
            assertEquals(declared, posed.subList(0, declared.size()),
                entity.id() + " keeps the bones it declares, in the order it declares them");
            posed.subList(declared.size(), posed.size()).forEach(added ->
                assertTrue(mesh.getBones().get(added).getCubes().isEmpty(),
                    entity.id() + " adds only the container, which draws nothing: " + added));
        }
    }

    @Test
    @DisplayName("a subject whose model reads elapsed time stands somewhere else a tick later")
    void elapsedTimeMovesTheSubject() {
        // A humanoid bobs its arms off ageInTicks unconditionally, so a zombie standing perfectly
        // still is the cheapest subject that has to differ between two instants.
        Entity zombie = subject("minecraft:zombie");
        EntityModelData at0 = PoseKit.posed(EntityOptions.PoseMode.ANIMATED, zombie, 0);
        EntityModelData at9 = PoseKit.posed(EntityOptions.PoseMode.ANIMATED, zombie, 9);

        assertNotEquals(at0.getBones().get("left_arm").getRotation(),
            at9.getBones().get("left_arm").getRotation(), "nine ticks of standing still is an arm bob");
        assertEquals(at0.getBones(), PoseKit.posed(EntityOptions.PoseMode.ANIMATED, zombie, 0).getBones(),
            "and one tick asked twice is one subject");
    }

    @Test
    @DisplayName("a channel put back where it started keeps the mesh's own number")
    void writingTheAuthoredValueBackIsExact() {
        // Thirty-one degrees is a value the trip out to radians and back does not return. The table
        // computes in radians where the mesh stores degrees, so a pose resolving to the bind pose
        // would walk a bone off by an ulp per render if a written channel were converted rather than
        // recognised - and a pose full of them would do it to every bone at once.
        EulerRotation authored = new EulerRotation(31f, 0f, 0f);
        assertNotEquals(authored.pitch(), (float) Math.toDegrees(authored.pitchRadians()),
            "the value is expected to be one the round trip loses");

        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", new EntityModelData.Bone(new Vector3f(1f, 2f, 3f), authored,
            EulerRotation.NONE, 1f, Concurrent.newList(), null));

        EntityPose readsItself = new EntityPose(Map.of(),
            Map.of("head", Map.of(PoseChannel.X_ROT, new PoseExpr.BoneRead("head", PoseChannel.X_ROT))),
            List.of(), Map.of(), Optional.empty());

        EntityModelData posed = PoseKit.posed(EntityOptions.PoseMode.ANIMATED,
            subject("minecraft:test", mesh, readsItself), 4);
        assertEquals(authored, posed.getBones().get("head").getRotation(),
            "a channel written back to what it held is the number it held");
    }

    @Test
    @DisplayName("a container becomes the parent of every bone the mesh names at top level")
    void aContainerIsSeatedAboveTheRoots() {
        // The container is a transform above the whole mesh that the mesh names nowhere, so it has
        // to arrive as something the chain composition already knows how to walk - and it draws none
        // of its own, so no bone that draws changes the order it is drawn in.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(null));
        mesh.getBones().put("head", bone("body"));

        EntityPose drops = new EntityPose(
            Map.of(PoseChannel.Y, new PoseExpr.Const(-3d, PoseOperator.Width.FLOAT)),
            Map.of(), List.of(), Map.of(), Optional.empty());

        EntityModelData posed = PoseKit.posed(EntityOptions.PoseMode.ANIMATED,
            subject("minecraft:test", mesh, drops), 0);

        String container = List.copyOf(posed.getBones().keySet()).getLast();
        assertEquals(-3f, posed.getBones().get(container).getPivot().y(),
            "the container holds what the pose wrote it");
        assertTrue(posed.getBones().get(container).getCubes().isEmpty(), "and draws nothing of its own");
        assertEquals(container, posed.getBones().get("body").getParent(), "a root hangs from it");
        assertEquals("body", posed.getBones().get("head").getParent(), "and a bone that had a parent keeps it");
    }

    @Test
    @DisplayName("no subject in the corpus gains or loses a bone to a posed frame")
    void theFlagChannelsAgreeWithTheRestingStrip() {
        // The measurement the whole flag half rests on: not one flag in the corpus reads elapsed
        // time, so a posed frame decides visibility exactly the way the load-time strip already did
        // and no subject can differ from itself between two instants. It is asserted rather than
        // assumed because the day a model starts reading a clock for a flag is the day the two part,
        // and the failure would otherwise be a limb quietly appearing partway through an animation.
        for (Entity entity : entities.values()) {
            Set<String> declared = entity.model().getBones().keySet();
            for (int tick : TICKS)
                assertTrue(PoseKit.posed(EntityOptions.PoseMode.ANIMATED, entity, tick)
                        .getBones().keySet().containsAll(declared),
                    entity.id() + " draws every bone it declares at tick " + tick);
        }
    }

    @Test
    @DisplayName("a fox stands on four legs and a guardian keeps its eye, at every tick")
    void theTwoFrameDrivenVisibilityWritersDraw() {
        // Both write visibility with no toggle over them, which is why they waited for a runtime that
        // reads the channel at all. Vanilla decides both outside every branch a still subject could
        // take: FoxModel.setupAnim calls setWalkingPose - which sets all four legs visible - before it
        // tests anything, leaving only setSleepingPose behind an isSleeping that rests false; and
        // GuardianModel writes its eye visible unconditionally, past the guard that skips the eye's
        // position when nothing is being looked at. So both draw, and this is what says they still do.
        for (int tick : TICKS) {
            EntityModelData fox = PoseKit.posed(EntityOptions.PoseMode.ANIMATED, subject("minecraft:fox"), tick);
            for (String leg : new String[] {"left_front_leg", "right_front_leg", "left_hind_leg", "right_hind_leg"})
                assertTrue(fox.getBones().containsKey(leg), "a fox stands on its " + leg + " at tick " + tick);
            for (String id : new String[] {"minecraft:guardian", "minecraft:elder_guardian"})
                assertTrue(PoseKit.posed(EntityOptions.PoseMode.ANIMATED, subject(id), tick)
                    .getBones().containsKey("eye"), id + " keeps its eye at tick " + tick);
        }
    }

    @Test
    @DisplayName("a hidden bone takes its subtree and a skipped one takes only its own cubes")
    void theTwoFlagsDropDifferentThings() {
        // The difference is the whole reason there are two channels. Hiding is vanilla's
        // `visible = false`, which skips the part and everything under it - dropping the name alone
        // would re-parent each orphan onto the root and land geometry where the subject is not.
        // Skipping keeps every descendant drawing and loses only what the bone itself owns.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", cubed(null));
        mesh.getBones().put("head", cubed("body"));
        mesh.getBones().put("hat", cubed("head"));
        mesh.getBones().put("tail", cubed("body"));

        EntityModelData hidden = PoseKit.posed(EntityOptions.PoseMode.ANIMATED,
            subject("minecraft:test", mesh, flags(Map.of("head",
                Map.of(PoseChannel.VISIBLE, new PoseExpr.Const(0d, PoseOperator.Width.FLOAT))))), 0);
        assertEquals(List.of("body", "tail"), List.copyOf(hidden.getBones().keySet()),
            "the hidden bone goes and takes its hat with it");

        EntityModelData skipped = PoseKit.posed(EntityOptions.PoseMode.ANIMATED,
            subject("minecraft:test", mesh, flags(Map.of("head",
                Map.of(PoseChannel.SKIP_DRAW, new PoseExpr.Const(1d, PoseOperator.Width.FLOAT))))), 0);
        assertEquals(List.of("body", "head", "hat", "tail"), List.copyOf(skipped.getBones().keySet()),
            "the skipped bone stays, and so does everything under it");
        assertTrue(skipped.getBones().get("head").getCubes().isEmpty(), "and draws none of its own cubes");
        assertFalse(skipped.getBones().get("hat").getCubes().isEmpty(), "while its child draws all of its");
    }

    @Test
    @DisplayName("a bone scaled unevenly is refused rather than folded to one of its axes")
    void perAxisScaleIsRefused() {
        // A bone holds one scale where the table holds three. Every write in the corpus puts one
        // expression on all three, so the fold is exact - and a mesh that needs otherwise is a mesh
        // this cannot draw, which is worth saying rather than picking an axis to believe.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(null));

        EntityPose uneven = new EntityPose(Map.of(), Map.of("body", Map.of(
            PoseChannel.X_SCALE, new PoseExpr.Const(2d, PoseOperator.Width.FLOAT),
            PoseChannel.Y_SCALE, new PoseExpr.Const(3d, PoseOperator.Width.FLOAT),
            PoseChannel.Z_SCALE, new PoseExpr.Const(2d, PoseOperator.Width.FLOAT))),
            List.of(), Map.of(), Optional.empty());

        RendererException refused = assertThrows(RendererException.class, () -> PoseKit.posed(
            EntityOptions.PoseMode.ANIMATED, subject("minecraft:test", mesh, uneven), 0));
        assertTrue(refused.getMessage().contains("body"), "the refusal names the bone: " + refused.getMessage());
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        assertTrue(entity.pose().isReadable(), id + " is expected to have a readable pose");
        return entity;
    }

    private static @NotNull Entity subject(
        @NotNull String id, @NotNull EntityModelData mesh, @NotNull EntityPose pose) {
        return Entity.builder().id(ResourceId.parse(id)).model(mesh).pose(pose).build();
    }

    private static @NotNull EntityModelData.Bone bone(String parent) {
        return new EntityModelData.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(), parent);
    }

    /** A bone carrying one cube, so dropping its cubes is tellable from leaving them alone. */
    private static @NotNull EntityModelData.Bone cubed(String parent) {
        ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();
        cubes.add(new EntityModelData.Cube());
        return new EntityModelData.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes, parent);
    }

    /** A pose that writes the given flags and nothing else. */
    private static @NotNull EntityPose flags(@NotNull Map<String, Map<PoseChannel, PoseExpr>> bones) {
        return new EntityPose(Map.of(), bones, List.of(), Map.of(), Optional.empty());
    }

    private static boolean finite(@NotNull Vector3f vector) {
        return Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z());
    }

    private static boolean finite(@NotNull EulerRotation rotation) {
        return Float.isFinite(rotation.pitch()) && Float.isFinite(rotation.yaw())
            && Float.isFinite(rotation.roll());
    }

}
