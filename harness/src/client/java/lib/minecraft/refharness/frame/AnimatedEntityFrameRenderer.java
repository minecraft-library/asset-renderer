package lib.minecraft.refharness.frame;

import lib.minecraft.refharness.AnimationClock;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders one entity at a named tick, and measures the frame that holds every tick of a schedule.
 *
 * <p>The draw itself is {@link EntityFrameRenderer}'s and is not repeated here: an animated
 * reference has to come off the same pipeline, the same iso pose, the same lighting entry and the
 * same chirality compensation as the still it is compared beside, or the two differ for a reason
 * that is not the pose. What this adds is the tick - armed on {@link AnimationClock} before the
 * render state is extracted, which is where {@code FreezeAnimationStateMixin} reads it - and the
 * union across the ticks a schedule samples.
 *
 * <p><b>It deliberately does not implement {@link FrameRenderer}.</b> That interface renders a
 * subject, and a tick is part of what is being drawn rather than a setting on the draw: a
 * {@code render} that took no tick would draw at whatever the clock last held, which is a frame
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
     * Renders one entity as it stands at one tick.
     *
     * @param client the running client
     * @param entity the entity to draw
     * @param tick the tick to pose it at
     * @param canvas the canvas to draw onto, carrying the fit measured across the whole schedule
     * @param out where to write the PNG
     * @return whether a PNG was written
     * @throws IOException if the PNG write fails
     */
    public boolean renderAtTick(Minecraft client, Entity entity, int tick, Canvas canvas, Path out)
        throws IOException {
        armAt(tick);
        return frames.render(client, entity, canvas, out);
    }

    /**
     * Measures the frame that holds one entity at every tick of a schedule.
     *
     * @param client the running client
     * @param entity the entity to measure
     * @param ticks the ticks the schedule samples
     * @return the union of its bounds over them
     */
    public Bounds measureAcrossTicks(Minecraft client, Entity entity, List<Integer> ticks) {
        Bounds union = null;
        for (int tick : ticks) {
            armAt(tick);
            Bounds at = frames.measureBounds(client, entity);
            union = union == null ? at : union.union(at);
        }
        if (union == null)
            throw new IllegalArgumentException("A schedule that samples no tick measures no bounds");
        return union;
    }

    /**
     * Arms the clock at one tick, refusing a run the mixins would ignore it on.
     *
     * <p>The flag is read once per JVM by both freezes, so a tick armed on a frozen run reaches
     * nothing: every render state would still be stamped zero and every frame of a strip would be
     * the same still. That is a whole reference set silently written wrong, which is worth a
     * refusal rather than a warning.
     */
    private static void armAt(int tick) {
        if (!HarnessConfig.POSED)
            throw new IllegalStateException(
                "An animated render needs -Drefharness.animated=true: the freezes read it once per "
                    + "JVM, so without it every tick of the schedule draws the same still");
        AnimationClock.tick = tick;
    }

    @Override
    public void close() {
        frames.close();
    }
}
