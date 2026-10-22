package lib.minecraft.refharness.sweep;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lib.minecraft.refharness.Gait;
import lib.minecraft.refharness.PoseState;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Animated entity sweep. Each subject is rendered once per tick of its own schedule, which the asset
 * side's roster names, with vanilla's own {@code setupAnim} running, so the reference says where a
 * model actually puts its bones at that tick rather than where they were authored.
 *
 * <p><b>This is a second reference set beside {@code entities/}, never a replacement for it.</b> The
 * still tree is ground truth for the mesh as authored, which is what the asset-renderer's default
 * renders and what 88 of the 90 modelled entities would move away from the moment the freeze came
 * off. The two sets come from one boot, the frozen sweeps first, the freeze lifted per sweep by the
 * gait {@link PoseState} holds.
 *
 * <p><b>It writes two sub-trees and which one is a gait rather than a work list.</b> The walking
 * instance sends the same subjects to {@code walk/}, each at its walk schedule, which is what the
 * asset-renderer's {@code WALK} preset is compared against. The subjects - each type's default, its
 * baby, and the posed trait subjects the roster lists - the canvas rule and the naming are shared by
 * both; the schedule is the gait's.
 *
 * <p><b>One subject per POSE, which is the adult and the baby and neither a coat nor a toggle.</b>
 * The still sweep enumerates coats and per-axis selections because those are what a texture and an
 * appearance gate answer for; a pose answers for neither. A pose belongs to the model class the
 * renderer hands the model, and every coat of a family shares one - so a second coat would render
 * the same bones in the same places under a different skin and say nothing about the table this
 * sweep exists to measure. The roster's posed trait subjects are the selections that do change a
 * pose - a carried block or an offered flower is held in raised arms - so each is a subject of its
 * own, named and canvassed as the still sweep names and canvasses the same appearance.
 *
 * <p><b>A baby is the one appearance that fails that test.</b> It is a different model class for
 * most types that have one, so it is a different row of the pose table rather than the same row
 * under another skin; and for those that share their adult's class it is still a different mesh, so
 * the same row lands on geometry of another size. Either way what it exercises is not what the adult
 * exercised, which is the whole criterion. The name each subject takes is the still sweep's own for
 * that same appearance, so a strip and the frame it is compared against are one spelling.
 *
 * <p><b>No bone is pinned.</b> {@link EntityRoster#bonePins} forces the flags vanilla writes from
 * {@code setupAnim} precisely because the still sweep does not run it; here it does, so the real
 * writes land and a pin beside them would be a second authority for the same bone. That is also why
 * the toggle selections are not enumerated: what a toggle selects is a persistent entity state this
 * sweep's subjects are not in, so a pinned one would render the resting subject twice.
 *
 * <p><b>Each sub-tree records what it rendered.</b> After the last frame the sweep merges every
 * enumerated stem's schedule into its sub-tree's {@code schedule.json}, recorded only for a stem
 * whose every frame was written, and the asset side's visual sweep holds each row to that record, to
 * its own resolution and to the frame files on disk.
 */
@Parity(claim = "harness-animation-sweep", mode = Mode.DEMOTE, subject = Subject.ENTITY)
public final class EntityAnimationSweep implements Sweep<EntityAnimationSweep.Frame> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * The tick frame 0 samples. MUST match asset-renderer's
     * {@code EntityAnimationParitySweep.START_TICK}.
     *
     * <p>Zero, so the first frame of a strip is the one instant both sides already have a still of:
     * the frozen reference is this same subject at {@code ageInTicks = 0} with {@code setupAnim}
     * cancelled, so frame 0 is where the difference between running it and not is readable directly.
     */
    public static final int START_TICK = 0;

    /** The format of the schedule sidecar each posed sub-tree records. */
    private static final int SIDECAR_FORMAT = 1;

    /** A frame file this sweep writes, with its index. */
    private static final Pattern FRAME_FILE = Pattern.compile("frame_(\\d{3})\\.png");

    /**
     * The gait this instance renders, which is also the sub-tree it writes. Held rather than read
     * from a property so one boot can run both: two instances of one sweep are two sub-trees, where
     * a static read made them two runs.
     */
    private final Gait gait;

    /** The schedule every subject is rendered at, read at boot. */
    private final ScheduleRoster roster;

    private final AnimatedEntityFrameRenderer frameRenderer = new AnimatedEntityFrameRenderer();

    /** One canvas per family COHORT, measured by {@link #prepare} across every member and frame. */
    private Map<EntitySweep.CanvasKey, Canvas> familyFits = Map.of();

    /** Every stem this run enumerated, with the subject and the schedule it is rendered at. */
    private final Map<String, Scheduled> scheduled = new TreeMap<>();

    /** The frames written for each stem this run enumerated. */
    private final Map<String, Integer> written = new HashMap<>();

    /**
     * Constructs a sweep that renders one gait.
     *
     * @param gait the gait to render, which decides both the stride and the output directory
     * @param roster the schedule each subject is rendered at
     */
    public EntityAnimationSweep(Gait gait, ScheduleRoster roster) {
        this.gait = gait;
        this.roster = roster;
    }

    @Override
    public Gait gait() {
        return this.gait;
    }

    /**
     * One frame of one subject.
     *
     * @param subject what is being drawn, at the appearance the still sweep names it by
     * @param frame the frame index within the subject's strip
     * @param schedule the subject's schedule
     */
    public record Frame(EntitySweep.Subject subject, int frame, SubjectSchedule schedule) {

        /** The tick this frame poses its subject at. */
        public int tick() {
            return this.schedule.tickAt(this.frame);
        }

        /** The subject's directory name, which is the still sweep's name for the same appearance. */
        private String directory() {
            return stemOf(this.subject);
        }
    }

    /**
     * One enumerated stem.
     *
     * @param subject the subject the stem draws
     * @param schedule the schedule it is rendered at
     */
    private record Scheduled(EntitySweep.Subject subject, SubjectSchedule schedule) {}

    /**
     * The sub-tree this run writes, which is the gait it renders rather than the sweep that renders
     * it.
     *
     * <p>One sweep and two sub-trees, because what separates them is the walk each subject is armed
     * at and not a work list: the subjects, the canvas rule and the naming are the same on both, and
     * the asset-renderer compares one against {@code IDLE} and the other against {@code WALK}. Each
     * answers the gait's own name, so the mapping is one spelling from the enum constant through to
     * the directory on disk; driving the stride into {@code idle/} instead would move every row of a
     * promoted gate rather than adding a second one.
     */
    @Override
    public String outputDir() {
        return this.gait == Gait.WALK ? "walk" : "idle";
    }

    /**
     * Enumerates every frame of every subject - per type its default, its baby, then the posed trait
     * subjects the roster names in key order - each at its own schedule.
     *
     * @param ctx the sweep context
     * @return the frames, in render order
     * @throws IllegalStateException if the roster names no schedule for a subject this client renders
     */
    @Override
    public List<Frame> enumerate(SweepContext ctx) {
        this.scheduled.clear();
        this.written.clear();
        List<Frame> frames = new ArrayList<>();
        for (EntityType<?> type : EntitySweep.selectTypes(ctx)) {
            EntitySweep.Subject adult = EntitySweep.defaultOf(type);
            this.schedule(frames, adult, this.roster.scheduleOf(type, false, this.gait, adult.qualifier(), Optional.empty()));
            // A baby is the one appearance that is a different POSE rather than a different skin, so
            // it is enumerated where the coats are not - see the class doc.
            Optional<EntitySweep.Subject> baby = EntitySweep.babyOf(ctx, type);
            baby.ifPresent(subject -> this.schedule(frames, subject,
                this.roster.scheduleOf(type, true, this.gait, subject.qualifier(), Optional.empty())));
            for (EntitySweep.Subject subject : this.traitSubjects(type, baby.isPresent())) {
                String key = subject.appearance().traits().getFirst().token();
                this.schedule(frames, subject, this.roster.scheduleOf(type, subject.appearance().baby(), this.gait,
                    subject.qualifier(), Optional.of(key)));
            }
        }
        TreeSet<Integer> steps = new TreeSet<>();
        this.scheduled.values().forEach(stem -> steps.add(stem.schedule().ticksPerFrame()));
        LOG.info("EntityAnimationSweep built: {} frames over {} subjects ({}..{} frames a strip, {} ticks apart)",
            frames.size(), this.scheduled.size(),
            this.scheduled.values().stream().mapToInt(stem -> stem.schedule().frames()).min().orElse(0),
            this.scheduled.values().stream().mapToInt(stem -> stem.schedule().frames()).max().orElse(0),
            steps);
        return frames;
    }

    /** Adds every frame of one subject's strip to the work list, and records the stem. */
    private void schedule(List<Frame> frames, EntitySweep.Subject subject, SubjectSchedule schedule) {
        this.scheduled.put(stemOf(subject), new Scheduled(subject, schedule));
        for (int frame = 0; frame < schedule.frames(); frame++) frames.add(new Frame(subject, frame, schedule));
    }

    /**
     * The posed trait subjects the roster names for one type - the adult's, then the baby's where the
     * type has one - each in key order.
     *
     * @param type the entity type
     * @param hasBaby whether this client renders the type's baby
     * @return the trait subjects
     */
    private List<EntitySweep.Subject> traitSubjects(EntityType<?> type, boolean hasBaby) {
        List<EntitySweep.Subject> subjects = new ArrayList<>();
        for (String key : this.roster.traitsOf(type, false, this.gait)) subjects.add(traitSubject(type, key, false));
        if (hasBaby)
            for (String key : this.roster.traitsOf(type, true, this.gait)) subjects.add(traitSubject(type, key, true));
        return subjects;
    }

    /**
     * Returns the posed trait subject one roster trait key names: the type's default appearance, aged
     * down first for a baby, with the one trait the key spells selected on top.
     *
     * @param type the entity type
     * @param key the trait key, {@code axis=value}
     * @param baby whether the subject is aged down
     * @return the subject, named as the still sweep names the same appearance
     * @throws IllegalStateException if the key names no axis
     */
    private static EntitySweep.Subject traitSubject(EntityType<?> type, String key, boolean baby) {
        int split = key.indexOf('=');
        if (split < 0)
            throw new IllegalStateException(String.format("Posed trait '%s' names no axis", key));
        EntitySweep.Subject base = EntitySweep.defaultOf(type);
        Appearance appearance = baby ? base.appearance().asBaby() : base.appearance();
        Appearance.Trait trait = new Appearance.Trait(key.substring(0, split), key.substring(split + 1));
        return new EntitySweep.Subject(type, appearance.with(trait), base.qualifier());
    }

    /**
     * Measures one canvas per family, across every member of it and every frame of that member's own
     * schedule, then clears the frames a shorter strip leaves behind.
     *
     * <p>Three unions rather than one, and they compose: a family shares a canvas so its members'
     * common geometry lands on common pixels, a type is measured across every adult coat because the
     * asset-renderer unions those too, and a strip shares one canvas so a subject that moves is not
     * cropped at the frames the fit was not measured at. All three are monotone, so the canvas holds
     * every frame of every member whichever order they are taken in. Each member is measured over its
     * own schedule - a coat that resolves another one over that one - which is the rule the
     * asset-renderer sizes its own canvas by.
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
                Bounds bounds = measure(ctx, member,
                    this.roster.scheduleOf(type, false, this.gait, member.qualifier(), Optional.empty()));
                if (bounds == null) continue;
                familyBounds.merge(member.canvasKey(), bounds, Bounds::union);
                measured++;
            }
            // Keyed apart from the adults for the reason the still sweep keys it apart: unioning a
            // baby into its family grows every adult frame in it, and unioning an adult into the
            // babies leaves every baby adrift in a frame sized for a body it is not drawn on.
            Optional<EntitySweep.Subject> baby = EntitySweep.babyOf(ctx, type);
            for (EntitySweep.Subject subject : baby.stream().toList()) {
                Bounds bounds = measure(ctx, subject,
                    this.roster.scheduleOf(type, true, this.gait, subject.qualifier(), Optional.empty()));
                if (bounds == null) continue;
                familyBounds.merge(subject.canvasKey(), bounds, Bounds::union);
                measured++;
            }
            // A posed trait subject is a cohort of its own, measured over its own schedule and seeded
            // below from the plain subjects it selects on, as the still sweep sizes the same appearance.
            for (EntitySweep.Subject subject : this.traitSubjects(type, baby.isPresent())) {
                Bounds bounds = measure(ctx, subject, this.scheduled.get(stemOf(subject)).schedule());
                if (bounds == null) continue;
                familyBounds.merge(subject.canvasKey(), bounds, Bounds::union);
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

        this.clearStaleFrames(ctx);
    }

    /**
     * Measures one subject across every frame of its schedule, or answers null when vanilla declines
     * to build it.
     *
     * @param ctx the sweep context
     * @param subject the subject to measure
     * @param schedule the schedule the subject is measured over
     * @return the union of its bounds over every frame, or null
     */
    private Bounds measure(SweepContext ctx, EntitySweep.Subject subject, SubjectSchedule schedule) {
        AppearanceRequest.set(subject.appearance());
        try {
            Entity entity = AppearanceApplier.build(ctx, subject.type(), subject.appearance());
            if (entity == null) return null;
            return frameRenderer.measureAcrossTicks(ctx.client(), entity, schedule);
        } catch (RuntimeException ex) {
            LOG.warn("EntityAnimationSweep: measure failed for {}: {}",
                EntityType.getKey(subject.type()), ex.toString());
            return null;
        } finally {
            AppearanceRequest.clear();
        }
    }

    /**
     * Deletes every frame file at or past the end of each enumerated stem's strip, so a strip shorter
     * than the one on disk leaves no frame behind for the tree's manifest to digest as ground truth
     * nobody rendered.
     *
     * <p>Only frame files of the stems this run enumerated, under this sweep's own sub-tree: no other
     * file name, no directory, and no stem a narrowed run did not enumerate.
     *
     * @param ctx the sweep context
     * @throws UncheckedIOException if a stem directory cannot be listed or a frame deleted
     */
    private void clearStaleFrames(SweepContext ctx) {
        Path root = ctx.outputRoot().resolve(outputDir());
        int cleared = 0;
        int stems = 0;
        for (Map.Entry<String, Scheduled> stem : this.scheduled.entrySet()) {
            Path directory = root.resolve(stem.getKey());
            if (!Files.isDirectory(directory)) continue;
            List<Path> stale;
            try (Stream<Path> files = Files.list(directory)) {
                stale = files.filter(Files::isRegularFile)
                    .filter(file -> {
                        Matcher name = FRAME_FILE.matcher(file.getFileName().toString());
                        return name.matches() && Integer.parseInt(name.group(1)) >= stem.getValue().schedule().frames();
                    })
                    .sorted()
                    .toList();
                for (Path file : stale) Files.delete(file);
            } catch (IOException ex) {
                throw new UncheckedIOException(String.format("Clearing stale frames of '%s' failed", directory), ex);
            }
            cleared += stale.size();
            if (!stale.isEmpty()) stems++;
        }
        LOG.info("EntityAnimationSweep: cleared {} stale frames across {} stems", cleared, stems);
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
        if (canvas == null) {
            throw new IllegalStateException("No measured canvas for '" + key(subject).fileName()
                + "' - the render enumeration and the measurement enumeration disagree");
        }
        Appearance appearance = subject.subject().appearance();
        AppearanceRequest.set(appearance);
        try {
            Entity entity = AppearanceApplier.build(ctx, subject.subject().type(), appearance);
            if (entity == null) {
                LOG.warn("EntityAnimationSweep: could not build {}", subject.directory());
                return false;
            }
            boolean drawn = frameRenderer.renderAtTick(ctx.client(), entity, subject.schedule(), subject.frame(), canvas, out);
            if (drawn) this.written.merge(subject.directory(), 1, Integer::sum);
            return drawn;
        } finally {
            AppearanceRequest.clear();
        }
    }

    /**
     * Records the schedule every enumerated stem was rendered at in this sub-tree's
     * {@code schedule.json}, merged into what an earlier run recorded.
     *
     * <p>A stem whose every frame was written is recorded as rendered; a stem with a skipped or failed
     * frame has its entry removed, so the asset side fails that row rather than comparing a partial
     * strip as a whole one. Every stem this run did not enumerate keeps the entry it had, so a narrowed
     * run leaves the rest of the record standing.
     *
     * @param ctx the sweep context
     * @throws IllegalStateException if an existing sidecar does not parse or is of another format
     * @throws UncheckedIOException if the sidecar cannot be read or written
     */
    @Override
    public void afterSweep(SweepContext ctx) {
        Path file = ctx.outputRoot().resolve(outputDir()).resolve("schedule.json");
        Map<String, JsonElement> subjects = new TreeMap<>(recorded(file));
        int recorded = 0;
        for (Map.Entry<String, Scheduled> stem : this.scheduled.entrySet()) {
            if (this.written.getOrDefault(stem.getKey(), 0) == stem.getValue().schedule().frames()) {
                subjects.put(stem.getKey(), sidecarEntry(stem.getValue()));
                recorded++;
            } else {
                subjects.remove(stem.getKey());
            }
        }

        JsonObject entries = new JsonObject();
        subjects.forEach(entries::add);
        JsonObject root = new JsonObject();
        root.addProperty("format", SIDECAR_FORMAT);
        root.add("subjects", entries);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(String.format("Writing the schedule sidecar '%s' failed", file), ex);
        }
        LOG.info("EntityAnimationSweep: recorded {} of {} enumerated stems in {}", recorded, this.scheduled.size(), file);
    }

    /**
     * The entries an earlier run recorded, empty where no sidecar exists yet.
     *
     * @param file the sidecar
     * @return the recorded entries, keyed by stem
     * @throws IllegalStateException if the sidecar does not parse or is of another format
     * @throws UncheckedIOException if the sidecar cannot be read
     */
    private static Map<String, JsonElement> recorded(Path file) {
        if (!Files.exists(file)) return Map.of();
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(String.format("Reading the schedule sidecar '%s' failed", file), ex);
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalStateException(String.format("The schedule sidecar '%s' does not parse", file), ex);
        }
        JsonElement format = root.get("format");
        JsonElement subjects = root.get("subjects");
        if (format == null || !String.valueOf(SIDECAR_FORMAT).equals(format.getAsString()) || subjects == null || !subjects.isJsonObject())
            throw new IllegalStateException(String.format("The schedule sidecar '%s' is not format '%s'", file, SIDECAR_FORMAT));
        Map<String, JsonElement> out = new TreeMap<>();
        subjects.getAsJsonObject().entrySet().forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    /**
     * One stem's sidecar entry - its entity and age, the four schedule values, then its clocks where
     * it carries any, fields sorted and values in frame order.
     *
     * @param stem the stem
     * @return its entry
     */
    private static JsonObject sidecarEntry(Scheduled stem) {
        SubjectSchedule schedule = stem.schedule();
        JsonObject entry = new JsonObject();
        entry.addProperty("entity", EntityType.getKey(stem.subject().type()).toString());
        entry.addProperty("age", stem.subject().appearance().baby() ? "baby" : "adult");
        entry.addProperty("frames", schedule.frames());
        entry.addProperty("ticks_per_frame", schedule.ticksPerFrame());
        entry.addProperty("walk_speed", schedule.walkSpeed());
        entry.addProperty("walk_rate", schedule.walkRate());
        if (!schedule.clocks().isEmpty()) {
            JsonObject clocks = new JsonObject();
            new TreeMap<>(schedule.clocks()).forEach((field, values) -> {
                JsonArray array = new JsonArray();
                values.forEach(array::add);
                clocks.add(field, array);
            });
            entry.add("clocks", clocks);
        }
        return entry;
    }

    /** The stem one subject's frames are written under - the still sweep's name for the same appearance. */
    private static String stemOf(EntitySweep.Subject subject) {
        return EntitySweep.nameOf(subject).fileName();
    }
}
