package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.codec.gif.GifImageWriter;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.FrameBlend;
import dev.simplified.image.data.FrameDisposal;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.parity.AppearanceCodec;
import lib.minecraft.renderer.parity.AppearanceKey;
import lib.minecraft.renderer.parity.ParityMetrics;
import lib.minecraft.renderer.parity.ParityPaths;
import lib.minecraft.renderer.parity.SweepReport;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-entity <em>animated</em> parity report: the Java pipeline posed at each tick of one shared
 * schedule, against the harness references the same schedule produced with vanilla's own
 * {@code setupAnim} running.
 *
 * <p>This is the gate on the shipped pose table. Everything else compares the mesh as authored -
 * both sides freeze, so both sides agree by construction and a pose that transcribes
 * {@code setupAnim} plausibly rather than faithfully costs nothing. Here frame {@code N} is the same
 * tick on both sides, so a bone the table sends the wrong way is a difference in the picture.
 *
 * <p><b>The schedule is pinned on both sides and the three constants must match the harness's
 * {@code EntityAnimationSweep}.</b> Frame {@code N} is tick {@code START_TICK + N * TICKS_PER_FRAME};
 * the harness stamps that tick onto the render state's elapsed age and the Java side hands it to
 * {@code PoseKit}, and neither derives it from the other.
 *
 * <p>Output per subject under {@code cache/visual/entity-animation-parity-vanilla/<subject>/}:
 * per-frame {@code java/}, {@code vanilla/} and {@code diff/} PNGs, a {@code contact_sheet.png}
 * carrying all three rows at a glance, {@code java.gif} and {@code vanilla.gif} for the motion, and
 * a {@code diff_panel.png} of the worst frame - the one panel worth opening first. A top-level
 * {@code parity-report.tsv} ranks subjects by mean per-frame ARGB delta.
 *
 * <p>Usage: {@code ./gradlew entityAnimationParityVanilla [-PentityId=minecraft:zombie]}. Run
 * {@code renderVanillaAnimationReferences} first if the reference tree has no {@code animation/}.
 */
@UtilityClass
public final class TestEntityAnimationParityVanilla {

    /** Output directory for the per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-animation-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced animated references, one directory per subject. */
    private static final Path VANILLA_DIR = ParityPaths.references("animation");

    /** Frames per subject. MUST match the harness {@code EntityAnimationSweep.FRAME_COUNT}. */
    private static final int FRAME_COUNT = 8;

    /** Ticks between frames. MUST match the harness {@code EntityAnimationSweep.TICKS_PER_FRAME}. */
    private static final int TICKS_PER_FRAME = 3;

    /** The tick frame 0 samples. MUST match the harness {@code EntityAnimationSweep.START_TICK}. */
    private static final int START_TICK = 0;

    /** Display delay per GIF frame - a watchable speed, decoupled from the tick schedule. */
    private static final int GIF_FRAME_DELAY_MS = 100;

