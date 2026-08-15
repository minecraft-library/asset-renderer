package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.parity.ParityMetrics;
import lib.minecraft.renderer.parity.ParityPaths;
import lib.minecraft.renderer.parity.SweepReport;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-subject container-screen parity report comparing {@link MenuRenderer} against the
 * vanilla-reference-harness ground truth in the reference tree's {@code menus/}.
 *
 * <p>The harness draws each screen through the client's own GUI pipeline - vanilla's panel, its two
 * labels, and its slots through vanilla's GUI item atlas - so the reference is the composed screen
 * rather than the panel's texture. That is what this sweep is for and what the shipped-art oracle
 * cannot see: the oracle is byte-exact about chrome and silent about composition, so a row that moves
 * here while the oracle stays green is a composition finding.
 *
 * <p><b>This is a direct diff, not a bbox-aligned one.</b> Both sides render the same panel at the
 * same Minecraft-pixel scale, so the two canvases are equal by construction and a pixel compares
 * against the pixel vanilla drew at that position. The player and armour sweeps rescale both sides
 * and are LOOK gauges for that reason; this one is not, and a canvas that disagrees is itself the
 * finding rather than something to resample away.
 *
 * <p>The roster is the eight menus the harness renders, and each subject's title is its own name on
 * both sides - one string, so a label divergence is a real one rather than two rosters disagreeing.
 * Every subject draws the player's section, because that is the panel the client draws.
 *
 * <p>Output: one folder per subject under {@code cache/visual/menu-parity-vanilla/} holding
 * {@code vanilla.png}, {@code java.png}, {@code diff.png} and {@code diff_panel.png}, plus a
 * top-level {@code parity-report.tsv}.
 *
 * <p>Usage: {@code ./gradlew menuParityVanilla}. Run {@code renderVanillaMenuReferences} first if the
 * references are missing.
 */
@UtilityClass
public final class TestMenuParityVanilla {

    /** Output directory for the per-subject sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/menu-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced menu reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("menus");

    /**
     * One subject, named exactly as the harness names it.
     *
     * @param name the subject's stem, which is also the title both sides draw
     * @param options the options the Java side renders it from
     */
    private record Subject(@NotNull String name, @NotNull MenuOptions options) {}

    /**
     * The roster, which is the harness's own. A chest is the four row counts one sheet composes; the
     * other four are screens the client ships whole.
     */
    private static @NotNull List<Subject> roster() {
        List<Subject> subjects = new ArrayList<>();
        for (int rows : new int[] { 1, 2, 3, 6 })
            subjects.add(subject("chest_" + rows + "row", MenuOptions.Type.CHEST, rows));

        subjects.add(subject("shulker_box", MenuOptions.Type.SHULKER_BOX, 0));
        subjects.add(subject("hopper", MenuOptions.Type.HOPPER, 0));
        subjects.add(subject("dispenser", MenuOptions.Type.DISPENSER, 0));
        subjects.add(subject("crafting_table", MenuOptions.Type.CRAFTING_TABLE, 0));
        return subjects;
    }

    /**
     * Builds one subject. A row count is read by the chest alone, so every other screen is handed the
     * count it carries itself and ignores the argument.
     */
    private static @NotNull Subject subject(@NotNull String name, @NotNull MenuOptions.Type type, int rows) {
        MenuOptions.MenuOptionsBuilder options = MenuOptions.builder()
            .type(type)
            .playerInventory(true)
            .title(name);

        return new Subject(name, (rows > 0 ? options.rows(rows) : options).build());
    }

    /**
     * Runs the menu parity sweep over the roster, or over the one subject named by the first argument.
     *
     * @param args an optional subject name to scope the run to
     * @throws IOException if the report cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla menu reference directory missing: %s%n  Run renderVanillaMenuReferences first.%n",
                VANILLA_DIR.toAbsolutePath());
            return;
        }
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        MenuRenderer javaRenderer = new MenuRenderer(PipelineRendererContext.of(result));

        List<Subject> subjects = roster().stream()
            .filter(subject -> args.length == 0 || subject.name().equals(args[0]))
            .toList();
        if (subjects.isEmpty()) {
            System.err.printf("No menu subject named '%s'%n", args[0]);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (Subject subject : subjects)
            rows.add(renderAndCompare(subject, javaRenderer));

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.subject(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage())));
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", REPORT_FILE, rows.size());
        System.out.println("Note: direct diff at a shared canvas - a size mismatch is the finding, not something to align away.");
    }

    /**
     * Renders one subject through the Java pipeline, diffs it against the vanilla reference at the
     * shared canvas, writes the per-subject PNGs and returns the comparison {@link Row}.
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull MenuRenderer javaRenderer) {
        String name = subject.name();
        Path subjectDir = OUTPUT_DIR.resolve(name);
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = VANILLA_DIR.resolve(name + ".png");
            BufferedImage vanillaImg = ImageIO.read(vanillaPng.toFile());
            if (vanillaImg == null) {
                System.err.printf("       %-15s vanilla PNG unreadable: %s%n", name, vanillaPng);
                return new Row(name, Double.POSITIVE_INFINITY, -1, 0, 0);
            }

            ImageData java = javaRenderer.render(subject.options());
            BufferedImage javaImg = java.toBufferedImage();

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "java.png"));

            if (vanillaImg.getWidth() != javaImg.getWidth() || vanillaImg.getHeight() != javaImg.getHeight()) {
                System.err.printf("       %-15s canvas %dx%d against vanilla's %dx%d - not comparable%n",
                    name, javaImg.getWidth(), javaImg.getHeight(), vanillaImg.getWidth(), vanillaImg.getHeight());
                return new Row(name, Double.POSITIVE_INFINITY, -1, 0, 0);
            }

            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaImg);
            PixelBuffer javaPB = PixelBuffer.wrap(javaImg);
            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
                new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB), "PNG",
                new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-15s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%n",
                name, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100);
            return new Row(name, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage());
        } catch (Exception ex) {
            System.err.printf("       %-15s FAILED: %s%n", name, ex.getMessage());
            return new Row(name, Double.POSITIVE_INFINITY, -1, 0, 0);
        }
    }

    /** Per-subject row in the TSV report. */
    private record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
