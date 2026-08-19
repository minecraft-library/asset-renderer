package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.AnimationOptions;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * A render timeline - the frame schedule of one offline bake and the terminal that plays it out.
 * <p>
 * Time is one millisecond axis on which the game tick is a {@link #MILLIS_PER_TICK 50 ms} lattice.
 * Each frame carries a sample instant (the world moment it depicts - {@link #millisAt} as the exact
 * instant, {@link TickTimeline#tickAt} as the integer tick on tick-native timelines) and a playback
 * delay ({@link #delayMs}, with running sum {@link #playbackMsAt}). Implementations own their
 * schedule data and the constants that derive it; the terminal methods {@link #bake} and
 * {@link #wrap} own the final render call.
 */
public sealed interface Timeline permits Timeline.TickTimeline, Timeline.FpsLoop, Timeline.SubTickLoop {

    /**
     * Milliseconds in one game tick - vanilla derives this as {@code 1000.0f / ticksPerSecond} when
     * advancing game time; {@code 50} is the 20 TPS evaluation.
     */
    int MILLIS_PER_TICK = 50;

    /** Game ticks per second - the reciprocal view of {@link #MILLIS_PER_TICK}. */
    int TICKS_PER_SECOND = 1000 / MILLIS_PER_TICK;

    /**
     * Upper bound on a derived loop length in ticks - caps the LCM so a long-frametime texture
     * (prismarine's 300-tick frames, thousands-of-ticks loop) cannot explode the frame count.
     */
    int MAX_LOOP_TICKS = 200;

    /** Upper bound on a merged loop duration in milliseconds - {@link #MAX_LOOP_TICKS} on the ms axis. */
    long MAX_LOOP_MS = MAX_LOOP_TICKS * (long) MILLIS_PER_TICK;

    /**
     * Returns the number of frames in this schedule.
     *
     * @return the frame count, always at least 1 for renderable timelines
     */
    int frames();

    /**
     * Returns the exact sample instant of a frame in milliseconds - the world moment the frame
     * depicts. Tick-native timelines sit on the {@link #MILLIS_PER_TICK} lattice.
     *
     * @param frame the frame index
     * @return the sample instant in milliseconds
     */
    double millisAt(int frame);

    /**
     * Returns the playback delay of a frame - how long the frame displays, in the integer
     * milliseconds animated container formats carry.
     *
     * @param frame the frame index
     * @return the playback delay in milliseconds
     */
    int delayMs(int frame);

    /**
     * Returns the game time a frame samples, measured in ticks and allowed to fall between them -
     * the continuous age vanilla feeds its model animation, as opposed to the integer tick a frame
     * sits on.
     * <p>
     * This is the off-lattice companion to {@link TickTimeline#tickAt}. It reads the frame's position
     * on the millisecond axis in tick units, so every schedule has one, including the wall-rate
     * {@link FpsLoop} that sits on no tick lattice and so has no {@code tickAt} at all (at 30 fps its
     * frames age {@code 0}, {@code 0.667}, {@code 1.333}, ...). On a tick-native schedule the two
     * views agree exactly - a frame sampling tick {@code t} has age {@code t} - because those
     * schedules sit on the {@link #MILLIS_PER_TICK} lattice by construction.
     *
     * <p>An offline bake that samples whole ticks therefore learns nothing from this that
     * {@code tickAt} does not already tell it. It earns its keep only for a schedule that samples
     * between ticks, where the fraction is the whole point.
     *
     * @param frame the frame index
     * @return the elapsed game time in ticks
     */
    default float ageInTicks(int frame) {
        return (float) (millisAt(frame) / MILLIS_PER_TICK);
    }

    /**
     * Returns the accumulated playback offset at which a frame begins displaying - the running sum of
     * the preceding frames' delays, and the instant merge-style consumers sample children at.
     *
     * @param frame the frame index
     * @return the playback offset in milliseconds
     */
    default long playbackMsAt(int frame) {
        long sum = 0;
        for (int f = 0; f < frame; f++) sum += delayMs(f);
        return sum;
    }

    /**
     * Plays this schedule out over caller-drawn frames: invokes {@code frameAt} serially for each
     * frame index in order, then wraps the frames at this schedule's per-frame delays. The frame index
     * is first-class - producers whose drawing is seeded per frame consume it directly.
     *
     * @param frameAt draws the frame at an index
     * @return the finished image
     * @throws RenderException if the schedule is empty
     */
    default @NotNull ImageData wrap(@NotNull IntFunction<PixelBuffer> frameAt) {
        ConcurrentList<PixelBuffer> buffers = Concurrent.newList();
        for (int f = 0; f < frames(); f++) buffers.add(frameAt.apply(f));
        return wrapAt(buffers, this);
    }

    /**
     * Converts an output frame rate to the per-frame playback delay animated container formats carry,
     * floored at one millisecond.
     *
     * @param framesPerSecond the output frame rate
     * @return the per-frame playback delay in milliseconds
     */
    static int delayForFps(int framesPerSecond) {
        return Math.max(1, Math.round(1000f / framesPerSecond));
    }

    /**
     * Returns the least common multiple of two durations, computed as {@code |a / gcd(a, b) * b|} to
     * divide before multiplying and limit overflow. Returns {@code 0} when either input is {@code 0} -
     * a bare fold over a possibly-absent schedule propagates the zero span rather than adopting the
     * other operand, so a downstream frame count clamps to a single frame.
     *
     * @param a the first duration
     * @param b the second duration
     * @return the least common multiple
     */
    static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a / gcd(a, b) * b);
    }

    /**
     * Builds the texture tick-strip schedule of an animation: one output frame per cadence step, each
     * displaying for its own span of game time ({@code ticksPerFrame * }{@link #MILLIS_PER_TICK}). A
     * single-frame animation normalizes to a {@link Static} at its start tick.
     *
     * @param animation the subject's animation timeline (start tick, frame count, ticks per frame)
     * @return the tick-strip schedule
     */
    static @NotNull TickTimeline tickStrip(@NotNull AnimationOptions animation) {
        return animation.getFrameCount() <= 1
            ? new Static(animation.getStartTick())
            : new TickLoop(animation.getStartTick(), animation.getFrameCount(),
                animation.getTicksPerFrame(), animation.getTicksPerFrame() * MILLIS_PER_TICK);
    }

    /**
     * Builds the schedule an animation asks for - the {@link #tickStrip texture strip} that plays a
     * flipbook at its authored rate, or the {@link #gameTime game-time} simulation that compresses the
     * world time it covers into real-time playback. This is the seam a caller selects through
     * {@link AnimationOptions#getSchedule()}; both branches sample the same ticks and differ only in
     * how long each frame is held.
     *
     * @param animation the subject's animation timeline (start tick, frame count, ticks per frame,
     *        playback schedule)
     * @return the requested schedule
     */
    static @NotNull TickTimeline schedule(@NotNull AnimationOptions animation) {
        return switch (animation.getSchedule()) {
            case TEXTURE_STRIP -> tickStrip(animation);
            case GAME_TIME -> gameTime(animation.getStartTick(), animation.getFrameCount(), animation.getTicksPerFrame());
        };
    }

    /**
     * Builds a game-time simulation schedule: {@code ticksPerFrame} advances a continuous simulation
     * between baked frames while every frame plays back in real time (one tick's worth of wall clock,
     * {@link #MILLIS_PER_TICK} ms) - the simulation-speed knob deliberately never stretches playback. A
     * single-frame schedule normalizes to a {@link Static} at the start tick.
     *
     * @param startTick the absolute sample tick of frame 0
     * @param frameCount the number of frames to bake
     * @param ticksPerFrame the simulation ticks advanced between successive frames
     * @return the game-time schedule
     */
    static @NotNull TickTimeline gameTime(int startTick, int frameCount, int ticksPerFrame) {
        return frameCount <= 1
            ? new Static(startTick)
            : new TickLoop(startTick, frameCount, ticksPerFrame, MILLIS_PER_TICK);
    }

    /**
     * Builds a game-time schedule that samples between ticks, subdividing each step of
     * {@link #gameTime(int, int, int) the whole-tick schedule} into {@code subTickSteps} frames. The
     * loop covers the same span of game time and plays at the same speed - each subdivided frame holds
     * for its share of the original delay - so the only thing that changes is how finely the motion is
     * sampled. What makes it useful is that a frame per tick caps output at
     * {@link #TICKS_PER_SECOND 20} frames a second, which is visibly coarse for a subject that moves
     * continuously.
     * <p>
     * {@code subTickSteps} of {@code 1} or less returns the whole-tick schedule itself, so leaving it
     * alone costs a caller nothing. A single-frame schedule stays a {@link Static}: a still has no
     * motion to sample more finely.
     *
     * <p>The original per-frame delay is shared out across the sub-steps, with any leftover
     * milliseconds going to the earliest frames of each step rather than being rounded away - at 3
     * steps a tick runs 17 / 17 / 16 rather than 17 / 17 / 17. Every step therefore spans exactly the
     * time it did undivided, so the loop keeps real time however the count divides.
     *
     * @param startTick the absolute sample tick of frame 0
     * @param frameCount the number of whole-tick frames to subdivide
     * @param ticksPerFrame the simulation ticks advanced between successive whole-tick frames
     * @param subTickSteps the frames sampled per whole-tick frame; {@code 1} leaves the schedule alone
     * @return the subdivided schedule
     */
    static @NotNull Timeline gameTime(int startTick, int frameCount, int ticksPerFrame, int subTickSteps) {
        if (subTickSteps <= 1 || frameCount <= 1) return gameTime(startTick, frameCount, ticksPerFrame);
        return new SubTickLoop(startTick, frameCount * subTickSteps,
            ticksPerFrame / (double) subTickSteps, MILLIS_PER_TICK, subTickSteps);
    }

    /**
     * Derives a tick-strip schedule from resolved animated textures: the cadence is the GCD of every
     * entry duration (forced to 1 when any source interpolates, so every distinct tick is sampled);
     * the loop length is the LCM of every source's total ticks, capped at {@link #MAX_LOOP_TICKS}; the
     * frame count is {@code ceil(loopTicks / ticksPerFrame)}. Returns a {@link Static} at the start
     * tick when no source is playable.
     *
     * @param sources the subject's resolved animated textures
     * @param startTick the absolute sample tick of frame 0
     * @return the derived schedule
     */
    static @NotNull TickTimeline deriveTickStrip(@NotNull List<Source> sources, int startTick) {
        List<int[]> durationsPerSource = new ArrayList<>();
        boolean anyInterpolate = false;
        for (Source source : sources) {
            int[] durations = AnimationKit.entryDurations(source.frameCount(), source.animation());
            if (durations.length == 0) continue;
            long total = 0;
            for (int d : durations) total += d;
            if (total <= 0) continue;
            durationsPerSource.add(durations);
            if (source.animation().interpolate()) anyInterpolate = true;
        }
        if (durationsPerSource.isEmpty()) return new Static(startTick);

        int ticksPerFrame = anyInterpolate ? 1 : gcdCadence(durationsPerSource);
        int loopTicks = cappedLoopTicks(durationsPerSource);
        if (loopTicks <= 0) return new Static(startTick);

        int frameCount = Math.max(1, (int) Math.ceil(loopTicks / (double) ticksPerFrame));
        return frameCount <= 1
            ? new Static(startTick)
            : new TickLoop(startTick, frameCount, ticksPerFrame, ticksPerFrame * MILLIS_PER_TICK);
    }

    /**
     * Wraps a single finished buffer as a static image - the one-frame terminal for renderers that
     * draw without a schedule.
     *
     * @param buffer the finished frame
     * @return the static image
     */
    static @NotNull ImageData still(@NotNull PixelBuffer buffer) {
        return StaticImageData.of(buffer.toBufferedImage());
    }

    /**
     * Returns a minimal 1x1 transparent static image - the canonical "nothing to render" result for
     * renderers short-circuiting on missing or empty input.
     *
     * @return a 1x1 transparent static image
     */
    static @NotNull ImageData empty() {
        return still(PixelBuffer.create(1, 1));
    }

    /** Greatest common divisor by the iterative Euclidean algorithm, canonicalized to a non-negative result. */
    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return Math.abs(a);
    }

    /** GCD of every entry duration across all sources, floored at 1. */
    private static int gcdCadence(@NotNull List<int[]> durationsPerSource) {
        long g = 0;
        for (int[] durations : durationsPerSource)
            for (int d : durations) g = gcd(g, d);
        return (int) Math.max(1, g);
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

    /**
     * Bakes this schedule through a raster pass and returns the finished image: rasters one frame per
     * sample instant in parallel (frame order preserved), applies the pass's finish step, then wraps
     * the result at the finish's playback schedule. This default is the one bake loop every rasterizing
     * renderer shares; an implementation that overrides it forks that shared loop and takes on its full
     * contract - frame order and the finish seam - alone.
     *
     * <p>Each frame is handed both views of its instant: the integer tick that indexes discrete
     * per-tick state (a texture flipbook's frame), and the {@link #ageInTicks continuous age} for
     * anything that reads time as a quantity. The tick is the age floored, read off the millisecond
     * axis rather than {@link TickTimeline#tickAt} so that a schedule sampling between ticks still has
     * one; on a tick-native schedule the two agree exactly, because that axis is the tick lattice by
     * construction.
     *
     * @param pass the raster configuration and callbacks to play this schedule through
     * @return the finished image
     * @throws RenderException if the schedule is empty
     */
    default @NotNull ImageData bake(@NotNull RasterPass pass) {
        ConcurrentList<PixelBuffer> buffers = Concurrent.newList();
        IntStream.range(0, frames())
            .parallel()
            .mapToObj(f -> {
                double age = millisAt(f) / MILLIS_PER_TICK;
                return pass.renderFrame((int) Math.floor(age), (float) age);
            })
            .forEachOrdered(buffers::add);

        RasterPass.Finish.Result result = pass.finish().finish(buffers, this);
        return wrapAt(result.frames(), result.playback());
    }

    /**
     * Wraps finished frames at a playback schedule's per-frame delays: one frame becomes a static
     * image, several become an animated image whose frame {@code f} displays for
     * {@code playback.delayMs(f)}.
     */
    private static @NotNull ImageData wrapAt(@NotNull ConcurrentList<PixelBuffer> frames, @NotNull Timeline playback) {
        if (frames.isEmpty())
            throw new RenderException("Frame list must contain at least one frame");

        if (frames.size() == 1)
            return StaticImageData.of(frames.getFirst().toBufferedImage());

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (int f = 0; f < frames.size(); f++)
            builder.withFrame(ImageFrame.of(frames.get(f), playback.delayMs(f)));

        return builder.build();
    }

    /**
     * A timeline whose sample instants are integer game ticks - the schedules that can drive a
     * per-tick frame rasterizer. Sample instants sit on the {@link #MILLIS_PER_TICK} lattice.
     */
    sealed interface TickTimeline extends Timeline permits Static, TickLoop, ChangePoints {

        /**
         * Returns the integer sample tick of a frame - the game tick the frame depicts.
         *
         * @param frame the frame index
         * @return the absolute sample tick
         */
        int tickAt(int frame);

        /** {@inheritDoc} */
        @Override
        default double millisAt(int frame) {
            return tickAt(frame) * (double) MILLIS_PER_TICK;
        }

    }

    /**
     * A single frame sampled at one tick - the degenerate schedule of every static render.
     *
     * @param startTick the absolute tick the frame samples
     */
    record Static(int startTick) implements TickTimeline {

        /** The tick-zero static timeline - the schedule of a render with no time input. */
        public static final @NotNull Static ZERO = new Static(0);

        /** {@inheritDoc} */
        @Override
        public int frames() {
            return 1;
        }

        /** {@inheritDoc} */
        @Override
        public int tickAt(int frame) {
            return startTick;
        }

        /** {@inheritDoc} */
        @Override
        public int delayMs(int frame) {
            return 0;
        }
    }

    /**
     * A uniform tick-cadence loop: frame {@code f} samples {@code startTick + f * ticksPerFrame} and
     * every frame displays for the same delay.
     *
     * @param startTick the absolute sample tick of frame 0
     * @param frameCount the number of frames in the loop
     * @param ticksPerFrame the ticks between successive sample instants
     * @param delayMs the uniform per-frame playback delay in milliseconds
     */
    record TickLoop(int startTick, int frameCount, int ticksPerFrame, int delayMs) implements TickTimeline {

        /** {@inheritDoc} */
        @Override
        public int frames() {
            return frameCount;
        }

        /** {@inheritDoc} */
        @Override
        public int tickAt(int frame) {
            return startTick + frame * ticksPerFrame;
        }

        /** {@inheritDoc} */
        @Override
        public int delayMs(int frame) {
            return delayMs;
        }
    }

    /**
     * A uniform wall-rate loop: frame {@code f} samples the exact instant
     * {@code f * (1000.0 / framesPerSecond)} and displays for the rounded integer delay. The two views
     * intentionally disagree at rates that do not divide 1000 evenly (at 30 fps the sample instants run
     * 0 / 33.33 / 66.67 while the playback lattice runs 0 / 33 / 66): sampling consumes the exact
     * double instant, playback the container-format int. This timeline has no tick view - a wall-rate
     * schedule samples no game tick.
     *
     * @param framesPerSecond the loop's frame rate
     * @param frameCount the number of frames in the loop
     */
    record FpsLoop(int framesPerSecond, int frameCount) implements Timeline {

        /** {@inheritDoc} */
        @Override
        public int frames() {
            return frameCount;
        }

        /** {@inheritDoc} */
        @Override
        public double millisAt(int frame) {
            return frame * (1000.0 / framesPerSecond);
        }

        /** {@inheritDoc} */
        @Override
        public int delayMs(int frame) {
            return delayForFps(framesPerSecond);
        }
    }

    /**
     * A game-time loop that samples between ticks: frame {@code f} sits at
     * {@code startTick + f * ticksPerFrame} game ticks, where the step is allowed to be fractional, and
     * every frame displays for the same delay. The schedule for a subject whose appearance is a
     * continuous function of time and which one frame per tick is too coarse to show moving smoothly.
     * <p>
     * Deliberately not a {@link TickTimeline}. Its instants do not sit on the
     * {@link #MILLIS_PER_TICK} lattice, so it has no honest integer tick to report, and claiming one
     * would break the very identity that makes the lattice checkable. A frame's draw is still handed a
     * whole tick - its age floored - for any discrete per-tick lookup it performs.
     *
     * @param startTick the game tick frame 0 samples
     * @param frameCount the number of frames in the loop
     * @param ticksPerFrame the game ticks between successive frames, which may be fractional
     * @param stepMs the playback span of one undivided step, shared out across its sub-steps
     * @param subTickSteps the frames each undivided step is sampled as
     */
    record SubTickLoop(double startTick, int frameCount, double ticksPerFrame, int stepMs, int subTickSteps)
        implements Timeline {

        /** {@inheritDoc} */
        @Override
        public int frames() {
            return frameCount;
        }

        /** {@inheritDoc} */
        @Override
        public double millisAt(int frame) {
            return (startTick + frame * ticksPerFrame) * MILLIS_PER_TICK;
        }

        /**
         * {@inheritDoc}
         * <p>
         * Whole milliseconds are all an animated container carries, so a step count that does not
         * divide {@link #stepMs} evenly cannot give every frame the same delay. The leftover
         * milliseconds go to the earliest frames of each step rather than being rounded away, so every
         * step spans exactly the time it did undivided and the loop keeps real time - at three steps a
         * tick runs 17 / 17 / 16 rather than three frames of 17 that would stretch it.
         */
        @Override
        public int delayMs(int frame) {
            int even = stepMs / subTickSteps;
            int leftover = stepMs % subTickSteps;
            return Math.max(1, even + (Math.floorMod(frame, subTickSteps) < leftover ? 1 : 0));
        }
    }

    /**
     * An explicit per-frame schedule: each keyframe names its own sample tick and playback delay, for
     * animations whose cadence is irregular.
     *
     * @param keyframes the ordered keyframes, one per output frame
     */
    record ChangePoints(@NotNull List<Keyframe> keyframes) implements TickTimeline {

        /**
         * One frame of an explicit schedule.
         *
         * @param tick the absolute sample tick of the frame
         * @param delayMs the frame's playback delay in milliseconds
         */
        public record Keyframe(int tick, int delayMs) {}

        /**
         * Constructs a new {@code ChangePoints} with a defensive copy of the keyframes.
         *
         * @throws IllegalArgumentException if the list is empty, the ticks are not strictly ascending,
         *         or any delay is not positive
         */
        public ChangePoints {
            keyframes = List.copyOf(keyframes);
            if (keyframes.isEmpty())
                throw new IllegalArgumentException("Change-point schedule must contain at least one keyframe");
            for (int f = 0; f < keyframes.size(); f++) {
                Keyframe keyframe = keyframes.get(f);
                if (keyframe.delayMs() <= 0)
                    throw new IllegalArgumentException("Keyframe delay must be positive");
                if (f > 0 && keyframe.tick() <= keyframes.get(f - 1).tick())
                    throw new IllegalArgumentException("Keyframe ticks must be strictly ascending");
            }
        }

        /** {@inheritDoc} */
        @Override
        public int frames() {
            return keyframes.size();
        }

        /** {@inheritDoc} */
        @Override
        public int tickAt(int frame) {
            return keyframes.get(frame).tick();
        }

        /** {@inheritDoc} */
        @Override
        public int delayMs(int frame) {
            return keyframes.get(frame).delayMs();
        }
    }

    /**
     * One resolved animated texture feeding schedule derivation.
     *
     * @param frameCount the texture strip's implicit frame count (strip height / frame height), used
     *        only when the animation declares no explicit {@code frames} list
     * @param animation the parsed {@code .mcmeta} metadata
     */
    record Source(int frameCount, @NotNull AnimationData animation) {

        /**
         * Builds a source from a texture strip, deriving the implicit frame count from the strip's
         * height and the animation's frame height.
         *
         * @param strip the vertical texture strip
         * @param animation the parsed {@code .mcmeta} metadata
         * @return the derivation source
         */
        public static @NotNull Source of(@NotNull PixelBuffer strip, @NotNull AnimationData animation) {
            return new Source(strip.height() / AnimationKit.frameHeight(strip, animation), animation);
        }
    }
}
