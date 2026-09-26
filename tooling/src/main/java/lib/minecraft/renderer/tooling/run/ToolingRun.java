package lib.minecraft.renderer.tooling.run;

import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import org.jetbrains.annotations.NotNull;

/**
 * The AutoCloseable run context every tooling flow lives inside - options, the sole jar
 * cache, and the diagnostics root.
 *
 * <p>Every main starts {@code try (ToolingRun run = ToolingPipeline.openSession(...))} - no
 * flow ever sees a {@code ZipFile} or a {@code Path}.
 *
 * @param options the pipeline options (source version, cache root)
 * @param cache the run's sole jar cache
 * @param diagnostics the diagnostics root scope
 */
public record ToolingRun(
    @NotNull ClientOptions options,
    @NotNull ClassNodeCache cache,
    @NotNull Diagnostics diagnostics
) implements AutoCloseable {

    @Override
    public void close() {
        this.cache.close();
        this.diagnostics.flush();
    }

}
