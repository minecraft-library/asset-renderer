package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.ArmorOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.parity.ParityMetrics;
import lib.minecraft.renderer.parity.ParityPaths;
import lib.minecraft.renderer.parity.SweepReport;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-subject worn-armor parity report comparing the Java pipeline's armored entity renders against
 * the vanilla-reference-harness ground truth in
 * the harness reference tree's {@code armor/}. The 90-entity parity sweep equips
 * nothing and renders no babies, so worn armor - and above all the baby form, which vanilla draws
 * with a <b>separate baby armor model</b> while this pipeline stretches the adult {@code humanoid}
 * sheet over the baby body - has no ground truth anywhere else.
 *
 * <p>The roster pairs each baby subject with the <b>same entity in the same armor as an adult</b>.
 * That adult row is the control: whatever it reports is the armor path in general (texture
 * resolution, shading, silhouette convention), so the baby-model gap is the excess the baby row
 * carries over its adult twin - not the baby row's absolute number.
 *
 * <p>Both sides are <b>alpha-tight-cropped and scaled to a common box</b> before diffing, exactly as
 * {@link TestPlayerParityVanilla} does: the two pipelines fit these ad-hoc subjects to their own
 * canvases, so an un-aligned diff would report framing rather than appearance. The mean delta
 * therefore gauges appearance within the silhouette and includes residual silhouette-edge
 * mismatch - a LOOK gauge, not a byte gate. The separately reported coverage pair is where a
 * genuine silhouette (proportion) difference shows up.
 *
 * <p>Output: one folder per subject under {@code cache/visual/armor-parity-vanilla/} with six files,
 * plus a top-level {@code parity-report.tsv}. {@code vanilla.png} and {@code java.png} are the
 * <b>raw</b> renders, byte-for-byte what each pipeline produced and the only pair here a digest can
 * be taken over. {@code aligned_vanilla.png} and {@code aligned_java.png} are those two rescaled onto
 * the common box; {@code diff.png} and {@code diff_panel.png} are built from the aligned pair, as is
 * the reported delta.
 *
 * <p>Usage: {@code ./gradlew armorParityVanilla}. Run
 * {@code renderVanillaArmorReferences} first if the references are missing.
 */
@UtilityClass
public final class TestArmorParityVanilla {

    /**
     * Output directory for the per-subject sub-folders plus the report file.
     */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/armor-parity-vanilla");

    /**
     * TSV report file path.
     */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /**
     * Source of the harness-produced armored-mob reference PNGs.
     */
    private static final Path VANILLA_DIR = ParityPaths.references("armor");

    /**
     * Square render size, matching the harness {@code refharness.size} default.
     */
    private static final int RENDER_SIZE = 512;

    /**
     * Fraction of the common box the alpha-tight silhouette is scaled to fill before diffing.
     */
    private static final float ALIGN_FILL = 0.94f;

    /**
     * Leather dye applied to the dyed-leather subjects; must match the harness roster's dye.
     */
    private static final int LEATHER_DYE_RGB = 0xB04030;

    /**
     * One armored render. Mirrors the harness roster one-for-one - the reference file name is
     * derived the same way on both sides, so a mismatch surfaces as a missing PNG rather than as a
     * silently wrong comparison.
     *
     * @param entityId the entity to render
     * @param material the armor material worn in all four humanoid slots
     * @param baby whether the wearer is a baby
     */
    private record Subject(@NotNull String entityId, @NotNull ArmorMaterial material, boolean baby) {

        /**
         * Whether this material is dyed, which only leather is.
         */
        private boolean dyed() {
            return material == ArmorMaterial.LEATHER;
        }

        /**
         * The shared file-name stem: {@code minecraft__zombie_iron_baby}.
         */
        private @NotNull String stem() {
            String materialName = material.name().toLowerCase(Locale.ROOT);
            String dye = dyed() ? String.format(Locale.ROOT, "-dye%06x", LEATHER_DYE_RGB) : "";
            return entityId.replace(":", "__") + "_" + materialName + dye + (baby ? "_baby" : "");
        }
    }

    private static final List<Subject> SUBJECTS = List.of(
        new Subject("minecraft:zombie", ArmorMaterial.IRON, false),
        new Subject("minecraft:zombie", ArmorMaterial.IRON, true),
        new Subject("minecraft:zombie", ArmorMaterial.LEATHER, false),
        new Subject("minecraft:zombie", ArmorMaterial.LEATHER, true),
        new Subject("minecraft:piglin", ArmorMaterial.IRON, false),
        new Subject("minecraft:piglin", ArmorMaterial.IRON, true),
        new Subject("minecraft:piglin", ArmorMaterial.LEATHER, true));

