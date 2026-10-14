package lib.minecraft.renderer.sweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-stack parity report for the block items a resource pack shadows, comparing the renderer's
 * inventory icon ({@link ItemRenderer} at {@link ItemOptions.Type#GUI_ICON GUI_ICON}) against the
 * client's slot drawing of the same stack with the same packs loaded.
 * <p>
 * The subjects are {@link PackIconRoster}'s, and so are the packs: this side stacks them over the
 * client in the roster's order, and the harness's pack-icon run draws each subject's stack through the
 * client's own GUI item path, its components decoded by the client's codec, into the roster's folder as
 * {@code <key>.png}. A subject whose stack chooses nothing is drawn here with no stack, which renders as
 * no stack does.
 * <p>
 * Output is one folder per subject under {@code cache/visual/pack-icon-parity-vanilla/}, each holding
 * {@code vanilla.png}, {@code java.png}, {@code diff.png} and {@code diff_panel.png}, and a top-level
 * {@code parity-report.tsv} ranking the subjects by mean ARGB delta.
 * <p>
 * Usage: {@code ./gradlew packIconParityVanilla [-PitemId=minecraft:oak_log]}, after {@code
 * renderVanillaPackIcons}.
 */
@UtilityClass
public final class PackIconParity {

    /** Output directory for the per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/pack-icon-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Square render size, the harness's {@code refharness.size}. */
    private static final int RENDER_SIZE = 512;

    /**
     * Runs the parity sweep.
     *
     * @param args {@code args[0]} optional comma-separated list of item ids whose subjects alone are
     *     drawn; every subject of the roster when absent
     * @throws IOException if the roster cannot be read or the report cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Set<String> itemFilter = args.length > 0 && !args[0].isBlank() ? Set.of(args[0].split(",")) : Set.of();
        if (!Files.isRegularFile(PackIconRoster.ROSTER)) {
            System.err.printf("No pack-icon roster at %s%n  Run renderVanillaPackIcons first.%n", PackIconRoster.ROSTER.toAbsolutePath());
            System.exit(1);
        }

        JsonObject roster = JsonParser.parseString(Files.readString(PackIconRoster.ROSTER)).getAsJsonObject();
        Path directory = Path.of(roster.get("pack_directory").getAsString());
        List<File> packs = new ArrayList<>();
        roster.getAsJsonArray("packs").forEach(name -> packs.add(directory.resolve(name.getAsString()).toFile()));
        RendererContext context = RendererContext.load(ClientAcquisition.acquire(
            ClientOptions.builder().texturePacks(Concurrent.adoptList(packs)).build()));
        ItemRenderer renderer = new ItemRenderer(context);
        Files.createDirectories(OUTPUT_DIR);

        JsonArray subjects = roster.getAsJsonArray("subjects");
        List<Row> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (JsonElement element : subjects) {
            JsonObject subject = element.getAsJsonObject();
            String item = subject.get("item").getAsString();
            if (!itemFilter.isEmpty() && !itemFilter.contains(item)) continue;
            rows.add(renderAndCompare(subject, renderer));
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.key(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage()),
                Integer.toString(r.javaW()), Integer.toString(r.javaH()),
                Integer.toString(r.vanillaW()), Integer.toString(r.vanillaH())));
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage"
            + "\tjava_w\tjava_h\tvanilla_w\tvanilla_h", lines);
        System.out.printf("Pack-icon parity over %s: wrote %s (%d rows, %d ms total)%n",
            roster.get("packs"), REPORT_FILE, rows.size(), totalMs);

        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        List<Row> worst = rows.stream()
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Worst deltas (worst first):");
        for (Row r : worst.subList(0, Math.min(15, worst.size())))
            System.out.printf("    %-60s mean delta %.2f%n", r.key(), r.meanDelta());
    }

    /**
     * Draws one subject's stack, diffs it against the client's drawing, writes the per-subject
     * {@code vanilla/java/diff/diff_panel} PNGs and returns the row. A missing or unreadable reference,
     * or a render that throws, is a {@code POSITIVE_INFINITY} row rather than the end of the sweep.
     */
    private static @NotNull Row renderAndCompare(@NotNull JsonObject subject, @NotNull ItemRenderer renderer) {
        String key = subject.get("key").getAsString();
        String item = subject.get("item").getAsString();
        Path subjectDir = OUTPUT_DIR.resolve(key.replace('=', '_'));
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = PackIconRoster.HOME.resolve(key + ".png");
            BufferedImage vanillaImg = Files.isRegularFile(vanillaPng) ? ImageIO.read(vanillaPng.toFile()) : null;
            if (vanillaImg == null) {
                System.err.printf("       %-60s no client drawing at %s%n", key, vanillaPng);
                return new Row(key, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
            }
            int vw = vanillaImg.getWidth();
            int vh = vanillaImg.getHeight();

            ItemOptions.Builder options = ItemOptions.builder()
                .itemId(item)
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(RENDER_SIZE).build());
            if (!subject.getAsJsonArray("models").isEmpty())
                options.context(ItemContext.ofStack(LeafStacks.itemStack(item, NbtFactory.fromSnbt(subject.get("components").getAsString()))));
            ImageData java = renderer.render(options.build()).image();
            BufferedImage javaImg = java.toBufferedImage();
            int jw = javaImg.getWidth();
            int jh = javaImg.getHeight();

            int cw = Math.max(vw, jw);
            int ch = Math.max(vh, jh);
            BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanillaImg, cw, ch);
            BufferedImage javaPadded = ParityMetrics.padToCanvas(javaImg, cw, ch);

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "java.png"));
            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
            PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG", new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB), "PNG", new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
            return new Row(key, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage(), jw, jh, vw, vh);
        } catch (Exception ex) {
            System.err.printf("       %-60s FAILED: %s%n", key, ex.getMessage());
            return new Row(key, Double.POSITIVE_INFINITY, -1, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Per-subject row in the TSV report. */
    private record Row(@NotNull String key, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage, int javaW, int javaH, int vanillaW, int vanillaH) {}

}
