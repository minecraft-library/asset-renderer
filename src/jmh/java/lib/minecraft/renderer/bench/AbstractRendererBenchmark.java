package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Shared base for every rendering benchmark. Loads the asset pipeline once per JMH trial so the
 * pack-download + decode cost does not contaminate the per-iteration measurements that each
 * concrete benchmark captures.
 * <p>
 * Benchmarks extend this class and access {@link #context} from their {@code @Benchmark} methods
 * to construct renderer instances exactly as the tooling {@code Main} classes do.
 */
@State(Scope.Benchmark)
public abstract class AbstractRendererBenchmark {

    /**
     * Resolved pipeline context, populated once per {@link Level#Trial}. Concrete benchmarks
     * build their renderer(s) from this reference inside their own {@link Setup} methods.
     */
    protected PipelineRendererContext context;

    /**
     * Raw pipeline result kept alongside {@link #context} for benchmarks that need the on-disk
     * pack root or the loaded-model counts for sanity-checking before a measurement kicks off.
     */
    protected AssetPipeline.Result pipelineResult;

    @Setup(Level.Trial)
    public final void bootstrapPipeline() throws Exception {
        this.pipelineResult = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        this.context = PipelineRendererContext.of(this.pipelineResult);
        onSetupTrial();
    }

    /**
     * Hook invoked after the pipeline context is ready. Subclasses override to pre-build
     * renderer instances, option builders, or cached geometry so the {@code @Benchmark} body
     * stays minimal.
     */
    protected void onSetupTrial() {
        // No-op by default.
    }

    @TearDown(Level.Trial)
    public final void tearDownTrial() {
        onTearDownTrial();
        this.context = null;
        this.pipelineResult = null;
    }

    /**
     * Hook invoked before the trial-level references are cleared. Subclasses override to release
     * renderer-specific state.
     */
    protected void onTearDownTrial() {
        // No-op by default.
    }

    /**
     * Returns the pipeline context for subclasses. {@code final} so subclasses cannot accidentally
     * shadow the field with a non-Trial-scoped instance.
     *
     * @return the resolved pipeline renderer context
     */
    protected final @NotNull PipelineRendererContext context() {
        return this.context;
    }

}
