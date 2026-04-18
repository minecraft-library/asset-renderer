package dev.sbs.renderer.bench;

import dev.sbs.renderer.AtlasRenderer;
import dev.sbs.renderer.options.AtlasOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end atlas bake benchmark - the dominant CLI workload and primary measurement target for
 * Task 1 (parallel {@code AtlasRenderer} block/item dispatch) and Task 5 (parallel
 * {@code GridRenderer} tile blitting).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AtlasBakeBenchmark extends AbstractRendererBenchmark {

    private AtlasRenderer renderer;
    private AtlasOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new AtlasRenderer(context());
        // Default tile size (128) + static (non-animated) mode - mirrors the generateAtlas
        // Gradle task baseline. Changing these knobs is out of scope for Task 0; override via
        // -Pjmh.atlas.tileSize=... in a follow-up if needed.
        this.options = AtlasOptions.defaults();
    }

    @Benchmark
    public void bakeFullAtlas(Blackhole bh) {
        bh.consume(this.renderer.renderAtlas(this.options));
    }

}
