package lib.minecraft.renderer.visual;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The one shape the six {@code parity-report.tsv} writers share.
 *
 * <p>They had six shapes between them: the key column spelled five ways, the delta second in five
 * of them and third in the sixth, {@code Locale.ROOT} on two writers of six, three bucket-label
 * spellings across the three that printed buckets at all, and a failure carried as
 * {@code POSITIVE_INFINITY} beside {@code differing_pixels = -1}. Each divergence was correct in
 * isolation and none was decided; this class is where the decisions live.
 *
 * <p><b>LF, and by construction.</b> Every writer built its rows with {@code String.format("%n")}
 * against a literal {@code \n} header, so on Windows each table was one LF header line followed by
 * CRLF rows - measured on all six, CR count exactly lines minus one. Nothing here emits
 * {@code %n} or {@code System.lineSeparator()}, and {@link #write} is the one place a table becomes
 * bytes.
 *
 * <p><b>A failed subject is a status, not a magic value</b> (I-27). A crash is not a bad render: it
 * fails every bucket test identically, and a sum that admits {@code Infinity} is {@code Infinity}.
 * The two columns that carried the magic - the delta and the pixel count - are emitted <b>empty</b>
 * on a failed row, and every other column keeps its real value, because those are facts about the
 * subject rather than results of a comparison that did not happen. {@code vanilla_present=false} on
 * a glint row is the explanation for its failure and blanking it would delete the reason.
 *
 * <p>{@code POSITIVE_INFINITY} survives <b>in memory</b> as each sweep's failure marker, and that is
 * deliberate: the reports rank by delta ascending, so it is what sorts a failure last. What I-27 is
 * about is the file, which is the interface.
 */
public final class SweepReport {

    /** The key column, spelled the same in all six so one reader joins them without a table. */
    public static final @NotNull String KEY_COLUMN = "subject";

    /** A subject that was compared. */
    public static final @NotNull String STATUS_OK = "ok";

    /** A subject whose comparison did not happen - a missing reference, an unreadable PNG, a throw. */
    public static final @NotNull String STATUS_FAILED = "failed";

    /** Cumulative, not histogram bins: each count includes every lower one. */
    private static final double[] BUCKET_EDGES = {0.25, 0.50, 0.75, 1.00};

    private SweepReport() {}

    /**
     * Returns a row's status token.
     *
     * @param meanDelta the row's mean ARGB delta, non-finite when its comparison did not happen
     * @return {@link #STATUS_OK} or {@link #STATUS_FAILED}
     */
    public static @NotNull String status(double meanDelta) {
        return failed(meanDelta) ? STATUS_FAILED : STATUS_OK;
    }

    /**
     * Returns the delta cell: four places under {@code Locale.ROOT}, or empty on a failed row.
     *
     * @param meanDelta the row's mean ARGB delta
     * @return the cell text
     */
    public static @NotNull String delta(double meanDelta) {
        return failed(meanDelta) ? "" : String.format(Locale.ROOT, "%.4f", meanDelta);
    }

    /**
     * Returns a coverage or ratio cell: four places under {@code Locale.ROOT}.
     *
     * <p>Not blanked on a failed row, because a coverage of zero is what the row holds rather than a
     * value standing in for something else.
     *
     * @param value the ratio
     * @return the cell text
     */
    public static @NotNull String ratio(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Returns the differing-pixel cell, or empty on a failed row.
     *
     * <p>The second half of the retired sentinel: it read {@code -1} where no comparison had
     * happened, which is the out-of-band magic value the status column replaces.
     *
     * @param meanDelta the row's mean ARGB delta, which is what says whether it failed
     * @param differingPixels the pixel count
     * @return the cell text
     */
    public static @NotNull String pixels(double meanDelta, long differingPixels) {
        return failed(meanDelta) ? "" : Long.toString(differingPixels);
    }

    /**
     * Writes a table: the header, then one row per line, LF throughout and one trailing newline.
     *
     * @param file the table's path
     * @param header the tab-separated header line, without its terminator
     * @param rows the tab-separated row lines, without their terminators
     * @throws IOException if the file cannot be written
     */
    public static void write(@NotNull Path file, @NotNull String header, @NotNull List<String> rows)
        throws IOException {
        StringBuilder out = new StringBuilder(header).append('\n');
        for (String row : rows) out.append(row).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Prints the bucket line every sweep prints, in the one spelling.
     *
     * <p>Cumulative and failure-excluding, matching {@code sweep.buckets} exactly - a sweep whose
     * console line disagreed with the toolkit's own answer for the same table would be worse than
     * printing nothing.
     *
     * @param deltas every row's mean ARGB delta, non-finite for a failed row
     */
    public static void printBuckets(double @NotNull [] deltas) {
        StringBuilder line = new StringBuilder("Parity buckets:");
        for (double edge : BUCKET_EDGES) {
            long count = 0;
            for (double delta : deltas)
                if (!failed(delta) && delta < edge) count++;
            line.append(String.format(Locale.ROOT, " <%.2f: %d /", edge, count));
        }
        long failures = 0;
        for (double delta : deltas)
            if (failed(delta)) failures++;
        System.out.println(line + String.format(Locale.ROOT, " failed: %d / total: %d",
            failures, deltas.length));
    }

    /**
     * Whether a row's comparison did not happen.
     *
     * @param meanDelta the row's mean ARGB delta
     * @return whether it is the in-memory failure marker
     */
    private static boolean failed(double meanDelta) {
        return !Double.isFinite(meanDelta);
    }

}
