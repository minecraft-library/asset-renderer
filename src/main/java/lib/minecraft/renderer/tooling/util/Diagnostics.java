package lib.minecraft.renderer.tooling.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accumulates parser diagnostics into a single flat list. Each entry carries a severity
 * prefix ({@code ERROR:}, {@code WARN:}, {@code INFO:}) for grep-friendly log scraping.
 * <ul>
 * <li>{@link #error} - missing class / method / parse exception. Fails strict mode.</li>
 * <li>{@link #warn} - suspected data corruption (numStack underflow at an addBox /
 *     PartPose boundary, numStack overflow). Fails strict mode.</li>
 * <li>{@link #info} - informational notes like leftover literals at end-of-method that
 *     don't corrupt output but hint at parser accounting gaps. Does NOT fail strict
 *     mode so the current 26.1 baseline (three known sources) stays clean.</li>
 * </ul>
 * A per-instance dedupe set suppresses duplicate entries so a parser loop that fires the
 * same underflow on every banner source doesn't spam the log with 32 copies of the same
 * message.
 */
public final class Diagnostics {

    /** Every diagnostic entry (error + warn + info) in insertion order, post-dedupe. */
    private final @NotNull List<String> entries = new ArrayList<>();

    /** Subset of {@link #entries} at WARN+ severity; non-empty means strict mode should fail. */
    private final @NotNull List<String> strictFailingEntries = new ArrayList<>();

    /** Message-content dedupe set so a repeating parser diagnostic only lands once in the log. */
    private final @NotNull Set<String> dedupe = new HashSet<>();

    /**
     * Records a strict-failing warning. Format args are applied via {@link String#format}; the
     * resulting line is prefixed with {@code WARN: } for grep-friendly log scraping.
     *
     * @param format the printf-style format string
     * @param args the format arguments
     */
    public void warn(@NotNull String format, @Nullable Object... args) {
        add("WARN: " + String.format(format, args), true);
    }

    /**
     * Records a strict-failing error. Format args are applied via {@link String#format}; the
     * resulting line is prefixed with {@code ERROR: }.
     *
     * @param format the printf-style format string
     * @param args the format arguments
     */
    public void error(@NotNull String format, @Nullable Object... args) {
        add("ERROR: " + String.format(format, args), true);
    }

    /**
     * Records an informational note. Does NOT fail strict mode; used for benign accounting
     * observations like "leftover literals on numStack" that never corrupt output. Prefixed
     * with {@code INFO: }.
     *
     * @param format the printf-style format string
     * @param args the format arguments
     */
    public void info(@NotNull String format, @Nullable Object... args) {
        add("INFO: " + String.format(format, args), false);
    }

    private void add(@NotNull String message, boolean strictFails) {
        if (!this.dedupe.add(message)) return;
        this.entries.add(message);
        if (strictFails) this.strictFailingEntries.add(message);
    }

    /** Every recorded diagnostic line in insertion order, post-dedupe. */
    public @NotNull List<String> entries() {
        return this.entries;
    }

    /** The count of recorded diagnostics at WARN+ severity; non-zero fails strict mode. */
    public int strictFailingCount() {
        return this.strictFailingEntries.size();
    }

    /** {@code true} when no diagnostics have been recorded. */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

}
