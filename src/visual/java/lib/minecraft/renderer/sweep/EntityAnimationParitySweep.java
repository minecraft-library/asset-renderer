package lib.minecraft.renderer.sweep;

import api.simplified.mojang.exception.MojangApiException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.FrameBlend;
import dev.simplified.image.frame.FrameDisposal;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.image.frame.LoopCount;
import dev.simplified.image.option.GifWriteOptions;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.bake.pose.StyleResolution;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.sweep.EntityScheduleRoster.Schedule;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * <p>Output per subject under {@code cache/visual/entity-animation-parity-vanilla/<subject>/}:
 * per-frame {@code java/}, {@code vanilla/} and {@code diff/} PNGs, a {@code contact_sheet.png}
 * carrying all three rows at a glance, {@code java.gif} and {@code vanilla.gif} playing the strip at
 * its own step, and a {@code diff_panel.png} of the worst frame - the one panel worth opening first.
 * A top-level {@code parity-report.tsv} ranks subjects by mean per-frame ARGB delta.
 *
 * <p>Usage: {@code ./gradlew entityAnimationParityVanilla [-PentityId=minecraft:zombie]}. Run
 * {@code renderVanillaAnimationReferences} first if the reference tree has no {@code idle/}.
 */
@UtilityClass
public final class EntityAnimationParitySweep {

    /**
     * Which gait this invocation measures, which is the whole of what separates the two sweeps.
     *
     * <p>The subjects, the naming, the checks, the diff and every artifact written are the same on
     * both; what the gait decides is the style the Java side poses at, the harness sub-tree it is
     * compared against, and so the schedule each subject takes - the harness produces the two the same
     * way for the same reason. So this is one driver taking a parameter rather than two drivers, and a
     * divergence between the two reports cannot be a divergence in how they were measured.
     *
     * @param style the style id the Java side renders at
     * @param references the harness sub-tree holding the ground truth for it
     * @param output the directory this sweep's own artifacts are written under
     */
    private record Gait(
        @NotNull String style, @NotNull String references, @NotNull String output) {

        /** A subject standing still, which is what the promoted animation gate measures. */
        private static final Gait IDLE =
            new Gait(PoseStyle.IDLE, "idle", "entity-animation-parity-vanilla");

        /** A subject walking at its own ambling pace. */
        private static final Gait WALK =
            new Gait(PoseStyle.STRIDE, "walk", "entity-walk-parity-vanilla");

        /**
         * The gait this invocation was launched for.
         *
         * <p>Read off a property rather than off an argument because the first argument is already
         * the subject filter, and because {@code asset.*} is the namespace that forwards into a
         * {@code JavaExec} fork. Absent is {@link #IDLE}, so every existing invocation means what it
         * meant.
         *
         * @return the selected gait
         */
        private static @NotNull Gait selected() {
            String named = System.getProperty("asset.parity.gait", "idle");
            return switch (named.toLowerCase(java.util.Locale.ROOT)) {
                case "idle" -> IDLE;
                case "walk" -> WALK;
                default -> throw new IllegalArgumentException(
                    "asset.parity.gait: '" + named + "' is not a gait this sweep renders");
            };
        }
    }

    /** The gait this invocation measures. */
    private static final Gait GAIT = Gait.selected();

    /** Output directory for the per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual").resolve(GAIT.output());

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced posed references, one directory per subject. */
    private static final Path VANILLA_DIR = ParityPaths.references(GAIT.references());

    /** The harness's record of the schedule each subject of the sub-tree was rendered at. */
    private static final Path SIDECAR = VANILLA_DIR.resolve("schedule.json");

    /** The tick frame 0 samples, the still. MUST match the harness {@code EntityAnimationSweep.START_TICK}. */
    static final int START_TICK = 0;

    /** Longest edge (px) of one frame cell in the contact sheet. */
    private static final int CONTACT_CELL = 96;

