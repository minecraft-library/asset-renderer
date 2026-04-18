package dev.sbs.renderer.bench;

import dev.sbs.renderer.BlockRenderer;
import dev.sbs.renderer.options.BlockOptions;
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
 * Primary measurement target for Task 1 (via atlas dispatch), Task 7 (triangle transform pass),
 * Task 8 (tiled rasterization), Task 10 (SIMD math), and Task 12 (trig LUT).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BlockIsometricBenchmark extends AbstractRendererBenchmark {

    @Param({
        "minecraft:stone",
        "minecraft:oak_fence",
        "minecraft:short_grass",
        "minecraft:white_banner",
        "minecraft:oak_stairs",
        "minecraft:piston"
    })
    public String blockId;

    private BlockRenderer renderer;
    private BlockOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new BlockRenderer(context());
        this.options = BlockOptions.builder()
            .blockId(this.blockId)
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .outputSize(256)
            .supersample(2)
            .antiAlias(true)
            .build();
    }

    @Benchmark
    public void renderBlock(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
