package dev.sbs.renderer.tooling.blockentity;

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

    private final @NotNull List<String> entries = new ArrayList<>();
    private final @NotNull List<String> strictFailingEntries = new ArrayList<>();
    private final @NotNull Set<String> dedupe = new HashSet<>();

    public void warn(@NotNull String format, @Nullable Object... args) {
        add("WARN: " + String.format(format, args), true);
    }

    public void error(@NotNull String format, @Nullable Object... args) {
        add("ERROR: " + String.format(format, args), true);
    }

    public void info(@NotNull String format, @Nullable Object... args) {
        add("INFO: " + String.format(format, args), false);
    }

    private void add(@NotNull String message, boolean strictFails) {
        if (!this.dedupe.add(message)) return;
        this.entries.add(message);
        if (strictFails) this.strictFailingEntries.add(message);
    }

    public @NotNull List<String> entries() {
        return this.entries;
    }

    public int strictFailingCount() {
        return this.strictFailingEntries.size();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

}
