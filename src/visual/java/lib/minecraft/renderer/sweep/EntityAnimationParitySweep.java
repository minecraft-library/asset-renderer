package lib.minecraft.renderer.sweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.bake.pose.StyleResolution;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.sweep.EntityScheduleRoster.Schedule;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Per-entity <em>animated</em> parity report: the Java pipeline posed at each tick of the strip a
 * render naming no frame count draws, against the harness references posed at the schedule the
 * {@link EntityScheduleRoster roster} names for each subject, with vanilla's own {@code setupAnim}
 * running.
 *
 * <p>This is the gate on the shipped pose table. Everything else compares the mesh as authored -
 * both sides freeze, so both sides agree by construction and a pose that transcribes
 * {@code setupAnim} plausibly rather than faithfully costs nothing. Here frame {@code N} is the same
 * tick on both sides, so a bone the table sends the wrong way is a difference in the picture.
 *
 * <p><b>The render names no frame count and takes the production default, and each row holds the
 * harness's sidecar, its own resolution and the frame files to one another.</b> The harness records
 * the schedule it rendered each subject at in its sub-tree's {@code schedule.json}; a row whose
 * sidecar entry, whose own {@link Schedule#of schedule} and whose frame files disagree fails, so a
 * reference rendered at a schedule this tree does not resolve is a failed row rather than a delta.
 * Frame {@code N} is tick {@code START_TICK + N * ticksPerFrame} on both sides; the harness stamps
 * that tick onto the render state's elapsed age and the Java side hands it to {@code PosePlayer}.
 *
 * <p>Output per subject under {@code cache/visual/entity-animation-parity-vanilla/<subject>/}, the
 * folder cleared first so it holds this run's files alone: per-frame {@code java/} PNGs, the
 * reference strip's own files under {@code vanilla/}, a {@code contact_sheet.png} carrying the
 * vanilla, java and diff rows at a glance, {@code java.gif} playing the strip at its own step, and,
 * where any frame differs, a {@code diff_panel.png} of the worst frame - the one panel worth opening
 * first. A top-level {@code parity-report.tsv} ranks subjects by mean per-frame ARGB delta.
 *
 * <p>Usage: {@code ./gradlew entityAnimationParityVanilla [-PentityId=minecraft:zombie]}, or
 * {@code entityWalkParityVanilla} for the walk gait. Run {@code renderVanillaAnimationReferences}
 * first if the reference tree has no {@code idle/}.
 */
@UtilityClass
public final class EntityAnimationParitySweep {

    /**
     * Which gait a run measures, which is the whole of what separates the two sweeps.
     *
     * <p>The subjects, the naming, the checks, the diff and every artifact written are the same on
     * both; what the gait decides is the style the Java side poses at, the harness sub-tree it is
     * compared against, and so the schedule each subject takes - the harness produces the two the same
     * way for the same reason. So this is one driver taking a parameter rather than two drivers, and a
     * divergence between the two reports cannot be a divergence in how they were measured.
     *
     * @param name the name a run is handed it by, which is also the harness sub-tree holding the ground
     *     truth for it
     * @param style the style id the Java side renders at
     * @param output the directory under {@code cache/visual/} this sweep's own artifacts are written into
     */
    private record Gait(
        @NotNull String name, @NotNull String style, @NotNull String output) {

        /** A subject standing still, which is what the promoted animation gate measures. */
        private static final Gait IDLE =
            new Gait("idle", PoseStyle.IDLE, "entity-animation-parity-vanilla");

        /** A subject walking at its own ambling pace. */
        private static final Gait WALK =
            new Gait("walk", PoseStyle.STRIDE, "entity-walk-parity-vanilla");

        /**
         * Returns the gait a name selects.
         *
         * @param name {@code idle} or {@code walk}
         * @return the gait
         * @throws IllegalArgumentException if the name selects none
         */
        private static @NotNull Gait named(@NotNull String name) {
            return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "idle" -> IDLE;
                case "walk" -> WALK;
                default -> throw new IllegalArgumentException(
                    String.format("'%s' is not a gait this sweep renders", name));
            };
        }

        /**
         * Returns the directory the per-subject sub-folders and the report are written into.
         *
         * @return the directory
         */
        private @NotNull Path outputDir() {
            return Path.of("cache/visual").resolve(this.output);
        }

        /**
         * Returns the harness's posed references for this gait, one directory per subject.
         *
         * @return the directory
         */
        private @NotNull Path referenceDir() {
            return ParityPaths.references(this.name);
        }

        /**
         * Returns the harness's record of the schedule each subject of the sub-tree was rendered at.
         *
         * @return the file
         */
        private @NotNull Path sidecar() {
            return this.referenceDir().resolve("schedule.json");
        }
    }

    /** The tick frame 0 samples, the still. MUST match the harness {@code EntityAnimationSweep.START_TICK}. */
    static final int START_TICK = 0;

    /** Frame cells on one line of the contact sheet, past which the strip wraps onto the next. */
    private static final int CONTACT_COLUMNS = 16;

    /** The one sidecar format this sweep reads. */
    private static final int SIDECAR_FORMAT = 1;

    /**
     * Runs the animated parity sweep over a context of its own, loaded over vanilla's own client
     * options.
     *
     * @param args what {@link #run} reads
     * @throws IOException if the report write fails
     */
    public static void main(String @NotNull [] args) throws IOException {
        run(RendererContext.load(SweepPair.acquire(ClientOptions.defaults())), args);
    }

    /**
     * Runs the animated parity sweep over a loaded context.
     *
     * @param context the context every render resolves through, loaded over vanilla's own client options
     * @param args {@code args[0]} the gait, {@code idle} or {@code walk}; {@code args[1]} optional
     *     comma-separated list of entity ids or reference stems, absent rendering every subject the
     *     harness wrote a strip for that the pipeline also models
     * @throws IOException if the report write fails
     * @throws IllegalArgumentException if no gait is given, or one this sweep does not render
     * @throws IllegalStateException if the sidecar or a reference name cannot be read
     */
    static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        if (args.length == 0)
            throw new IllegalArgumentException("Usage: EntityAnimationParitySweep <idle | walk> [subjects] - the gait is required");
        Gait gait = Gait.named(args[0]);
        List<String> filter = args.length > 1 ? List.of(args[1].split(",")) : List.of();
        Path outputDir = gait.outputDir();
        Path report = outputDir.resolve("parity-report.tsv");

        if (!Files.isDirectory(gait.referenceDir())) {
            System.err.printf("Posed reference directory missing: %s%n  Run the harness run that writes '%s/' first.%n",
                gait.referenceDir().toAbsolutePath(), gait.name());
            return;
        }
        Map<String, Recorded> sidecar = readSidecar(gait);
        Files.createDirectories(outputDir);

        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json missing - run entityModels first");
            return;
        }
        EntityRenderer javaRenderer = new EntityRenderer(context);
        TreeSet<String> javaKeys = new TreeSet<>(javaEntities.keySet());
        AppearanceCodec codec = AppearanceCodec.of(javaEntities);

        // Each subject is a DIRECTORY of frames, named by the still sweep's own spelling of that
        // appearance - so the one grammar both repositories implement reads it, and a name this
        // parser cannot read stops the sweep rather than quietly rendering nothing for it.
        List<Subject> subjects = new ArrayList<>();
        List<AppearanceKey.Result.Malformed> malformed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String stem : collectReferenceStems(gait.referenceDir())) {
            switch (codec.parse(stem + ".png")) {
                case AppearanceKey.Result.Malformed bad -> malformed.add(bad);
                case AppearanceKey.Result.Parsed parsed -> {
                    if (javaKeys.contains(parsed.key().entityId())) subjects.add(new Subject(stem, parsed.key()));
                    else unresolved.add(stem);
                }
            }
        }
        if (!malformed.isEmpty()) {
            System.err.printf("%d reference name(s) the parser could not read:%n", malformed.size());
            for (AppearanceKey.Result.Malformed bad : malformed)
                System.err.printf("    %-56s %s%n", bad.name(), bad.reason());
            throw new IllegalStateException(String.format("%d reference name(s) the parser could not read",
                malformed.size()));
        }
        if (!filter.isEmpty())
            subjects = subjects.stream()
                .filter(subject -> filter.contains(subject.key().entityId()) || filter.contains(subject.refStem()))
                .collect(Collectors.toCollection(ArrayList::new));

        IntSummaryStatistics strips = subjects.stream()
            .map(subject -> sidecar.get(subject.refStem()))
            .filter(Objects::nonNull)
            .mapToInt(recorded -> recorded.schedule().frames())
            .summaryStatistics();
        System.out.printf("%s parity sweep (vs vanilla harness): %d subjects, %d..%d frames a strip to %s "
                + "(unresolved harness refs: %d)%n",
            gait.style(), subjects.size(), strips.getCount() == 0 ? 0 : strips.getMin(), strips.getMax(),
            outputDir.toAbsolutePath(), unresolved.size());

        long t0 = System.nanoTime();
        // Parallel across independent per-subject renders, for EntityParitySweep's reason: the
        // context indexes are concurrent, the renderer holds only read-only state, and each render
        // allocates its own engine and buffers.
        List<Row> rows = subjects.parallelStream()
            .map(subject -> renderAndCompare(gait, subject, Optional.ofNullable(sidecar.get(subject.refStem())),
                context, javaRenderer))
            .collect(Collectors.toCollection(ArrayList::new));
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort(SweepReport.byDelta(Row::meanDelta));
        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.subject(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                Integer.toString(r.frames()), Integer.toString(r.worstFrame()),
                SweepReport.delta(r.worstDelta()), SweepReport.delta(r.spread()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH())));
        SweepReport.write(report, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tframes\tworst_frame\tworst_delta\tframe_spread"
            + "\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", report, rows.size(), totalMs);

        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        // The spread is what a still sweep cannot report: a row whose frames all differ alike is a
        // subject the pose moves the same way on both sides and something else is wrong with, while
        // one whose delta climbs with the tick is the pose itself parting company.
        System.out.println("Worst deltas (worst first; spread = worst frame minus best):");
        for (Row r : worst.subList(0, Math.min(20, worst.size())))
            System.out.printf("    %-44s mean %8.2f  worst frame %d at %8.2f  spread %8.2f%n",
                r.subject(), r.meanDelta(), r.worstFrame(), r.worstDelta(), r.spread());
    }

    /**
     * Holds one subject's sidecar entry, its own resolution and its frame files to one another, then
     * renders its strip, diffs it frame by frame against the harness's, and writes the two strips, the
     * contact sheet, the GIF and, where any frame differs, the worst frame's panel - into the subject's
     * folder, cleared first.
     *
     * <p>Any failure marks the row {@code POSITIVE_INFINITY} rather than aborting the sweep, which
     * is what sorts it last and what {@link SweepReport} emits as {@code failed}; each check that
     * fails prints one line naming every value it compared.
     *
     * @param gait the gait the strip is posed at
     * @param subject what to render and which reference to read
     * @param recorded the schedule the harness recorded for the subject's stem, empty where it recorded none
     * @param context the context the render resolves through
     * @param javaRenderer the shared read-only renderer
     * @return the comparison row
     */
    private static @NotNull Row renderAndCompare(@NotNull Gait gait, @NotNull Subject subject,
                                                 @NotNull Optional<Recorded> recorded, @NotNull RendererContext context,
                                                 @NotNull EntityRenderer javaRenderer) {
        String stem = subject.refStem();
        Path dir = gait.outputDir().resolve(stem);
        Path references = gait.referenceDir().resolve(stem);
        try {
            SweepStrip.clear(dir);
            if (recorded.isEmpty()) {
                System.err.printf("       %-44s no sidecar entry: the harness did not record a whole strip for it%n", stem);
                return Row.failed(stem);
            }
            Recorded sidecar = recorded.get();

            // The resolution the render below takes, read once more for the schedule it draws at - a
            // harness that rendered another schedule rendered a tree this one does not resolve.
            String entityId = subject.key().entityId();
            AppearanceOptions appearance = subject.key().appearance();
            String age = appearance.isBaby() ? "baby" : "adult";
            StyleResolution resolved = StyleResolution.of(context.strips(), context.findEntity(entityId), entityId,
                gait.style(), appearance);
            Schedule own = Schedule.of(resolved);
            if (!sidecar.entity().equals(entityId) || !sidecar.age().equals(age) || !sidecar.schedule().equals(own)) {
                System.err.printf("       %-44s schedule parted: sidecar %s %s %s, own resolution %s %s %s%n",
                    stem, sidecar.entity(), sidecar.age(), sidecar.schedule(), entityId, age, own);
                return Row.failed(stem);
            }

            int frames = sidecar.schedule().frames();
            List<String> files = frameFiles(references);
            List<String> expected = IntStream.range(0, frames).mapToObj(SweepStrip::frameName).toList();
            if (!files.equals(expected)) {
                System.err.printf("       %-44s frame files %d %s, sidecar frames %d%n", stem, files.size(), files, frames);
                return Row.failed(stem);
            }
            List<BufferedImage> vanillaFrames = SweepStrip.readFrames(references, frames)
                .orElseThrow(() -> new IOException(String.format("Strip '%s' holds a frame that is not an image",
                    references)));

            // No frame count and no step named, so the render draws the strip its own resolution
            // derives - the path a caller who names nothing takes.
            EntityOptions.Builder options = EntityOptions.builder()
                .entityId(entityId)
                .appearance(appearance)
                .fitMode(EntityOptions.FitMode.GROUP_BOUNDS)
                .style(gait.style())
                .animation(AnimationOptions.builder().startTick(START_TICK).build());
            subject.key().armor().ifPresent(options::armor);
            ImageData rendered = javaRenderer.render(options.build()).image();
            // Each PNG holds premultiplied colour as the harness's do: a frame's held ints are its bytes.
            List<BufferedImage> javaFrames = rendered.getFrames().stream()
                .map(frame -> PixelBuffer.ofStraight(frame.pixels().data(), frame.pixels().width(), frame.pixels().height()).toBufferedImage())
                .toList();
            if (javaFrames.size() != resolved.strip().frames()) {
                System.err.printf("       %-44s rendered %d frames, its own resolution draws %d and the sidecar %d%n",
                    stem, javaFrames.size(), resolved.strip().frames(), frames);
                return Row.failed(stem);
            }

            Files.createDirectories(dir.resolve("java"));
            List<BufferedImage> diffFrames = new ArrayList<>(frames);
            boolean identical = true;
            double total = 0;
            double worstDelta = Double.NEGATIVE_INFINITY;
            double bestDelta = Double.POSITIVE_INFINITY;
            int worstFrame = 0;
            BufferedImage worstVanilla = null;
            BufferedImage worstJava = null;

            for (int frame = 0; frame < frames; frame++) {
                BufferedImage vanilla = vanillaFrames.get(frame);
                BufferedImage java = javaFrames.get(frame);
                int cw = Math.max(vanilla.getWidth(), java.getWidth());
                int ch = Math.max(vanilla.getHeight(), java.getHeight());
                BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanilla, cw, ch);
                BufferedImage javaPadded = ParityMetrics.padToCanvas(java, cw, ch);

                ImageIO.write(java, "PNG", dir.resolve("java").resolve(SweepStrip.frameName(frame)).toFile());
                diffFrames.add(PixelBuffer.wrap(vanillaPadded).diff(PixelBuffer.wrap(javaPadded), DiffType.OVER_WHITE)
                    .toBufferedImage());

                ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
                identical &= SweepPair.identical(vanilla, java, stats);
                double delta = stats.meanDelta();
                total += delta;
                bestDelta = Math.min(bestDelta, delta);
                if (delta > worstDelta) {
                    worstDelta = delta;
                    worstFrame = frame;
                    worstVanilla = vanillaPadded;
                    worstJava = javaPadded;
                }
            }

            SweepStrip.copyFrames(references, dir.resolve("vanilla"), frames);
            // Each frame shows for the game time its step spans, so a strip plays at vanilla's speed.
            int delayMs = sidecar.schedule().ticksPerFrame() * Timeline.MILLIS_PER_TICK;
            SweepStrip.writeGif(dir.resolve("java.gif"), javaFrames, delayMs);
            ImageIO.write(SweepStrip.contactSheet(vanillaFrames, javaFrames, diffFrames, CONTACT_COLUMNS), "PNG",
                dir.resolve("contact_sheet.png").toFile());
            if (!identical)
                ImageIO.write(ParityMetrics.panelDiff(worstVanilla, worstJava,
                        PixelBuffer.wrap(worstVanilla), PixelBuffer.wrap(worstJava)), "PNG",
                    dir.resolve(SweepPair.PANEL).toFile());

            double mean = total / frames;
            int jw = javaFrames.getFirst().getWidth();
            int jh = javaFrames.getFirst().getHeight();
            int vw = vanillaFrames.getFirst().getWidth();
            int vh = vanillaFrames.getFirst().getHeight();
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %-44s mean delta %8.2f  worst frame %d (%.2f)  spread %.2f%s%n",
                stem, mean, worstFrame, worstDelta, worstDelta - bestDelta, dimMismatch);
            return new Row(stem, frames, mean, worstFrame, worstDelta, worstDelta - bestDelta,
                jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-44s FAILED: %s%n", stem, ex);
            return Row.failed(stem);
        }
    }

    /**
     * Reads the sub-tree's schedule sidecar, stopping the sweep before any row where it is absent,
     * unreadable or of another format - such a sub-tree predates the schedule contract, and nothing in
     * it can be judged.
     *
     * @param gait the gait whose sub-tree the sidecar describes
     * @return the schedule each stem was rendered at, keyed by stem
     * @throws IllegalStateException if the sidecar is absent, unreadable or of another format
     */
    private static @NotNull Map<String, Recorded> readSidecar(@NotNull Gait gait) {
        Path sidecar = gait.sidecar();
        Map<String, Recorded> out = new TreeMap<>();
        JsonElement format;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(sidecar, StandardCharsets.UTF_8)).getAsJsonObject();
            format = root.get("format");
            if (format != null && format.getAsInt() == SIDECAR_FORMAT) {
                for (Map.Entry<String, JsonElement> subject : root.getAsJsonObject("subjects").entrySet()) {
                    JsonObject entry = subject.getValue().getAsJsonObject();
                    out.put(subject.getKey(), new Recorded(entry.get("entity").getAsString(), entry.get("age").getAsString(),
                        scheduleOf(entry)));
                }
            }
        } catch (IOException | RuntimeException ex) {
            System.err.printf("Schedule sidecar %s is absent or unreadable: %s%n"
                + "  The posed sub-tree predates the schedule contract - run renderVanillaAllReferences.%n",
                sidecar.toAbsolutePath(), ex);
            throw new IllegalStateException(String.format("Schedule sidecar '%s' is absent or unreadable", sidecar), ex);
        }
        if (format == null || format.getAsInt() != SIDECAR_FORMAT) {
            System.err.printf("Schedule sidecar %s is format %s, and this sweep reads format %d%n"
                + "  Run renderVanillaAllReferences.%n", sidecar.toAbsolutePath(), format, SIDECAR_FORMAT);
            throw new IllegalStateException(String.format("Schedule sidecar '%s' is format '%s'", sidecar, format));
        }
        return out;
    }

    /**
     * One sidecar entry's schedule - the four members and its clocks, absent clocks read as none, each
     * float read back from the text it was written as.
     *
     * @param entry the entry
     * @return the schedule it records
     */
    private static @NotNull Schedule scheduleOf(@NotNull JsonObject entry) {
        Map<String, List<Float>> clocks = new TreeMap<>();
        JsonObject spelled = entry.getAsJsonObject("clocks");
        if (spelled != null) {
            for (Map.Entry<String, JsonElement> clock : spelled.entrySet()) {
                List<Float> values = new ArrayList<>();
                for (JsonElement value : clock.getValue().getAsJsonArray())
                    values.add(Float.parseFloat(value.getAsString()));
                clocks.put(clock.getKey(), values);
            }
        }
        return new Schedule(entry.get("frames").getAsInt(), entry.get("ticks_per_frame").getAsInt(),
            Float.parseFloat(entry.get("walk_speed").getAsString()), Float.parseFloat(entry.get("walk_rate").getAsString()),
            clocks);
    }

    /** Lists every subject directory the harness wrote a strip into, sorted. */
    private static @NotNull List<String> collectReferenceStems(@NotNull Path referenceDir) throws IOException {
        try (Stream<Path> stream = Files.list(referenceDir)) {
            return stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    /** Lists the {@code frame_*.png} names of one strip directory, sorted; none where it is absent. */
    private static @NotNull List<String> frameFiles(@NotNull Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("frame_") && name.endsWith(".png"))
                .sorted()
                .toList();
        }
    }

    /**
     * One parity subject: the strip to compare against, and what it says to render.
     *
     * @param refStem the reference directory name, which also names the output folder
     * @param key the render the name resolved to
     */
    private record Subject(@NotNull String refStem, @NotNull AppearanceKey key) {}

    /**
     * One stem's sidecar entry: what the harness rendered and the schedule it rendered it at.
     *
     * @param entity the entity id the harness rendered
     * @param age the age it rendered, {@code adult} or {@code baby}
     * @param schedule the schedule it rendered the strip at
     */
    private record Recorded(@NotNull String entity, @NotNull String age, @NotNull Schedule schedule) {}

    /**
     * One subject's row in the TSV report.
     *
     * @param subject the reference stem
     * @param frames how many frames the strip carries
     * @param meanDelta the mean per-frame ARGB delta
     * @param worstFrame the index of the frame that differs most
     * @param worstDelta that frame's delta
     * @param spread the worst frame's delta less the best frame's
     * @param javaW the rendered canvas width
     * @param javaH the rendered canvas height
     * @param vanillaW the reference canvas width
     * @param vanillaH the reference canvas height
     */
    private record Row(@NotNull String subject, int frames, double meanDelta, int worstFrame,
                       double worstDelta, double spread, int javaW, int javaH, int vanillaW, int vanillaH) {

        /** A subject whose strip could not be read or rendered, which sorts last. */
        private static @NotNull Row failed(@NotNull String subject) {
            return new Row(subject, 0, Double.POSITIVE_INFINITY, -1,
                Double.POSITIVE_INFINITY, 0, 0, 0, 0, 0);
        }
    }
}
