package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.options.PlayerOptions;
import lib.minecraft.renderer.options.spec.RenderOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Player render benchmark across all three body scopes in 3D mode at {@code 256} px:
 * {@link PlayerOptions.Type#SKULL} (head only), {@link PlayerOptions.Type#BUST} (head + torso +
 * arms), and {@link PlayerOptions.Type#FULL} (full body). With no skin source supplied,
 * {@link PlayerRenderer} falls back to the pack-resolved {@code minecraft:entity/steve} texture, so
 * results are deterministic across runs. Measures the SIMD render math plus the renderer's skin
 * resolution / cache path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PlayerRenderBenchmark extends AbstractRendererBenchmark {

    /** Body scope under test - one JMH sub-benchmark per scope. */
    @Param({"SKULL", "BUST", "FULL"})
    public PlayerOptions.Type type;

    /** Player renderer bound to the trial's pipeline context. */
    private PlayerRenderer renderer;

    /** Render options for the current {@link #type} in {@link PlayerOptions.Dimension#THREE_D}. */
    private PlayerOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new PlayerRenderer(context());
        this.options = PlayerOptions.builder()
            .type(this.type)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .render(RenderOptions.builder()
                .outputSize(256)
                .build())
            .build();
    }

    @Benchmark
    public void renderPlayer(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
