package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.options.ItemOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Held-item 3D render benchmark - exercises {@link ItemRenderer.Held3D} via the display
 * transform path. Primary measurement target for Task 7/8 (ModelEngine) and Task 10 (SIMD math)
 * on the non-block rasterization branch.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class HeldItemBenchmark extends AbstractRendererBenchmark {

    @Param({
        "minecraft:diamond_sword",
        "minecraft:iron_chestplate",
        "minecraft:bow",
        "minecraft:compass"
    })
    public String itemId;

    private ItemRenderer renderer;
    private ItemOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new ItemRenderer(context());
        this.options = ItemOptions.builder()
            .itemId(this.itemId)
            .type(ItemOptions.Type.HELD_3D)
            .outputSize(256)
            .build();
    }

    @Benchmark
    public void renderHeldItem(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
