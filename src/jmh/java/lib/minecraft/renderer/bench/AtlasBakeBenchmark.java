package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.option.AtlasOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end atlas bake benchmark - the dominant CLI workload. Renders the full block + item set
 * ({@link AtlasOptions.Source#BOTH}) into one grid image, exercising the parallel
 * {@link AtlasRenderer} per-model dispatch and its {@link GridRenderer} tile blitting under one
 * measurement.
 * <p>
 * Reported in {@link Mode#AverageTime} milliseconds. This is a whole-atlas timing, not a per-tile
 * one, so it captures the outer parallelism as well as the inner per-model render.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AtlasBakeBenchmark extends AbstractRendererBenchmark {

    /** Atlas renderer bound to the trial's pipeline context. */
    private AtlasRenderer renderer;

    /** Bake options - {@link AtlasOptions#defaults()}, held across all invocations. */
    private AtlasOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new AtlasRenderer(context());
        // Defaults: 128 px tiles, both sources, static (non-animated) - mirrors the generateAtlas
        // Gradle task baseline. To sweep tile size, edit the AtlasOptions.builder().tileSize(...)
        // call here; there is no dedicated -P override wired for this benchmark.
        this.options = AtlasOptions.defaults();
    }

    @Benchmark
    public void bakeFullAtlas(Blackhole bh) {
        bh.consume(this.renderer.renderAtlas(this.options));
    }

}
