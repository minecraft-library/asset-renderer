package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authored clips a model plays, evaluated onto its bones.
 *
 * <p>Four things are worth pinning. The linear curve has to be JOML's OWN arithmetic, because
 * vanilla reaches it through {@code Vector3fc.lerp} and JOML's {@code fma} is not the intrinsic one
 * - a mirror written from the method's name rather than its body is wrong at the last bit on every
 * interpolated frame. A state-driven clip has to contribute nothing, because that is what a subject
 * standing still gets and it is what the breeze's own reference says vanilla does. A walk-driven one
 * has to contribute even when nothing walks, because that is where the corpus puts the terms that
 * keep a clip running at rest. And a clip has to reach the bone's DESCENDANTS through the scale it
 * writes, which is the whole reason that scale is not the uniform factor beside it.
 */
@DisplayName("the authored clips a model plays")
class ClipKitTest {

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("the linear curve is JOML's own lerp, at the bit")
    void theLinearCurveMirrorsJoml() {
        // JOML is on this classpath and on no other, which is what lets the mirror be held to the
        // real thing rather than to a reading of it. Its `lerp` is `org.joml.Math.fma(b - a, t, a)`,
        // and that routes to java.lang.Math.fma ONLY when `joml.useMathFma` is set - the property
        // defaults to false, so what runs is the written-out form. Asserted across a spread of
        // instants because the two groupings agree at the ends and part in the middle.
        float from = RAMP_FROM;
        float to = RAMP_TO;
        for (int millis = 0; millis <= 1000; millis += 31) {
            // The progress is derived the way the evaluator derives it rather than from the position
            // handed in, because a play site truncates its instant to whole milliseconds and the
            // product that reaches that truncation is itself a float - `0.217 * 50 * 20` lands just
            // under 217 and floors to 216. That is real and separately visible; comparing the curve
            // against a progress the evaluator never used would only hide it behind this assertion.
            float at = millis / 1000f;
            float progress = (long) (at * WALK_MILLIS_PER_POSITION * WALK_RATE) / 1000f;
            Vector3f expected = new Vector3f(from, from, from)
                .lerp(new Vector3f(to, to, to), progress, new Vector3f());

            float measured = displacement(at);
            assertEquals(expected.x(), measured, 0f,
                "the linear curve at " + progress + " is JOML's own number, not one a rewrite lands on");
        }
    }

    @Test
    @DisplayName("a clip reaching a bone's own scale reaches everything under it too")
    void aScaledBoneCarriesItsSubtree() {
        // The reason a clip's scale is not the uniform factor beside it. That one is the whole-mesh
        // scale the tooling already flattened onto every bone, so it is applied to a cube's own
        // operands and must not propagate; this one was written at render time and has to, the way
        // vanilla's PoseStack.scale does.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", new EntityModelData.Bone());
        mesh.getBones().put("head", child("body"));

        EntityModelData posed =
            PoseKit.posed(EntityOptions.PoseMode.IDLE, scaling("body"), mesh, 0);

        assertTrue(posed.getBones().get("body").isPoseScaled(), "the bone the clip names is scaled");
        assertFalse(posed.getBones().get("head").isPoseScaled(),
            "and its child carries no scale of its own - it inherits one through the chain");
        assertEquals(1f, posed.getBones().get("body").getScale(), 0f,
            "the whole-mesh factor beside it is untouched, the two being different facts");
    }

    @Test
    @DisplayName("a state-driven clip contributes nothing to a subject standing still")
    void aStateDrivenClipDoesNotPlay() {
        // Sixty-seven of the corpus's play sites are state-driven and the breeze's six are among
        // them - and the breeze writes no bone outside its clips, so if one ran it would move. Its
        // own reference is identical across every frame of the strip, which is what says vanilla
        // runs none of them either. Asserted as identity: the mesh is handed back, not rebuilt.
        Entity breeze = subject("minecraft:breeze");
        assertFalse(breeze.pose().clips().isEmpty(), "a breeze plays clips");
        assertTrue(breeze.pose().clips().stream().allMatch(clip -> clip.gate() == EntityPose.Gate.STATE),
            "and every one of them is state-driven");

        for (int tick : new int[] {0, 3, 9, 21})
            assertEquals(breeze.model(), PoseKit.posed(
                    EntityOptions.PoseMode.IDLE, breeze, tick),
                "a breeze standing still holds still at tick " + tick);
    }

