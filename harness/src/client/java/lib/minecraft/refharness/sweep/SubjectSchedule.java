package lib.minecraft.refharness.sweep;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * One posed subject's schedule, as the asset side's roster names it - how many frames its strip draws,
 * the ticks between them, the walk it is armed at, and each clock's value at every frame.
 *
 * <p>Read, never computed: the asset side resolves a subject's strip, its walk and its clocks through
 * the code its own render runs, and writes them into the roster {@link ScheduleRoster} reads. So the
 * harness holds no frame count, no step, no walk and no wave of its own, and a subject is drawn at the
 * schedule the asset side draws it at.
 *
 * @param frames the frames the strip draws
 * @param ticksPerFrame the ticks between two frames
 * @param walkSpeed the walk speed a walking frame is armed at
 * @param walkRate the walk clock's advance a tick on a walking frame, a baby's scale already inside it
 * @param clocks each clock field's value at every frame, in frame order
 */
@Parity(claim = "harness-animation-sweep", mode = Mode.DEMOTE, subject = Subject.ENTITY)
public record SubjectSchedule(int frames, int ticksPerFrame, float walkSpeed, float walkRate,
                              Map<String, List<Float>> clocks) {

    /**
     * Constructs a new {@code SubjectSchedule}, copying the clocks deep and unmodifiable so equality
     * compares every value.
     */
    public SubjectSchedule {
        clocks = clocks.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /**
     * Returns the tick one frame is posed at.
     *
     * @param frame the frame index within the strip
     * @return the frame's tick
     */
    public int tickAt(int frame) {
        return EntityAnimationSweep.START_TICK + frame * this.ticksPerFrame;
    }

    /**
     * Returns the ticks the strip samples, in the order its frames are written.
     *
     * @return every frame's tick
     */
    public List<Integer> ticks() {
        return IntStream.range(0, this.frames).mapToObj(this::tickAt).toList();
    }

    /**
     * Returns one clock's value at one frame.
     *
     * @param field the render-state field the clock fills
     * @param frame the frame index within the strip
     * @return the clock's value at that frame
     * @throws IllegalStateException if this schedule carries no clock for the field
     */
    public float clockAt(String field, int frame) {
        List<Float> values = this.clocks.get(field);
        if (values == null)
            throw new IllegalStateException(String.format("The armed schedule carries no clock '%s'", field));
        return values.get(frame);
    }
}
