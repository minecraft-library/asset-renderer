package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.option.spec.SkinOptions;
import lib.minecraft.renderer.option.spec.TextureOptions;
import lib.minecraft.renderer.parity.ParityMetrics;
import lib.minecraft.renderer.parity.ParityPaths;
import lib.minecraft.renderer.parity.SweepReport;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
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
import java.util.Locale;
import java.util.Optional;

/**
 * Per-scope player parity report comparing the Java pipeline's 3D {@link PlayerRenderer} output against
 * the vanilla-reference-harness ground truth in the reference tree's {@code players/}.
 * The harness renders the vanilla {@link net.minecraft.client.model.player.PlayerModel} (default steve skin)
 * under vanilla's inventory {@code ENTITY_IN_UI} lighting, so its output is the canonical baseline the Java
 * player aims to match - specifically the parity gate for R7 (player lit like a humanoid entity, not like a
 * block icon).
 *
 * <p>Two scopes have a vanilla ground truth: {@link PlayerOptions.Type#FULL FULL} and
 * {@link PlayerOptions.Type#SKULL SKULL} (a bust is a crop of the full body, so it inherits the same
 * lighting). Unlike the block / entity sweeps - where both sides share one pose + fit convention and diff
 * pixel-for-pixel - the Java player uses its own body-part cubes (vanilla-humanoid proportions, but not the
 * exact {@code PlayerModel} geometry), so the two renders differ in silhouette. Both sides are therefore
 * <b>alpha-tight-cropped and scaled to a common box</b> before diffing, isolating the per-face
 * <b>lighting</b> (R7's subject) from framing / geometry differences. The mean-delta number consequently
 * includes some residual silhouette-edge and geometry mismatch and is a LOOK gauge, not a byte gate.
 *
 * <p>Output: one folder per scope under {@code cache/visual/player-parity-vanilla/} with six files,
 * plus a top-level {@code parity-report.tsv}. {@code vanilla.png} and {@code java.png} are the
 * <b>raw</b> renders, byte-for-byte what each pipeline produced and the only pair here a digest can
 * be taken over. {@code aligned_vanilla.png} and {@code aligned_java.png} are those two rescaled onto
 * the common box; {@code diff.png} and {@code diff_panel.png} are built from the aligned pair, as is
 * the reported delta.
 *
 * <p>Usage: {@code ./gradlew playerParityVanilla}. Run
 * {@code renderVanillaPlayerReferences} first if the references are missing.
 */
@UtilityClass
public final class TestPlayerParityVanilla {

    /** Output directory for the per-scope sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/player-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced player reference PNGs. */
    private static final Path VANILLA_DIR = ParityPaths.references("players");

    /** Offline, pack-resolvable default skin texture id (matches the harness's {@code DefaultPlayerSkin} steve). */
    private static final String SKIN_ID = "minecraft:entity/player/wide/steve";

    /** Square render size (matches the harness player reference {@code refharness.size} default). */
    private static final int RENDER_SIZE = 512;

    /** Fraction of the common box the alpha-tight silhouette is scaled to fill before diffing. */
    private static final float ALIGN_FILL = 0.94f;

