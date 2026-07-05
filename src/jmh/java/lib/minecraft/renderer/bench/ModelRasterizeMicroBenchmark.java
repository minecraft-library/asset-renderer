package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.RenderOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Rasterizer-focused micro benchmark. Renders the {@code piston} block (highest triangle count
 * among the standard blockstates) at {@code 128} px with supersampling at its {@code 1x} default
 * (no SSAA) and {@code antiAlias(false)} (no FXAA), so the {@link ModelEngine} rasterization loop
 * dominates the wall-clock time - model lookup and texture resolution are effectively free after
 * warmup.
 * <p>
 * Measures the parallel triangle transform (Pass 1) and tiled rasterization with per-tile depth
 * slices (Pass 2). Contrast with {@link BlockIsometricBenchmark}, which renders the same piston at
 * {@code 256} px with SSAA + FXAA on and thus folds downsample/anti-alias cost into the timing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ModelRasterizeMicroBenchmark extends AbstractRendererBenchmark {

    /** Block renderer bound to the trial's pipeline context. */
    private BlockRenderer renderer;

    /** Fixed piston render options - 128 px, no SSAA, no FXAA. */
    private BlockOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new BlockRenderer(context());
        this.options = BlockOptions.builder()
            .blockId("minecraft:piston")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .render(RenderOptions.builder()
                .outputSize(128)
                .antiAlias(false)
                .build())
            .build();
    }

    @Benchmark
    public void rasterize(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
