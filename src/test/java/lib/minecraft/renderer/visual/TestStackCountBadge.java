package lib.minecraft.renderer.visual;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.kit.ItemStackKit;
import lib.minecraft.text.font.MinecraftFont;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders {@link ItemStackKit#drawStackCount} over a solid grey background at
 * representative icon {@link #SIZES sizes} x stack {@link #COUNTS counts} (20 renders per label), so
 * changes to the stack-count badge layout can be pixel-diffed before/after a refactor. This is a
 * <b>functional / visual</b> tool - the diff mode compares two label directories rather than
 * against a fixed ground truth.
 * <p>
 * Invocation modes (the Gradle {@code -Plabel} / {@code -Pdiff} flags are forwarded verbatim as the
 * first program argument):
 * <ul>
 *   <li>{@code stackCountBadge -Plabel=before} - renders every combination to
 *       {@code cache/visual/stack-count-badge/before/}.</li>
 *   <li>{@code stackCountBadge -Plabel=after} - renders every combination to
 *       {@code cache/visual/stack-count-badge/after/}.</li>
 *   <li>{@code stackCountBadge -Pdiff=before,after} - pixel-diffs every filename in
 *       {@code before/} against its twin in {@code after/} and prints a per-file delta summary.</li>
 * </ul>
 * With no flag the render lands under the {@code default/} label directory.
 */
@UtilityClass
public final class TestStackCountBadge {

    /** Root output directory for all labelled renders + diff runs. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/stack-count-badge");

    /** Icon edge lengths (pixels) covered by a render pass; the outer loop over each size x count pair. */
    private static final int[] SIZES = { 16, 32, 64, 128, 256 };

    /** Stack counts covered by a render pass; the inner loop over each size x count pair. */
    private static final int[] COUNTS = { 2, 5, 64, 99 };

    /** Mid-light grey backdrop: white main stands out against grey, dark grey shadow stays visible. */
    private static final int BG_ARGB = 0xFF808080;

    /**
     * Dispatches to either render or diff mode based on {@code args[0]}.
     *
     * @param args {@code diff=<labelA>,<labelB>} runs the pixel diff; any other value (or absence) is treated as a label and renders every {@code (size, count)} pair under that label's directory
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        if (args.length >= 1 && args[0].startsWith("diff=")) {
            String[] labels = args[0].substring("diff=".length()).split(",");
            if (labels.length != 2) {
                System.err.println("diff=<labelA>,<labelB> requires exactly two labels");
                System.exit(1);
            }
            runDiff(labels[0].trim(), labels[1].trim());
            return;
        }

        String label = args.length >= 1 ? args[0] : "default";
        Path labelDir = OUTPUT_DIR.resolve(label);
        Files.createDirectories(labelDir);
        runRender(labelDir);
    }

    /**
     * Renders every {@link #SIZES size} x {@link #COUNTS count} pair under {@code labelDir}, one
     * PNG named {@code s<size>_c<count>.png} per pair.
     *
     * @param labelDir directory the label's PNGs are written into
     * @throws IOException if a PNG cannot be written
     */
    private static void runRender(@NotNull Path labelDir) throws IOException {
        for (int size : SIZES) {
            for (int count : COUNTS) {
                PixelBuffer buffer = PixelBuffer.create(size, size);
                buffer.fill(BG_ARGB);
                ItemStackKit.drawStackCount(buffer, count, MinecraftFont.REGULAR);

                String name = "s%03d_c%02d.png".formatted(size, count);
                Path out = labelDir.resolve(name);
                ImageIO.write(buffer.toBufferedImage(), "PNG", out.toFile());
                System.out.printf("  rendered %s%n", out);
            }
        }
        System.out.println("Done. Outputs in " + labelDir.toAbsolutePath());
    }

    /**
     * Pixel-diffs every {@code (size, count)} file in {@code labelA}'s directory against its twin
     * in {@code labelB}'s, printing per-file and summary delta counts. Per file it reports the
     * differing-pixel count, total pixels, the max per-channel delta, and the differing-pixel
     * percentage; the summary line adds the identical-file count and an overall percentage.
     *
     * @param labelA first label directory (the {@code before} side)
     * @param labelB second label directory (the {@code after} side)
     * @throws IOException if a PNG cannot be read
     */
    private static void runDiff(@NotNull String labelA, @NotNull String labelB) throws IOException {
        Path dirA = OUTPUT_DIR.resolve(labelA);
        Path dirB = OUTPUT_DIR.resolve(labelB);
        if (!Files.isDirectory(dirA) || !Files.isDirectory(dirB)) {
            System.err.println("missing directory: " + (Files.isDirectory(dirA) ? dirB : dirA));
            System.exit(1);
        }

        int totalFiles = 0;
        int identicalFiles = 0;
        long totalDiffPixels = 0;
        long totalPixels = 0;

        System.out.printf("%-24s %10s %10s %8s %8s%n", "file", "diff-px", "total-px", "maxΔ", "% diff");
        System.out.println("-".repeat(72));

        for (int size : SIZES) {
            for (int count : COUNTS) {
                String name = "s%03d_c%02d.png".formatted(size, count);
                Path fileA = dirA.resolve(name);
                Path fileB = dirB.resolve(name);
                if (!Files.exists(fileA) || !Files.exists(fileB)) {
                    System.out.printf("%-24s %s%n", name, "MISSING");
                    continue;
                }
                totalFiles++;

                BufferedImage imgA = ImageIO.read(fileA.toFile());
                BufferedImage imgB = ImageIO.read(fileB.toFile());
                if (imgA.getWidth() != imgB.getWidth() || imgA.getHeight() != imgB.getHeight()) {
                    System.out.printf("%-24s DIM MISMATCH %dx%d vs %dx%d%n", name,
                        imgA.getWidth(), imgA.getHeight(), imgB.getWidth(), imgB.getHeight());
                    continue;
                }
                PixelBuffer bufA = PixelBuffer.wrap(imgA);
                PixelBuffer bufB = PixelBuffer.wrap(imgB);

                long diffPixels = 0;
                int maxDelta = 0;
                long px = (long) bufA.width() * bufA.height();
                for (int y = 0; y < bufA.height(); y++) {
                    for (int x = 0; x < bufA.width(); x++) {
                        int a = bufA.getPixel(x, y);
                        int b = bufB.getPixel(x, y);
                        if (a != b) {
                            diffPixels++;
                            int dA = Math.abs(ColorMath.alpha(a) - ColorMath.alpha(b));
                            int dR = Math.abs(ColorMath.red(a) - ColorMath.red(b));
                            int dG = Math.abs(ColorMath.green(a) - ColorMath.green(b));
                            int dB = Math.abs(ColorMath.blue(a) - ColorMath.blue(b));
                            maxDelta = Math.max(maxDelta, Math.max(dA, Math.max(dR, Math.max(dG, dB))));
                        }
                    }
                }
                totalDiffPixels += diffPixels;
                totalPixels += px;
                if (diffPixels == 0) identicalFiles++;
                double pct = 100.0 * diffPixels / px;
                System.out.printf("%-24s %10d %10d %8d %7.2f%%%n", name, diffPixels, px, maxDelta, pct);
            }
        }

        System.out.println("-".repeat(72));
        double overallPct = totalPixels == 0 ? 0 : 100.0 * totalDiffPixels / totalPixels;
        System.out.printf("SUMMARY: %d/%d identical, %d diff pixels / %d total (%.4f%%)%n",
            identicalFiles, totalFiles, totalDiffPixels, totalPixels, overallPct);
    }

}