    /** Longest edge (px) of one frame cell in the contact sheet. */
    private static final int CONTACT_CELL = 96;

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
            System.err.printf("Animated reference directory missing: %s%n  Run renderVanillaAnimationReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets assets;
        try {
            assets = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(assets);
        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json missing - run entityModels first");
            return;
        }
        EntityRenderer javaRenderer = new EntityRenderer(context, javaEntities);
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

        System.out.printf("Animated parity sweep (vs vanilla harness): %d subjects x %d frames, ticks %d..%d to %s "
                + "(unresolved harness refs: %d)%n",
            subjects.size(), FRAME_COUNT, tickOf(0), tickOf(FRAME_COUNT - 1),
            OUTPUT_DIR.toAbsolutePath(), unresolved.size());

        long t0 = System.nanoTime();
        // Parallel across independent per-subject renders, for TestEntityParityVanilla's reason: the
        // context indexes are concurrent, the renderer holds only read-only state, and each render
        // allocates its own engine and buffers.
        List<Row> rows = subjects.parallelStream()
            .map(subject -> renderAndCompare(subject, javaRenderer))
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
     * Renders one subject's strip, diffs it frame by frame against the harness's, and writes the
     * per-frame PNGs, the contact sheet, the two GIFs and the worst frame's panel.
     *
     * <p>Any failure marks the row {@code POSITIVE_INFINITY} rather than aborting the sweep, which
     * is what sorts it last and what {@link SweepReport} emits as {@code failed}.
     *
     * @param subject what to render and which reference to read
     * @param javaRenderer the shared read-only renderer
     * @return the comparison row
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull EntityRenderer javaRenderer) {
        String stem = subject.refStem();
        Path dir = OUTPUT_DIR.resolve(stem);
        try {
            List<BufferedImage> vanillaFrames = readVanillaFrames(VANILLA_DIR.resolve(stem));
            if (vanillaFrames == null) {
                System.err.printf("       %-44s incomplete reference strip%n", stem);
                return Row.failed(stem);
            }

            EntityOptions.Builder options = EntityOptions.builder()
                .entityId(Optional.of(subject.key().entityId()))
                .appearance(subject.key().appearance())
                .fitMode(EntityOptions.FitMode.GROUP_BOUNDS)
                .poseMode(EntityOptions.PoseMode.IDLE)
                .animation(AnimationOptions.builder()
                    .startTick(START_TICK)
                    .frameCount(FRAME_COUNT)
                    .ticksPerFrame(TICKS_PER_FRAME)
                    .build());
            subject.key().armor().ifPresent(options::armor);
            ImageData rendered = javaRenderer.render(options.build());
            List<BufferedImage> javaFrames = rendered.getFrames().stream()
                .map(frame -> frame.pixels().toBufferedImage())
                .toList();
            if (javaFrames.size() != FRAME_COUNT) {
                System.err.printf("       %-44s rendered %d frames, schedule asks for %d%n",
                    stem, javaFrames.size(), FRAME_COUNT);
                return Row.failed(stem);
            }

            Files.createDirectories(dir.resolve("java"));
            Files.createDirectories(dir.resolve("vanilla"));
            Files.createDirectories(dir.resolve("diff"));
            List<BufferedImage> diffFrames = new ArrayList<>(FRAME_COUNT);
            double total = 0;
            double worstDelta = Double.NEGATIVE_INFINITY;
            double bestDelta = Double.POSITIVE_INFINITY;
            int worstFrame = 0;
            BufferedImage worstVanilla = null;
            BufferedImage worstJava = null;

            for (int frame = 0; frame < FRAME_COUNT; frame++) {
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

            writeGif(dir.resolve("vanilla.gif"), vanillaFrames);
            writeGif(dir.resolve("java.gif"), javaFrames);
            ImageIO.write(contactSheet(vanillaFrames, javaFrames, diffFrames), "PNG",
                dir.resolve("contact_sheet.png").toFile());
            ImageIO.write(ParityMetrics.panelDiff(worstVanilla, worstJava,
                    PixelBuffer.wrap(worstVanilla), PixelBuffer.wrap(worstJava)), "PNG",
                dir.resolve("diff_panel.png").toFile());

            double mean = total / FRAME_COUNT;
            int jw = javaFrames.getFirst().getWidth();
            int jh = javaFrames.getFirst().getHeight();
            int vw = vanillaFrames.getFirst().getWidth();
            int vh = vanillaFrames.getFirst().getHeight();
            String dimMismatch = (vw == jw && vh == jh) ? "" : String.format(" [%dx%d vs %dx%d]", jw, jh, vw, vh);
            System.out.printf("  %-44s mean delta %8.2f  worst frame %d (%.2f)  spread %.2f%s%n",
                stem, mean, worstFrame, worstDelta, worstDelta - bestDelta, dimMismatch);
            return new Row(stem, FRAME_COUNT, mean, worstFrame, worstDelta, worstDelta - bestDelta,
                jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-44s FAILED: %s%n", stem, ex);
            return Row.failed(stem);
        }
    }

    /** The tick one frame index samples, which is the same arithmetic the harness steps by. */
    private static int tickOf(int frame) {
        return START_TICK + frame * TICKS_PER_FRAME;
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

    /** Reads {@code frame_000.png ... frame_(N-1).png}; answers null if the strip is incomplete. */
    private static @Nullable List<BufferedImage> readVanillaFrames(@NotNull Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        List<BufferedImage> frames = new ArrayList<>(FRAME_COUNT);
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            Path png = dir.resolve(frameName(frame));
            if (!Files.isRegularFile(png)) return null;
            BufferedImage image = ImageIO.read(png.toFile());
            if (image == null) return null;
            frames.add(image);
        }
        return frames;
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
     */
    private static void writeGif(@NotNull Path out, @NotNull List<BufferedImage> frames) throws IOException {
        AnimatedImageData.Builder builder = AnimatedImageData.builder()
            .withWidth(frames.getFirst().getWidth())
            .withHeight(frames.getFirst().getHeight())
            .withLoopCount(0);
        for (BufferedImage frame : frames)
            builder.withFrame(ImageFrame.of(PixelBuffer.wrap(frame), GIF_FRAME_DELAY_MS, 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));

        GifWriteOptions options = GifWriteOptions.builder()
            .withLoopCount(0)
            .isTransparent(true)
            .withAlphaThreshold(8)
            .build();
        Files.write(out, new GifImageWriter().write(builder.build(), options));
    }

    /**
     * Builds a contact sheet: three labelled rows (vanilla / java / diff), one cell per frame, each
     * scaled to fit {@link #CONTACT_CELL} with its aspect kept - an entity canvas is not square, and
     * a squashed cell reads as a pose difference.
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
        int width = leftMargin + FRAME_COUNT * (CONTACT_CELL + gap) + gap;
        int height = 3 * rowH + gap;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(28, 28, 32));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            String[] labels = {"vanilla", "java", "diff"};
            List<List<BufferedImage>> rows = List.of(vanilla, java, diff);
            for (int row = 0; row < rows.size(); row++) {
                int y = gap + row * rowH;
                g.setColor(new Color(220, 220, 230));
                g.drawString(labels[row], 4, y + CONTACT_CELL / 2);
                List<BufferedImage> cells = rows.get(row);
                for (int frame = 0; frame < cells.size(); frame++) {
                    BufferedImage cell = cells.get(frame);
                    float fit = Math.min(CONTACT_CELL / (float) cell.getWidth(),
                        CONTACT_CELL / (float) cell.getHeight());
                    int w = Math.max(1, Math.round(cell.getWidth() * fit));
                    int h = Math.max(1, Math.round(cell.getHeight() * fit));
                    int x = leftMargin + frame * (CONTACT_CELL + gap) + (CONTACT_CELL - w) / 2;
                    g.drawImage(cell, x, y + (CONTACT_CELL - h) / 2, w, h, null);
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
