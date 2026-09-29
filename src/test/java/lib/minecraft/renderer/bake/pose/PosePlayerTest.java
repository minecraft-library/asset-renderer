package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.table.EntityTables;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.chainAt;
import static lib.minecraft.renderer.fixture.CompilerFixtures.drawnScale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table written back onto the mesh it poses.
 *
 * <p>These pin what posing must not cost: bones stay in the order the mesh declares them, because
 * that order is the tied-depth priority; a channel put back where it started stays bit-identical
 * rather than making the trip out to radians and back; and every subject in the corpus poses to a
 * number at every tick, which is what says the table and the shipped meshes agree about what a bone
 * is called. Which bones a subject draws is not posing's to decide - the tables carry it resolved on
 * their {@code undrawn} lists - so the four subjects whose models write visibility with no toggle
 * over it are pinned by where their renders land rather than by any channel.
 */
@DisplayName("the shipped pose table applied to a mesh")
class PosePlayerTest {

    /** Ticks a subject is posed at - zero, a couple of odd instants, and one before the start. */
    private static final int @NotNull [] TICKS = {0, 7, 41, -5};

    /** The applicability of an index-form request - the adult default every loaded subject is. */
    private static final @NotNull EntityOptions ADULT = EntityOptions.of("minecraft:subject");

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("every subject in the corpus poses to numbers, at every tick asked")
    void theWholeCorpusPoses() {
        int posed = 0;
        for (Entity entity : entities.values()) {
            for (int tick : TICKS) {
                EntityMesh mesh = body(entity, idle(entity), tick);
                String where = entity.id() + " at tick " + tick;
                // A written or clip scale rides the pose scale as a ratio over the rest, which the rest
                // itself never shows, so both are held to a number.
                mesh.getBones().forEach((name, bone) -> {
                    assertTrue(finite(bone.getPivot()), where + ": " + name + " stands somewhere");
                    assertTrue(finite(bone.getRotation()), where + ": " + name + " points somewhere");
                    assertTrue(Float.isFinite(bone.getScale()), where + ": " + name + " is some size");
                    assertTrue(finite(bone.getPoseScale()), where + ": " + name + " is posed to some size");
                });
            }
            if (body(entity, idle(entity), 0) != entity.model()) posed++;
        }
        // A floor rather than a count, for the reason the evaluator's own corpus walk carries one:
        // the roster follows the entity registry and moves on a version bump.
        assertTrue(posed > 50, "the corpus is expected to be posed, not skipped past: " + posed);
    }

