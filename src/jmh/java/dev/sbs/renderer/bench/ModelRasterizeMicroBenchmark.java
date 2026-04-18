package dev.sbs.renderer.bench;

import dev.sbs.renderer.BlockRenderer;
import dev.sbs.renderer.options.BlockOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Rasterizer-focused micro benchmark. Renders the {@code piston} block (highest triangle count
 * among the standard blockstates) at a small output size with no SSAA and no FXAA so the
 * ModelEngine rasterization loop dominates the wall-clock time - model lookup and texture
 * resolution are effectively free after warmup.
 * <p>
 * Primary measurement target for Task 7 (parallel triangle transform) and Task 8 (tiled
 * rasterization with per-tile depth slices).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ModelRasterizeMicroBenchmark extends AbstractRendererBenchmark {

    private BlockRenderer renderer;
    private BlockOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new BlockRenderer(context());
        this.options = BlockOptions.builder()
            .blockId("minecraft:piston")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .outputSize(128)
            .supersample(1)
            .antiAlias(false)
            .build();
    }

    @Benchmark
    public void rasterize(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
