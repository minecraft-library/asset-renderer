package lib.minecraft.renderer.sweep;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.DiffType;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.store.diff.ParityMetrics;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The plumbing the single-pair sweeps share: acquiring the client, reading a reference directory's
 * subjects, and the files one row writes into its folder beside its table row.
 *
 * <p>A row's folder holds {@code vanilla.png} and {@code java.png}, and {@code diff.png} and
 * {@code diff_panel.png} only where the two differ. Two renders on one canvas whose packed ARGB
 * agrees at every pixel leave a diff and a panel nothing to show, so neither is written, and a pair
 * an earlier run left there is deleted rather than kept beside a row whose two renders agree. The
 * table row is computed the same way either way, so the rule decides files and never a number.
 *
 * <p>{@code vanilla.png} is the reference file's own bytes, copied rather than decoded and encoded
 * again: every reader of it compares decoded pixels, which a copy keeps.
 */
@UtilityClass
final class SweepPair {

    /** The name of a row's reference image. */
    static final @NotNull String VANILLA = "vanilla.png";

    /** The name of a row's render. */
    static final @NotNull String JAVA = "java.png";

    /** The name of a row's over-white diff image. */
    static final @NotNull String DIFF = "diff.png";

    /** The name of a row's six-lens panel. */
    static final @NotNull String PANEL = "diff_panel.png";

    /** The prefix the harness spells a namespaced id's {@code :} as in a reference file name. */
    private static final @NotNull String REFERENCE_PREFIX = "minecraft__";

    /** The namespace every reference file name carries. */
    private static final @NotNull String NAMESPACE = "minecraft:";

    /**
     * Acquires the client a sweep renders through, printing why before rethrowing where it cannot.
     *
     * @param options the client options
     * @return the acquired client assets
     */
    static @NotNull ClientAssets acquire(@NotNull ClientOptions options) {
        try {
            return ClientAcquisition.acquire(options);
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Lists the subjects a reference directory holds a drawing of, each {@code minecraft__<stem>.png}
     * read as {@code minecraft:<stem>}.
     *
     * @param directory the reference directory
     * @return the subjects, sorted
     * @throws IOException if the directory cannot be listed
     */
    static @NotNull TreeSet<String> referenceIds(@NotNull Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            TreeSet<String> ids = new TreeSet<>();
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(REFERENCE_PREFIX) || !name.endsWith(".png")) return;
                ids.add(NAMESPACE + name.substring(REFERENCE_PREFIX.length(), name.length() - ".png".length()));
            });
            return ids;
        }
    }

    /**
     * Resolves the reference file a {@code minecraft:<stem>} subject is drawn in.
     *
     * @param directory the reference directory
     * @param subject the subject, namespaced
     * @return the file {@code minecraft__<stem>.png} in that directory
     */
    static @NotNull Path reference(@NotNull Path directory, @NotNull String subject) {
        return directory.resolve(REFERENCE_PREFIX + subject.substring(NAMESPACE.length()) + ".png");
    }

    /**
     * Writes a row's pair, then compares the two through {@link #compare}.
     *
     * @param directory the row's folder
     * @param reference the reference file
     * @param vanilla the reference as decoded
     * @param java the render
     * @return the comparison over a canvas both fit
     * @throws IOException if a file cannot be written or deleted
     * @see #writeRenders
     */
    static @NotNull ParityMetrics.Stats writePair(@NotNull Path directory, @NotNull Path reference,
                                                  @NotNull BufferedImage vanilla, @NotNull BufferedImage java)
        throws IOException {
        writeRenders(directory, reference, java);
        return compare(directory, vanilla, java);
    }

    /**
     * Writes a row's two renders: the reference file copied as {@code vanilla.png}, and the render as
     * {@code java.png}.
     *
     * @param directory the row's folder
     * @param reference the reference file
     * @param java the render
     * @throws IOException if a file cannot be written
     */
    static void writeRenders(@NotNull Path directory, @NotNull Path reference, @NotNull BufferedImage java)
        throws IOException {
        Files.copy(reference, directory.resolve(VANILLA), StandardCopyOption.REPLACE_EXISTING);
        ImageIO.write(java, "PNG", directory.resolve(JAVA).toFile());
    }

    /**
     * Compares a pair on a canvas both fit, centring the smaller without resampling, and writes the
     * row's {@code diff.png} and {@code diff_panel.png} where the two differ, deleting any an earlier
     * run left where they do not.
     *
     * @param directory the row's folder
     * @param vanilla the reference image
     * @param java the render
     * @return the comparison over the shared canvas
     * @throws IOException if a file cannot be written or deleted
     */
    static @NotNull ParityMetrics.Stats compare(@NotNull Path directory, @NotNull BufferedImage vanilla,
                                                @NotNull BufferedImage java) throws IOException {
        int width = Math.max(vanilla.getWidth(), java.getWidth());
        int height = Math.max(vanilla.getHeight(), java.getHeight());
        BufferedImage vanillaPadded = ParityMetrics.padToCanvas(vanilla, width, height);
        BufferedImage javaPadded = ParityMetrics.padToCanvas(java, width, height);
        ParityMetrics.Stats stats = ParityMetrics.compareImages(vanillaPadded, javaPadded);
        if (identical(vanilla, java, stats)) {
            Files.deleteIfExists(directory.resolve(DIFF));
            Files.deleteIfExists(directory.resolve(PANEL));
            return stats;
        }

        PixelBuffer vanillaPB = PixelBuffer.wrap(vanillaPadded);
        PixelBuffer javaPB = PixelBuffer.wrap(javaPadded);
        ImageIO.write(vanillaPB.diff(javaPB, DiffType.OVER_WHITE).toBufferedImage(), "PNG",
            directory.resolve(DIFF).toFile());
        ImageIO.write(ParityMetrics.panelDiff(vanillaPadded, javaPadded, vanillaPB, javaPB), "PNG",
            directory.resolve(PANEL).toFile());
        return stats;
    }

    /**
     * Answers whether two renders are one image: the same canvas, and the same packed ARGB at every
     * pixel of it.
     *
     * <p>Raw rather than over white, because over white hides the colour under a transparent pixel and
     * lets two translucent pixels collide - a pair it calls equal can still differ in bytes.
     *
     * @param vanilla the reference image
     * @param java the render
     * @param stats the comparison of the two
     * @return whether nothing about the pair differs
     */
    static boolean identical(@NotNull BufferedImage vanilla, @NotNull BufferedImage java,
                             @NotNull ParityMetrics.Stats stats) {
        return vanilla.getWidth() == java.getWidth() && vanilla.getHeight() == java.getHeight()
            && stats.rawDifferingPixels() == 0;
    }

}
