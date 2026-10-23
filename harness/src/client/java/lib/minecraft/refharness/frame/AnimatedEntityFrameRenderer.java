package lib.minecraft.refharness.frame;

import lib.minecraft.refharness.AnimationClock;
import lib.minecraft.refharness.PoseState;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.sweep.SubjectSchedule;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders one entity at one frame of its schedule, and measures the frame that holds every frame of
 * it.
 *
 * <p>The draw itself is {@link EntityFrameRenderer}'s and is not repeated here: an animated
 * reference has to come off the same pipeline, the same iso pose, the same lighting entry and the
 * same chirality compensation as the still it is compared beside, or the two differ for a reason
 * that is not the pose. What this adds is the tick and the walk the subject's schedule arms - armed
 * on {@link AnimationClock} before the render state is extracted, which is where
 * {@code FreezeAnimationStateMixin} and the clock-writing mixins read them, and armed for the entity
 * being drawn or measured, so they reach its render state and no other entity's - and the union
 * across the frames a schedule samples.
 *
 * <p><b>It deliberately does not implement {@link FrameRenderer}.</b> That interface renders a
 * subject, and a frame is part of what is being drawn rather than a setting on the draw: a
 * {@code render} that took no frame would draw at whatever the clock last held, which is a frame
 * nobody asked for and no name says.
 *
 * <p><b>Bounds are unioned across every frame, each measured through its own posed mesh.</b> A
 * canvas fitted to one tick crops the subject at the others - a ghast's tentacles and a wither's
 * ribcage move far enough to leave a frame sized for their rest - and the union is monotone, so a
 * canvas sized from it holds all of them. That is what the asset-renderer does too, in
 * {@code EntityRenderer.computeScreenBoundsAcrossFrames}, so the two sides frame one subject alike.
 */
@Parity(claim = "harness-animation-sweep", mode = Mode.DEMOTE, subject = Subject.ENTITY)
public final class AnimatedEntityFrameRenderer implements AutoCloseable {

    /** The draw, shared whole with the still references so a posed frame differs only by its pose. */
    private final EntityFrameRenderer frames = new EntityFrameRenderer();

    /**
     * Renders one entity as it stands at one frame of its schedule.
     *
     * @param client the running client
     * @param entity the entity to draw, armed as the frame's subject
     * @param schedule the subject's schedule
     * @param frame the frame index within the strip
     * @param canvas the canvas to draw onto, carrying the fit measured across the whole schedule
     * @param out where to write the PNG
     * @return whether a PNG was written
     * @throws IOException if the PNG write fails
     */
    public boolean renderAtTick(Minecraft client, Entity entity, SubjectSchedule schedule, int frame, Canvas canvas, Path out)
        throws IOException {
        armAt(entity, frame, schedule);
        return frames.render(client, entity, canvas, out);
    }

    /**
     * Measures the frame that holds one entity at every frame of its schedule, each frame armed with
     * the schedule's own tick, walk and clocks.
     *
     * @param client the running client
     * @param entity the entity to measure, armed as each frame's subject
     * @param schedule the subject's schedule
     * @return the union of its bounds over every frame
     */
    public Bounds measureAcrossTicks(Minecraft client, Entity entity, SubjectSchedule schedule) {
        Bounds union = null;
        for (int frame = 0; frame < schedule.frames(); frame++) {
            armAt(entity, frame, schedule);
            Bounds at = frames.measureBounds(client, entity);
            union = union == null ? at : union.union(at);
        }
        if (union == null)
            throw new IllegalArgumentException("A schedule that samples no tick measures no bounds");
        return union;
    }

    /**
     * Arms the clock at one frame of a schedule for the entity about to be drawn or measured, refusing
     * a gait the mixins would ignore it on.
     *
     * <p>A tick armed under a gait that does not pose reaches nothing: both freezes ask
     * {@link PoseState} per render, so every render state would still be stamped zero and every frame
     * of a strip would be the same still. That is a whole reference set silently written wrong, which
     * is worth a refusal rather than a warning.
     *
     * @param entity the entity whose render state the frame's walk and clocks reach
     * @param frame the frame index within the strip
     * @param schedule the subject's schedule
     * @throws IllegalStateException if the armed gait does not pose
     */
    private static void armAt(Entity entity, int frame, SubjectSchedule schedule) {
        if (!PoseState.posed()) {
            throw new IllegalStateException(String.format(
                "An animated render needs a posed gait armed, and PoseState holds '%s' - every tick of the schedule would draw the same still",
                PoseState.current()));
        }
        AnimationClock.arm(entity, schedule, frame);
    }

    @Override
    public void close() {
        frames.close();
    }
}
