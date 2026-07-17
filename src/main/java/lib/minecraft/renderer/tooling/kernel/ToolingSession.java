package lib.minecraft.renderer.tooling.kernel;

import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import org.jetbrains.annotations.NotNull;

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
    @NotNull PipelineOptions options,
    @NotNull ClassNodeCache cache,
    @NotNull Diagnostics diagnostics
) implements AutoCloseable {

    @Override
    public void close() {
        this.cache.close();
        this.diagnostics.flush();
    }

    /**
     * A fresh resource envelope root: the {@code //} header (generator, regen task, and the file's
     * declared ordering source), {@code format: 2}, and {@code source_version} derived from the
     * session's jar options.
     *
     * @param orderingSource the declared ordering source stamped into the header
     * @return the envelope root, ready for its payload member
     */
    public @NotNull JsonNode envelope(@NotNull String orderingSource) {
        String flow = this.diagnostics.path();
        return JsonNode.object()
            .put("//", "tooling." + flow + " · regen: ./gradlew " + flow + " · order: " + orderingSource)
            .putInt("format", 2)
            .put("source_version", this.options.getVersion());
    }

}
