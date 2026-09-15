package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Hierarchical diagnostic sink for style authoring - parent-to-child scopes whose paths mirror
 * the install tree.
 *
 * <p>Every surface receiving a {@code StyleDiagnostics} immediately takes {@link #child(String)}:
 * an install childs per entity id and per style id, so scope paths read
 * {@code styles/minecraft:armor_stand/sit/compile}. Entries ALWAYS record (chronologically, at
 * the root); {@link Output} gates emission only - a library must not print uninvited, so
 * {@code NONE} is the resting mode and {@code CONSOLE}/{@code FILE} are caller opt-ins.
 *
 * <p>No refusal is DECIDED here: a refusal throws on its own facts, and the entry beside it is the
 * post-mortem rather than a second refusal channel. What is recorded is still read - the install
 * surface hands its root out, and so does a compiled result - so an entry is observable to a caller
 * even though none gates a compile. Lines speak driver, field, bone, channel and count vocabulary -
 * never a rendered expression graph, whose per-path expansion is exactly what the pose tables exist
 * to avoid.
 *
 * <p>Counts are SUBTREE-aggregated: {@link #count(Severity)} and {@link #failed()} cover this
 * scope and every descendant.
 */
@Parity(subject = Subject.ENTITY)
public final class StyleDiagnostics {

    /**
     * Where recorded entries are emitted. Recording itself is unconditional.
     */
    public enum Output { NONE, CONSOLE, FILE }

    /**
     * Entry severities, written in escalation order for a reader.
     *
     * <p>Nothing reads the order: a count matches one severity against another and never compares
     * or indexes by ordinal, so these three reorder without moving a verdict. The order is a
     * convention for whoever reads the list, not a contract.
     *
     * <p><b>Neither is the classification.</b> What each severity means is stated with the member
     * that records it, and that criterion is the contract; which severity a given line carries is a
     * reading of it, and a line moves when the reading is corrected. So a consumer counting one
     * severity is counting how many lines meet a criterion today rather than a number this holds
     * still - what a count is stable against is a refusal, which throws on its own facts and never
     * on an entry.
     */
    public enum Severity { INFO, WARN, ERROR }

    /**
     * One recorded diagnostic line.
     *
     * @param timestamp the recording instant
     * @param severity the entry severity
     * @param path the recording scope's path ({@code <root>/<entityId>/<styleId>/...})
     * @param message the formatted message
     */
    public record Entry(@NotNull Instant timestamp, @NotNull Severity severity, @NotNull String path, @NotNull String message) {}

    private final @Nullable StyleDiagnostics parent;
    private final @NotNull String path;
    private final @NotNull Output mode;
    private final @Nullable Path fileTarget;
    private final @NotNull List<Entry> rootEntries;
    private final @NotNull Map<String, StyleDiagnostics> children = new LinkedHashMap<>();

    private StyleDiagnostics(@Nullable StyleDiagnostics parent, @NotNull String path, @NotNull Output mode, @Nullable Path fileTarget) {
        this.parent = parent;
        this.path = path;
        this.mode = mode;
        this.fileTarget = fileTarget;
        this.rootEntries = parent == null ? new ArrayList<>() : parent.rootEntries;
    }

    /**
     * Creates the root scope. The {@code fileTarget} is resolved by the caller - the sink stays
     * blind to path policy - and is consulted only under {@link Output#FILE}.
     *
     * @param name the root path segment (e.g. {@code styles})
     * @param mode the emission mode
     * @param fileTarget the {@link Output#FILE} log path, or {@code null} outside FILE mode
     * @return the root scope
     */
    public static @NotNull StyleDiagnostics root(@NotNull String name, @NotNull Output mode, @Nullable Path fileTarget) {
        return new StyleDiagnostics(null, name, mode, fileTarget);
    }

    /**
     * This scope's path ({@code <root>} at the root, {@code <root>/<tag>/...} below).
     */
    @NotNull String path() {
        return this.path;
    }

    /**
     * Returns the child scope for {@code tag}, creating it on first use and reusing it after -
     * scope identity is stable per tag, so repeated installs on one path share one scope.
     *
     * @param tag the scope segment (entity id, style id, layer coordinate)
     * @return the child scope
     */
    public @NotNull StyleDiagnostics child(@NotNull String tag) {
        return this.children.computeIfAbsent(tag, key -> new StyleDiagnostics(this, this.path + "/" + key, this.mode, this.fileTarget));
    }

    /**
     * Records an {@link Severity#INFO} entry in this scope - a choice the compile made that the
     * author may want to see.
     *
     * <p>It reads back what was spelled and never says that anything went wrong. A fact already
     * warned once by an aggregate in THIS scope is recorded here rather than warned twice.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void info(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.INFO, String.format(message, args));
    }

    /**
     * Records a {@link Severity#WARN} entry in this scope - authored intent that will not render
     * as spelled.
     *
     * <p>The instances: an address the subject carries none of, fewer addresses than the chain
     * named, none of them at all on a woven layer, an address opposite the one named, a wave
     * nothing follows, a seat carried round to itself, or a span the render window truncates.
     *
     * <p>A fact that would refuse under a strict install records the SAME warning under a tolerant
     * one. Strictness adds the error and the throw; it never removes an entry, and tolerance never
     * adds one - so the two installs differ by the error and by nothing else.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void warn(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.WARN, String.format(message, args));
    }

    /**
     * Records an {@link Severity#ERROR} entry in this scope - refusal context, recorded beside a
     * throw and never in place of one.
     *
     * <p>Nothing gates on the count: a refusal is the throw, and this is what a reader consults
     * afterwards to find out which one it was.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void error(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.ERROR, String.format(message, args));
    }

    /**
     * Records an {@link Severity#ERROR} entry carrying a throwable's message.
     *
     * @param cause the underlying throwable
     * @param message the format string
     * @param args the format arguments
     */
    public void error(@NotNull Throwable cause, @NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.ERROR, String.format(message, args) + " (caused by " + cause + ")");
    }

    /**
     * Whether this scope's subtree recorded at least one {@link Severity#ERROR}. A convenience
     * read over {@link #count(Severity)}; nothing gates on it - refusals throw on their own
     * facts.
     *
     * @return {@code true} when the subtree recorded an {@code ERROR}
     */
    public boolean failed() {
        return count(Severity.ERROR) > 0;
    }

    /**
     * Whether one recorded entry falls in this scope or anywhere below it.
     *
     * <p>The subtree test, spelled once. A count and a listing answer the same question about the
     * same path in two idioms, with nothing binding them, and the two are read by different
     * callers - so a change to one that misses the other is a divergence nothing catches.
     *
     * @param entry the recorded entry
     * @return whether the entry belongs to this subtree
     */
    private boolean holds(@NotNull Entry entry) {
        return entry.path().equals(this.path) || entry.path().startsWith(this.path + "/");
    }

    /**
     * Counts the entries of the given severity recorded by this scope and every descendant.
     *
     * @param severity the severity to count
     * @return the subtree-aggregated count
     */
    public int count(@NotNull Severity severity) {
        int total = 0;
        for (Entry entry : this.rootEntries)
            if (entry.severity() == severity && this.holds(entry)) total++;
        return total;
    }

    /**
     * An immutable snapshot of this scope's subtree entries, in recording order.
     */
    public @NotNull List<Entry> entries() {
        return this.rootEntries.stream()
            .filter(this::holds)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Flushes the recorded entries to the {@link Output#FILE} target (a no-op under
     * {@code NONE}/{@code CONSOLE}, whose emission happened at record time). Root-only.
     *
     * @throws IllegalStateException if called on a child scope
     * @throws UncheckedIOException if the FILE target cannot be written
     */
    public void flush() {
        if (this.parent != null)
            throw new IllegalStateException(String.format("Flush is root-only (called on scope '%s')", this.path));
        if (this.mode != Output.FILE || this.fileTarget == null) return;
        // Literal LF on both the separator and the terminator, so the written log is a fact
        // about the run rather than about the host it ran on.
        StringJoiner lines = new StringJoiner("\n", "", "\n");
        for (Entry entry : this.rootEntries)
            lines.add(entry.timestamp() + " [" + entry.severity() + "] " + entry.path() + " - " + entry.message());
        try {
            // A bare filename names no parent - nothing to create, and the write lands as given.
            Path parent = this.fileTarget.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(this.fileTarget, lines.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(String.format("Failed to write diagnostics log '%s'", this.fileTarget), ex);
        }
    }

    private void record(@NotNull Severity severity, @NotNull String message) {
        Entry entry = new Entry(Instant.now(), severity, this.path, message);
        this.rootEntries.add(entry);
        if (this.mode == Output.CONSOLE) {
            String line = "[" + severity + "] " + this.path + " - " + message;
            if (severity == Severity.INFO) System.out.println(line);
            else System.err.println(line);
        }
    }

}
