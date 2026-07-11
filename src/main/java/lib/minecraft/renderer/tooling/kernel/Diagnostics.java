package lib.minecraft.renderer.tooling.kernel;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Hierarchical diagnostic sink for tooling flows - parent-to-child scopes whose paths mirror
 * the resolver tree.
 *
 * <p>Every class receiving a {@code Diagnostics} immediately takes {@link #child(String)}: the
 * walk childs per subject id, each node-resolver per node name, so scope paths read
 * {@code entityModels/minecraft:wolf/overlays}. Entries ALWAYS record (chronologically, at the
 * root); {@link Output} gates emission only - {@code NONE} for tests, {@code CONSOLE} under
 * gradle, {@code FILE} for the run log. Failed derivations call {@link #error} - never a
 * literal fallback.
 *
 * <p>Counts are SUBTREE-aggregated: {@link #count(Severity)} and {@link #failed()} cover this
 * scope and every descendant, so strict gates read the root. {@link Severity} declaration
 * order is load-bearing (ordinal-indexed counters).
 */
public final class Diagnostics {

    /** Where recorded entries are emitted. Recording itself is unconditional. */
    public enum Output { NONE, CONSOLE, FILE }

    /** Entry severities, in escalation order. Declaration order is load-bearing. */
    public enum Severity { INFO, WARN, ERROR }

    /**
     * One recorded diagnostic line.
     *
     * @param timestamp the recording instant
     * @param severity the entry severity
     * @param path the recording scope's path ({@code <flow>/<subject>/<node>})
     * @param message the formatted message
     */
    public record Entry(@NotNull Instant timestamp, @NotNull Severity severity, @NotNull String path, @NotNull String message) {}

    private final @Nullable Diagnostics parent;
    private final @NotNull String path;
    private final @NotNull Output mode;
    private final @Nullable Path fileTarget;
    private final @NotNull List<Entry> rootEntries;
    private final @NotNull Map<String, Diagnostics> children = new LinkedHashMap<>();

    private Diagnostics(@Nullable Diagnostics parent, @NotNull String path, @NotNull Output mode, @Nullable Path fileTarget) {
        this.parent = parent;
        this.path = path;
        this.mode = mode;
        this.fileTarget = fileTarget;
        this.rootEntries = parent == null ? new ArrayList<>() : parent.rootEntries;
    }

    /**
     * Creates the root scope for a flow. The {@code fileTarget} is resolved by the caller
     * ({@code ToolingPipeline.openSession}; {@code Diagnostics} itself stays mode-blind) and
     * is consulted only under {@link Output#FILE}.
     *
     * @param flow the flow name (the root path segment, e.g. {@code entityModels})
     * @param mode the emission mode
     * @param fileTarget the {@link Output#FILE} log path, or {@code null} outside FILE mode
     * @return the root scope
     */
    public static @NotNull Diagnostics root(@NotNull String flow, @NotNull Output mode, @Nullable Path fileTarget) {
        return new Diagnostics(null, flow, mode, fileTarget);
    }

    /**
     * This scope's path ({@code <flow>} at the root, {@code <flow>/<tag>/...} below).
     * Package-private - the envelope stamps the flow name from the root path.
     */
    @NotNull String path() {
        return this.path;
    }

    /**
     * Returns the child scope for {@code tag}, creating it on first use and reusing it after
     * (scope identity is stable per tag, so repeated resolver passes share one path).
     *
     * @param tag the scope segment (subject id, node name)
     * @return the child scope
     */
    public @NotNull Diagnostics child(@NotNull String tag) {
        return this.children.computeIfAbsent(tag, key -> new Diagnostics(this, this.path + "/" + key, this.mode, this.fileTarget));
    }

    /**
     * Records an {@link Severity#INFO} entry in this scope.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void info(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.INFO, String.format(message, args));
    }

    /**
     * Records a {@link Severity#WARN} entry in this scope.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void warn(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.WARN, String.format(message, args));
    }

    /**
     * Records an {@link Severity#ERROR} entry in this scope - the loud-failure channel for
     * derivations that cannot complete.
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
     * Returns whether this scope's subtree recorded at least one {@link Severity#ERROR} -
     * the strict-gate read (an {@code ERROR} fails the flow).
     */
    public boolean failed() {
        return count(Severity.ERROR) > 0;
    }

    /**
     * Counts the entries of the given severity recorded by this scope and every descendant.
     *
     * @param severity the severity to count
     * @return the subtree-aggregated count
     */
    public int count(@NotNull Severity severity) {
        int total = 0;
        String subtree = this.path + "/";
        for (Entry entry : this.rootEntries) {
            if (entry.severity() != severity) continue;
            if (entry.path().equals(this.path) || entry.path().startsWith(subtree)) total++;
        }
        return total;
    }

    /**
     * An immutable snapshot of this scope's subtree entries, in recording order.
     */
    public @NotNull List<Entry> entries() {
        String subtree = this.path + "/";
        List<Entry> out = new ArrayList<>();
        for (Entry entry : this.rootEntries)
            if (entry.path().equals(this.path) || entry.path().startsWith(subtree)) out.add(entry);
        return List.copyOf(out);
    }

    /**
     * Flushes the recorded entries to the {@link Output#FILE} target (a no-op under
     * {@code NONE}/{@code CONSOLE}, whose emission happened at record time). Root-only -
     * called by {@code ToolingSession.close()}.
     *
     * @throws ToolingException if called on a child scope, or the FILE target cannot be written
     */
    public void flush() {
        if (this.parent != null)
            throw new ToolingException("flush is root-only (called on scope '%s')", this.path);
        if (this.mode != Output.FILE || this.fileTarget == null) return;
        StringJoiner lines = new StringJoiner(System.lineSeparator(), "", System.lineSeparator());
        for (Entry entry : this.rootEntries)
            lines.add(entry.timestamp() + " [" + entry.severity() + "] " + entry.path() + " - " + entry.message());
        try {
            Files.createDirectories(this.fileTarget.getParent());
            Files.writeString(this.fileTarget, lines.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to write diagnostics log '%s'", this.fileTarget);
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
