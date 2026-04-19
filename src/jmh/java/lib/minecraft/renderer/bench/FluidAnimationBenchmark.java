package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.options.FluidOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Fluid animation benchmark - walks the full {@code water_still} (32 frames, frametime=2) or
 * {@code lava_still} (20 frames, frametime=3) strip through {@link FluidRenderer}. Primary
 * measurement target for Task 4 (frame-parallel animation baking).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FluidAnimationBenchmark extends AbstractRendererBenchmark {

    @Param({"WATER", "LAVA"})
    public FluidOptions.Fluid fluid;

    private FluidRenderer renderer;
    private FluidOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new FluidRenderer(context());
        int frameCount = this.fluid == FluidOptions.Fluid.WATER ? 32 : 20;
        int ticksPerFrame = this.fluid == FluidOptions.Fluid.WATER ? 2 : 3;
        this.options = FluidOptions.builder()
            .fluid(this.fluid)
            .outputSize(256)
            .frameCount(frameCount)
            .ticksPerFrame(ticksPerFrame)
            .build();
    }

    @Benchmark
    public void bakeAnimation(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