    @Test
    @DisplayName("a walking subject moves what a standing one holds still, and stays finite doing it")
    void walkingDrivesTheStrideAndNothingElse() {
        // A stride row is the standing drivers plus the walk pair, so the whole of what separates
        // the two rows is that pair. Two things are worth pinning and they fail differently.
        //
        // That the stride reaches anything at all: the pair is read by 81 of the corpus's pose
        // classes and by far more bones than elapsed time is, so a row that answered them at rest
        // would look like it worked and draw an idle subject. Counted over the corpus rather than
        // asserted on one animal, the roster following the entity registry.
        int strides = 0;
        for (Entity entity : entities.values()) {
            for (int tick : TICKS) {
                EntityMesh walked = body(entity, stride(entity), tick);
                String where = entity.id() + " walking at tick " + tick;
                // And that it stays a number. The stride is divided by `speedValue` in every
                // humanoid arm swing, so a row that drove the amplitude without the phase - or
                // either of them past what vanilla clamps to - is a NaN rather than a wrong angle.
                walked.getBones().forEach((name, bone) -> {
                    assertTrue(finite(bone.getPivot()), where + ": " + name + " stands somewhere");
                    assertTrue(finite(bone.getRotation()), where + ": " + name + " points somewhere");
                    assertTrue(Float.isFinite(bone.getScale()), where + ": " + name + " is some size");
                    assertTrue(finite(bone.getPoseScale()), where + ": " + name + " is posed to some size");
                });
            }
            EntityMesh idle = body(entity, idle(entity), 7);
            EntityMesh walking = body(entity, stride(entity), 7);
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
            EntityMesh mesh = body(entity, idle(entity), 13);
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
        EntityMesh at0 = body(zombie, idle(zombie), 0);
        EntityMesh at9 = body(zombie, idle(zombie), 9);

        assertNotEquals(at0.getBones().get("left_arm").getRotation(),
            at9.getBones().get("left_arm").getRotation(), "nine ticks of standing still is an arm bob");
        assertEquals(at0.getBones(), body(zombie, idle(zombie), 0).getBones(),
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

        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("head", new EntityMesh.Bone(new Vector3f(1f, 2f, 3f), authored,
            EulerRotation.NONE, 1f, Concurrent.newList(), null));

        EntityPose readsItself = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("head",
                Map.of(PoseChannel.X_ROT, new PoseExpr.BoneRead("head", PoseChannel.X_ROT)))),
            Concurrent.newUnmodifiableList(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, readsItself);
        EntityMesh posed = body(built, idle(built), 4);
        assertEquals(authored, posed.getBones().get("head").getRotation(),
            "a channel written back to what it held is the number it held");
    }

    @Test
    @DisplayName("a container becomes the parent of every bone the mesh names at top level")
    void aContainerIsSeatedAboveTheRoots() {
        // The container is a transform above the whole mesh that the mesh names nowhere, so it has
        // to arrive as something the chain composition already knows how to walk - and it draws none
        // of its own, so no bone that draws changes the order it is drawn in.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(null));
        mesh.getBones().put("head", bone("body"));

        EntityPose drops = new EntityPose(
            Concurrent.newUnmodifiableList(Map.of(PoseChannel.Y, new PoseExpr.Constant(-3d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, drops);
        EntityMesh posed = body(built, idle(built), 0);

        String container = List.copyOf(posed.getBones().keySet()).getLast();
        assertEquals(-3f, posed.getBones().get(container).getPivot().y(),
            "the container holds what the pose wrote it");
        assertTrue(posed.getBones().get(container).getCubes().isEmpty(), "and draws nothing of its own");
        assertEquals(container, posed.getBones().get("body").getParent(), "a root hangs from it");
        assertEquals("body", posed.getBones().get("head").getParent(), "and a bone that had a parent keeps it");
    }

    @Test
    @DisplayName("a top-level pivot of a flattened mesh is placed through the factor and the feet anchor")
    void aFlattenedTopLevelPivotIsPlacedThroughFactorAndAnchor() {
        // The tooling stores a top-level pivot as F * p + anchor * (1 - F) on y, so a delta a pose
        // adds to the bone's own read lands multiplied by F with the anchor cancelling, an absolute
        // vanilla number lands the way the generator would have stored it, and a child - whose pivot
        // is parent-relative - crosses the factor alone.
        float factor = 2f;
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone(new Vector3f(1f, 20f, 4f), EulerRotation.NONE,
            EulerRotation.NONE, factor, Concurrent.newList(), null));
        mesh.getBones().put("tail", new EntityMesh.Bone(new Vector3f(0f, 6f, 8f), EulerRotation.NONE,
            EulerRotation.NONE, factor, Concurrent.newList(), "body"));
        PoseExpr three = new PoseExpr.Constant(3d, PoseWidth.DOUBLE);

        EntityPose shifted = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of(
                "body", Map.of(
                    PoseChannel.Y, new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(new PoseExpr.BoneRead("body", PoseChannel.Y), three)),
                    PoseChannel.X, new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(new PoseExpr.BoneRead("body", PoseChannel.X), three))),
                "tail", Map.of(
                    PoseChannel.Y, new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(new PoseExpr.BoneRead("tail", PoseChannel.Y), three))))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Entity built = subject("minecraft:test", mesh, shifted);
        EntityMesh posed = body(built, idle(built), 0);
        assertEquals(20f + factor * 3f, posed.getBones().get("body").getPivot().y(), 1e-4f,
            "a delta on the root's own read lands multiplied by the factor, the anchor cancelling");
        assertEquals(1f + factor * 3f, posed.getBones().get("body").getPivot().x(), 1e-4f,
            "x carries no anchor");
        assertEquals(6f + factor * 3f, posed.getBones().get("tail").getPivot().y(), 1e-4f,
            "a child crosses the factor alone");

        EntityPose absolute = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body",
                Map.of(PoseChannel.Y, new PoseExpr.Constant(30d, PoseWidth.DOUBLE)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Entity assigned = subject("minecraft:test", mesh, absolute);
        assertEquals(30f * factor + EntityMesh.flattenedShift(factor),
            body(assigned, idle(assigned), 0).getBones().get("body").getPivot().y(), 1e-4f,
            "an absolute vanilla number is stored the way the generator stores a top-level pivot");

        EntityPose readsItself = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body",
                Map.of(PoseChannel.Y, new PoseExpr.BoneRead("body", PoseChannel.Y)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Entity held = subject("minecraft:test", mesh, readsItself);
        assertEquals(20f, body(held, idle(held), 0).getBones().get("body").getPivot().y(),
            "written back to what it reads, the mesh's own bits stand");
    }

    @Test
    @DisplayName("the container of a flattened mesh seats at the numbers the pose wrote, above the root the factor and the anchor ride")
    void aFlattenedContainerSeatsAtWhatThePoseWrote() {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone(new Vector3f(0f, 20f, 0f), EulerRotation.NONE,
            EulerRotation.NONE, 2f, Concurrent.newList(), null));

        EntityPose dropped = new EntityPose(
            Concurrent.newUnmodifiableList(
                Map.of(PoseChannel.X, new PoseExpr.Constant(0d, PoseWidth.FLOAT),
                    PoseChannel.Y, new PoseExpr.Constant(0d, PoseWidth.FLOAT)),
                Map.of(PoseChannel.Y, new PoseExpr.Constant(-3d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        Entity placed = subject("minecraft:test", mesh, dropped);
        EntityMesh seated = body(placed, idle(placed), 0);
        List<String> names = List.copyOf(seated.getBones().keySet());
        assertEquals(3, names.size(), "the body and two cubeless steps");
        assertEquals("body", names.getFirst());
        EntityMesh.Bone outer = seated.getBones().get(names.get(1));
        EntityMesh.Bone inner = seated.getBones().get(names.get(2));
        assertEquals(new Vector3f(0f, 0f, 0f), outer.getPivot(), "the outer step lands at the zeros written");
        assertEquals(-3f, inner.getPivot().y(), "the inner step lands at the number written");
        assertEquals(names.get(2), seated.getBones().get("body").getParent(), "the body hangs off the inner step");
        assertEquals(20f, seated.getBones().get("body").getPivot().y(), "and keeps the pivot nothing wrote");

        EntityPose turned = new EntityPose(
            Concurrent.newUnmodifiableList(Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.5d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        Entity tilted = subject("minecraft:test", mesh, turned);
        assertEquals(2, body(tilted, idle(tilted), 0).getBones().size(),
            "a rotation-only step seats above the root");
    }

    @Test
    @DisplayName("a clip keying the container's scale at vanilla's identity leaves the step unscaled")
    void anIdentityContainerScaleLeavesTheStepUnscaled() {
        // The baby camel's sit pose, on a mesh declaring no bone named `root`: the root's scale keyed
        // at scaleVec(1, 1, 1), which is a displacement of nothing, beside its position keyed at
        // (0, -0, 0). Vanilla's offsetScale adds nothing to a root reset to one and translateAndRotate
        // skips a scale standing at one, so the frame is the one the clip draws without the channel.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));
        mesh.getBones().put("head", cubed("body"));

        Entity sitting = subject("minecraft:test", mesh,
            rooted(root(PoseChannel.Kind.POSITION, 0f, -0f, 0f), root(PoseChannel.Kind.SCALE, 0f, 0f, 0f)));
        Entity unscaled = subject("minecraft:test", mesh, rooted(root(PoseChannel.Kind.POSITION, 0f, -0f, 0f)));
        EntityMesh posed = body(sitting, idle(sitting), 0);

        List<String> names = List.copyOf(posed.getBones().keySet());
        assertEquals(3, names.size(), "the body, the head and one cubeless step");
        assertTrue(posed.getBones().get(names.getLast()).getCubes().isEmpty(), "the step draws nothing");
        posed.getBones().forEach((name, bone) ->
            assertFalse(bone.isPoseScaled(), name + " is scaled by nothing"));
        assertEquals(body(unscaled, idle(unscaled), 0).getBones(), posed.getBones(),
            "every bone stands where the same clip without the scale channel puts it");
    }

    @Test
    @DisplayName("a clip's container scale rides the innermost step the pose writes, and no step above it")
    void aContainerScaleRidesTheInnermostStep() {
        // Vanilla scales the root after its own translate and rotation, below every step put on the
        // stack before it, so the scale reaches the bones and none of the steps above. Carried on the
        // outer step here, it would scale the inner step's translate too.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));

        EntityPose turned = new EntityPose(
            Concurrent.newUnmodifiableList(
                Map.of(PoseChannel.Y_ROT, new PoseExpr.Constant(0.5d, PoseWidth.FLOAT)),
                Map.of(PoseChannel.Y, new PoseExpr.Constant(-3d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), rooted(root(PoseChannel.Kind.SCALE, 0.5f, 0f, 0f)).clips(),
            Optional.empty());
        Entity built = subject("minecraft:test", mesh, turned);
        EntityMesh posed = body(built, idle(built), 0);

        List<String> names = List.copyOf(posed.getBones().keySet());
        assertEquals(3, names.size(), "the body and two cubeless steps");
        EntityMesh.Bone outer = posed.getBones().get(names.get(1));
        EntityMesh.Bone inner = posed.getBones().get(names.get(2));
        assertFalse(outer.isPoseScaled(), "the outer step carries no scale");
        assertEquals(new Vector3f(1.5f, 1f, 1f), inner.getPoseScale(),
            "the inner step carries one plus the displacement");
        assertEquals(-3f, inner.getPivot().y(), "at the number the pose wrote it");
        assertFalse(posed.getBones().get("body").isPoseScaled(), "and the body carries no scale of its own");
    }

    @Test
    @DisplayName("a clip's container scale on a flattened mesh poses at zero and refuses a displacement, naming the factor")
    void aFlattenedContainerScaleRefusesADisplacement() {
        // Vanilla's root rests at the flattened factor inside the feet-anchor translate, so a clip
        // scales it to the factor plus the displacement, where no step above the dissolved root
        // reaches. A zero is exact at any factor, the factor plus nothing being the factor.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone(new Vector3f(0f, 20f, 0f), EulerRotation.NONE,
            EulerRotation.NONE, 2f, Concurrent.newList(), null));

        Entity identity = subject("minecraft:test", mesh, rooted(root(PoseChannel.Kind.SCALE, 0f, 0f, 0f)));
        EntityMesh posed = body(identity, idle(identity), 0);
        assertEquals(2, posed.getBones().size(), "a zero displacement seats its step");
        posed.getBones().forEach((name, bone) ->
            assertFalse(bone.isPoseScaled(), name + " is scaled by nothing"));
        assertEquals(20f, posed.getBones().get("body").getPivot().y(), "and the body keeps its pivot");

        Entity displaced = subject("minecraft:test", mesh, rooted(root(PoseChannel.Kind.SCALE, 0.5f, 0f, 0f)));
        RendererException refused = assertThrows(RendererException.class,
            () -> body(displaced, idle(displaced), 0));
        assertTrue(refused.getMessage().contains("flattened at '2.0'"),
            "the refusal names the factor: " + refused.getMessage());
    }

    @Test
    @DisplayName("a scale the pose writes on the container is refused, a flattened root holding its scale inside the feet anchor")
    void aWrittenContainerScaleIsRefused() {
        // A written container scale assigns the root's own field, which on a flattened mesh holds the
        // factor inside the feet anchor the seat stands above - a ratio on the step would multiply the
        // factor and scale the anchor with it. No shipped model writes one, so every mesh refuses it.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));

        EntityPose grows = new EntityPose(
            Concurrent.newUnmodifiableList(Map.of(PoseChannel.X_SCALE, new PoseExpr.Constant(2d, PoseWidth.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        Entity built = subject("minecraft:test", mesh, grows);
        RendererException refused = assertThrows(RendererException.class,
            () -> body(built, idle(built), 0));
        assertTrue(refused.getMessage().contains("writes 'x_scale', a root scale a flattened mesh holds inside the feet anchor"),
            "the refusal names the channel and why a step cannot carry it: " + refused.getMessage());
    }

    @Test
    @DisplayName("a small pufferfish rolls the fins its own model writes, which the large one's pose never names")
    void theSmallPufferfishRollsItsOwnFins() {
        Entity small = AppearanceOptions.builder().size(Optional.of(Size.SMALL)).build()
            .resolve(subject("minecraft:pufferfish"));
        EntityPose own = EntityTables.read().orElseThrow().poses().poses().get("PufferfishSmallModel");
        assertNotNull(own, "the pose table carries the small pufferfish's own model");
        for (int tick : TICKS) {
            EntityMesh posed = body(small, idle(small), tick);
            EntityMesh expected = PosePlayer.posed(own, small.model(), idle(small), period(small), tick);
            for (String fin : List.of("right_fin", "left_fin")) {
                String where = "'" + fin + "' at tick " + tick;
                float roll = posed.getBones().get(fin).getRotation().roll();
                assertEquals(expected.getBones().get(fin).getRotation().roll(), roll,
                    where + " rolls where its own model puts it");
                assertNotEquals(small.model().getBones().get(fin).getRotation().roll(), roll,
                    where + " leaves its bind roll");
            }
        }
    }

    @Test
    @DisplayName("the renderer's transform reaches every mesh the subject submits, not the body alone")
    void theRenderTransformReachesEveryPass() {
        // A tropical fish is a body and a run of pattern overlays, and vanilla turns the pose stack
        // once for all of them. The index build seats the renderer's steps at the front of every
        // pose the subject's meshes take, so a posed frame gives each pass the same container bones
        // the body gains - a transform that reached the body alone would swim the fish out from
        // under its own markings, visible as the passes gaining fewer bones than the body did.
        Entity fish = entities.get("minecraft:tropical_fish");
        assertNotNull(fish, "the corpus carries a tropical fish");
        int steps = fish.pose().container().size();
        assertTrue(steps > 0, "whose renderer composes a transform");
        assertFalse(fish.overlays().isEmpty(), "and which draws overlay passes");

        Entity posed = PosePlayer.posed(fish, idle(fish), period(fish), 4);
        assertEquals(fish.model().getBones().size() + steps, posed.model().getBones().size(),
            "the body carries a bone per step");
        for (int pass = 0; pass < fish.overlays().size(); pass++)
            assertEquals(
                fish.overlays().get(pass).model().getBones().size() + steps,
                posed.overlays().get(pass).model().getBones().size(),
                "pass " + pass + " carries the same steps the body does");
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
            Entity entity = subject(id);
            EntityMesh mesh = body(entity, idle(entity), 0);
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
            assertSame(entity, PosePlayer.posed(entity, StyleCatalog.bind(), period(entity), 13),
                entity.id() + " is its own subject under the authored pose");
            Entity posed = PosePlayer.posed(entity, idle(entity), period(entity), 13);
            assertEquals(entity.overlays().size(), posed.overlays().size(),
                entity.id() + " draws the passes it drew");
            for (int pass = 0; pass < entity.overlays().size(); pass++)
                if (posed.overlays().get(pass).model() != entity.overlays().get(pass).model()) moved++;
        }
        assertTrue(moved > 0, "some overlay pass in the corpus is expected to move: " + moved);
    }

    @Test
    @DisplayName("a posed overlay pass keeps the texture scroll its pass declares")
    void aPosedOverlayKeepsItsTextureScroll() {
        // The scroll is a property of the pass's render type rather than of the mesh it draws, so a
        // pass whose mesh a pose moves must come back still carrying it. The rebuilt row is a new
        // record, and one that answered the scroll with its default would hold the breeze's wind and
        // the charged creeper's swirl still the day either subject's pass gains a moving pose.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));
        EntityPose turns = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body",
                Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.5d, PoseWidth.FLOAT)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
        Optional<Vector2f> scroll = Optional.of(new Vector2f(0.02f, 0.01f));
        Entity subject = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(mesh)
            .pose(turns)
            .overlays(Concurrent.newUnmodifiableList(new Entity.OverlayLayer(mesh, Optional.empty(),
                PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), turns, scroll)))
            .build();

        Entity posed = PosePlayer.posed(subject, idle(subject), period(subject), 7);
        assertNotSame(mesh, posed.overlays().getFirst().model(), "the pass's mesh is expected to move");
        assertEquals(scroll, posed.overlays().getFirst().textureScroll(),
            "the rebuilt pass carries the scroll the authored one declared");
    }

    @Test
    @DisplayName("the four subjects whose models write visibility outside every branch")
    void theFrameDrivenVisibilityWritersLandWhereVanillaPutsThem() {
        // These four write visibility with nothing a still subject could branch on selecting it.
        // Vanilla decides each outside every such branch, so each has one right answer - resolved at
        // generation - and this is what says the render still lands where vanilla puts it, at every
        // tick a pose is asked for.
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
        //
        // It is KEPT and hidden rather than dropped, because that state is one a caller can select:
        // the mesh carries a `croak` toggle over it, so what rests undrawn is not what can never be
        // drawn. The other three carry no toggle and their answer is the whole answer.
        for (int tick : TICKS) {
            Entity fox = subject("minecraft:fox");
            EntityMesh foxMesh = body(fox, idle(fox), tick);
            for (String leg : new String[] {"left_front_leg", "right_front_leg", "left_hind_leg", "right_hind_leg"})
                assertTrue(foxMesh.getBones().containsKey(leg), "a fox stands on its " + leg + " at tick " + tick);
            for (String id : new String[] {"minecraft:guardian", "minecraft:elder_guardian"}) {
                Entity guardian = subject(id);
                assertTrue(body(guardian, idle(guardian), tick).getBones().containsKey("eye"),
                    id + " keeps its eye at tick " + tick);
            }
            Entity enderman = subject("minecraft:enderman");
            assertTrue(body(enderman, idle(enderman), tick).getBones().containsKey("head"),
                "an enderman keeps its head at tick " + tick);

            Entity frog = subject("minecraft:frog");
            EntityMesh frogMesh = body(frog, idle(frog), tick);
            EntityMesh.Bone sac = frogMesh.getBones().get("croaking_body");
            assertNotNull(sac, "a frog keeps the sac a croak selection draws, at tick " + tick);
            assertFalse(sac.isVisible(), "and is not mid-croak at tick " + tick);
        }
    }

    @Test
    @DisplayName("a bone scaled unevenly is refused rather than folded to one of its axes")
    void perAxisScaleIsRefused() {
        // A written scale rides the chain as one uniform ratio where the table holds three axes, since
        // a non-uniform one would need vanilla's inverse-scaled normal matrix. Every write in the
        // corpus puts one expression on all three, so the fold is exact - and a pose that needs
        // otherwise is one this cannot shade, which is worth saying rather than picking an axis to believe.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", bone(null));

        EntityPose uneven = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", Map.of(
                PoseChannel.X_SCALE, new PoseExpr.Constant(2d, PoseWidth.FLOAT),
                PoseChannel.Y_SCALE, new PoseExpr.Constant(3d, PoseWidth.FLOAT),
                PoseChannel.Z_SCALE, new PoseExpr.Constant(2d, PoseWidth.FLOAT)))),
            Concurrent.newUnmodifiableList(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, uneven);
        RendererException refused = assertThrows(RendererException.class,
            () -> body(built, idle(built), 0));
        assertTrue(refused.getMessage().contains("body"), "the refusal names the bone: " + refused.getMessage());
    }

    @Test
    @DisplayName("a scale written on a bone resting at zero is refused, no ratio over that rest carrying it")
    void aScaleOverAZeroRestIsRefused() {
        // A written scale rides the chain as its ratio to the bone's rest, and a rest of zero has
        // none - dividing by it would put an infinite factor on the chain rather than a scale.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 0f,
            Concurrent.newList(), null));

        PoseExpr one = new PoseExpr.Constant(1d, PoseWidth.FLOAT);
        EntityPose grows = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", Map.of(
                PoseChannel.X_SCALE, one, PoseChannel.Y_SCALE, one, PoseChannel.Z_SCALE, one))),
            Concurrent.newUnmodifiableList(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, grows);
        RendererException refused = assertThrows(RendererException.class,
            () -> body(built, idle(built), 0));
        assertTrue(refused.getMessage().contains("bone 'body' is scaled to '1.0' from a rest of zero"),
            "the refusal names the bone, the scale and the rest: " + refused.getMessage());
    }

