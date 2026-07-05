package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.RenderOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Isometric 3D block render benchmark across a representative spread of model shapes:
 * <ul>
 * <li>{@code stone} - plain cube, baseline</li>
 * <li>{@code oak_fence} - multipart (4 potential connections)</li>
 * <li>{@code short_grass} - cross-foliage (two-sided, no cull)</li>
 * <li>{@code white_banner} - block-entity (banner layer stack)</li>
 * <li>{@code oak_stairs} - orientation + camera override</li>
 * <li>{@code piston} - multipart head + body, most triangles</li>
 * </ul>
 * Exercises the full block-render hot path: triangle transform, tiled rasterization, SIMD math,
 * and the trig LUT. Every subject renders at {@code 256} px with {@code 2x} supersampling and FXAA
 * enabled, so downsample and anti-alias cost are included in each timing. Contrast with
 * {@link ModelRasterizeMicroBenchmark}, which strips SSAA/FXAA to isolate the rasterizer.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BlockIsometricBenchmark extends AbstractRendererBenchmark {

    /** Block id under test - one JMH sub-benchmark per model shape in the class-doc spread. */
    @Param({
        "minecraft:stone",
        "minecraft:oak_fence",
        "minecraft:short_grass",
        "minecraft:white_banner",
        "minecraft:oak_stairs",
        "minecraft:piston"
    })
    public String blockId;

    /** Block renderer bound to the trial's pipeline context. */
    private BlockRenderer renderer;

    /** Render options for the current {@link #blockId}, rebuilt once per trial. */
    private BlockOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new BlockRenderer(context());
        this.options = BlockOptions.builder()
            .blockId(this.blockId)
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .render(RenderOptions.builder()
                .outputSize(256)
                .supersample(2)
                .antiAlias(true)
                .build())
            .build();
    }

    @Benchmark
    public void renderBlock(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
