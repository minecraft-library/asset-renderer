package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import lib.minecraft.renderer.engine.compose.Timeline;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The animation timing shared by the animated renderers (item, block, entity, fluid, portal): seed
 * tick, frame count, ticks-per-frame and playback schedule.
 */
@Getter
@ClassBuilder
public class AnimationOptions {

    /**
     * Animation seed tick - frame 0 samples at this tick.
     */
    private final int startTick = 0;

    /**
     * Number of output frames. Empty means the subject decides - one frame for a still subject,
     * the shipped strip for a moving one; an explicit {@code 1} means one frame OF the animation,
     * sampled at the start tick.
     */
    private final @NotNull Optional<Integer> frameCount = Optional.empty();

    /**
     * Vanilla ticks advanced between successive output frames. Empty means the subject decides -
     * the subject's shipped cadence.
     */
    private final @NotNull Optional<Integer> ticksPerFrame = Optional.empty();

    /**
     * How the baked frames play back, honoured by every subject that builds its schedule through
     * {@link lib.minecraft.renderer.engine.compose.Timeline#schedule}. Defaults to
     * {@link Schedule#TEXTURE_STRIP}, the authored-rate flipbook every subject rendered before the
     * alternative existed.
     */
    private final @NotNull Schedule schedule = Schedule.TEXTURE_STRIP;

    /**
     * Opt-in {@code AUTO} timeline derivation: when {@code true}, a
     * subject that cannot know a sensible {@link #frameCount} / {@link #ticksPerFrame} for its textures
     * probes its resolved {@code .mcmeta} sidecars once at {@link #startTick} and derives the timeline
     * (LCM loop capped at 200 ticks, GCD cadence) via {@link Timeline#deriveTickStrip}, then renders that
     * ordinary explicit timeline. Default {@code false} leaves the caller's explicit values untouched;
     * a subject with no animated texture degrades to a single static frame, so requesting it costs
     * nothing on a static subject. The parity floor is preserved mechanically - the default is static.
     */
    private final boolean deriveTimeline = false;

    /**
     * The frame count, answering {@code 1} where none is named, so a renderer that treats
     * {@code 1} as static reads what an unnamed count means to it.
     *
     * @return the named frame count, or {@code 1}
     */
    public int getFrameCount() {
        return this.frameCount.orElse(1);
    }

    /**
     * The tick step, answering {@code 1} where none is named, so a renderer stepping one tick a
     * frame reads what an unnamed cadence means to it.
     *
     * @return the named tick step, or {@code 1}
     */
    public int getTicksPerFrame() {
        return this.ticksPerFrame.orElse(1);
    }

    /**
     * Whether the caller named a frame count, telling an explicit {@code 1} apart from an unnamed
     * count the subject decides.
     *
     * @return whether a frame count is named
     */
    public boolean isFrameCountNamed() {
        return this.frameCount.isPresent();
    }

    /**
     * Whether the caller named a tick step, telling an explicit cadence apart from an unnamed one
     * the subject decides.
     *
     * @return whether a tick step is named
     */
    public boolean isTicksPerFrameNamed() {
        return this.ticksPerFrame.isPresent();
    }

    /**
     * These options with every unnamed strip knob filled - the entity path's one defaulting site.
     * A named knob always wins.
     *
     * @param fallbackFrames what fills an unnamed frame count
     * @param fallbackTicks what fills an unnamed tick step
     * @return these options with both strip knobs concrete
     */
    public @NotNull AnimationOptions resolved(int fallbackFrames, int fallbackTicks) {
        if (isFrameCountNamed() && isTicksPerFrameNamed()) return this;
        return mutate()
            .frameCount(this.frameCount.orElse(fallbackFrames))
            .ticksPerFrame(this.ticksPerFrame.orElse(fallbackTicks))
            .build();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default animation timing
     */
    public static @NotNull AnimationOptions defaults() {
        return builder().build();
    }

    /**
     * What a frame's tick means to the schedule that bakes it, and so how fast the baked frames play
     * back.
     */
    public enum Schedule {

        /**
         * A texture flipbook at its authored rate: each frame holds for the whole span of game time it
         * covers, so playback runs at the speed the texture declares.
         */
        TEXTURE_STRIP,

        /**
         * A stretch of world time compressed into real-time playback: game time advances a whole
         * {@code ticksPerFrame} between frames while each frame displays for a single tick of wall
         * clock. A subject whose appearance the world clock drives - a clock item reading its face from
         * the sun angle - follows the frame's tick as world time and advances with it, so a full day
         * plays out in seconds instead of the twenty real-time minutes it depicts.
         */
        GAME_TIME

    }

}