    /**
     * Runs the player parity sweep across the {@link PlayerOptions.Type#FULL} and
     * {@link PlayerOptions.Type#SKULL} scopes.
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (!Files.isDirectory(VANILLA_DIR)) {
            System.err.printf("Vanilla player reference directory missing: %s%n  Run renderVanillaPlayerReferences first.%n",
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
        PlayerRenderer javaRenderer = new PlayerRenderer(PipelineRendererContext.of(result));

        List<Row> rows = new ArrayList<>();
        for (PlayerOptions.Type scope : List.of(PlayerOptions.Type.FULL, PlayerOptions.Type.SKULL))
            rows.add(renderAndCompare(scope, javaRenderer));

        rows.sort(SweepReport.byDelta(Row::meanDelta));

        List<String> lines = new ArrayList<>(rows.size());
        for (Row r : rows)
            lines.add(String.join("\t",
                r.scope(), SweepReport.delta(r.meanDelta()), SweepReport.status(r.meanDelta()),
                SweepReport.pixels(r.meanDelta(), r.differingPixels()),
                SweepReport.ratio(r.javaCoverage()), SweepReport.ratio(r.vanillaCoverage())));
        SweepReport.write(REPORT_FILE, SweepReport.KEY_COLUMN
            + "\tmean_argb_delta\tstatus\tdiffering_pixels\tjava_coverage\tvanilla_coverage", lines);
        SweepReport.printBuckets(rows.stream().mapToDouble(Row::meanDelta).toArray());
        System.out.printf("Wrote %s (%d rows)%n", REPORT_FILE, rows.size());
        System.out.println("Note: bbox-aligned diff - mean delta gauges lighting + residual geometry, not a byte gate. LOOK at diff_panel.png.");
    }

    /**
     * Renders one scope through the Java pipeline, alpha-aligns it against the vanilla reference, writes the
     * per-scope raw, aligned, diff and panel PNGs, and returns the comparison {@link Row}.
     */
    private static @NotNull Row renderAndCompare(@NotNull PlayerOptions.Type scope, @NotNull PlayerRenderer javaRenderer) {
        String name = scope.name().toLowerCase(Locale.ROOT);
        Path scopeDir = OUTPUT_DIR.resolve(name);
        try {
            Files.createDirectories(scopeDir);
            Path vanillaPng = VANILLA_DIR.resolve(name + ".png");
            BufferedImage vanillaRaw = ImageIO.read(vanillaPng.toFile());
            if (vanillaRaw == null) {
                System.err.printf("       %-8s vanilla PNG unreadable: %s%n", name, vanillaPng);
                return new Row(name, Double.POSITIVE_INFINITY, -1, 0, 0);
            }

            PlayerOptions options = PlayerOptions.builder()
                .type(scope)
                .dimension(PlayerOptions.Dimension.THREE_D)
                .output(OutputOptions.builder()
                    .projection(Projection.VANILLA_ISO)
                    .canvasSize(RENDER_SIZE)
                    .supersample(1)
                    .antiAlias(false)
                    .build())
                .skin(SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(SKIN_ID)).build()).build())
                .build();
            ImageData java = javaRenderer.render(options);
            BufferedImage javaRaw = java.toBufferedImage();

            // The raw pair goes to disk FIRST and under the plain names, because it is the only output
            // of this sweep that is what a renderer actually produced and can therefore be hashed.
            ImageIO.write(vanillaRaw, "PNG", new File(scopeDir.toFile(), "vanilla.png"));
            ImageIO.write(javaRaw, "PNG", new File(scopeDir.toFile(), "java.png"));

            // Alpha-tight-crop + scale-to-common-box both sides so the per-face lighting compares free of
            // the framing / geometry silhouette gap between the Java cubes and vanilla's PlayerModel.
            BufferedImage vanillaImg = ParityMetrics.alignToBox(vanillaRaw, RENDER_SIZE, ALIGN_FILL);
            BufferedImage javaImg = ParityMetrics.alignToBox(javaRaw, RENDER_SIZE, ALIGN_FILL);

            ImageIO.write(vanillaImg, "PNG", new File(scopeDir.toFile(), "aligned_vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(scopeDir.toFile(), "aligned_java.png"));
            PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaImg);
            PixelBuffer javaPB = PixelBuffer.wrap(javaImg);
            BufferedImage diffImg = vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage();
            ImageIO.write(diffImg, "PNG", new File(scopeDir.toFile(), "diff.png"));
            BufferedImage panelImg = ParityMetrics.panelDiff(vanillaImg, javaImg, vanillaPB, javaPB);
            ImageIO.write(panelImg, "PNG", new File(scopeDir.toFile(), "diff_panel.png"));

            ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaImg, javaImg);
            System.out.printf("  %-8s mean delta %.2f  diff-px %d  java-cov %.1f%%  vanilla-cov %.1f%%%n",
                name, stats.meanDelta(), stats.differingPixels(),
                stats.javaCoverage() * 100, stats.vanillaCoverage() * 100);
            return new Row(name, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.vanillaCoverage());
        } catch (Exception ex) {
            System.err.printf("       %-8s FAILED: %s%n", name, ex.getMessage());
            return new Row(name, Double.POSITIVE_INFINITY, -1, 0, 0);
        }
    }

    /** Per-scope row in the TSV report. */
    private record Row(@NotNull String scope, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
