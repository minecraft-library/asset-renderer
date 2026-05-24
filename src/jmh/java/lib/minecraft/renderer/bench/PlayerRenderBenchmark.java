package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.options.PlayerOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Player render benchmark across all three body scopes in 3D mode. With no skin source supplied,
 * {@link PlayerRenderer} falls back to the pack-resolved {@code minecraft:entity/steve} texture,
 * so results are deterministic across runs. Measures SIMD math and skin-fetch dedup overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PlayerRenderBenchmark extends AbstractRendererBenchmark {

    @Param({"SKULL", "BUST", "FULL"})
    public PlayerOptions.Type type;

    private PlayerRenderer renderer;
    private PlayerOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new PlayerRenderer(context());
        this.options = PlayerOptions.builder()
            .type(this.type)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .outputSize(256)
            .build();
    }

    @Benchmark
    public void renderPlayer(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
