package lib.minecraft.renderer.option.spec;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The animation timing shared by the animated renderers (item, block, entity, fluid, portal): seed
 * tick, frame count, ticks-per-frame and playback schedule, plus the portal loop-crossfade fraction.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class AnimationOptions {

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

    /**
     * Animation seed tick - frame 0 samples at this tick.
     */
    @lombok.Builder.Default
    private final int startTick = 0;

    /**
     * Number of output frames; 1 = static, &gt;1 = animated.
     */
    @lombok.Builder.Default
    private final int frameCount = 1;

    /**
     * Vanilla ticks advanced between successive output frames.
     */
    @lombok.Builder.Default
    private final int ticksPerFrame = 1;

    /**
     * How the baked frames play back, honoured by every subject that builds its schedule through
     * {@link lib.minecraft.renderer.engine.compose.Timeline#schedule}. Defaults to
     * {@link Schedule#TEXTURE_STRIP}, the authored-rate flipbook every subject rendered before the
     * alternative existed.
     */
    @lombok.Builder.Default
    private final @NotNull Schedule schedule = Schedule.TEXTURE_STRIP;

    /**
     * Fraction of frameCount used as a shifted-continuation crossfade for a seamless loop; consumed
     * only by the portal parallax bake, inert for the simple fluid strip loop.
     */
    @lombok.Builder.Default
    private final float loopFadeBridgePct = 0.2f;

    /**
     * Opt-in {@code AUTO} timeline derivation: when {@code true}, a
     * subject that cannot know a sensible {@link #frameCount} / {@link #ticksPerFrame} for its textures
     * probes its resolved {@code .mcmeta} sidecars once at {@link #startTick} and derives the timeline
     * (LCM loop capped at 200 ticks, GCD cadence) via
     * {@link lib.minecraft.renderer.engine.compose.Timeline#deriveTickStrip}, then renders that
     * ordinary explicit timeline. Default {@code false} leaves the caller's explicit values untouched;
     * a subject with no animated texture degrades to a single static frame, so requesting it costs
     * nothing on a static subject. The parity floor is preserved mechanically - the default is static.
     */
    @lombok.Builder.Default
    private final boolean deriveTimeline = false;

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull AnimationOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default animation timing
     */
    public static @NotNull AnimationOptions defaults() {
        return builder().build();
    }
}
