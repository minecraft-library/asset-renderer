package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.exception.RendererException;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * The authored clips a model plays, evaluated at one instant into what they displace each bone by.
 *
 * <p><b>A clip ADDS where a pose REPLACES, on every target including scale.</b> Vanilla applies one
 * through {@code ModelPart.offsetPos}, {@code offsetRotation} and {@code offsetScale}, and all three
 * are a {@code +=} on the value already there - so what comes back here is a displacement to add to
 * whatever the pose left the bone at, never a place to put it. A bone's scale rests at one and a
 * clip's scale channel rests at zero, which is the same statement from two sides; reading the second
 * as a factor collapses every scaled bone at the instants the clip is at rest.
 *
 * <p><b>A gate decides whether a clip contributes at all, and each of the three reads a different
 * thing.</b> A walk-driven clip reads its own play site's arguments, which is where a model puts the
 * terms that keep it running at rest - a nautilus advances its swim on elapsed age and floors the
 * amplitude at a fifth, so it swims with nothing walking. A static one is held at its first instant.
 * A state-driven one plays while the render-state field its gate reads answers non-zero, and that
 * field is a member of a one-hot a caller selects.
 *
 * <p><b>A model declares several state-driven sites and at most one of them plays.</b> Sixty-seven
 * of the corpus's eighty-eight play sites are state-driven and a bat's two are its whole animation,
 * so a reader that played all of them would draw a bat flying and hanging at once. Vanilla's own
 * {@code setupAnimationStates} is where the exclusion lives - it stops one state to start another -
 * and the selection reproduces it: the selected member's field answers one and every other member of
 * its group answers zero, so nothing has to be told which sites conflict.
 *
 * <p><b>What a never-ticked subject plays is nothing, and that is why the group has a member driving
 * no field.</b> A state constructs STOPPED and {@code apply} is {@code ifStarted}, so an offline
 * vanilla subject runs none of these - which is measured rather than assumed: the breeze writes no
 * bone outside its six state-driven clips, and its reference silhouette is identical across every
 * frame of the sampled strip. Selecting nothing for a group is how a render asks for that subject
 * back.
 */
@UtilityClass
public final class ClipKit {

    /** Milliseconds a whole second holds, which is the unit vanilla's clip clock counts in. */
    private static final float MILLIS_PER_SECOND = 1000f;

    /** What a play site multiplies its own instant by before truncating to milliseconds - one tick. */
    private static final float MILLIS_PER_TICK = 50f;

    /** How many arguments a walk-driven site carries: position, amplitude, rate and scale. */
    private static final int WALK_ARGUMENTS = 4;

    /** How many a state-driven site carries: the elapsed age its own start tick is subtracted from. */
    private static final int STATE_ARGUMENTS = 1;

