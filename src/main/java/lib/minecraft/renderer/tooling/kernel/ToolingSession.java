package lib.minecraft.renderer.tooling.kernel;

import lib.minecraft.renderer.pipeline.PipelineOptions;
import org.jetbrains.annotations.NotNull;

/**
 * The AutoCloseable run context every tooling flow lives inside - options, the sole jar
 * cache, and the diagnostics root (SPINE 5.7).
 *
 * <p>Every main starts {@code try (ToolingSession s = ToolingPipeline.openSession(...))} - no
 * flow ever sees a {@code ZipFile} or a {@code Path}. The legacy {@code zip()} fluent leak
 * has no successor.
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

}
