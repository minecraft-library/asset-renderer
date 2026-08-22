package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table evaluated.
 *
 * <p>Three things are worth pinning here. Every pose in the corpus has to evaluate to finite
 * numbers, because a pose is arithmetic a caller cannot inspect before trusting and a NaN in a bone
 * channel is a limb that vanishes rather than an error anyone sees. A shared sub-expression has to
 * be computed ONCE, which is the difference between an evaluator that returns and one that does not
 * - and it is pinned as a COUNT rather than as a timeout, because the failure mode is exponential
 * and a timeout would report it as a slow machine. And an expression has to answer the arithmetic
 * the table spells, operand for operand.
 */
@DisplayName("the shipped pose table evaluated")
class PoseEvaluatorTest {

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    /**
     * Every render-state figure whose own constructor gives it something other than zero.
     *
     * <p>One today, and it is load-bearing rather than incidental: {@code HumanoidRenderState}
     * constructs {@code speedValue} at one and every humanoid divides an arm swing by it, so a
     * caller answering the blanket zero gets NaN where vanilla gets the swing undivided.
     */
    private static final @NotNull PoseEvaluator.Frame DECLARED_DEFAULTS = new PoseEvaluator.Frame() {
        @Override
        public float input(@NotNull String field) {
            return "speedValue".equals(field) ? 1f : 0f;
        }
    };

    @Test
    @DisplayName("every pose in the corpus evaluates to finite numbers")
    void theWholeCorpusEvaluates() {
        int evaluated = 0;
        for (Entity entity : entities.values()) {
            EntityPose pose = entity.pose();
            if (!pose.isReadable()) continue;

            PoseEvaluator.ChannelWrites written =
                PoseEvaluator.evaluate(pose, meshFor(pose), DECLARED_DEFAULTS);
            written.container().forEach((channel, value) -> assertTrue(Float.isFinite(value),
                "the container's " + channel.token() + " evaluates to a number"));
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
        PoseEvaluator.Frame counting = new PoseEvaluator.Frame() {
            @Override
            public float input(@NotNull String field) {
                asked.incrementAndGet();
                return 0f;
            }
        };

        PoseEvaluator.evaluate(zombie, meshFor(zombie), counting);
        // Nineteen, which is how many DISTINCT figures the zombie's arithmetic names. The threshold
        // is loose because the two regimes are not close: one asks once per named figure, the other
        // once per path that reaches one, and the second does not finish.
        assertTrue(asked.get() < 1_000,
            "a memo on node identity asks once per named figure; it was asked " + asked.get() + " times");
    }

    @Test
    @DisplayName("a turtle's container drops by one exactly when it is carrying an egg")
    void aConditionalChannelTakesTheArmItsConditionChooses() {
        // Select over Compare over Input, which is the commonest shape in the corpus, evaluated both
        // ways round. The zero frame answers nothing to every question, so it takes the no-egg arm.
        EntityPose turtle = poseOf("minecraft:turtle");
        EntityModelData mesh = meshFor(turtle);

        assertEquals(0f, PoseEvaluator.evaluate(turtle, mesh, PoseEvaluator.ZERO)
            .container().get(PoseChannel.Y), "a turtle at rest carries no egg and holds where it is");

        PoseEvaluator.Frame carrying = new PoseEvaluator.Frame() {
            @Override
            public float input(@NotNull String field) {
                return "hasEgg".equals(field) ? 1f : 0f;
            }
        };
        assertEquals(-1f, PoseEvaluator.evaluate(turtle, mesh, carrying).container().get(PoseChannel.Y),
            "and drops by one when it is");
    }

    @Test
    @DisplayName("an unwritten channel reads the mesh, and a rotation reads it in radians")
    void aBoneReadAnswersTheMeshInTheTablesOwnUnits() {
        // The table's arithmetic is in radians where the mesh stores degrees, so a read that handed
        // back the stored number would be out by a factor of about 57 and still look like an angle.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(new EulerRotation(90f, 0f, 0f)));

        EntityPose reads = new EntityPose(Map.of(), Map.of("head",
            Map.of(PoseChannel.X_ROT, new PoseExpr.BoneRead("head", PoseChannel.X_ROT))),
            List.of(), Optional.empty());

        assertEquals((float) Math.toRadians(90d),
            PoseEvaluator.evaluate(reads, mesh, PoseEvaluator.ZERO).bones().get("head").get(PoseChannel.X_ROT),
            "ninety degrees of authored pitch reads back as the radians the table computes in");
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
        pose.container().values().forEach(expr -> declareRead(expr, mesh, walked));
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
        switch (predicate) {
            case PosePredicate.Compare compare -> {
                declareRead(compare.left(), mesh, walked);
                declareRead(compare.right(), mesh, walked);
            }
            case PosePredicate.Not not -> declareRead(not.operand(), mesh, walked);
            default -> { /* an enum test, a presence test or a decided constant reads no bone */ }
        }
    }

}