    @Test
    @DisplayName("a walk-driven clip runs on a subject that is not walking")
    void aWalkDrivenClipRunsAtRest() {
        // The nautilus is where the corpus puts the terms that make this true: its play site carries
        // `walkAnimationPos + ageInTicks / 5` for the instant and `walkAnimationSpeed + 0.2` for the
        // amplitude, so the age term runs the clock and the floor keeps the amplitude off zero. A
        // reading that gated a walk-driven clip on something walking would freeze it.
        Entity nautilus = subject("minecraft:nautilus");
        EntityModelData still = PoseKit.posed(
            EntityOptions.PoseMode.IDLE, nautilus, 0);
        EntityModelData later = PoseKit.posed(
            EntityOptions.PoseMode.IDLE, nautilus, 9);

        assertNotEqualMeshes(still, later);
    }

    // ------------------------------------------------------------------------------------

    /** The two ends of the mirrored ramp, chosen to disagree in the middle rather than at the ends. */
    private static final float RAMP_FROM = -0.37f;
    private static final float RAMP_TO = 0.9128f;

    /** What a walk-driven site multiplies its position by before truncating - vanilla's own fifty. */
    private static final float WALK_MILLIS_PER_POSITION = 50f;

    /** The rate the mirrored site plays at, chosen so a second of position is a second of clip. */
    private static final float WALK_RATE = 20f;

    /** What a two-keyframe linear clip displaces its bone by at one instant of its own span. */
    private static float displacement(float at) {
        float from = RAMP_FROM;
        float to = RAMP_TO;
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", new EntityModelData.Bone());

        PoseClip clip = new PoseClip(1f, false, Concurrent.newUnmodifiableList(new PoseClip.Channel("body",
            PoseClip.Target.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, from, from, from, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, to, to, to, PoseClip.Interpolation.LINEAR)))));

        // A static site holds the clip at nothing, so the instant is driven in as a walk position:
        // millis is `position * 50 * rate`, and a rate of 20 turns a second of clip into a second.
        EntityPose pose = new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip(
                "test", EntityPose.Gate.WALK,
                Concurrent.newUnmodifiableList(constant(at), constant(1f), constant(WALK_RATE), constant(1f)), clip)),
            Optional.empty());

        Map<PoseChannel, Float> written =
            ClipKit.deltas(pose, mesh, PoseEvaluator.AT_REST).get("body");
        assertNotNull(written, "the clip displaces the bone it names");
        return written.get(PoseChannel.X_ROT);
    }

    /** A pose playing one clip that scales a named bone on one axis. */
    private static @NotNull EntityPose scaling(@NotNull String bone) {
        PoseClip clip = new PoseClip(1f, false, Concurrent.newUnmodifiableList(new PoseClip.Channel(bone,
            PoseClip.Target.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0.5f, PoseClip.Interpolation.LINEAR)))));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip(
                "test", EntityPose.Gate.STATIC, Concurrent.newUnmodifiableList(), clip)),
            Optional.empty());
    }

    private static @NotNull EntityModelData.Bone child(@NotNull String parent) {
        EntityModelData.Bone bone = new EntityModelData.Bone();
        return new EntityModelData.Bone(bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
            bone.getScale(), bone.getCubes(), parent);
    }

    private static @NotNull PoseExpr constant(float value) {
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

    private static void assertNotEqualMeshes(
        @NotNull EntityModelData first, @NotNull EntityModelData second) {

        assertFalse(first.getBones().equals(second.getBones()),
            "a walk-driven clip is expected to stand somewhere different at a later tick");
    }

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

}
