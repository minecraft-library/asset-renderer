package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.OutputOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Fluid animation benchmark - bakes the full still-fluid animation strip through
 * {@link FluidRenderer} at {@code 256} px, measuring frame-parallel animation baking end to end.
 * <p>
 * The two parameters mirror vanilla's {@code .mcmeta} frame data: {@link FluidOptions.Fluid#WATER}
 * ({@code block/water_still}) bakes 32 frames at 2 ticks each, {@link FluidOptions.Fluid#LAVA}
 * ({@code block/lava_still}) bakes 20 frames at 3 ticks each. Frame/tick counts are set explicitly
 * in {@link #onSetupTrial()} rather than left at the {@link FluidOptions} single-frame defaults, so
 * the timing reflects a real animated bake.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FluidAnimationBenchmark extends AbstractRendererBenchmark {

    /** Fluid under test - one JMH sub-benchmark per variant. */
    @Param({"WATER", "LAVA"})
    public FluidOptions.Fluid fluid;

    /** Fluid renderer bound to the trial's pipeline context. */
    private FluidRenderer renderer;

    /** Bake options for the current {@link #fluid}, including its frame count and tick cadence. */
    private FluidOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new FluidRenderer(context());
        int frameCount = this.fluid == FluidOptions.Fluid.WATER ? 32 : 20;
        int ticksPerFrame = this.fluid == FluidOptions.Fluid.WATER ? 2 : 3;
        this.options = FluidOptions.builder()
            .fluid(this.fluid)
            .output(OutputOptions.builder()
                .canvasSize(256)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(frameCount)
                .ticksPerFrame(ticksPerFrame)
                .build())
            .build();
    }

    @Benchmark
    public void bakeAnimation(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
