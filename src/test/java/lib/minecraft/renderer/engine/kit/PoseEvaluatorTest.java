package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table evaluated.
 *
 * <p>Five things are worth pinning here. Every pose in the corpus has to evaluate to finite numbers,
 * because a pose is arithmetic a caller cannot inspect before trusting and a NaN in a bone channel
 * is a limb that vanishes rather than an error anyone sees. A shared sub-expression has to be
 * computed ONCE, which is the difference between an evaluator that returns and one that does not -
 * and it is pinned as a COUNT rather than as a timeout, because the failure mode is exponential and
 * a timeout would report it as a slow machine. A figure its own render state builds non-zero has to
 * be answered as built, which the humanoid arm proves by being NaN when it is not. An expression has
 * to answer the arithmetic the table spells, operand for operand. And a bone the mesh does not declare
 * has to be passed over, because a pose belongs to a model class where a mesh belongs to a subject,
 * and the two part company wherever a bone rests undrawn.
 */
@DisplayName("the shipped pose table evaluated")
class PoseEvaluatorTest {

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("every pose in the corpus evaluates to finite numbers at rest")
    void theWholeCorpusEvaluates() {
        int evaluated = 0;
        for (Entity entity : entities.values()) {
            EntityPose pose = entity.pose();
            if (!pose.isReadable()) continue;

            PoseEvaluator.ChannelWrites written =
                PoseEvaluator.evaluate(pose, meshFor(pose), PoseEvaluator.AT_REST);
            written.container().forEach(step -> step.forEach((channel, value) ->
                assertTrue(Float.isFinite(value),
                    "the container's " + channel.token() + " evaluates to a number")));
            written.bones().forEach((bone, channels) -> channels.forEach((channel, value) ->
                assertTrue(Float.isFinite(value),
                    bone + "." + channel.token() + " evaluates to a number")));
            evaluated++;
        }
        // A floor rather than a count: this is a function of the entity registry, where the roster
        // the walk answers for is a function of the model classes, so pinning it exactly would move
        // on a version bump for reasons that say nothing about evaluating.
        assertTrue(evaluated > 50, "the corpus is expected to be walked, not skipped past: " + evaluated);
    }

    @Test
    @DisplayName("a shared sub-expression is computed once, so a humanoid's arms cost hundreds and not millions")
    void sharingIsReadAsSharing() {
        // The load-bearing one. The generator followed both arms of everything it could not decide,
        // so one sub-expression is reached down enormously many PATHS while the table writes the
        // distinct NODES - a humanoid's arms are nine hundred standing for twenty-two million. An
        // evaluator that walked the paths would not return on this input, so what is asserted is the
        // number of times the frame was asked anything: once per reaching node, not once per path.
        EntityPose zombie = poseOf("minecraft:zombie");
        assertFalse(zombie.bones().isEmpty(), "the zombie is expected to pose its bones");

        AtomicInteger asked = new AtomicInteger();
        ToDoubleFunction<String> counting = field -> {
            asked.incrementAndGet();
            return 0d;
        };

        PoseEvaluator.evaluate(zombie, meshFor(zombie), counting);
        // Nineteen, which is how many DISTINCT figures the zombie's arithmetic names. The threshold
        // is loose because the two regimes are not close: one asks once per named figure, the other
        // once per path that reaches one, and the second does not finish.
        assertTrue(asked.get() < 1_000,
            "a memo on node identity asks once per named figure; it was asked " + asked.get() + " times");
    }

    @Test
    @DisplayName("a figure its render state builds non-zero was answered as built before it shipped")
    void aRestingFrameAnswersWhatTheRenderStateBuilds() {
        // What the render state builds is load-bearing rather than tidy, and a humanoid is where it
        // shows: HumanoidRenderState builds speedValue at one and every humanoid DIVIDES a limb swing
        // by it, so a subject answered nothing there is a division by zero and the limb is NaN.
        //
        // The shipped row is resolved against that value before it is written, so the division is
        // gone rather than merely survivable - and the assertion is the stronger one the fold earns:
        // the leg is a number whatever a caller answers, there being no figure left in the channel
        // for a caller to answer wrongly. A row that started reading one again would fail here on
        // the zero frame exactly as it used to.
        //
        // Read at the LEG rather than the arm. A zombie is posed by its own class and not by the one
        // that baked its mesh, and that class assigns the arm outright after the humanoid arithmetic
        // has run - the arms-out stance overwrites the swing. The legs it leaves alone.
        EntityPose zombie = poseOf("minecraft:zombie");
        EntityModelData mesh = meshFor(zombie);
        for (ToDoubleFunction<String> frame :
            List.<ToDoubleFunction<String>>of(PoseEvaluator.AT_REST, field -> 1d))
            assertTrue(Float.isFinite(PoseEvaluator.evaluate(zombie, mesh, frame)
                    .bones().get("left_leg").get(PoseChannel.X_ROT)),
                "a zombie stands on a leg that is a number, whatever it is asked");
    }

