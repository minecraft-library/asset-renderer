package lib.minecraft.renderer.tooling.util;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Shared client-jar acquisition for the tooling generators - the {@link PipelineOptions} defaults plus
 * the {@link Pipeline#downloadJarToCache} call and its failure log that every uniform snapshot
 * generator's {@code main} otherwise repeats verbatim.
 */
@UtilityClass
public class ToolingPipeline {

    /**
     * Downloads (or reuses the cached) vanilla client jar for a tooling generator, logging a
     * generator-specific failure line before rethrowing.
     *
     * @param failureContext the phrase describing what is being generated, interpolated into the
     *     failure log ({@code "Failed to download client jar for <failureContext>: ..."})
     * @return the default pipeline options plus the resolved client-jar path
     */
    public static @NotNull JarSource downloadJar(@NotNull String failureContext) {
        PipelineOptions options = PipelineOptions.defaults();
        try {
            Path jarPath = Pipeline.downloadJarToCache(options);
            return new JarSource(options, jarPath);
        } catch (PipelineException ex) {
            System.err.println("Failed to download client jar for " + failureContext + ": " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * The default pipeline options and the resolved client-jar path from {@link #downloadJar}.
     *
     * @param options the default pipeline options (source version, cache root)
     * @param jarPath the cached vanilla client-jar path
     */
    public record JarSource(@NotNull PipelineOptions options, @NotNull Path jarPath) {}
}
