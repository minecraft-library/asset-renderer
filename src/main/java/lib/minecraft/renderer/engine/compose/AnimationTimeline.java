package lib.minecraft.renderer.engine.compose;

import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Derives an output-clock animation timeline from a subject's resolved {@code .mcmeta} sidecars
 * (the {@code AUTO} feeder). A subject that opts in to
 * {@code AnimationOptions.deriveTimeline} cannot generally know a sensible {@code frameCount} /
 * {@code ticksPerFrame} for its textures (a block may composite a 32-frame 2-tick water face and a
 * 300-frametime prismarine face); this helper probes each animated texture's loop length + entry
 * cadence and reconciles them into either:
 * <ul>
 * <li>a {@link Uniform} (frameCount + ticksPerFrame) for {@link Finalize.FinalizeSpec#tickStrip}, or</li>
 * <li>a {@link Schedule} (change-point sample ticks + per-frame delays) for
 *     {@link Finalize#renderSchedule}.</li>
 * </ul>
 *
 * <p>Both reconcile within one capped loop: {@link #MAX_LOOP_TICKS} = 200 ticks (the tick equivalent
 * of {@code FrameCompositor.MAX_LOOP_MS} = 10 000 ms; also the precedent in {@code GlintKit}'s 2 s
 * truncation of the impractical 330 s vanilla glint loop). Interpolating textures have no discrete
 * change points - every tick is distinct - so derivation treats them as cadence 1 and lets the cap
 * bound the frame count. When no source is animated the result degrades to {@link Uniform#STATIC}, so
 * requesting {@code AUTO} on a static subject costs nothing.
 */
@UtilityClass
public class AnimationTimeline {

    /**
     * Upper bound on the derived loop length in ticks - the tick equivalent of
     * {@code FrameCompositor.MAX_LOOP_MS} (10 000 ms / 50 ms-per-tick). Caps the LCM so a
     * long-frametime texture (prismarine's 300-tick frames, thousands-of-ticks loop) cannot explode
     * the frame count.
     */
    public static final int MAX_LOOP_TICKS = 200;

    /**
     * One resolved animated texture feeding derivation.
     *
     * @param frameCount the texture strip's implicit frame count (strip height / frame height), used
     *     only when the animation declares no explicit {@code frames} list
     * @param animation the parsed {@code .mcmeta} metadata
     */
    public record Source(int frameCount, @NotNull AnimationData animation) {}

    /**
     * A uniform-cadence derived timeline: {@code frameCount} output frames, {@code ticksPerFrame} ticks
     * apart, seeded at tick 0.
     *
     * @param frameCount the number of output frames
     * @param ticksPerFrame the ticks between successive output frames
     */
    public record Uniform(int frameCount, int ticksPerFrame) {

        /** The static (non-animated) timeline: a single frame. */
        public static final @NotNull Uniform STATIC = new Uniform(1, 1);
    }

    /**
     * A change-point derived timeline: one output frame per distinct texture state, each drawn at its
     * absolute tick and held for its own delay.
     *
     * @param sampleTicks the absolute tick each output frame is drawn at, ascending
     * @param frameDelaysMs the playback duration of each frame in milliseconds, parallel to {@code sampleTicks}
     */
    public record Schedule(long @NotNull [] sampleTicks, int @NotNull [] frameDelaysMs) {}

    /**
     * Derives a {@link Uniform} timeline from the animated sources: {@code ticksPerFrame} is the GCD of
     * every entry duration (floored at 1, forced to 1 when any source interpolates so every distinct
     * tick is sampled); the loop length is the LCM of every source's total ticks, capped at
     * {@link #MAX_LOOP_TICKS}; {@code frameCount = ceil(loopTicks / ticksPerFrame)}. Returns
     * {@link Uniform#STATIC} when no source is playable.
     *
     * @param sources the subject's resolved animated textures (sidecar-less textures excluded upstream)
     * @return the derived uniform timeline, or {@link Uniform#STATIC} when nothing animates
     */
    public static @NotNull Uniform deriveUniform(@NotNull List<Source> sources) {
        List<int[]> durationsPerSource = playableDurations(sources);
        if (durationsPerSource.isEmpty()) return Uniform.STATIC;

        int ticksPerFrame = anyInterpolate(sources) ? 1 : gcdCadence(durationsPerSource);
        int loopTicks = cappedLoopTicks(durationsPerSource);
        if (loopTicks <= 0) return Uniform.STATIC;

        int frameCount = Math.max(1, (int) Math.ceil(loopTicks / (double) ticksPerFrame));
        return new Uniform(frameCount, ticksPerFrame);
    }

    /**
     * Derives a change-point {@link Schedule} from the animated sources: within one capped loop
     * ({@link #MAX_LOOP_TICKS}), unions the tick offsets at which ANY source changes frame (each
     * source's cumulative entry offsets, repeated to fill the loop; an interpolating source changes
     * every tick), producing one output frame per distinct state. Frame {@code f} plays for
     * {@code (sampleTicks[f+1] - sampleTicks[f]) * 50} ms, the last frame wrapping to the loop end. For
     * a single non-interpolating texture this reproduces its {@code .mcmeta} timeline frame-for-frame
     * (fire's authored, reordered {@code frames} list round-trips exactly). Returns a single frame at
     * tick 0 when nothing animates.
     *
     * @param sources the subject's resolved animated textures
     * @return the derived change-point schedule
     */
    public static @NotNull Schedule deriveSchedule(@NotNull List<Source> sources) {
        List<int[]> durationsPerSource = playableDurations(sources);
        if (durationsPerSource.isEmpty())
            return new Schedule(new long[]{0L}, new int[]{Finalize.MILLIS_PER_TICK});

        int loopTicks = cappedLoopTicks(durationsPerSource);
        if (loopTicks <= 0)
            return new Schedule(new long[]{0L}, new int[]{Finalize.MILLIS_PER_TICK});

        TreeSet<Long> changePoints = new TreeSet<>();
        changePoints.add(0L);
        for (int s = 0; s < durationsPerSource.size(); s++) {
            int[] durations = durationsPerSource.get(s);
            if (interpolatesAt(sources, s)) {
                for (long t = 0; t < loopTicks; t++) changePoints.add(t);
                continue;
            }
            long t = 0;
            int i = 0;
            while (t < loopTicks) {
                changePoints.add(t);
                t += durations[i % durations.length];
                i++;
            }
        }

        List<Long> ticks = new ArrayList<>(changePoints.headSet((long) loopTicks));
        long[] sampleTicks = new long[ticks.size()];
        int[] frameDelaysMs = new int[ticks.size()];
        for (int f = 0; f < ticks.size(); f++) {
            sampleTicks[f] = ticks.get(f);
            long next = f + 1 < ticks.size() ? ticks.get(f + 1) : loopTicks;
            frameDelaysMs[f] = (int) (next - sampleTicks[f]) * Finalize.MILLIS_PER_TICK;
        }
        return new Schedule(sampleTicks, frameDelaysMs);
    }

    /** The non-empty per-entry duration arrays of every playable source. */
    private static @NotNull List<int[]> playableDurations(@NotNull List<Source> sources) {
        List<int[]> out = new ArrayList<>();
        for (Source source : sources) {
            int[] durations = AnimationKit.entryDurations(source.frameCount(), source.animation());
            if (durations.length == 0) continue;
            long total = 0;
            for (int d : durations) total += d;
            if (total <= 0) continue;
            out.add(durations);
        }
        return out;
    }

    /** Whether the source at the given index into {@code sources} declares interpolation. */
    private static boolean interpolatesAt(@NotNull List<Source> sources, int playableIndex) {
        // playableDurations preserves order and only drops unplayable sources; re-walk to align indices.
        int seen = 0;
        for (Source source : sources) {
            int[] durations = AnimationKit.entryDurations(source.frameCount(), source.animation());
            if (durations.length == 0) continue;
            long total = 0;
            for (int d : durations) total += d;
            if (total <= 0) continue;
            if (seen == playableIndex) return source.animation().interpolate();
            seen++;
        }
        return false;
    }

    /** Whether any playable source interpolates. */
    private static boolean anyInterpolate(@NotNull List<Source> sources) {
        for (Source source : sources) {
            int[] durations = AnimationKit.entryDurations(source.frameCount(), source.animation());
            if (durations.length == 0) continue;
            long total = 0;
            for (int d : durations) total += d;
            if (total > 0 && source.animation().interpolate()) return true;
        }
        return false;
    }

    /** GCD of every entry duration across all sources, floored at 1. */
    private static int gcdCadence(@NotNull List<int[]> durationsPerSource) {
        int g = 0;
        for (int[] durations : durationsPerSource)
            for (int d : durations) g = gcd(g, d);
        return Math.max(1, g);
    }

    /** LCM of every source's total loop ticks, capped at {@link #MAX_LOOP_TICKS}. */
    private static int cappedLoopTicks(@NotNull List<int[]> durationsPerSource) {
        long loop = 0;
        for (int[] durations : durationsPerSource) {
            long total = 0;
            for (int d : durations) total += d;
            loop = loop == 0 ? total : lcm(loop, total);
            if (loop >= MAX_LOOP_TICKS) return MAX_LOOP_TICKS;
        }
        return (int) Math.min(loop, MAX_LOOP_TICKS);
    }

    /** Greatest common divisor by the iterative Euclidean algorithm ({@code gcd(0, n) == n}). */
    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return Math.abs(a);
    }

    /** Least common multiple, dividing before multiplying to limit overflow; {@code 0} when either is {@code 0}. */
    private static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a / gcdLong(a, b) * b);
    }

    /** Long greatest common divisor for the LCM. */
    private static long gcdLong(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return Math.abs(a);
    }
}
