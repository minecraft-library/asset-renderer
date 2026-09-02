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
 * <p><b>What a never-ticked subject plays is nothing, and a group whose vanilla selector has a
 * resting arm carries a member for it.</b> A state constructs STOPPED and {@code apply} is
 * {@code ifStarted}, so an offline vanilla subject runs none of these - which is measured rather
 * than assumed: the breeze writes no bone outside its six state-driven clips, and its reference
 * silhouette is identical across every frame of the sampled strip. Selecting that member is how a
 * render asks for a still subject back. <b>It is not offered where vanilla has no such arm</b> - a
 * bat's own tick stops one of its two states to start the other, so both of its members play a clip
 * and a never-ticked bat is a frame vanilla draws only before its first tick.
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
     * The channel name vanilla reserves for the part every bone hangs from.
     *
     * <p>{@code ModelPart.createPartLookup} seeds its map with {@code root -> this} and adds the
     * named children afterwards, so a channel spelled this way reaches the model's own root - which
     * is the container, flattened away and named nowhere - and a mesh that declares a bone of this
     * name overwrites the entry and takes it back. Ten of the corpus's meshes do, so the precedence
     * is read off that map rather than assumed either way.
     */
    private static final @NotNull String ROOT_PART = "root";

    /**
     * What a pose's clips displace at one instant: every bone they reach, and the container.
     *
     * <p>The container is held apart for the reason {@link PoseEvaluator.ChannelWrites} holds it
     * apart - it is not a bone, and nothing downstream can seat it as one.
     *
     * @param bones the displacement each written channel takes, by bone name
     * @param container the displacement the container takes, empty where no clip reaches it
     */
    public record Displacement(
        @NotNull Map<String, Map<PoseChannel, Float>> bones,
        @NotNull Map<PoseChannel, Float> container
    ) {

        /** What a pose playing no clip displaces, which is nothing anywhere. */
        public static final @NotNull Displacement NONE = new Displacement(Map.of(), Map.of());

        /** Whether no clip reaches a bone or the container. */
        public boolean isEmpty() {
            return this.bones.isEmpty() && this.container.isEmpty();
        }

        /** What the clips displace one bone by, which is nothing for a bone none of them reach. */
        public @NotNull Map<PoseChannel, Float> of(@NotNull String bone) {
            return this.bones.getOrDefault(bone, Map.of());
        }
    }

    /**
     * What every clip this pose plays displaces at one instant.
     *
     * <p>Accumulated across clips rather than resolved per clip, because two sites can drive one
     * bone and vanilla adds both onto the same part. A bone the mesh does not declare is passed
     * over, on the same terms {@link PoseEvaluator#evaluate} passes one over: a clip belongs to a
     * model class where a mesh belongs to a subject, and the two part company wherever a bone rests
     * undrawn and took its subtree with it.
     *
     * <p><b>{@link #ROOT_PART} is the one name that is not a bone at all</b>, and a clip that walks
     * a subject rocks it with one - the camel's stride carries a two-and-a-half degree roll of the
     * whole animal there. Passed over as an undeclared bone it is silently nothing, which is a camel
     * that walks without leaning and a canvas measured around one.
     *
     * @param pose the model's pose, read for the clips it plays
     * @param model the mesh being posed, which is what says a bone exists to displace
     * @param frame what each render-state figure reads as
     * @return what the clips displace, empty when nothing plays
     */
    public static @NotNull Displacement deltas(
        @NotNull EntityPose pose, @NotNull EntityModelData model,
        @NotNull ToDoubleFunction<String> frame) {

        if (pose.clips().isEmpty()) return Displacement.NONE;

        // Order-preserving rather than Map.copyOf, for the reason the evaluator's own return is:
        // what comes out is read in order downstream and copyOf salts its iteration per JVM launch.
        Map<String, Map<PoseChannel, Float>> bones = new LinkedHashMap<>();
        Map<PoseChannel, Float> container = new EnumMap<>(PoseChannel.class);
        for (EntityPose.Clip site : pose.clips()) {
            Drive drive = driveOf(site, model, frame);
            if (drive == null) continue;
            accumulate(bones, container, site.clip(), drive, model);
        }
        return new Displacement(bones, container);
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

        return switch (site.drive()) {
            // Held at its first instant, at the full amplitude - vanilla's own `apply(0L, 1.0f)`.
            case NONE -> new Drive(0L, 1f);
            // Playing exactly when the frame answers the field the gate reads. That field is a state
            // a caller selects, the way every other one-hot is selected, and it answers zero for a
            // state nobody chose - so a model declaring six of them still plays at most the one.
            case SELECT -> {
                if (frame.applyAsDouble(site.field().orElseThrow()) == 0d) yield null;
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
            case STRIDE -> {
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
            case TICK, FIGURE, SCROLL -> throw new RendererException(
                "entity clip: '%s' cannot be driven by '%s'", site.coordinate(), site.drive().token());
        };
    }

    /** Adds one clip's displacement at this instant onto what the others already contributed. */
    private static void accumulate(
        @NotNull Map<String, Map<PoseChannel, Float>> bones,
        @NotNull Map<PoseChannel, Float> container, @NotNull PoseClip clip,
        @NotNull Drive drive, @NotNull EntityModelData model) {

        float elapsed = drive.millis() / MILLIS_PER_SECOND;
        if (clip.looping()) elapsed %= clip.lengthSeconds();

        for (PoseClip.Channel channel : clip.channels()) {
            Map<PoseChannel, Float> written = target(bones, container, channel.bone(), model);
            if (written == null) continue;
            for (int axis = 0; axis < 3; axis++) {
                float displaced = component(channel, elapsed, drive.amplitude(), axis);
                written.merge(channel.target().channel(axis), displaced, Float::sum);
            }
        }
    }

    /**
     * Where one channel's displacement accumulates, or {@code null} where the mesh has nothing for it.
     *
     * <p>The lookup vanilla builds, in vanilla's own order: the root part answers {@link #ROOT_PART}
     * and every named bone answers its own name, the bones being added second so one of them spelled
     * that way wins.
     *
     * <p><b>The container answers to the name the MESH gave it as well as to {@link #ROOT_PART}</b>,
     * and it has to, because vanilla's own lookup is built before the flattening this side does.
     * {@code createPartLookup} seeds {@code root -> this} and then adds every named DESCENDANT, so a
     * model whose root holds one named part above the rest resolves a clip channel at that part's
     * own name - and the geometry flow dissolves exactly such a part into the bones below it, which
     * leaves the name spelled nowhere a bone lookup can reach. Passed over as an undeclared bone it
     * is silently nothing, and that is not a small loss: it cost the breeze the whole six-pixel
     * shove its slide gives the body, against a reference that had it.
     */
    private static Map<PoseChannel, Float> target(
        @NotNull Map<String, Map<PoseChannel, Float>> bones,
        @NotNull Map<PoseChannel, Float> container, @NotNull String named,
        @NotNull EntityModelData model) {

        if (model.getBones().containsKey(named))
            return bones.computeIfAbsent(named, bone -> new EnumMap<>(PoseChannel.class));
        return isContainer(named, model) ? container : null;
    }

    /**
     * Whether a name the mesh declares no bone for is the container every top-level bone hangs from.
     *
     * <p>Read off the mesh rather than carried beside it, because the mesh already says it: the flow
     * dissolves the container's pose into the bones below it and leaves each of them naming it as a
     * PARENT, so a dangling parent reference is what a flattened container is. It is the same test
     * {@code PoseKit.isTopLevel} applies from the other side - a bone whose parent the mesh does not
     * declare hangs from the container - asked of the parent instead of the child.
     *
     * <p><b>One dangling name per mesh is what makes this an answer rather than a guess</b>, and it
     * is a property of the emitted corpus rather than an assumption: of the geometries shipped
     * today exactly one carries a dangling parent at all, and no mesh carries two. A second would
     * mean a surgery had dropped an intermediate bone and left its children pointing at it, which
     * is a different thing wearing the same shape, so {@code ClipKitContainerTest} holds the corpus
     * to one.
     */
    private static boolean isContainer(@NotNull String named, @NotNull EntityModelData model) {
        if (ROOT_PART.equals(named)) return true;
        for (EntityModelData.Bone bone : model.getBones().values())
            if (named.equals(bone.getParent())) return true;
        return false;
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