    @Test
    @DisplayName("a conditional channel takes the arm its condition chooses, each way round")
    void aConditionalChannelTakesTheArmItsConditionChooses() {
        // Select over Compare over Input, which is the shape a body's every branch decomposes into.
        // Built here rather than taken from the corpus: a shipped row is resolved against the frame
        // its subject rests in before it is written, so every branch a subject could reach is already
        // decided and none of them is left to exercise this. The mechanism outlives the rows that
        // happened to use it - a caller driving the tick still reaches it - so it is pinned on a pose
        // this test owns and cannot be quietly emptied by what the generator resolves next.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", new EntityModelData.Bone(Vector3f.ZERO, EulerRotation.NONE,
            EulerRotation.NONE, 1f, Concurrent.newList(), null));

        EntityPose carried = new EntityPose(
            Concurrent.newUnmodifiableList(Map.of(PoseChannel.Y, new PoseExpr.Select(
                new PosePredicate(PosePredicate.Comparison.EQ,
                    new PoseExpr.Input("hasEgg"), new PoseExpr.Const(0, PoseOperator.Width.INT)),
                new PoseExpr.Const(0f, PoseOperator.Width.FLOAT),
                new PoseExpr.Const(-1f, PoseOperator.Width.FLOAT)))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());

        assertEquals(0f, PoseEvaluator.evaluate(carried, mesh, PoseEvaluator.AT_REST)
                .container().getFirst().get(PoseChannel.Y),
            "answering nothing takes the arm the condition holds for");

        ToDoubleFunction<String> carrying = field -> "hasEgg".equals(field) ? 1d : 0d;
        assertEquals(-1f, PoseEvaluator.evaluate(carried, mesh, carrying)
                .container().getFirst().get(PoseChannel.Y),
            "and answering the figure takes the other");
    }

    @Test
    @DisplayName("an armour stand's legs splay a degree each way before anything happens to it")
    void aQuestionRestsAtWhatItsReceiverWasBuiltHolding() {
        // The corpus's one subject whose legs stand at anything: ArmorStand builds its default leg
        // poses at (-1, 0, -1) and (1, 0, 1), which the stand's own state holds before it has ticked.
        // A stand answering zero to every question stands with its legs together, and the two arms of
        // the splay are opposite - so a table that lost which leg it was answering would splay them
        // the same way and cost more than answering nothing does.
        //
        // Read off the SHIPPED row rather than off a frame: what a reference the render state holds
        // rests at is a fact about a subject standing still, so the generator resolved it, and what
        // is left to pin is that the number it resolved to is in the table and is the right way round
        // on each leg.
        Entity stand = entities.get("minecraft:armor_stand");
        assertNotNull(stand, "the corpus carries an armour stand");
        PoseEvaluator.ChannelWrites written =
            PoseEvaluator.evaluate(stand.pose(), stand.model(), PoseEvaluator.AT_REST);

        float degree = (float) Math.toRadians(1d);
        assertEquals(-degree, written.bones().get("left_leg").get(PoseChannel.X_ROT),
            "the left leg turns one way");
        assertEquals(degree, written.bones().get("right_leg").get(PoseChannel.X_ROT),
            "and the right turns the other");
        assertEquals(0f, written.bones().get("left_leg").get(PoseChannel.Y_ROT),
            "a component built at nothing rests there");
    }

    @Test
    @DisplayName("an unwritten channel reads the mesh, and a rotation reads it in radians")
    void aBoneReadAnswersTheMeshInTheTablesOwnUnits() {
        // The table's arithmetic is in radians where the mesh stores degrees, so a read that handed
        // back the stored number would be out by a factor of about 57 and still look like an angle.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(new EulerRotation(90f, 0f, 0f)));

        EntityPose reads = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("head",
                Map.of(PoseChannel.X_ROT, new PoseExpr.BoneRead("head", PoseChannel.X_ROT)))),
            Concurrent.newUnmodifiableList(), Optional.empty());

        assertEquals((float) Math.toRadians(90d),
            PoseEvaluator.evaluate(reads, mesh, PoseEvaluator.AT_REST).bones().get("head").get(PoseChannel.X_ROT),
            "ninety degrees of authored pitch reads back as the radians the table computes in");
    }

    @Test
    @DisplayName("a bone the mesh does not declare is passed over rather than read")
    void aBoneTheMeshDoesNotDeclareIsNotEvaluated() {
        // An illager rests with its arms crossed, which takes the pair it would hang out of the mesh
        // and the subtree under them with it - and the pose still writes those bones, because
        // vanilla's own setupAnim writes those fields on parts nothing renders. Evaluating one means
        // reading the mesh for a channel of a bone that is gone, which is a throw rather than a
        // number, and it is the whole roster of crossed-arm illagers plus the armour stand.
        Entity evoker = entities.get("minecraft:evoker");
        assertNotNull(evoker, "the evoker is expected to load");
        assertFalse(evoker.model().getBones().containsKey("left_arm"),
            "an evoker rests with its arms crossed, so the arm it would hang is not in its mesh");
        assertTrue(evoker.pose().bones().containsKey("left_arm"),
            "and its model poses that arm all the same");

        PoseEvaluator.ChannelWrites written =
            PoseEvaluator.evaluate(evoker.pose(), evoker.model(), PoseEvaluator.AT_REST);
        assertFalse(written.bones().containsKey("left_arm"), "so nothing is written for it");
        assertFalse(written.bones().isEmpty(), "while the bones the mesh does declare are posed");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull EntityPose poseOf(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        assertTrue(entity.pose().isReadable(), id + " is expected to have a readable pose");
        return entity.pose();
    }

    /**
     * A mesh declaring every bone a pose reads, so the evaluator has somewhere to read an unwritten
     * channel from. Built here rather than resolved through the geometry pipeline, because what is
     * under test is the arithmetic and a real mesh would only supply the same zeros.
     */
    private static @NotNull EntityModelData meshFor(@NotNull EntityPose pose) {
        EntityModelData mesh = new EntityModelData();
        Set<Object> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        pose.container().forEach(step -> step.values().forEach(expr -> declareRead(expr, mesh, walked)));
        pose.bones().forEach((bone, channels) -> {
            mesh.getBones().put(bone, bone(EulerRotation.NONE));
            channels.values().forEach(expr -> declareRead(expr, mesh, walked));
        });
        return mesh;
    }

    /** Every bone an expression reads, walked once per node rather than once per path. */
    private static void declareRead(
        @NotNull PoseExpr expr, @NotNull EntityModelData mesh, @NotNull Set<Object> walked) {

        if (!walked.add(expr)) return;
        switch (expr) {
            case PoseExpr.BoneRead read -> mesh.getBones()
                .computeIfAbsent(read.bone(), name -> bone(EulerRotation.NONE));
            case PoseExpr.Op op -> op.operands().forEach(operand -> declareRead(operand, mesh, walked));
            case PoseExpr.Select select -> {
                declareRead(select.whenTrue(), mesh, walked);
                declareRead(select.whenFalse(), mesh, walked);
                declareRead(select.condition(), mesh, walked);
            }
            default -> { /* a leaf reads no bone */ }
        }
    }

    /** A bone carrying nothing but the rotation under test, the rest at their own rest values. */
    private static @NotNull EntityModelData.Bone bone(@NotNull EulerRotation rotation) {
        return new EntityModelData.Bone(
            Vector3f.ZERO, rotation, EulerRotation.NONE, 1f, Concurrent.newList(), null);
    }

    /** Every bone a condition reads, through both of its operands. */
    private static void declareRead(
        @NotNull PosePredicate predicate, @NotNull EntityModelData mesh, @NotNull Set<Object> walked) {

        if (!walked.add(predicate)) return;
        declareRead(predicate.left(), mesh, walked);
        declareRead(predicate.right(), mesh, walked);
    }

}
