package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.frame.EntityFrameRenderer;
import lib.minecraft.refharness.mixin.FreezeAnimationStateMixin;
import lib.minecraft.refharness.sweep.EntityAnimationSweep;
import lib.minecraft.refharness.sweep.SubjectSchedule;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * Holds the frame a harness render is posed at, so an animated reference is a function of the
 * subject's own schedule rather than of how long the client has been running.
 *
 * <p>Vanilla drives every idle animation off {@code EntityRenderState.ageInTicks}, which a live
 * render fills from the entity's own age and a partial tick. {@link FreezeAnimationStateMixin}
 * overwrites that field on every harness render and reads {@link #ageInTicks()} for what to put
 * there, so one place decides it: zero on a frozen run, and the tick of the frame
 * {@link EntityAnimationSweep} armed on an animated one.
 *
 * <p>The same holder pattern as {@link GlintClock} - the sweep arms it before each frame and the
 * mixin reads it during that frame - and {@code volatile} for the same reason, the value being
 * written where the sweep steps and read inside a separately compiled injection.
 *
 * <p><b>Elapsed age is not the only thing a posed frame moves.</b> On the frozen and idle gaits the
 * subject walks at no speed and swings at nothing; on the walking gait it walks at the speed and the
 * walk-clock rate its schedule armed. Beside those, the armed frame's clock values are read by the
 * mixins that write the fields vanilla's own tick fills, and {@link IdleFigures} holds the one-hot
 * selections a never-ticked subject is in. Every value comes from the schedule the asset side resolved
 * and wrote, so the two sides read one schedule the same way.
 *
 * <p><b>Only the subject takes the schedule.</b> Arming names the entity the frame draws, and the walk
 * and the clocks are written into that entity's render state alone, which {@link #isSubject} answers.
 * Every other entity the client extracts on a posed run - a world mob drawn in the level behind the
 * capture, before the first frame is armed or while another subject's is - keeps what vanilla's own
 * extract gave it, so a schedule reaches no mob it was not resolved for. The elapsed age is the one
 * value every render state takes alike. The capture holds the other side of that line:
 * {@link EntityFrameRenderer} refuses to measure or draw an entity on a posed run that no frame is
 * armed for, so a subject armed under another instance, or under none, never passes for a stray.
 */
@UtilityClass
public final class AnimationClock {

    /** The tick the frame being rendered is posed at, armed before each render on an animated run. */
    public static volatile int tick;

    /** The schedule the frame being rendered was armed from, empty until a posed frame is armed. */
    private static volatile Optional<SubjectSchedule> armed = Optional.empty();

    /** The entity the armed frame draws, empty until a posed frame is armed. */
    private static volatile Optional<Entity> subject = Optional.empty();

    /** The frame of {@link #armed} being rendered. */
    private static volatile int frame;

    /**
     * Arms one frame of one subject's schedule - its tick, its walk and its clock values - for the
     * render about to happen, naming the entity that render draws.
     *
     * @param subject the entity the frame draws, the one render state the walk and the clocks reach
     * @param schedule the subject's schedule
     * @param frame the frame index within the strip
     */
    public static void arm(Entity subject, SubjectSchedule schedule, int frame) {
        armed = Optional.of(schedule);
        AnimationClock.subject = Optional.of(subject);
        AnimationClock.frame = frame;
        tick = schedule.tickAt(frame);
    }

    /**
     * Returns whether an entity is the one the armed posed frame draws.
     *
     * <p>Asked by every mixin that writes the walk or a clock, so the schedule reaches the subject's
     * render state and no other, and by the capture before it extracts a posed render state, so the
     * entity it measures or draws is one a frame is armed for. The answer is by identity: the sweep
     * arms the very instance it draws or measures, so a mob of the same type drawn elsewhere is never
     * mistaken for it.
     *
     * @param entity the entity a render state is being extracted from
     * @return whether a posed frame is armed for this entity, false on every frozen frame
     */
    public static boolean isSubject(Entity entity) {
        return PoseState.posed() && subject.filter(armedSubject -> armedSubject == entity).isPresent();
    }

    /**
     * Returns the elapsed age a render state is stamped with.
     *
     * @return the armed tick when this run poses, and zero when it freezes
     */
    public static float ageInTicks() {
        return PoseState.posed() ? tick : 0.0f;
    }

    /**
     * Returns how hard the subject is walking, read for the entity {@link #isSubject} answers true for.
     *
     * @return the armed walk speed on a walking run, and zero on every other
     * @throws IllegalStateException if a walking render reaches the clock with no schedule armed
     */
    public static float walkAnimationSpeed() {
        return PoseState.walking() ? walking().walkSpeed() : 0.0f;
    }

    /**
     * Returns how far through its stride the subject is, read for the entity {@link #isSubject} answers
     * true for.
     *
     * <p>The tick times the armed walk-clock rate, which is the walk speed on an adult and three
     * times it on a baby, because the roster carries the rate as vanilla scales a baby's walk clock. A
     * frozen run and an idle one both answer zero, which is what a subject nothing has moved holds it
     * at.
     *
     * @return the armed tick's phase on a walking run, and zero on every other
     * @throws IllegalStateException if a walking render reaches the clock with no schedule armed
     */
    public static float walkAnimationPos() {
        return PoseState.walking() ? tick * walking().walkRate() : 0.0f;
    }

    /**
     * Returns the armed frame's value of one clock - a field vanilla's own tick fills and a
     * never-ticked subject leaves at rest, whose value at every frame the subject's schedule carries.
     *
     * <p>Read for the entity {@link #isSubject} answers true for, so a schedule that carries no clock
     * for the field is refused only for a subject that schedule was resolved for.
     *
     * @param field the render-state field the clock fills
     * @return the clock's value at the armed frame
     * @throws IllegalStateException if the render is a frozen one, if no schedule is armed, or if the
     *     armed schedule carries no clock for the field
     */
    public static float clock(String field) {
        if (!PoseState.posed())
            throw new IllegalStateException(String.format("Clock '%s' was read on a frozen frame, which no clock reaches", field));
        SubjectSchedule schedule = armed.orElseThrow(() ->
            new IllegalStateException(String.format("Clock '%s' was read with no schedule armed", field)));
        return schedule.clockAt(field, frame);
    }

    /**
     * Returns whether the armed posed frame's schedule carries a clock for one field.
     *
     * <p>Asked only where a mixin fires for subjects whose rows do not all drive the field, so a
     * subject that drives none keeps vanilla's never-ticked value; everywhere else a missing clock is
     * the refusal {@link #clock} raises.
     *
     * @param field the render-state field the clock fills
     * @return whether a posed frame is armed whose schedule carries the clock, false on every frozen frame
     */
    public static boolean drives(String field) {
        return PoseState.posed() && armed.map(schedule -> schedule.clocks().containsKey(field)).orElse(false);
    }

    /** The schedule a walking render reads its walk from, refusing a walking render nothing armed. */
    private static SubjectSchedule walking() {
        return armed.orElseThrow(() ->
            new IllegalStateException("A walking render reached the animation clock with no schedule armed"));
    }
}
