package lib.minecraft.refharness.sweep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lib.minecraft.refharness.EntityFrameRenderer;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.HarnessPose;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic pose sweep. Renders one entity at every combination of pitch and roll, both stepped in
 * {@link #STEP_DEGREES} increments through a full turn, holding yaw at the iso-locked value.
 *
 * <p>Each output is named so a file browser sorted by name shows pitch as the outer dimension and
 * roll as the inner one. Used to find the right pitch and roll when neither axis alone gives the
 * wanted screen orientation, which Euler-angle interaction makes hard to reason about.
 *
 * <p>Frames are square and each is scaled to the entity's own bounds rather than a shared family
 * canvas, because the point of a pose sweep is to see the whole silhouette at every angle; a
 * family-locked canvas would crop the frames where the model rotates outside it. Frames are
 * therefore not pixel-comparable with each other, and this sweep writes outside the reference tree.
 */
public final class PitchRollSweep implements Sweep<PitchRollSweep.Frame> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Angular step, in degrees, of both the pitch and the roll axis. */
    private static final int STEP_DEGREES = 15;

    private final EntityFrameRenderer frameRenderer = new EntityFrameRenderer();

    /** The subject, built once and reused across every frame - only the pose changes between them. */
    private Entity subject;

    /** Yaw held constant across the sweep, extracted from the iso pose. */
    private float yawRadians;

    /**
     * One posed frame.
     *
     * @param type the entity being posed
     * @param pitchDegrees the pitch this frame is rendered at
     * @param rollDegrees the roll this frame is rendered at
     */
    public record Frame(EntityType<?> type, int pitchDegrees, int rollDegrees) {}

    /**
     * Returns a directory outside the reference tree - these frames are a diagnostic, and the
     * manifest that gates the tree must not acquire several hundred of them.
     */
    @Override
    public String outputDir() {
        return "entities-pitch-roll-sweep";
    }

    @Override
    public List<Frame> enumerate(SweepContext ctx) {
        EntityType<?> type = firstTarget(ctx);
        if (type == null) {
            LOG.warn("PitchRollSweep: no target selected - pass a target to scope the sweep");
            return List.of();
        }
        List<Frame> frames = new ArrayList<>();
        for (int pitch = 0; pitch < 360; pitch += STEP_DEGREES)
            for (int roll = 0; roll < 360; roll += STEP_DEGREES)
                frames.add(new Frame(type, pitch, roll));
        LOG.info("PitchRollSweep built: {} frames of {}", frames.size(),
            BuiltInRegistries.ENTITY_TYPE.getKey(type));
        return frames;
    }

    @Override
    public void prepare(SweepContext ctx, List<Frame> frames) {
        Vector3f euler = new Vector3f();
        HarnessPose.ISO.getEulerAnglesXYZ(euler);
        yawRadians = euler.y;
        if (frames.isEmpty()) return;
        subject = frames.getFirst().type().create(ctx.level(), EntitySpawnReason.LOAD);
        if (subject != null) EntitySubjects.zeroRotations(subject);
    }

    @Override
    public RefKey key(Frame frame) {
        return RefKey.of(BuiltInRegistries.ENTITY_TYPE.getKey(frame.type()))
            .with(String.format("p%03d", frame.pitchDegrees()))
            .with(String.format("r%03d", frame.rollDegrees()));
    }

    @Override
    public Canvas canvas(SweepContext ctx, Frame frame) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, Frame frame, Canvas canvas, Path out) throws IOException {
        if (subject == null) return false;
        Quaternionf pose = new Quaternionf().rotationXYZ(
            (float) Math.toRadians(frame.pitchDegrees()),
            yawRadians,
            (float) Math.toRadians(frame.rollDegrees()));
        return frameRenderer.renderAtRotation(ctx.client(), subject, canvas, out, pose);
    }

    /** Returns the first selected entity type, which is the one subject this sweep poses. */
    private static EntityType<?> firstTarget(SweepContext ctx) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE)
            if (ctx.targets().accepts(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())) return type;
        return null;
    }
}
