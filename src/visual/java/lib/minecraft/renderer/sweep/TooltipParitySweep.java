package lib.minecraft.renderer.sweep;

import api.simplified.mojang.exception.MojangApiException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.bake.gui.TooltipChrome;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-subject tooltip parity report comparing {@link TextRenderer} against the
 * vanilla-reference-harness ground truth in the reference tree's {@code tooltips/}.
 *
 * <p>The harness draws every subject of the tracked tooltip roster through the client's own tooltip
 * call onto the box's whole sprite rect - the client's measurement of the lines plus
 * {@link #CLIENT_MARGIN_MCPX} on every side. This sweep reads the same roster off its own classpath and
 * renders each subject the way a caller would: lore lines over the sprite chrome, the subject's tooltip
 * style named, through a renderer bound to the client's assets.
 *
 * <p><b>Each subject is two rows.</b> The full row renders through {@link TextRenderer}, so it measures
 * the renderer whole. The box-alone row, keyed {@code _chrome} as the harness keys its twin, draws the
 * production chrome for the CLIENT's box - the content size read off the reference - through the same
 * calls {@code TextRenderer} makes, so it measures whether the chrome draws that box whatever width the
 * renderer's font would have given. A mover in a box-alone row is the box; one in the full row alone is
 * the text.
 *
 * <p><b>The cut follows the chrome.</b> The reference is the whole sprite rect and the renderer's canvas
 * is the text padded by what its chrome answers, so the reference is cut by the difference on every
 * side, the padding being the production chrome's own answer rather than a number restated here. A
 * chrome padding by the client's whole margin cuts nothing. What the cut drops of the client's drawing
 * is the {@code clipped_pixels} column. After the cut the two canvases must be equal, and a size that
 * disagrees is the finding rather than something to align away, so its row fails.
 *
 * <p><b>The byte gate.</b> Both sides are read as held - the reference through {@link ImageIO}, which
 * keeps the stored framebuffer bytes, and the render through its own pixel buffer whatever the image's
 * alpha mark - so {@code raw_differing_pixels} is a byte comparison and the over-white columns are what
 * a person reads beside it.
 *
 * <p>Output: one folder per row under {@code cache/visual/tooltip-parity-vanilla/} holding
 * {@code vanilla.png} (the reference region compared), {@code java.png}, {@code diff.png} and
 * {@code diff_panel.png}, plus a top-level {@code parity-report.tsv}.
 *
 * <p>Usage: {@code ./gradlew tooltipParityVanilla}, or {@code -PtooltipId=one_line} for the subjects
 * one roster key names. Run {@code renderVanillaTooltipReferences} first if the references are
 * missing.
 */
@UtilityClass
public final class TooltipParitySweep {

    /** Output directory for the per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/tooltip-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced tooltip reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("tooltips");

    /** The roster the harness draws the references from, a resource beside this class. */
    private static final @NotNull String ROSTER = "tooltip-roster.json";

    /**
     * Minecraft pixels between the content origin and the reference's edge on every side - the margin
     * the client's tooltip box blits its sprites over, which is the reference's own format.
     */
    private static final int CLIENT_MARGIN_MCPX = 12;

    /**
     * One roster subject, named exactly as the harness names it.
     *
     * @param key the roster key, which each of the subject's reference stems begins with, ahead of
     *     the box-alone qualifier and the style token
     * @param style the tooltip style the subject names, empty for vanilla's own box
     * @param options the options the Java side renders it from
     */
    private record Subject(@NotNull String key, @NotNull Optional<ResourceId> style, @NotNull TextOptions options) {}

    /**
     * Reads the roster, which is the harness's own file: each line a text component, drawn as lore
     * over the sprite chrome in the style the subject names.
     *
     * @return the subjects, in roster order
     * @throws IOException if the roster cannot be read
     */
    private static @NotNull List<Subject> roster() throws IOException {
        JsonObject roster;
        try (InputStream in = TooltipParitySweep.class.getResourceAsStream(ROSTER)) {
            if (in == null) {
                throw new IllegalStateException(String.format(
                    "The tooltip roster '%s' is not on the classpath beside '%s'", ROSTER, TooltipParitySweep.class.getName()));
            }
            roster = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        List<Subject> subjects = new ArrayList<>();
        for (JsonElement element : roster.getAsJsonArray("subjects")) {
            JsonObject entry = element.getAsJsonObject();
            Optional<ResourceId> style = entry.has("style")
                ? Optional.of(ResourceId.parse(entry.get("style").getAsString()))
                : Optional.empty();
            ConcurrentList<LineSegment> lines = Concurrent.newList();
            for (JsonElement line : entry.getAsJsonArray("lines"))
                lines.addAll(TextSegment.fromJson(line).toLines());

            subjects.add(new Subject(entry.get("key").getAsString(), style, TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .chromeStyle(ChromeStyle.SPRITE)
                .tooltipStyle(style)
                .lines(lines)
                .build()));
        }
        return subjects;
    }

    /**
     * Runs the tooltip parity sweep over the roster, or over the subjects one roster key names.
     *
     * @param args an optional roster key to scope the run to
     * @throws IOException if the roster cannot be read or the report cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla tooltip reference directory missing: %s%n  Run renderVanillaTooltipReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        RendererContext context = RendererContext.load(result);
        TextRenderer javaRenderer = new TextRenderer(context);

        List<Subject> subjects = roster().stream()
            .filter(subject -> args.length == 0 || subject.key().equals(args[0]))
            .toList();
        if (subjects.isEmpty()) {
            System.err.printf("No tooltip subject keyed '%s'%n", args[0]);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (Subject subject : subjects) {
            rows.add(renderAndCompare(subject, false, javaRenderer, context));
            rows.add(renderAndCompare(subject, true, javaRenderer, context));
        }

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows) {
            lines.add(String.join("\t",
                r.subject(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage()),
                SweepReport.pixels(r.meanDelta(), r.rawDifferingPixels()), Long.toString(r.clippedPixels()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH())));
        }
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\traw_differing_pixels\tclipped_pixels\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", REPORT_FILE, rows.size());
        System.out.println("Note: direct diff at a shared canvas - a size mismatch is the finding, not something to align away.");
    }

    /**
     * Renders one row - the subject whole through the Java pipeline, or its box alone - cuts the
     * vanilla reference to the renderer's canvas, diffs the two, writes the per-row PNGs and returns
     * the comparison {@link Row}.
     *
     * <p>A failed row keeps the facts it has: the reference's size and what the cut drops whenever the
     * reference was read, and the renderer's canvas whenever one was drawn.
     *
     * @param subject the roster subject
     * @param boxAlone whether the row is the subject's box alone rather than the whole tooltip
     * @param javaRenderer the text renderer drawing the whole tooltip
     * @param context the context the box alone resolves its sprites through
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, boolean boxAlone,
                                                 @NotNull TextRenderer javaRenderer, @NotNull RendererContext context) {
        String stem = subject.key() + (boxAlone ? "_chrome" : "")
            + subject.style().map(id -> "~style=" + id.namespace() + "__" + id.name()).orElse("");
        Path subjectDir = OUTPUT_DIR.resolve(stem);
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        long clipped = 0;
        int javaW = 0;
        int javaH = 0;
        int vanillaW = 0;
        int vanillaH = 0;
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = VANILLA_DIR.resolve(stem + ".png");
            BufferedImage reference = ImageIO.read(vanillaPng.toFile());
            if (reference == null) {
                System.err.printf("       %-50s vanilla PNG unreadable: %s%n", stem, vanillaPng);
                return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
            }
            vanillaW = reference.getWidth();
            vanillaH = reference.getHeight();

            // The cut, per side, in Minecraft pixels until the pixel edge: the client's margin less
            // what the renderer's chrome pads the text by.
            int padMcPx = TooltipChrome.of(subject.options().getChromeStyle()).paddingMcPx(subject.options());
            int cut = (CLIENT_MARGIN_MCPX - padMcPx) * scale;
            PixelBuffer whole = PixelBuffer.wrap(reference);
            for (int y = 0; y < vanillaH; y++) {
                for (int x = 0; x < vanillaW; x++) {
                    boolean kept = x >= cut && x < vanillaW - cut && y >= cut && y < vanillaH - cut;
                    if (!kept && ColorMath.alpha(whole.getPixel(x, y)) > 0) clipped++;
                }
            }
            PixelBuffer vanillaPB = whole.crop(cut, cut, vanillaW - 2 * cut, vanillaH - 2 * cut);
            BufferedImage vanillaImg = vanillaPB.toBufferedImage();

            PixelBuffer javaPB = boxAlone
                ? drawBoxAlone(context, subject.options(),
                    vanillaW / scale - 2 * CLIENT_MARGIN_MCPX, vanillaH / scale - 2 * CLIENT_MARGIN_MCPX)
                : javaRenderer.render(subject.options()).image().toPixelBuffer();
            BufferedImage javaImg = javaPB.toBufferedImage();
            javaW = javaImg.getWidth();
            javaH = javaImg.getHeight();

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "java.png"));

            if (vanillaImg.getWidth() != javaW || vanillaImg.getHeight() != javaH) {
                System.err.printf("       %-50s canvas %dx%d against vanilla's %dx%d after the cut - not comparable%n",
                    stem, javaW, javaH, vanillaImg.getWidth(), vanillaImg.getHeight());
                return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
            }

            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
                new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB), "PNG",
                new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-50s mean delta %.2f  diff-px %d  raw-px %d  java-cov %.1f%%  vanilla-cov %.1f%%  clipped %d%n",
                stem, stats.meanDelta(), stats.differingPixels(), stats.rawDifferingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100, clipped);
            return new Row(stem, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(),
                stats.rawDifferingPixels(), clipped, javaW, javaH, vanillaW, vanillaH);
        } catch (Exception ex) {
            System.err.printf("       %-50s FAILED: %s%n", stem, ex.getMessage());
            return Row.failed(stem, clipped, javaW, javaH, vanillaW, vanillaH);
        }
    }

    /**
     * Draws a subject's box alone for the client's content size, through the calls
     * {@link TextRenderer} makes for its own canvas: the chrome the options name contributes its
     * layers for a box framing that content, folded onto a transparent canvas the chrome's padding
     * wider on every side.
     *
     * @param context the context the chrome's sprites resolve through
     * @param options the subject's options, which name the chrome and its tooltip style
     * @param contentWMcPx the client's content width, in Minecraft pixels
     * @param contentHMcPx the client's content height, in Minecraft pixels
     * @return the drawn box
     */
    private static @NotNull PixelBuffer drawBoxAlone(@NotNull RendererContext context, @NotNull TextOptions options,
                                                     int contentWMcPx, int contentHMcPx) {
        TooltipChrome chrome = TooltipChrome.of(options.getChromeStyle());
        int padMcPx = chrome.paddingMcPx(options);
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        int w = (contentWMcPx + 2 * padMcPx) * scale;
        int h = (contentHMcPx + 2 * padMcPx) * scale;

        LayerStack<ImageLayer> stack = new LayerStack<>();
        chrome.contribute(stack, new TooltipChrome.ChromeBox(w, h, scale),
            TooltipChrome.ChromeSprites.resolve(context, options.getTooltipStyle()), options);
        PixelBuffer buffer = PixelBuffer.create(w, h);
        Layers.foldInto(stack, options.getLayerDecorator(), buffer);
        return buffer;
    }

    /**
     * Per-row line in the TSV report.
     *
     * @param subject the row's reference stem
     * @param meanDelta the mean over-white delta, {@code POSITIVE_INFINITY} when the comparison did not happen
     * @param differingPixels the count of pixels whose over-white delta is non-zero
     * @param javaCoverage the fraction of the renderer's pixels with non-zero alpha
     * @param vanillaCoverage the fraction of the compared reference pixels with non-zero alpha
     * @param rawDifferingPixels the count of pixels whose packed ARGB differ
     * @param clippedPixels the count of reference pixels with non-zero alpha the cut drops
     * @param javaW the renderer's canvas width in output px, 0 when nothing was drawn
     * @param javaH the renderer's canvas height in output px, 0 when nothing was drawn
     * @param vanillaW the whole reference's width in output px, before the cut
     * @param vanillaH the whole reference's height in output px, before the cut
     */
    private record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage,
                       double vanillaCoverage, long rawDifferingPixels, long clippedPixels,
                       int javaW, int javaH, int vanillaW, int vanillaH) {

        /**
         * Builds a row whose comparison did not happen, keeping the facts it has.
         *
         * @param subject the row's reference stem
         * @param clippedPixels the count of reference pixels the cut drops, 0 when the reference was not read
         * @param javaW the renderer's canvas width, 0 when nothing was drawn
         * @param javaH the renderer's canvas height, 0 when nothing was drawn
         * @param vanillaW the whole reference's width, 0 when it was not read
         * @param vanillaH the whole reference's height, 0 when it was not read
         * @return the failed row
         */
        static @NotNull Row failed(@NotNull String subject, long clippedPixels, int javaW, int javaH, int vanillaW, int vanillaH) {
            return new Row(subject, Double.POSITIVE_INFINITY, -1, 0, 0, -1, clippedPixels, javaW, javaH, vanillaW, vanillaH);
        }

    }

}
