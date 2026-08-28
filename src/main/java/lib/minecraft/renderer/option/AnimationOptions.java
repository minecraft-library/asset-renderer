package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import lib.minecraft.renderer.asset.pose.IdleFigure;
import lib.minecraft.renderer.asset.pose.IdleState;
import lib.minecraft.renderer.engine.compose.Timeline;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
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
     * Number of output frames; 1 = static, &gt;1 = animated.
     */
    private final int frameCount = 1;

    /**
     * Vanilla ticks advanced between successive output frames.
     */
    private final int ticksPerFrame = 1;

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
     * What each scalar idle figure rests at and reaches, where a caller means something other than
     * the excursion {@link IdleFigure} declares.
     *
     * <p>Empty is the harness's own convention, so a render that overrides nothing is comparable
     * against the reference set. <b>A render that overrides one has left the reference behind</b>,
     * and a parity row taken from it compares two conventions rather than two renderers.
     */
    private final @NotNull Map<IdleFigure, IdleFigure.Excursion> idleFigures = Map.of();

    /**
     * Which member of a one-hot an idle render selects, where a caller means other than the default.
     *
     * <p>A selection rather than an excursion, because the factors behind it encode one choice and
     * moving them apart renders a crossfade between two states. Empty takes
     * {@link IdleState#DEFAULT}, and {@link IdleState#IN_AIR} is how a caller asks for every factor
     * of the group to rest.
     */
    private final @NotNull Optional<IdleState> idleState = Optional.empty();

    /**
     * What one idle figure holds at a tick, this caller's override applied where there is one.
     *
     * @param figure the figure being answered
     * @param tick the tick being posed
     * @return its value at that tick
     */
    public float idleValue(@NotNull IdleFigure figure, int tick) {
        IdleFigure.Excursion over = this.idleFigures.get(figure);
        return over == null ? figure.at(tick) : figure.at(tick, over.rest(), over.extent());
    }

    /**
     * What one factor of a one-hot holds, given the member this caller selects.
     *
     * @param factor the factor being answered
     * @return one where it belongs to the selected member, zero otherwise
     */
    public float idleValue(@NotNull IdleState factor) {
        return factor.when(this.idleState.orElse(IdleState.DEFAULT));
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
