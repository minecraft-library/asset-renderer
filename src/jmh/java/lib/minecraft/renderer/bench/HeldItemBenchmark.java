package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.spec.RenderOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Held-item 3D render benchmark - exercises {@link ItemRenderer.Held3D}, which renders the item
 * through {@link ModelEngine} with the model's {@code thirdperson_righthand} display transform
 * applied. Measures the ModelEngine + SIMD math on the item (non-block) rasterization branch at
 * {@code 256} px.
 * <p>
 * The item spread covers both {@code Held3D} dispatch paths: {@code diamond_sword} / {@code bow} /
 * {@code compass} carry model element boxes (full 3D geometry), while a layer-only item such as
 * {@code iron_chestplate} falls back to the thin textured slab path.
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
            .render(ItemOptions.DEFAULT_RENDER.mutate()
                .outputSize(256)
                .build())
            .build();
    }

    @Benchmark
    public void renderHeldItem(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