    @Test
    @DisplayName("a bone the pose scales and a clip scales too is refused, one field holding both in vanilla")
    void aWrittenAndAClipScaleOnOneBoneAreRefused() {
        // Vanilla adds the clip onto the field the body assigned, where a ratio and a displacement
        // would multiply - and the written ratio is assigned over whatever pose scale the bone holds,
        // which is exact only because no bone reaches it carrying a clip's scale as well.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));

        PoseExpr two = new PoseExpr.Constant(2d, PoseWidth.FLOAT);
        EntityPose both = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", Map.of(
                PoseChannel.X_SCALE, two, PoseChannel.Y_SCALE, two, PoseChannel.Z_SCALE, two))),
            rooted(keyed("body", PoseChannel.Kind.SCALE, 0f, 0f, 0.5f)).clips(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, both);
        RendererException refused = assertThrows(RendererException.class,
            () -> body(built, idle(built), 0));
        assertTrue(refused.getMessage().contains("bone 'body' is scaled by its model and by a clip"),
            "the refusal names the bone: " + refused.getMessage());
    }

    @Test
    @DisplayName("a written scale and a clip's scale on different bones multiply down the chain, the clip's surviving a turn")
    void writtenAndClipScalesMultiplyDownTheChain() {
        // Vanilla assigns the body's field and the jaw's, and adds the clip onto the head's between
        // them, and each part's translateAndRotate scales the stack after its turn - so the jaw draws
        // at the three multiplied, axis by axis, and hangs from the head at its pivot scaled by the two
        // above it. The head is turned as well, which is what copies it: that copy keeps the clip's
        // scale only because a bone written no scale is handed back rather than assigned a ratio of one.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", cubed(null));
        mesh.getBones().put("head", new EntityMesh.Bone(new Vector3f(0f, 4f, 0f), EulerRotation.NONE,
            EulerRotation.NONE, 1f, Concurrent.newList(), "body"));
        mesh.getBones().put("jaw", new EntityMesh.Bone(new Vector3f(0f, 0f, -2f), EulerRotation.NONE,
            EulerRotation.NONE, 1f, Concurrent.newList(), "head"));

        PoseExpr two = new PoseExpr.Constant(2d, PoseWidth.FLOAT);
        PoseExpr three = new PoseExpr.Constant(3d, PoseWidth.FLOAT);
        PoseExpr turn = new PoseExpr.Constant(0.5d, PoseWidth.FLOAT);
        EntityPose scaled = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of(
                "body", Map.of(PoseChannel.X_SCALE, two, PoseChannel.Y_SCALE, two, PoseChannel.Z_SCALE, two),
                "head", Map.of(PoseChannel.X_ROT, turn),
                "jaw", Map.of(PoseChannel.X_SCALE, three, PoseChannel.Y_SCALE, three, PoseChannel.Z_SCALE, three))),
            rooted(keyed("head", PoseChannel.Kind.SCALE, 0f, 0f, 0.5f)).clips(), Optional.empty());

        Entity built = subject("minecraft:test", mesh, scaled);
        EntityMesh posed = body(built, idle(built), 0);
        assertEquals(new Vector3f(2f, 2f, 2f), posed.getBones().get("body").getPoseScale(), "the body rides its write");
        assertEquals(new Vector3f(1f, 1f, 1.5f), posed.getBones().get("head").getPoseScale(),
            "the turned head keeps one plus the clip's displacement");
        assertEquals(new Vector3f(3f, 3f, 3f), posed.getBones().get("jaw").getPoseScale(), "the jaw rides its write");

        assertEquals(6f, drawnScale(posed, "jaw", 1), 1e-5f, "the jaw draws its x at the body's 2 times its own 3");
        assertEquals(6f, drawnScale(posed, "jaw", 2), 1e-5f, "and its y the same");
        assertEquals(9f, drawnScale(posed, "jaw", 3), 1e-5f, "and its z at the head's 1.5 between them");
        Vector3f head = chainAt(posed, "head");
        Vector3f jaw = chainAt(posed, "jaw");
        assertEquals(8f, head.y(), 1e-5f, "the head hangs at its pivot scaled by the body's write");
        float dx = jaw.x() - head.x();
        float dy = jaw.y() - head.y();
        float dz = jaw.z() - head.z();
        assertEquals(6f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz), 1e-5f,
            "and the jaw two pixels along the head's z, scaled by the head's 1.5 and the body's 2");
    }

    // ------------------------------------------------------------------------------------

    /** The subject's own idle row, resolved as an adult index form asks for it. */
    private static @NotNull PoseStyle idle(@NotNull Entity entity) {
        return entity.styles().resolve(PoseStyle.IDLE, ADULT.getAppearance()::applies, ADULT.getEntityId());
    }

    /** The subject's own stride row, resolved as an adult index form asks for it. */
    private static @NotNull PoseStyle stride(@NotNull Entity entity) {
        return entity.styles().resolve(PoseStyle.STRIDE, ADULT.getAppearance()::applies, ADULT.getEntityId());
    }

    private static int period(@NotNull Entity entity) {
        return entity.styles().periodTicks();
    }

    /** The subject's body mesh where one row leaves it at one tick. */
    private static @NotNull EntityMesh body(
        @NotNull Entity entity, @NotNull PoseStyle style, int tick) {

        return PosePlayer.posed(entity, style, period(entity), tick).model();
    }

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        assertTrue(entity.pose().isReadable(), id + " is expected to have a readable pose");
        return entity;
    }

    private static @NotNull Entity subject(
        @NotNull String id, @NotNull EntityMesh mesh, @NotNull EntityPose pose) {
        return Entity.builder().id(ResourceId.parse(id)).model(mesh).pose(pose).build();
    }

    /** A pose holding one undriven clip at its first instant, over the channels given. */
    private static @NotNull EntityPose rooted(@NotNull PoseClip.Channel... channels) {
        PoseClip clip = new PoseClip(1f, false, Concurrent.newUnmodifiableList(channels));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip(
                "test", ClipDrive.NONE, Optional.empty(), Concurrent.newUnmodifiableList(), clip)),
            Optional.empty());
    }

    /**
     * One channel keying a target of {@code root} once, which a mesh declaring no bone of that name
     * answers with the container.
     */
    private static @NotNull PoseClip.Channel root(
        @NotNull PoseChannel.Kind target, float x, float y, float z) {

        return keyed("root", target, x, y, z);
    }

    /** One channel keying a target of a named bone once. */
    private static @NotNull PoseClip.Channel keyed(
        @NotNull String bone, @NotNull PoseChannel.Kind target, float x, float y, float z) {

        return new PoseClip.Channel(bone, target, Concurrent.newUnmodifiableList(
            new PoseClip.Keyframe(0f, x, y, z, PoseClip.Interpolation.LINEAR)));
    }

    private static @NotNull EntityMesh.Bone bone(String parent) {
        return new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(), parent);
    }

    /** A bone carrying one cube, so a pose that moves the mesh is tellable from one that does not. */
    private static @NotNull EntityMesh.Bone cubed(String parent) {
        ConcurrentList<EntityMesh.Cube> cubes = Concurrent.newList();
        cubes.add(new EntityMesh.Cube());
        return new EntityMesh.Bone(Vector3f.ZERO, EulerRotation.NONE, EulerRotation.NONE, 1f,
            cubes, parent);
    }

    private static boolean finite(@NotNull Vector3f vector) {
        return Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z());
    }

    private static boolean finite(@NotNull EulerRotation rotation) {
        return Float.isFinite(rotation.pitch()) && Float.isFinite(rotation.yaw())
            && Float.isFinite(rotation.roll());
    }

}
