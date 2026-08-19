package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The animation timing shared by the animated renderers (item, block, entity, fluid, portal): seed
 * tick, frame count, ticks-per-frame and playback schedule, plus the portal loop-crossfade fraction.
 */
@Getter
@ClassBuilder
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
    private final int startTick = 0;

    /**
     * Number of output frames; 1 = static, &gt;1 = animated.
     */
    private final int frameCount = 1;

    /**
     * Vanilla ticks advanced between successive output frames.
     */
    private final int ticksPerFrame = 1;

    /**
     * Frames sampled per whole-tick step, for a subject whose appearance is a continuous function of
     * time. One frame per tick caps output at 20 frames a second, which is visibly coarse on
     * continuous motion; a higher value samples between ticks, covering the same span of game time at
     * the same speed but more finely. Defaults to {@code 1} - whole ticks, the only instants an
     * offline bake sampled before.
     * <p>
     * Honoured only by subjects that read time as a quantity rather than as a lookup key. A texture
     * flipbook has no state between its frames, so subdividing its ticks would bake duplicates.
     * <p>
     * Carried faithfully only by a container that stores delays in milliseconds. GIF stores
     * centiseconds, and the smallest delay players honour is two of them, so a 50 ms tick has room
     * for at most two sub-steps there and none at all at the default three - a GIF written from a
     * subdivided schedule declares a longer tick than intended. Write WebP for these, or drop back
     * to {@code 1} for a strip that has to be a GIF.
     */
    private final int subTickSteps = 1;

    /**
     * How the baked frames play back, honoured by every subject that builds its schedule through
     * {@link lib.minecraft.renderer.engine.compose.Timeline#schedule}. Defaults to
     * {@link Schedule#TEXTURE_STRIP}, the authored-rate flipbook every subject rendered before the
     * alternative existed.
     */
    private final @NotNull Schedule schedule = Schedule.TEXTURE_STRIP;

    /**
     * Fraction of frameCount used as a shifted-continuation crossfade for a seamless loop; consumed
     * only by the portal parallax bake, inert for the simple fluid strip loop.
     */
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
    private final boolean deriveTimeline = false;

    /**
     * Builds an instance with every field at its default value.
     *
     * @return the default animation timing
     */
    public static @NotNull AnimationOptions defaults() {
        return builder().build();
    }
}
