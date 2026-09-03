package lib.minecraft.renderer.author.pose;

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
 * <p>Nothing recorded is load-bearing: refusals throw on their own facts, and an entry beside a
 * throw is the post-mortem, never a second refusal channel. Lines speak driver, field, bone,
 * channel and count vocabulary - never a rendered expression graph, whose per-path expansion is
 * exactly what the pose tables exist to avoid.
 *
 * <p>Counts are SUBTREE-aggregated: {@link #count(Severity)} and {@link #failed()} cover this
 * scope and every descendant. {@link Severity} declaration order is escalation order.
 */
public final class StyleDiagnostics {

    /**
     * Where recorded entries are emitted. Recording itself is unconditional.
     */
    public enum Output { NONE, CONSOLE, FILE }

    /**
     * Entry severities, in escalation order. Declaration order is load-bearing.
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
     * Records an {@link Severity#INFO} entry in this scope - a choice made that the author may
     * want to see.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void info(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.INFO, String.format(message, args));
    }

    /**
     * Records a {@link Severity#WARN} entry in this scope - authored intent that will not
     * render as spelled.
     *
     * @param message the format string
     * @param args the format arguments
     */
    public void warn(@NotNull @PrintFormat String message, @Nullable Object... args) {
        record(Severity.WARN, String.format(message, args));
    }

    /**
     * Records an {@link Severity#ERROR} entry in this scope - refusal context, recorded beside
     * a throw and never in place of one.
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
        return this.rootEntries.stream()
            .filter(entry -> entry.path().equals(this.path) || entry.path().startsWith(subtree))
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
