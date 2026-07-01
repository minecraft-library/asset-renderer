package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Shared {@link Scope#Benchmark} state for every rendering benchmark. Loads the asset pipeline
 * once per JMH trial via {@link Pipeline#run} so the pack-download + PNG-decode + JSON-parse cost
 * does not contaminate the per-iteration measurements each concrete benchmark captures.
 * <p>
 * Subclasses override {@link #onSetupTrial()} to pre-build their renderer(s) and option builders
 * from {@link #context()} - constructing renderer instances exactly as the tooling {@code Main}
 * classes do - so the {@code @Benchmark} body reduces to a single {@code render(...)} call feeding
 * a {@code Blackhole}. The one exception is {@link TexturePackLoadBenchmark}, which measures the
 * pipeline load itself and therefore does <b>not</b> extend this base.
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
    protected Pipeline.Result pipelineResult;

    /**
     * Runs the pipeline once per trial, resolves the renderer context, then hands off to the
     * subclass hook. {@code final} so the load-once contract cannot be overridden away.
     *
     * @throws Exception if the pipeline fails to run (pack fetch / decode failure)
     */
    @Setup(Level.Trial)
    public final void bootstrapPipeline() throws Exception {
        this.pipelineResult = Pipeline.run(PipelineOptions.defaults());
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

    /**
     * Invokes the subclass release hook, then clears the trial-scoped references so the pipeline
     * result and its loaded models become eligible for GC between trials.
     */
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
