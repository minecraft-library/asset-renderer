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
                EntityModelData mesh = PoseKit.posed(EntityOptions.PoseMode.IDLE, entity, tick);
                String where = entity.id() + " at tick " + tick;
                mesh.getBones().forEach((name, bone) -> {
                    assertTrue(finite(bone.getPivot()), where + ": " + name + " stands somewhere");
                    assertTrue(finite(bone.getRotation()), where + ": " + name + " points somewhere");
                    assertTrue(Float.isFinite(bone.getScale()), where + ": " + name + " is some size");
                });
            }
            if (PoseKit.posed(EntityOptions.PoseMode.IDLE, entity, 0) != entity.model()) posed++;
        }
        // A floor rather than a count, for the reason the evaluator's own corpus walk carries one:
        // the roster follows the entity registry and moves on a version bump.
        assertTrue(posed > 50, "the corpus is expected to be posed, not skipped past: " + posed);
    }

    @Test
    @DisplayName("a walking subject moves what a standing one holds still, and stays finite doing it")
    void walkingDrivesTheStrideAndNothingElse() {
        // A gait is the further figures that stop resting, so the whole of what separates the two
        // presets is the stride pair. Two things are worth pinning and they fail differently.
        //
        // That WALK reaches anything at all: the pair is read by 81 of the corpus's pose classes and
        // by far more bones than elapsed time is, so a preset that answered them at rest would look
        // like it worked and draw an idle subject. Counted over the corpus rather than asserted on
        // one animal, the roster following the entity registry.
        int strides = 0;
        for (Entity entity : entities.values()) {
            for (int tick : TICKS) {
                EntityModelData walked = PoseKit.posed(EntityOptions.PoseMode.WALK, entity, tick);
                String where = entity.id() + " walking at tick " + tick;
                // And that it stays a number. The stride is divided by `speedValue` in every
                // humanoid arm swing, so a preset that drove the amplitude without the phase - or
                // either of them past what vanilla clamps to - is a NaN rather than a wrong angle.
                walked.getBones().forEach((name, bone) -> {
                    assertTrue(finite(bone.getPivot()), where + ": " + name + " stands somewhere");
                    assertTrue(finite(bone.getRotation()), where + ": " + name + " points somewhere");
                    assertTrue(Float.isFinite(bone.getScale()), where + ": " + name + " is some size");
                });
            }
            EntityModelData idle = PoseKit.posed(EntityOptions.PoseMode.IDLE, entity, 7);
            EntityModelData walking = PoseKit.posed(EntityOptions.PoseMode.WALK, entity, 7);
            if (!idle.getBones().equals(walking.getBones())) strides++;
        }
        assertTrue(strides > 50, "the corpus is expected to walk, not stand: " + strides);
    }

    @Test
    @DisplayName("a posed mesh keeps its bones in the order its mesh declares them")
    void theMeshsOwnOrderSurvives() {
        // The order is the tied-depth priority a coplanar pair is decided by, so a rebuild that
        // reordered the map would re-decide which face survives on subjects nothing else touched.
        for (Entity entity : entities.values()) {
            EntityModelData mesh = PoseKit.posed(EntityOptions.PoseMode.IDLE, entity, 13);
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
        EntityModelData at0 = PoseKit.posed(EntityOptions.PoseMode.IDLE, zombie, 0);
        EntityModelData at9 = PoseKit.posed(EntityOptions.PoseMode.IDLE, zombie, 9);

        assertNotEquals(at0.getBones().get("left_arm").getRotation(),
            at9.getBones().get("left_arm").getRotation(), "nine ticks of standing still is an arm bob");
        assertEquals(at0.getBones(), PoseKit.posed(EntityOptions.PoseMode.IDLE, zombie, 0).getBones(),
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
            List.of(), Map.of(), Map.of(), Optional.empty());

        EntityModelData posed = PoseKit.posed(EntityOptions.PoseMode.IDLE,
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
            Map.of(), List.of(), Map.of(), Map.of(), Optional.empty());

        EntityModelData posed = PoseKit.posed(EntityOptions.PoseMode.IDLE,
            subject("minecraft:test", mesh, drops), 0);

        String container = List.copyOf(posed.getBones().keySet()).getLast();
        assertEquals(-3f, posed.getBones().get(container).getPivot().y(),
            "the container holds what the pose wrote it");
        assertTrue(posed.getBones().get(container).getCubes().isEmpty(), "and draws nothing of its own");
        assertEquals(container, posed.getBones().get("body").getParent(), "a root hangs from it");
        assertEquals("body", posed.getBones().get("head").getParent(), "and a bone that had a parent keeps it");
    }

    @Test
    @DisplayName("a subject is posed by the class its renderer hands the model, not the one that baked it")
    void theRenderersClassIsWhatPosesTheBody() {
        // A zombie's mesh is HumanoidModel#createMesh, because ZombieModel declares no layer of its
        // own - but the renderer hands the layer a ZombieModel, and it is that class whose setupAnim
        // runs. Reading the coordinate poses it as a plain humanoid and loses AnimationUtils
        // .animateZombieArms, which is the arms-out stance every zombie stands in: both arms at
        // -PI/2.25, symmetric, where the humanoid alone leaves one at nothing and the other partway.
        float armsOut = (float) Math.toDegrees(-Math.PI / 2.25);
        for (String id : new String[] {"minecraft:zombie", "minecraft:husk", "minecraft:giant"}) {
            EntityModelData mesh = PoseKit.posed(EntityOptions.PoseMode.IDLE, subject(id), 0);
            float left = mesh.getBones().get("left_arm").getRotation().pitch();
            float right = mesh.getBones().get("right_arm").getRotation().pitch();
            assertEquals(left, right, 1e-4f, id + " holds both arms at one angle");
            assertEquals(armsOut, left, 1e-3f, id + " holds them out, at the angle vanilla swings them to");
        }
    }

    @Test
    @DisplayName("an overlay pass moves with the body rather than staying where it is authored")
    void anOverlayPassIsPosedToo() {
        // A pass carries geometry of its own and poses it with its own model class, so posing the
        // body alone leaves a sheep's wool where the sheep no longer is. Counted over the corpus
        // rather than named, because which passes move is a property of the shipped table.
        int moved = 0;
        for (Entity entity : entities.values()) {
            assertSame(entity, PoseKit.posedSubject(EntityOptions.PoseMode.BIND, entity, 13),
                entity.id() + " is its own subject under the authored pose");
            Entity posed = PoseKit.posedSubject(EntityOptions.PoseMode.IDLE, entity, 13);
            assertEquals(entity.overlays().size(), posed.overlays().size(),
                entity.id() + " draws the passes it drew");
            for (int pass = 0; pass < entity.overlays().size(); pass++)
                if (posed.overlays().get(pass).model() != entity.overlays().get(pass).model()) moved++;
        }
        assertTrue(moved > 0, "some overlay pass in the corpus is expected to move: " + moved);
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
                assertTrue(PoseKit.posed(EntityOptions.PoseMode.IDLE, entity, tick)
                        .getBones().keySet().containsAll(declared),
                    entity.id() + " draws every bone it declares at tick " + tick);
        }
    }

    @Test
    @DisplayName("the four subjects whose models write visibility with no toggle over them")
    void theFrameDrivenVisibilityWritersLandWhereVanillaPutsThem() {
        // These four wrote visibility with nothing selecting it, which is why they waited for a
        // runtime that reads the channel at all. Vanilla decides each of them outside every branch a
        // still subject could take, so each has one right answer and this is what says it still holds.
        //
        // Three draw. FoxModel.setupAnim calls setWalkingPose - which sets all four legs visible -
        // before it tests anything, leaving only setSleepingPose behind an isSleeping that rests
        // false. GuardianModel writes its eye visible unconditionally, past the guard that skips the
        // eye's POSITION when nothing is being looked at. EndermanModel writes its head visible
        // unconditionally too, on the instruction after its super call.
        //
        // The frog is the one that does not, and it is the only flag in the corpus whose right answer
        // is to hide: croakingBody.visible is croakAnimationState.isStarted(), and an animation
        // nothing started has not started - a frog at rest is not mid-croak, so it has no inflated
        // throat. The sac is a whole opaque cube sitting inside the body with its flanks exactly
        // coplanar with the body's, so drawing it would paint a tan patch over the brown - which is
        // what makes this assertable from the render rather than only from the bytecode.
        for (int tick : TICKS) {
            EntityModelData fox = PoseKit.posed(EntityOptions.PoseMode.IDLE, subject("minecraft:fox"), tick);
            for (String leg : new String[] {"left_front_leg", "right_front_leg", "left_hind_leg", "right_hind_leg"})
                assertTrue(fox.getBones().containsKey(leg), "a fox stands on its " + leg + " at tick " + tick);
            for (String id : new String[] {"minecraft:guardian", "minecraft:elder_guardian"})
                assertTrue(PoseKit.posed(EntityOptions.PoseMode.IDLE, subject(id), tick)
                    .getBones().containsKey("eye"), id + " keeps its eye at tick " + tick);
            assertTrue(PoseKit.posed(EntityOptions.PoseMode.IDLE, subject("minecraft:enderman"), tick)
                .getBones().containsKey("head"), "an enderman keeps its head at tick " + tick);
            assertFalse(PoseKit.posed(EntityOptions.PoseMode.IDLE, subject("minecraft:frog"), tick)
                .getBones().containsKey("croaking_body"), "a frog is not mid-croak at tick " + tick);
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

        EntityModelData hidden = PoseKit.posed(EntityOptions.PoseMode.IDLE,
            subject("minecraft:test", mesh, flags(Map.of("head",
                Map.of(PoseChannel.VISIBLE, new PoseExpr.Const(0d, PoseOperator.Width.FLOAT))))), 0);
        assertEquals(List.of("body", "tail"), List.copyOf(hidden.getBones().keySet()),
            "the hidden bone goes and takes its hat with it");

        EntityModelData skipped = PoseKit.posed(EntityOptions.PoseMode.IDLE,
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
            List.of(), Map.of(), Map.of(), Optional.empty());

        RendererException refused = assertThrows(RendererException.class, () -> PoseKit.posed(
            EntityOptions.PoseMode.IDLE, subject("minecraft:test", mesh, uneven), 0));
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
        return new EntityPose(Map.of(), bones, List.of(), Map.of(), Map.of(), Optional.empty());
    }

    private static boolean finite(@NotNull Vector3f vector) {
        return Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z());
    }

    private static boolean finite(@NotNull EulerRotation rotation) {
        return Float.isFinite(rotation.pitch()) && Float.isFinite(rotation.yaw())
            && Float.isFinite(rotation.roll());
    }

}