    /**
     * What every clip this pose plays displaces each bone by at one instant.
     *
     * <p>Accumulated across clips rather than resolved per clip, because two sites can drive one
     * bone and vanilla adds both onto the same part. A bone the mesh does not declare is passed
     * over, on the same terms {@link PoseEvaluator#evaluate} passes one over: a clip belongs to a
     * model class where a mesh belongs to a subject, and the two part company wherever a bone rests
     * undrawn and took its subtree with it.
     *
     * @param pose the model's pose, read for the clips it plays
     * @param model the mesh being posed, which is what says a bone exists to displace
     * @param frame what each render-state figure reads as
     * @return the displacement each written channel takes, by bone name, empty when nothing plays
     */
    public static @NotNull Map<String, Map<PoseChannel, Float>> deltas(
        @NotNull EntityPose pose, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame) {

        if (pose.clips().isEmpty()) return Map.of();

        // Order-preserving rather than Map.copyOf, for the reason the evaluator's own return is:
        // what comes out is read in order downstream and copyOf salts its iteration per JVM launch.
        Map<String, Map<PoseChannel, Float>> out = new LinkedHashMap<>();
        for (EntityPose.Clip site : pose.clips()) {
            Drive drive = driveOf(site, model, frame);
            if (drive == null) continue;
            accumulate(out, site.clip(), drive, model);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------

    /**
     * What a play site resolves to: where in the clip it sits, and what it scales the clip by.
     *
     * @param millis the clip clock, in the whole milliseconds vanilla truncates to
     * @param amplitude the factor every interpolated displacement is multiplied by
     */
    private record Drive(long millis, float amplitude) {}

    /**
     * Where one play site sits at this instant, or {@code null} where it does not play.
     *
     * @throws RendererException if a site does not carry the terms its drive takes
     */
    private static Drive driveOf(
        @NotNull EntityPose.Clip site, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame) {

        return switch (site.gate()) {
            // Held at its first instant, at the full amplitude - vanilla's own `apply(0L, 1.0f)`.
            case STATIC -> new Drive(0L, 1f);
            // Playing exactly when the frame answers the field the gate reads. That field is a state
            // a caller selects, the way every other one-hot is selected, and it answers zero for a
            // state nobody chose - so a model declaring six of them still plays at most the one.
            case STATE -> {
                if (frame.applyAsDouble(site.state()) == 0d) yield null;
                List<Float> terms = PoseEvaluator.values(site.arguments(), model, frame);
                if (terms.size() != STATE_ARGUMENTS)
                    throw new RendererException(
                        "entity clip: '%s' is state-driven on %d term(s), which takes %d",
                        site.coordinate(), terms.size(), STATE_ARGUMENTS);
                // Vanilla's `AnimationState.getTimeInMillis`, which is the elapsed age less the tick
                // the state was started at, truncated to whole milliseconds. A selected state starts
                // at tick zero on both sides, so the subtraction is of nothing and the term stands.
                yield new Drive((long) (terms.getFirst() * MILLIS_PER_TICK), 1f);
            }
            case WALK -> {
                List<Float> terms = PoseEvaluator.values(site.arguments(), model, frame);
                if (terms.size() != WALK_ARGUMENTS)
                    throw new RendererException(
                        "entity clip: '%s' is walk-driven on %d term(s), which takes %d",
                        site.coordinate(), terms.size(), WALK_ARGUMENTS);
                // Vanilla's applyWalk, operand for operand: the position scales to milliseconds by
                // the rate and TRUNCATES, and the amplitude is capped at one however hard it walks.
                long millis = (long) (terms.get(0) * MILLIS_PER_TICK * terms.get(2));
                yield new Drive(millis, Math.min(terms.get(1) * terms.get(3), 1f));
            }
        };
    }

    /** Adds one clip's displacement at this instant onto what the others already contributed. */
    private static void accumulate(
        @NotNull Map<String, Map<PoseChannel, Float>> out, @NotNull PoseClip clip,
        @NotNull Drive drive, @NotNull EntityModelData model) {

        float elapsed = drive.millis() / MILLIS_PER_SECOND;
        if (clip.looping()) elapsed %= clip.lengthSeconds();

        for (PoseClip.Channel channel : clip.channels()) {
            if (!model.getBones().containsKey(channel.bone())) continue;
            Map<PoseChannel, Float> written =
                out.computeIfAbsent(channel.bone(), bone -> new EnumMap<>(PoseChannel.class));
            for (int axis = 0; axis < 3; axis++) {
                float displaced = component(channel, elapsed, drive.amplitude(), axis);
                written.merge(channel.target().channel(axis), displaced, Float::sum);
            }
        }
    }

    /**
     * One component of one channel's displacement at one instant.
     *
     * <p>The bracketing pair is found the way vanilla finds it - the last keyframe at or before the
     * instant, and the one after it, both clamped into the table - and the fraction between them is
     * clamped rather than allowed to run past either end, which is what holds a clip at its last
     * frame once it stops looping.
     */
    private static float component(
        @NotNull PoseClip.Channel channel, float elapsed, float amplitude, int axis) {

        List<PoseClip.Keyframe> keyframes = channel.keyframes();
        int before = Math.max(0, firstAtOrAfter(keyframes, elapsed) - 1);
        int after = Math.min(keyframes.size() - 1, before + 1);

        PoseClip.Keyframe from = keyframes.get(before);
        PoseClip.Keyframe to = keyframes.get(after);
        float span = elapsed - from.timeSeconds();
        float progress = after == before ? 0f
            : clamp(span / (to.timeSeconds() - from.timeSeconds()));

        // The curve is the one the keyframe being APPROACHED carries, which is vanilla's convention.
        return switch (to.interpolation()) {
            case LINEAR -> lerp(from.component(axis), to.component(axis), progress) * amplitude;
            case CATMULLROM -> catmullrom(progress,
                keyframes.get(Math.max(0, before - 1)).component(axis),
                from.component(axis), to.component(axis),
                keyframes.get(Math.min(keyframes.size() - 1, after + 1)).component(axis)) * amplitude;
        };
    }

    /** The first keyframe at or after an instant, or the table's length where none is. */
    private static int firstAtOrAfter(@NotNull List<PoseClip.Keyframe> keyframes, float elapsed) {
        int low = 0;
        int high = keyframes.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (elapsed <= keyframes.get(middle).timeSeconds()) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    /**
     * Straight between two values, in JOML's own grouping.
     *
     * <p>Vanilla reaches the linear curve through {@code Vector3fc.lerp}, which is
     * {@code org.joml.Math.fma(other - self, t, self)} - and that is NOT
     * {@link Math#fma(float, float, float)}. JOML routes it to the intrinsic only when
     * {@code joml.useMathFma} is set, and the property defaults to false, so what actually runs is
     * the written-out {@code (other - self) * t + self}. The two differ at the last bit, and this is
     * a mirror of what JOML does rather than of what its name suggests - {@code ClipKitMirrorTest}
     * holds it to the real thing, JOML being on the test classpath where it is on no other.
     */
    private static float lerp(float from, float to, float progress) {
        return (to - from) * progress + from;
    }

    /** Vanilla's Catmull-Rom, term for term - a different grouping is a different float. */
    private static float catmullrom(float progress, float p0, float p1, float p2, float p3) {
        return 0.5f * (2f * p1 + (p2 - p0) * progress
            + (2f * p0 - 5f * p1 + 4f * p2 - p3) * progress * progress
            + (3f * p1 - p0 - 3f * p2 + p3) * progress * progress * progress);
    }

    private static float clamp(float progress) {
        return progress < 0f ? 0f : Math.min(progress, 1f);
    }

}