    /** Frame cells on one line of the contact sheet, past which the strip wraps onto the next. */
    private static final int CONTACT_COLUMNS = 16;

    /** The one sidecar format this sweep reads. */
    private static final int SIDECAR_FORMAT = 1;

    /**
     * Runs the animated parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of entity ids or reference stems;
     *     absent renders every subject the harness wrote a strip for that the pipeline also models
     * @throws IOException if the report write fails
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> filter = args.length > 0 ? List.of(args[0].split(",")) : List.of();

        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Posed reference directory missing: %s%n  Run the harness run that writes '%s/' first.%n",
                VANILLA_DIR.toAbsolutePath(), GAIT.references());
            return;
        }
        Map<String, Recorded> sidecar = readSidecar();
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets assets;
        try {
            assets = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        RendererContext context = RendererContext.load(assets);
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
        for (String stem : collectReferenceStems()) {
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
            System.exit(1);
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
            GAIT.style(), subjects.size(), strips.getCount() == 0 ? 0 : strips.getMin(), strips.getMax(),
            OUTPUT_DIR.toAbsolutePath(), unresolved.size());

        long t0 = System.nanoTime();
        // Parallel across independent per-subject renders, for EntityParitySweep's reason: the
        // context indexes are concurrent, the renderer holds only read-only state, and each render
        // allocates its own engine and buffers.
        List<Row> rows = subjects.parallelStream()
            .map(subject -> renderAndCompare(subject, Optional.ofNullable(sidecar.get(subject.refStem())),
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
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tframes\tworst_frame\tworst_delta\tframe_spread"
            + "\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

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
     * renders its strip, diffs it frame by frame against the harness's, and writes the per-frame PNGs,
     * the contact sheet, the two GIFs and the worst frame's panel.
     *
     * <p>Any failure marks the row {@code POSITIVE_INFINITY} rather than aborting the sweep, which
     * is what sorts it last and what {@link SweepReport} emits as {@code failed}; each check that
     * fails prints one line naming every value it compared.
     *
     * @param subject what to render and which reference to read
     * @param recorded the schedule the harness recorded for the subject's stem, empty where it recorded none
     * @param context the context the render resolves through
     * @param javaRenderer the shared read-only renderer
     * @return the comparison row
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull Optional<Recorded> recorded,
                                                 @NotNull RendererContext context, @NotNull EntityRenderer javaRenderer) {
        String stem = subject.refStem();
        Path dir = OUTPUT_DIR.resolve(stem);
        try {
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
                GAIT.style(), appearance);
            Schedule own = Schedule.of(resolved);
            if (!sidecar.entity().equals(entityId) || !sidecar.age().equals(age) || !sidecar.schedule().equals(own)) {
                System.err.printf("       %-44s schedule parted: sidecar %s %s %s, own resolution %s %s %s%n",
                    stem, sidecar.entity(), sidecar.age(), sidecar.schedule(), entityId, age, own);
                return Row.failed(stem);
            }

            int frames = sidecar.schedule().frames();
            List<String> files = frameFiles(VANILLA_DIR.resolve(stem));
            List<String> expected = IntStream.range(0, frames).mapToObj(EntityAnimationParitySweep::frameName).toList();
            if (!files.equals(expected)) {
                System.err.printf("       %-44s frame files %d %s, sidecar frames %d%n", stem, files.size(), files, frames);
                return Row.failed(stem);
            }
            List<BufferedImage> vanillaFrames = readFrames(VANILLA_DIR.resolve(stem), frames);

            // No frame count and no step named, so the render draws the strip its own resolution
            // derives - the path a caller who names nothing takes.
            EntityOptions.Builder options = EntityOptions.builder()
                .entityId(entityId)
                .appearance(appearance)
                .fitMode(EntityOptions.FitMode.GROUP_BOUNDS)
                .style(GAIT.style())
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
            Files.createDirectories(dir.resolve("vanilla"));
            Files.createDirectories(dir.resolve("diff"));
            List<BufferedImage> diffFrames = new ArrayList<>(frames);
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

                ImageIO.write(vanilla, "PNG", dir.resolve("vanilla").resolve(frameName(frame)).toFile());
                ImageIO.write(java, "PNG", dir.resolve("java").resolve(frameName(frame)).toFile());
                PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
                PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
                BufferedImage diff = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
                diffFrames.add(diff);
                ImageIO.write(diff, "PNG", dir.resolve("diff").resolve(frameName(frame)).toFile());

                double delta = ParityMetrics.compareImages(vanillaPadded, javaPadded).meanDelta();
                total += delta;
                bestDelta = Math.min(bestDelta, delta);
                if (delta > worstDelta) {
                    worstDelta = delta;
                    worstFrame = frame;
                    worstVanilla = vanillaPadded;
                    worstJava = javaPadded;
                }
            }

            // Each frame shows for the game time its step spans, so a strip plays at vanilla's speed.
            int delayMs = sidecar.schedule().ticksPerFrame() * Timeline.MILLIS_PER_TICK;
            writeGif(dir.resolve("vanilla.gif"), vanillaFrames, delayMs);
            writeGif(dir.resolve("java.gif"), javaFrames, delayMs);
            ImageIO.write(contactSheet(vanillaFrames, javaFrames, diffFrames), "PNG",
                dir.resolve("contact_sheet.png").toFile());
            ImageIO.write(ParityMetrics.panelDiff(worstVanilla, worstJava,
                    PixelBuffer.wrap(worstVanilla), PixelBuffer.wrap(worstJava)), "PNG",
                dir.resolve("diff_panel.png").toFile());

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
     * @return the schedule each stem was rendered at, keyed by stem
     */
    private static @NotNull Map<String, Recorded> readSidecar() {
        Map<String, Recorded> out = new TreeMap<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(SIDECAR, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement format = root.get("format");
            if (format == null || format.getAsInt() != SIDECAR_FORMAT) {
                System.err.printf("Schedule sidecar %s is format %s, and this sweep reads format %d%n"
                    + "  Run renderVanillaAllReferences.%n", SIDECAR.toAbsolutePath(), format, SIDECAR_FORMAT);
                System.exit(1);
            }
            for (Map.Entry<String, JsonElement> subject : root.getAsJsonObject("subjects").entrySet()) {
                JsonObject entry = subject.getValue().getAsJsonObject();
                out.put(subject.getKey(), new Recorded(entry.get("entity").getAsString(), entry.get("age").getAsString(),
                    scheduleOf(entry)));
            }
        } catch (IOException | RuntimeException ex) {
            System.err.printf("Schedule sidecar %s is absent or unreadable: %s%n"
                + "  The posed sub-tree predates the schedule contract - run renderVanillaAllReferences.%n",
                SIDECAR.toAbsolutePath(), ex);
            System.exit(1);
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
    private static @NotNull List<String> collectReferenceStems() throws IOException {
        try (Stream<Path> stream = Files.list(VANILLA_DIR)) {
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
     * Reads {@code frame_000.png ... frame_(N-1).png}.
     *
     * @param dir the strip directory
     * @param frames how many frames the strip carries
     * @return the frames, in order
     * @throws IOException if a frame cannot be read as an image
     */
    private static @NotNull List<BufferedImage> readFrames(@NotNull Path dir, int frames) throws IOException {
        List<BufferedImage> out = new ArrayList<>(frames);
        for (int frame = 0; frame < frames; frame++) {
            Path png = dir.resolve(frameName(frame));
            BufferedImage image = ImageIO.read(png.toFile());
            if (image == null) throw new IOException(String.format("Frame '%s' is not an image", png));
            out.add(image);
        }
        return out;
    }

    private static @NotNull String frameName(int frame) {
        return String.format("frame_%03d.png", frame);
    }

    /**
     * Builds an animated GIF (infinite loop, transparent background) from the frame list.
     *
     * <p><b>Each frame clears the canvas behind it rather than painting over what was there.</b> A
     * GIF frame carries its own disposal and the default is to leave the previous one standing,
     * which is right for an opaque strip and wrong for every transparent one: the subject is drawn
     * on nothing, so the pixels it does not cover are transparent, and a viewer showing the frame
     * before through them accumulates every pose the subject has held. It reads as smearing rather
     * than as a missing clear, which is why it survives a look at the PNGs.
     *
     * @param out the file to write
     * @param frames the frames, in order
     * @param delayMs how long each frame shows - the game time one step of the strip spans
     * @throws IOException if the write fails
     */
    private static void writeGif(@NotNull Path out, @NotNull List<BufferedImage> frames, int delayMs) throws IOException {
        AnimatedImageData.Builder builder = AnimatedImageData.builder()
            .withWidth(frames.getFirst().getWidth())
            .withHeight(frames.getFirst().getHeight())
            .withLoopCount(LoopCount.Preset.INFINITE);
        for (BufferedImage frame : frames)
            builder.withFrame(ImageFrame.of(PixelBuffer.wrap(frame), delayMs, 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));

        GifWriteOptions options = GifWriteOptions.builder()
            .withLoopCount(LoopCount.Preset.INFINITE)
            .isTransparent(true)
            .withAlphaThreshold(8)
            .build();
        Files.write(out, ImageFactory.toByteArray(builder.build(), ImageFormat.GIF, options));
    }

    /**
     * Builds a contact sheet: three labelled rows (vanilla / java / diff) a line, at most
     * {@link #CONTACT_COLUMNS} cells a line, the strip wrapping onto as many lines as it needs. Each
     * cell is scaled to fit {@link #CONTACT_CELL} with its aspect kept - an entity canvas is not
     * square, and a squashed cell reads as a pose difference.
     *
     * @param vanilla the reference frames
     * @param java the rendered frames
     * @param diff the per-frame diffs
     * @return the sheet
     */
    private static @NotNull BufferedImage contactSheet(
        @NotNull List<BufferedImage> vanilla,
        @NotNull List<BufferedImage> java,
        @NotNull List<BufferedImage> diff
    ) {
        int gap = 2;
        int leftMargin = 64;
        int rowH = CONTACT_CELL + gap;
        int cells = vanilla.size();
        int columns = Math.min(cells, CONTACT_COLUMNS);
        int lines = (cells + CONTACT_COLUMNS - 1) / CONTACT_COLUMNS;
        String[] labels = {"vanilla", "java", "diff"};
        List<List<BufferedImage>> rows = List.of(vanilla, java, diff);
        int width = leftMargin + columns * (CONTACT_CELL + gap) + gap;
        int height = lines * rows.size() * rowH + gap;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(28, 28, 32));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int line = 0; line < lines; line++) {
                for (int row = 0; row < rows.size(); row++) {
                    int y = gap + (line * rows.size() + row) * rowH;
                    g.setColor(new Color(220, 220, 230));
                    g.drawString(labels[row], 4, y + CONTACT_CELL / 2);
                    List<BufferedImage> strip = rows.get(row);
                    for (int column = 0; column < CONTACT_COLUMNS; column++) {
                        int frame = line * CONTACT_COLUMNS + column;
                        if (frame >= strip.size()) break;
                        BufferedImage cell = strip.get(frame);
                        float fit = Math.min(CONTACT_CELL / (float) cell.getWidth(),
                            CONTACT_CELL / (float) cell.getHeight());
                        int w = Math.max(1, Math.round(cell.getWidth() * fit));
                        int h = Math.max(1, Math.round(cell.getHeight() * fit));
                        int x = leftMargin + column * (CONTACT_CELL + gap) + (CONTACT_CELL - w) / 2;
                        g.drawImage(cell, x, y + (CONTACT_CELL - h) / 2, w, h, null);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return sheet;
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