    /**
     * Runs the armored-mob parity sweep over the whole roster.
     *
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla armor reference directory missing: %s%n  Run renderVanillaArmorReferences first.%n",
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
        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json missing - run entityModels first");
            return;
        }
        EntityRenderer javaRenderer = new EntityRenderer(PipelineRendererContext.of(result), javaEntities);

        List<Row> rows = new ArrayList<>();
        for (Subject subject : SUBJECTS)
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
        System.out.println("Note: bbox-aligned diff - read each baby row against its adult twin, not in isolation. LOOK at diff_panel.png.");
    }

    /**
     * Renders one subject through the Java pipeline, alpha-aligns it against the vanilla reference,
     * writes the per-subject raw, aligned, diff and panel PNGs, and returns the comparison
     * {@link Row}. Any failure marks the row {@code POSITIVE_INFINITY} rather than
     * aborting the sweep.
     */
    private static @NotNull Row renderAndCompare(@NotNull Subject subject, @NotNull EntityRenderer javaRenderer) {
        String stem = subject.stem();
        Path subjectDir = OUTPUT_DIR.resolve(stem);
        try {
            Files.createDirectories(subjectDir);
            Path vanillaPng = VANILLA_DIR.resolve(stem + ".png");
            BufferedImage vanillaRaw = ImageIO.read(vanillaPng.toFile());
            if (vanillaRaw == null) {
                System.err.printf("       %-44s vanilla PNG unreadable: %s%n", stem, vanillaPng);
                return new Row(stem, Double.POSITIVE_INFINITY, -1, 0, 0);
            }

            ArmorPiece piece = subject.dyed()
                ? ArmorPiece.dyedLeather(0xFF000000 | LEATHER_DYE_RGB)
                : ArmorPiece.of(subject.material());
            EntityOptions options = EntityOptions.builder()
                .entityId(Optional.of(subject.entityId()))
                .appearance(AppearanceOptions.builder()
                    .age(subject.baby() ? Age.BABY : Age.ADULT)
                    .build())
                .armor(ArmorOptions.builder()
                    .helmet(Optional.of(piece))
                    .chestplate(Optional.of(piece))
                    .leggings(Optional.of(piece))
                    .boots(Optional.of(piece))
                    .build())
                .output(OutputOptions.builder()
                    .projection(Projection.VANILLA_ISO)
                    .canvasSize(RENDER_SIZE)
                    .supersample(1)
                    .antiAlias(false)
                    .build())
                .build();
            ImageData java = javaRenderer.render(options);
            BufferedImage javaRaw = java.toBufferedImage();

            // The raw pair goes to disk FIRST and under the plain names, because it is the only output
            // of this sweep that is what a renderer actually produced and can therefore be hashed.
            ImageIO.write(vanillaRaw, "PNG", new File(subjectDir.toFile(), "vanilla.png"));
            ImageIO.write(javaRaw, "PNG", new File(subjectDir.toFile(), "java.png"));

            // Alpha-tight-crop + scale-to-common-box both sides so appearance compares free of the
            // framing gap between the two pipelines' independent canvas fits for these subjects.
            BufferedImage vanillaImg = ParityMetrics.alignToBox(vanillaRaw, RENDER_SIZE, ALIGN_FILL);
            BufferedImage javaImg = ParityMetrics.alignToBox(javaRaw, RENDER_SIZE, ALIGN_FILL);

            ImageIO.write(vanillaImg, "PNG", new File(subjectDir.toFile(), "aligned_vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(subjectDir.toFile(), "aligned_java.png"));
            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaImg);
            PixelBuffer javaPB = PixelBuffer.wrap(javaImg);
            ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
                new File(subjectDir.toFile(), "diff.png"));
            ImageIO.write(ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB), "PNG",
                new File(subjectDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-44s mean delta %6.2f  diff-px %7d  java-cov %.1f%%  vanilla-cov %.1f%%%n",
                stem, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100);
            return new Row(stem, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage());
        } catch (Exception ex) {
            System.err.printf("       %-44s FAILED: %s%n", stem, ex.getMessage());
            return new Row(stem, Double.POSITIVE_INFINITY, -1, 0, 0);
        }
    }

    /**
     * Per-subject row in the TSV report.
     */
    private record Row(@NotNull String subject, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
