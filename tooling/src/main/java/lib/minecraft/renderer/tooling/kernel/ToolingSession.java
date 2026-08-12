package lib.minecraft.renderer.tooling.kernel;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.client.ClientOptions;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The AutoCloseable run context every tooling flow lives inside - options, the sole jar
 * cache, and the diagnostics root.
 *
 * <p>Every main starts {@code try (ToolingSession s = ToolingPipeline.openSession(...))} - no
 * flow ever sees a {@code ZipFile} or a {@code Path}.
 *
 * @param options the pipeline options (source version, cache root)
 * @param cache the session's sole jar cache
 * @param diagnostics the diagnostics root scope
 */
public record ToolingSession(
    @NotNull ClientOptions options,
    @NotNull ClassNodeCache cache,
    @NotNull Diagnostics diagnostics
) implements AutoCloseable {

    /**
     * The directory every flow writes its table into, defaulting to the renderer's bundled resource
     * tree. {@code -Dasset.tooling.out} redirects the whole set, which is how an A/B is taken: the
     * clean side into one directory, the changed side into another, then diff. Relative either way,
     * and resolved against the working directory the build pins to the renderer root, so the default
     * names the same place from either build.
     */
    private static final @NotNull Path RESOURCE_DIR = Path.of(
        System.getProperty("asset.tooling.out", "src/main/resources/lib/minecraft/renderer"));

    @Override
    public void close() {
        this.cache.close();
        this.diagnostics.flush();
    }

    /**
     * Resolves a file name against the bundled resource directory. Flows that write a second table
     * of their own name it here; the ordinary single-table flow goes through {@link #write}.
     *
     * @param fileName the table's file name
     * @return the path the table is written to
     */
    public @NotNull Path resolve(@NotNull String fileName) {
        return RESOURCE_DIR.resolve(fileName);
    }

    /**
     * Writes a finished envelope into the bundled resource directory and records where it went. The
     * counterpart of {@link #envelope}, which opens the same file.
     *
     * @param root the envelope root to write
     * @param fileName the table's file name
     */
    public void write(@NotNull JsonTree root, @NotNull String fileName) {
        Path out = resolve(fileName);
        root.write(out);
        this.diagnostics.info("wrote %s", out.toAbsolutePath());
    }

    /**
     * Applies the strict gate to everything the flow recorded: an ERROR always fails the run, and
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate. The flow names itself - the
     * gate reads the diagnostics root's own path rather than taking it as an argument.
     *
     * @throws ToolingException if the flow recorded a diagnostic the gate fails on
     */
    public void failOnStrictGate() {
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling.strict", "").trim());
        int errors = this.diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = this.diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("%s flow recorded %d ERROR / %d WARN entries%s",
                this.diagnostics.path(), errors, warns, warnStrict ? " (strict=warn)" : "");
    }

    /**
     * A fresh resource envelope root: the {@code //} header (generator, regen task, and the file's
     * declared ordering source), {@code format: 2}, and {@code source_version} derived from the
     * session's jar options.
     *
     * <p>The flow name and this argument are the only two literals a flow's main owns that reach
     * shipped bytes - the flow twice, as {@code tooling.<flow>} and as the regen task, and the
     * ordering source once. Renaming a flow or editing an ordering string therefore rewrites the
     * header of every table that flow emits, and has to be diffed; the strict gate's message, the
     * resource directory and the {@code wrote %s} line are console output only.
     *
     * @param orderingSource the declared ordering source stamped into the header
     * @return the envelope root, ready for its payload member
     */
    public @NotNull JsonTree envelope(@NotNull String orderingSource) {
        String flow = this.diagnostics.path();
        return JsonTree.object()
            .put("//", "tooling." + flow + " · regen: ./gradlew " + flow + " · order: " + orderingSource)
            .putInt("format", 2)
            .put("source_version", this.options.getVersion());
    }

}
