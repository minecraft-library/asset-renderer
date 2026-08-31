package lib.minecraft.renderer.option;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import lib.minecraft.renderer.asset.pose.IdleFigure;
import lib.minecraft.renderer.asset.pose.IdleState;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.exception.RendererException;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The animation timing shared by the animated renderers (item, block, entity, fluid, portal): seed
 * tick, frame count, ticks-per-frame and playback schedule.
 */
@Getter
@ClassBuilder
public class AnimationOptions {

    /**
     * How many frames {@link #excursion} samples one excursion at.
     *
     * <p>The divisor rather than the cadence, so the strip covers {@link IdleFigure#PERIOD_TICKS}
     * exactly however that period is set: a frame count that did not divide it would either stop
     * short of the excursion or run past its own start.
     */
    private static final int EXCURSION_FRAMES = 8;

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
     * Which member of each one-hot an idle render selects, where a caller means other than the
     * group's default.
     *
     * <p>A selection rather than an excursion, because the fields behind one encode a single choice
     * and moving them apart renders a crossfade between two states. A group no entry names takes
     * {@link IdleState.Group#selected(boolean)} at the gait being rendered, and the member of it that
     * drives no field - the axolotl's {@link IdleState#IN_AIR}, a dolphin's {@link IdleState#STILL} -
     * is how a caller asks for the whole group to rest.
     *
     * <p><b>An entry here overrides the gait as well as the default.</b> Two groups answer a
     * different member under a stride, so a caller that names one has said which member it wants at
     * every gait rather than only at rest.
     */
    private final @NotNull Map<IdleState.Group, IdleState> idleStates = Map.of();

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
     * What one field of a one-hot holds, given the member this caller selects for its group.
     *
     * <p>Refuses a selection from another group rather than answering it. Every field of the group
     * would answer zero, which is indistinguishable from selecting the member that drives none - so
     * a caller who keyed the map wrong would get a subject holding still and no way to tell that
     * from having asked for one.
     *
     * <p>The gait reaches the DEFAULT alone. A rabbit travels by hopping and a breeze by sliding, so
     * the member each rests at is not the member each moves at; a caller that named one has already
     * said which it wants.
     *
     * @param factor the field being answered
     * @param walking whether the render drives a stride
     * @return one where it belongs to the selected member, zero otherwise
     * @throws RendererException if the selection for a group belongs to a different one
     */
    public float idleValue(@NotNull IdleState factor, boolean walking) {
        IdleState.Group group = factor.group();
        IdleState selected = this.idleStates.getOrDefault(group, group.selected(walking));
        if (selected.group() != group)
            throw new RendererException(
                "idle state: '%s' is selected for '%s' and belongs to '%s'",
                selected, group, selected.group());
        return factor.when(selected);
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
     * These options sampled across one whole excursion, so what they render moves rather than holding
     * a single instant of movement.
     *
     * <p>The strip is {@link IdleFigure#PERIOD_TICKS} long by construction, divided into
     * {@link #EXCURSION_FRAMES} frames - so it shows one whole excursion, its last frame does not
     * repeat its first, and the render loops. At {@link Schedule#TEXTURE_STRIP} each frame holds for
     * the span of game time it covers, so the excursion plays at the speed it happens at.
     *
     * <p>Everything but the strip is carried through: a caller's own excursions, selections, seed tick
     * and playback schedule are left as they were, because what this supplies is the sampling they
     * did not name rather than a different animation.
     *
     * @param base the options to sample across one excursion
     * @return the same options over an excursion-long strip
     */
    public static @NotNull AnimationOptions excursion(@NotNull AnimationOptions base) {
        return base.mutate()
            .frameCount(EXCURSION_FRAMES)
            .ticksPerFrame(IdleFigure.PERIOD_TICKS / EXCURSION_FRAMES)
            .build();
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
