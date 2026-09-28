package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Held-item 3D render benchmark - exercises {@link ItemRenderer.Held3D} on the thin-slab branch at
 * {@code 256} px: all four subjects are flat sprites whose layer stack is composited onto the slab
 * and rasterized through {@link Rasterizer} with the model's {@code thirdperson_righthand} display
 * transform.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class HeldItemBenchmark extends AbstractRendererBenchmark {

    /** Item id under test - one JMH sub-benchmark per item. */
    @Param({
        "minecraft:diamond_sword",
        "minecraft:iron_chestplate",
        "minecraft:bow",
        "minecraft:compass"
    })
    public String itemId;

    /** Item renderer bound to the trial's pipeline context. */
    private ItemRenderer renderer;

    /** Render options for the current {@link #itemId} in {@link ItemOptions.Type#HELD_3D} mode. */
    private ItemOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new ItemRenderer(context());
        this.options = ItemOptions.builder()
            .itemId(this.itemId)
            .type(ItemOptions.Type.HELD_3D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate()
                .canvasSize(256)
                .build())
            .build();
    }

    @Benchmark
    public void renderHeldItem(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
