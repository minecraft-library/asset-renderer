package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.DiffType;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.option.spec.SkinOptions;
import lib.minecraft.renderer.option.spec.TextureOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
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
 * the vanilla-reference-harness ground truth in {@code cache/asset-renderer/vanilla/26.1/references/players/}.
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
 * <p>Output: one folder per scope under {@code cache/visual/player-parity-vanilla/} with
 * {@code vanilla.png}, {@code java.png}, {@code diff.png}, {@code diff_panel.png}, plus a top-level
 * {@code parity-report.tsv}.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:playerParityVanilla}. Run
 * {@code :asset-renderer:renderVanillaPlayerReferences} first if the references are missing.
 */
@UtilityClass
public final class TestPlayerParityVanilla {

    /** Output directory for the per-scope sub-folders plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/player-parity-vanilla");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Source of the harness-produced player reference PNGs. */
    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/players");

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
            System.err.printf("Vanilla player reference directory missing: %s%n  Run :asset-renderer:renderVanillaPlayerReferences first.%n",
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

        StringBuilder report = new StringBuilder("scope\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tvanilla_coverage\n");
        for (Row r : rows)
            report.append(String.format(Locale.ROOT, "%s\t%.4f\t%d\t%.4f\t%.4f%n",
                r.scope(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.vanillaCoverage()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows)%n", REPORT_FILE, rows.size());
        System.out.println("Note: bbox-aligned diff - mean delta gauges lighting + residual geometry, not a byte gate. LOOK at diff_panel.png.");
    }

    /**
     * Renders one scope through the Java pipeline, alpha-aligns it against the vanilla reference, writes the
     * per-scope {@code vanilla/java/diff/diff_panel} PNGs, and returns the comparison {@link Row}.
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

            // Alpha-tight-crop + scale-to-common-box both sides so the per-face lighting compares free of
            // the framing / geometry silhouette gap between the Java cubes and vanilla's PlayerModel.
            BufferedImage vanillaImg = alignToBox(vanillaRaw, RENDER_SIZE, ALIGN_FILL);
            BufferedImage javaImg = alignToBox(javaRaw, RENDER_SIZE, ALIGN_FILL);

            ImageIO.write(vanillaImg, "PNG", new File(scopeDir.toFile(), "vanilla.png"));
            ImageIO.write(javaImg, "PNG", new File(scopeDir.toFile(), "java.png"));
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

    /**
     * Alpha-tight-crops {@code src} to its opaque silhouette, scales it (nearest-neighbour, aspect-preserving)
     * to {@link #ALIGN_FILL} of a {@code box × box} canvas, and centres it - so two differently-framed /
     * differently-proportioned renders line up by silhouette for a lighting-focused diff.
     */
    private static @NotNull BufferedImage alignToBox(@NotNull BufferedImage src, int box, float fill) {
        int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                if (ColorMath.alpha(src.getRGB(x, y)) > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX < minX) return new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        BufferedImage cropped = src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);

        float target = box * fill;
        float scale = Math.min(target / cropped.getWidth(), target / cropped.getHeight());
        int sw = Math.max(1, Math.round(cropped.getWidth() * scale));
        int sh = Math.max(1, Math.round(cropped.getHeight() * scale));

        BufferedImage out = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(cropped, (box - sw) / 2, (box - sh) / 2, sw, sh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Per-scope row in the TSV report. */
    private record Row(@NotNull String scope, double meanDelta, long differingPixels, double javaCoverage, double vanillaCoverage) {}

}
