package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.Gait;
import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.AppearanceRequest;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.AnimatedEntityFrameRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Animated entity sweep. Each subject is rendered once per tick of one shared schedule, with
 * vanilla's own {@code setupAnim} running, so the reference says where a model actually puts its
 * bones at that tick rather than where they were authored.
 *
 * <p><b>This is a second reference set beside {@code entities/}, never a replacement for it.</b> The
 * still tree is ground truth for the mesh as authored, which is what the asset-renderer's default
 * renders and what 88 of the 90 modelled entities would move away from the moment the freeze came
 * off. So the two are produced by two runs of the client with two mixin configurations, and this one
 * is selected by {@code -Drefharness.animated=true}.
 *
 * <p><b>It writes two sub-trees and which one is a gait rather than a work list.</b>
 * {@code -Drefharness.walking=true} drives the two figures a stride is carried on and sends the same
 * subjects at the same ticks to {@code walk/} instead, which is what the asset-renderer's
 * {@code WALK} preset is compared against. Everything below is shared by both because nothing below
 * is a function of the gait: the subjects, the schedule, the canvas pre-pass and the naming.
 *
 * <p><b>One subject per POSE, which is the adult and the baby and neither a coat nor a toggle.</b>
 * The still sweep enumerates coats and per-axis selections because those are what a texture and an
 * appearance gate answer for; a pose answers for neither. A pose belongs to the model class the
 * renderer hands the model, and every coat of a family shares one - so a second coat would render
 * the same bones in the same places under a different skin and say nothing about the table this
 * sweep exists to measure.
 *
 * <p><b>A baby is the one appearance that fails that test.</b> It is a different model class for 39
 * of the 41 that have one, so it is a different row of the pose table rather than the same row under
 * another skin; and for the two that share their adult's class it is still a different mesh, so the
 * same row lands on geometry of another size. Either way what it exercises is not what the adult
 * exercised, which is the whole criterion. The name each subject takes is the still sweep's own for
 * that same appearance, so a strip and the frame it is compared against are one spelling.
 *
 * <p><b>No bone is pinned.</b> {@link EntityRoster#bonePins} forces the flags vanilla writes from
 * {@code setupAnim} precisely because the still sweep does not run it; here it does, so the real
 * writes land and a pin beside them would be a second authority for the same bone. That is also why
 * the toggle selections are not enumerated: what a toggle selects is a persistent entity state this
 * sweep's subjects are not in, so a pinned one would render the resting subject twice.
 */
@Parity(claim = "harness-animation-sweep", mode = Mode.DEMOTE, subject = Subject.ENTITY)
public final class EntityAnimationSweep implements Sweep<EntityAnimationSweep.Frame> {

    /**
     * The gait this instance renders, which is also the sub-tree it writes. Held rather than read
     * from a property so one boot can run both: two instances of one sweep are two sub-trees, where
     * a static read made them two runs.
     */
    private final Gait gait;

    /**
     * Constructs a sweep that renders one gait.
     *
     * @param gait the gait to render, which decides both the stride and the output directory
     */
    public EntityAnimationSweep(Gait gait) {
        this.gait = gait;
    }

    @Override
    public Gait gait() {
        return this.gait;
    }

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Frames per subject. MUST match asset-renderer's
     * {@code TestEntityAnimationParityVanilla.FRAME_COUNT}.
     */
    public static final int FRAME_COUNT = 8;

    /**
     * Ticks advanced between successive frames. MUST match asset-renderer's
     * {@code TestEntityAnimationParityVanilla.TICKS_PER_FRAME}.
     *
     * <p>The product of the two spans 21 ticks, which is one whole cycle of the fastest idle driver
     * in the corpus - a ghast's tentacles and an allay's arms turn on {@code sin(ageInTicks * 0.3)},
     * whose period is {@code 2 * pi / 0.3}. A schedule shorter than that would sample a subject that
     * moves and report the part of its cycle it happened to cover.
     */
    public static final int TICKS_PER_FRAME = 3;

    /**
     * The tick frame 0 samples. MUST match asset-renderer's
     * {@code TestEntityAnimationParityVanilla.START_TICK}.
     *
     * <p>Zero, so the first frame of a strip is the one instant both sides already have a still of:
     * the frozen reference is this same subject at {@code ageInTicks = 0} with {@code setupAnim}
     * cancelled, so frame 0 is where the difference between running it and not is readable directly.
     */
    public static final int START_TICK = 0;

    private final AnimatedEntityFrameRenderer frameRenderer = new AnimatedEntityFrameRenderer();

    /** One canvas per family COHORT, measured by {@link #prepare} across every member and tick. */
    private Map<EntitySweep.CanvasKey, Canvas> familyFits = Map.of();

    /**
     * One frame of one subject.
     *
     * @param subject what is being drawn, at the appearance the still sweep names it by
     * @param frame the frame index within the subject's strip
     */
    public record Frame(EntitySweep.Subject subject, int frame) {

        /** The tick this frame poses its subject at. */
        public int tick() {
            return START_TICK + frame * TICKS_PER_FRAME;
        }

        /** The subject's directory name, which is the still sweep's name for the same appearance. */
        private String directory() {
            return EntitySweep.nameOf(subject).fileName();
        }
    }

    /** The ticks the schedule samples, in the order the frames are written. */
    public static List<Integer> ticks() {
        return IntStream.range(0, FRAME_COUNT).mapToObj(frame -> START_TICK + frame * TICKS_PER_FRAME).toList();
    }

    /**
     * The sub-tree this run writes, which is the gait it renders rather than the sweep that renders
     * it.
     *
     * <p>One sweep and two sub-trees, because what separates them is a pair of render-state fields
     * and not a work list: the subjects, the schedule, the canvas pass and the naming are the same
     * on both, and the asset-renderer compares one against {@code IDLE} and the other against
     * {@code WALK}. Driving the stride into {@code animation/} instead would move every row of a
     * promoted gate rather than adding a second one.
     */
    @Override
    public String outputDir() {
        return this.gait == Gait.WALK ? "walk" : "animation";
    }

    @Override
    public List<Frame> enumerate(SweepContext ctx) {
        List<Frame> frames = new ArrayList<>();
        for (EntityType<?> type : EntitySweep.selectTypes(ctx)) {
            for (int frame = 0; frame < FRAME_COUNT; frame++)
                frames.add(new Frame(EntitySweep.defaultOf(type), frame));
            // A baby is the one appearance that is a different POSE rather than a different skin, so
            // it is enumerated where the coats are not - see the class doc.
            for (EntitySweep.Subject baby : EntitySweep.babyOf(ctx, type).stream().toList())
                for (int frame = 0; frame < FRAME_COUNT; frame++) frames.add(new Frame(baby, frame));
        }
        LOG.info("EntityAnimationSweep built: {} frames over {} subjects ({} frames each, {} ticks apart)",
            frames.size(), frames.size() / FRAME_COUNT, FRAME_COUNT, TICKS_PER_FRAME);
        return frames;
    }

    /**
     * Measures one canvas per family, across every member of it and every tick of the schedule.
     *
     * <p>Three unions rather than one, and they compose: a family shares a canvas so its members'
     * common geometry lands on common pixels, a type is measured across every adult coat because the
     * asset-renderer unions those too, and a strip shares one canvas so a subject that moves is not
     * cropped at the ticks the fit was not measured at. All three are monotone, so the canvas holds
     * every frame of every member whichever order they are taken in.
     *
     * <p><b>What is measured is wider than what is rendered, and has to be.</b> One coat per type is
     * drawn, because a pose is the same under every skin; but a coat is a different set of opaque
     * texels, and the canvas is alpha-tight, so measuring the drawn coat alone framed a cow 386 px
     * wide against the 442 the asset-renderer framed it at and reported the difference as pixels.
     */
    @Override
    public void prepare(SweepContext ctx, List<Frame> subjects) {
        Map<EntitySweep.CanvasKey, Bounds> familyBounds = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        int measured = 0;
        for (EntityType<?> type : EntitySweep.selectTypes(ctx)) {
            for (EntitySweep.Subject member : EntitySweep.canvasMembers(ctx, type)) {
                Bounds bounds = measure(ctx, member);
                if (bounds == null) continue;
                familyBounds.merge(member.canvasKey(), bounds, Bounds::union);
                measured++;
            }
            // Keyed apart from the adults for the reason the still sweep keys it apart: unioning a
            // baby into its family grows every adult frame in it, and unioning an adult into the
            // babies leaves every baby adrift in a frame sized for a body it is not drawn on.
            for (EntitySweep.Subject baby : EntitySweep.babyOf(ctx, type).stream().toList()) {
                Bounds bounds = measure(ctx, baby);
                if (bounds == null) continue;
                familyBounds.merge(baby.canvasKey(), bounds, Bounds::union);
                measured++;
            }
        }

        // A derived cohort takes in the cohort it builds on exactly where the still sweep does, and
        // for the same reason: the asset-renderer unions a baby with the adult coats of its own
        // model, so a harness that sized the baby against its own extent alone would compare a
        // subject framed two ways and report the framing rather than the render. Keying the cohorts
        // apart WITHOUT this seeding is what put a baby cow in a 262x274 frame against the java
        // side's 442x482 and read as 139.6790 of delta on a row that renders correctly.
        for (Map.Entry<EntitySweep.CanvasKey, Bounds> entry : Map.copyOf(familyBounds).entrySet()
            .stream()
            .sorted(Comparator.comparingInt(e -> e.getKey().cohort().name().length()))
            .toList()) {
            if (!EntityRoster.sharesDefaultCanvas(entry.getKey().family())) continue;
            Optional<Appearance.Cohort> base = entry.getKey().cohort().base();
            if (base.isEmpty()) continue;
            Bounds seed =
                familyBounds.get(new EntitySweep.CanvasKey(entry.getKey().family(), base.get()));
            if (seed != null) familyBounds.merge(entry.getKey(), seed, Bounds::union);
        }

        Map<EntitySweep.CanvasKey, Canvas> fits = new LinkedHashMap<>();
        for (Map.Entry<EntitySweep.CanvasKey, Bounds> entry : familyBounds.entrySet())
            fits.put(entry.getKey(), EntitySweep.canvasFitting(entry.getValue()));
        familyFits = Map.copyOf(fits);
        LOG.info("EntityAnimationSweep: canvas pre-pass measured {} subjects in {} cohorts ({} ms)",
            measured, fits.size(), (System.nanoTime() - t0) / 1_000_000L);
        fits.entrySet().stream()
            .map(fit -> String.format("  fit %s/%s -> %dx%d @ %.6f (%.6f, %.6f)",
                EntityType.getKey(fit.getKey().family()), fit.getKey().cohort(),
                fit.getValue().width(), fit.getValue().height(),
                fit.getValue().fit().orElseThrow().scale(),
                fit.getValue().fit().orElseThrow().anchorX(),
                fit.getValue().fit().orElseThrow().anchorY()))
            .sorted()
            .forEach(LOG::info);
    }

    /**
     * Measures one subject across the whole schedule, or answers null when vanilla declines to build
     * it.
     *
     * @param ctx the sweep context
     * @param subject the subject to measure
     * @return the union of its bounds over every tick, or null
     */
    private Bounds measure(SweepContext ctx, EntitySweep.Subject subject) {
        AppearanceRequest.set(subject.appearance());
        try {
            Entity entity = AppearanceApplier.build(ctx, subject.type(), subject.appearance());
            if (entity == null) return null;
            return frameRenderer.measureAcrossTicks(ctx.client(), entity, ticks());
        } catch (RuntimeException ex) {
            LOG.warn("EntityAnimationSweep: measure failed for {}: {}",
                EntityType.getKey(subject.type()), ex.toString());
            return null;
        } finally {
            AppearanceRequest.clear();
        }
    }

    @Override
    public RefKey key(Frame subject) {
        return RefKey.named(String.format("frame_%03d", subject.frame())).in(subject.directory());
    }

    @Override
    public Canvas canvas(SweepContext ctx, Frame subject) {
        return familyFits.get(subject.subject().canvasKey());
    }

    @Override
    public boolean render(SweepContext ctx, Frame subject, Canvas canvas, Path out) throws IOException {
        // A subject with no canvas means the measurement pass and the render pass disagree about
        // what this sweep renders - a fault in this class rather than a property of the data, and it
        // costs a reference nobody asked about, so it stops the sweep rather than logging.
        if (canvas == null)
            throw new IllegalStateException("No measured canvas for '" + key(subject).fileName()
                + "' - the render enumeration and the measurement enumeration disagree");
        Appearance appearance = subject.subject().appearance();
        AppearanceRequest.set(appearance);
        try {
            Entity entity = AppearanceApplier.build(ctx, subject.subject().type(), appearance);
            if (entity == null) {
                LOG.warn("EntityAnimationSweep: could not build {}", subject.directory());
                return false;
            }
            return frameRenderer.renderAtTick(ctx.client(), entity, subject.tick(), canvas, out);
        } finally {
            AppearanceRequest.clear();
        }
    }
}
