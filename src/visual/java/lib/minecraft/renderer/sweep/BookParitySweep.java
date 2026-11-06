package lib.minecraft.renderer.sweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import lib.minecraft.text.TextSegment;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-page book parity report comparing {@link TextRenderer}'s {@link TextOptions.Style#BOOK} style against
 * the vanilla-reference-harness ground truth in the reference tree's {@code books/}.
 *
 * <p>The harness draws every page of the tracked book roster through the client's own book screen - the
 * book's background, then the page's text with the page-number line left out - onto the screen the book is
 * laid out on. This sweep reads the same roster off its own classpath and renders each page the way a caller
 * would: the page as one text component on a {@link TextOptions.Style#BOOK} request, through a renderer bound
 * to the client's assets. The subject naming no page is the book's background alone on both sides - the
 * client's frame without its text visit, and the renderer's book over the empty page - so a mover there is
 * the book's texture, and one in a page alone is its text.
 *
 * <p><b>Both sides end at their first pixel.</b> The reference is the whole screen, the two rows above the
 * book and the clear margin round the paper with it, so the sweep takes it to the bounds of every pixel the
 * client drew; the renderer's canvas ends at the first pixel the book's texture or its text draws. The two
 * must be equal, and a size that disagrees is the finding rather than something to align away, so its row
 * fails.
 *
 * <p><b>The byte gate.</b> Both sides are a PNG's bytes, read straight: the reference is the client
 * framebuffer's premultiplied ints as {@link ImageIO} decodes the harness's file, and the render is its
 * canvas's held premultiplied ints taken as those bytes through {@link PixelBuffer#ofStraight(int[], int, int)}.
 * So {@code java.png} is written as the harness writes the reference, the two are compared premultiplied with
 * premultiplied, and {@code raw_differing_pixels} is a byte comparison.
 *
 * <p>Vanilla only: the harness draws every page over vanilla's own resources, because no pack in the workspace
 * changes the book's texture. hypixel-skyblock, the one pack a sweep stores a table over, ships no
 * {@code textures/gui/book.png}; defrosted ships one whose every texel equals vanilla's, and no pack sweep takes
 * it because it redraws the letters; the two Catharsis containers are packs the client cannot read; and
 * Hypixel+ ships nothing the book screen reads - no {@code textures/gui} file, font or shader. On the roster's
 * pages, which hold only printable ASCII and the section sign and so none of the characters hypixel-skyblock's
 * font draws as icons, the pack's one change that reaches them is the page text's colour, through its text
 * palette, which {@code TextRendererTest$Book.theContextsTextPaletteReachesThePage} holds and
 * {@link TooltipParitySweep}'s hypixel-skyblock configuration measures through the same text kit, so no pack
 * table of the book is stored.
 *
 * <p>Output: one folder per page under {@code cache/visual/book-parity-vanilla/} holding {@code vanilla.png}
 * (the reference taken to its first pixel) and {@code java.png}, and {@code diff.png} and
 * {@code diff_panel.png} where the two differ, plus a top-level {@code parity-report.tsv}.
 *
 * <p>Usage: {@code ./gradlew bookParityVanilla} after {@code renderVanillaBookReferences}, taking
 * {@code -PbookId=one_line} for the page one roster key names.
 */
@UtilityClass
public final class BookParitySweep {

    /** Output directory for the per-page sub-folders plus the report file. */
    private static final @NotNull Path OUTPUT_DIR = Path.of("cache/visual/book-parity-vanilla");

    /** TSV report file path. */
    private static final @NotNull Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced book reference PNGs. */
    private static final @NotNull Path VANILLA_DIR = ParityPaths.references("books");

    /** The roster the harness draws the references from, a resource beside this class. */
    private static final @NotNull String ROSTER = "book-roster.json";

    /** The roster format this reader reads. */
    private static final int FORMAT = 1;

    /**
     * One roster page, named exactly as the harness names it.
     *
     * @param key the key the roster lists the page under - the stem of the client's drawing of it, and the
     *     row's key
     * @param options the book request the Java side renders it from, holding the page as one text component,
     *     or the empty page for the subject naming none
     */
    record Subject(@NotNull String key, @NotNull TextOptions options) {}

    /**
     * Reads the roster off the classpath, which is the harness's own file.
     *
     * @return the roster
     * @throws IOException if the roster cannot be read
     * @throws IllegalStateException if the roster is not on the classpath beside this class
     */
    static @NotNull JsonObject roster() throws IOException {
        try (InputStream in = BookParitySweep.class.getResourceAsStream(ROSTER)) {
            if (in == null) {
                throw new IllegalStateException(String.format(
                    "The book roster '%s' is not on the classpath beside '%s'", ROSTER, BookParitySweep.class.getName()));
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * Reads the roster's pages, each one book request holding the page as the one text component the
     * client's book holds - its section-sign codes still in its text, for the request's wrap to read - and the
     * subject naming no page the request's own empty page, the book's texture alone.
     *
     * @param roster the roster
     * @return the subjects, in roster order
     * @throws IllegalStateException if the roster is of another format
     */
    static @NotNull List<Subject> subjects(@NotNull JsonObject roster) {
        JsonElement format = roster.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The book roster is format '%s', and this sweep reads format '%s'", format, FORMAT));
        }

        List<Subject> subjects = new ArrayList<>();
        for (JsonElement element : roster.getAsJsonArray("subjects")) {
            JsonObject entry = element.getAsJsonObject();
            TextOptions.Builder options = TextOptions.builder().style(TextOptions.Style.BOOK);
            if (entry.has("page")) options.page(TextSegment.fromJson(entry.get("page")));
            subjects.add(new Subject(entry.get("key").getAsString(), options.build()));
        }
        return subjects;
    }

    /**
     * Runs the book parity sweep over a context of its own, loaded over vanilla's own client options.
     *
     * @param args what {@link #run} reads
     * @throws IOException if the roster cannot be read or the report cannot be written
     * @throws IllegalStateException if the roster is not on the classpath beside this class or is of another
     *     format
     */
    public static void main(String @NotNull [] args) throws IOException {
        run(RendererContext.load(SweepPair.acquire(ClientOptions.defaults())), args);
    }

    /**
     * Runs the book parity sweep over the roster, or over the one page named by the first argument.
     *
     * @param context the context every render resolves through, loaded over vanilla's own client options
     * @param args an optional roster key to scope the run to
     * @throws IOException if the roster cannot be read or the report cannot be written
     * @throws IllegalStateException if the roster is not on the classpath beside this class or is of another
     *     format
     */
    static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla book reference directory missing: %s%n  Run renderVanillaBookReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);
        TextRenderer javaRenderer = new TextRenderer(context);

        List<Subject> subjects = subjects(roster()).stream()
            .filter(subject -> args.length == 0 || subject.key().equals(args[0]))
            .toList();
        if (subjects.isEmpty()) {
            System.err.printf("No book subject keyed '%s'%n", args.length == 0 ? "" : args[0]);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (Subject subject : subjects)
            rows.add(renderAndCompare(subject, javaRenderer, VANILLA_DIR, OUTPUT_DIR));

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows) {
            lines.add(String.join("\t",
                r.subject(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage()),
                SweepReport.pixels(r.meanDelta(), r.rawDifferingPixels()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH()), r.standIns()));
        }
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\traw_differing_pixels\tjava_w\tjava_h\tvanilla_w\tvanilla_h\tstand_ins", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", REPORT_FILE, rows.size());
        System.out.println("Note: direct diff at a shared canvas - a size mismatch is the finding, not something to align away.");
    }

    /**
     * Renders one page through the Java pipeline, takes its reference to its first pixel, diffs the two,
     * writes the page's PNGs and returns the comparison {@link Row}.
     *
     * <p>A failed row keeps the facts it has: the reference's first-pixel bounds whenever the reference was
     * read, and the renderer's canvas and the stand-ins it drew whenever one was drawn.
     *
     * @param subject the roster page
     * @param javaRenderer the text renderer drawing the page
     * @param references the directory the harness drew the book references into
     * @param output the directory the per-page folders are written into
     * @return the comparison row, its delta {@code POSITIVE_INFINITY} on failure
     */
    static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull TextRenderer javaRenderer,
                                         @NotNull Path references, @NotNull Path output) {
        String key = subject.key();
        Path subjectDir = output.resolve(key);
        int javaW = 0;
        int javaH = 0;
        int vanillaW = 0;
        int vanillaH = 0;
        String standIns = "";
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = references.resolve(key + ".png");
            BufferedImage reference = ImageIO.read(vanillaPng.toFile());
            if (reference == null) {
                System.err.printf("       %-20s vanilla PNG unreadable: %s%n", key, vanillaPng);
                return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
            }

            // The reference is the whole screen the book is laid out on; the region compared is every
            // pixel the client drew.
            PixelBuffer whole = PixelBuffer.wrap(reference);
            Rectangle drawn = whole.opaqueBounds();
            vanillaW = drawn.width;
            vanillaH = drawn.height;
            BufferedImage vanillaImg = whole.crop(drawn.x, drawn.y, drawn.width, drawn.height).toBufferedImage();

            RenderResult result = javaRenderer.render(subject.options());
            PixelBuffer held = result.image().toPixelBuffer();
            standIns = SweepReport.standIns(result.substitutions());
            // The PNG holds premultiplied colour as the harness's does: the render's held ints are its bytes.
            BufferedImage javaImg = PixelBuffer.ofStraight(held.data(), held.width(), held.height()).toBufferedImage();
            javaW = javaImg.getWidth();
            javaH = javaImg.getHeight();

            ImageIO.write(vanillaImg, "PNG", subjectDir.resolve(SweepPair.VANILLA).toFile());
            ImageIO.write(javaImg, "PNG", subjectDir.resolve(SweepPair.JAVA).toFile());

            if (vanillaW != javaW || vanillaH != javaH) {
                System.err.printf("       %-20s canvas %dx%d against the client's %dx%d to its first pixel - not comparable%n",
                    key, javaW, javaH, vanillaW, vanillaH);
                return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
            }

            ParityMetrics.Stats stats = SweepPair.compare(subjectDir, vanillaImg, javaImg);
            System.out.printf("  %-20s mean delta %.2f  diff-px %d  raw-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%n",
                key, stats.meanDelta(), stats.differingPixels(), stats.rawDifferingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100);
            return new Row(key, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(),
                stats.rawDifferingPixels(), javaW, javaH, vanillaW, vanillaH, standIns);
        } catch (Exception ex) {
            System.err.printf("       %-20s FAILED: %s%n", key, ex.getMessage());
            return Row.failed(key, javaW, javaH, vanillaW, vanillaH, standIns);
        }
    }

    /**
     * Per-row line in the TSV report.
     *
     * @param subject the row's key, the roster key of its page
     * @param meanDelta the mean over-white delta, {@code POSITIVE_INFINITY} when the comparison did not happen
     * @param differingPixels the count of pixels whose over-white delta is non-zero
     * @param javaCoverage the fraction of the renderer's pixels with non-zero alpha
     * @param vanillaCoverage the fraction of the compared reference pixels with non-zero alpha
     * @param rawDifferingPixels the count of pixels whose packed ARGB differ
     * @param javaW the renderer's canvas width in output px, 0 when nothing was drawn
     * @param javaH the renderer's canvas height in output px, 0 when nothing was drawn
     * @param vanillaW the reference's first-pixel bounds width in output px, the region compared, 0 when
     *     it was not read
     * @param vanillaH the reference's first-pixel bounds height in output px, the region compared, 0 when
     *     it was not read
     * @param standIns the stand-ins the render drew, each its kind, state and id, then the item that named
     *     it where there is one, joined by {@code "; "}; empty where it drew none or nothing was drawn
     */
    record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage,
               double vanillaCoverage, long rawDifferingPixels,
               int javaW, int javaH, int vanillaW, int vanillaH, @NotNull String standIns) {

        /**
         * Builds a row whose comparison did not happen, keeping the facts it has.
         *
         * @param subject the row's key, the roster key of its page
         * @param javaW the renderer's canvas width, 0 when nothing was drawn
         * @param javaH the renderer's canvas height, 0 when nothing was drawn
         * @param vanillaW the reference's first-pixel bounds width, 0 when it was not read
         * @param vanillaH the reference's first-pixel bounds height, 0 when it was not read
         * @param standIns the stand-ins the render drew, empty where it drew none or nothing was drawn
         * @return the failed row
         */
        static @NotNull Row failed(@NotNull String subject, int javaW, int javaH, int vanillaW, int vanillaH,
                                   @NotNull String standIns) {
            return new Row(subject, Double.POSITIVE_INFINITY, -1, 0, 0, -1, javaW, javaH, vanillaW, vanillaH, standIns);
        }

    }

}
