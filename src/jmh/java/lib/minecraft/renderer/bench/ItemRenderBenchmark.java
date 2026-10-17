package lib.minecraft.renderer.bench;

import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Item render benchmark across the GUI shapes {@link HeldItemBenchmark} leaves out, one
 * {@link ItemRenderer#render} per operation:
 * <ul>
 * <li>{@code FLAT_SPRITE} - {@code minecraft:diamond_sword} as {@link ItemOptions.Type#GUI_2D}, one
 * layer sprite blitted onto the canvas through the pipeline-baked fast path - the baseline</li>
 * <li>{@code BLOCK_ICON} - {@code minecraft:grass_block} as {@link ItemOptions.Type#GUI_ICON}, routed to
 * the isometric block renderer with the item definition's grass tint on its faces</li>
 * <li>{@code GLINT} - {@code minecraft:enchanted_golden_apple} as {@link ItemOptions.Type#GUI_2D}, an
 * always-foil item, so the default animated glint bakes a 2-second loop at the default
 * {@link ItemOptions#getFramesPerSecond() 30 fps} - 60 frames, each the sprite under its own scrolled
 * foil</li>
 * <li>{@code DISPATCH} - {@code minecraft:clock} as {@link ItemOptions.Type#GUI_2D} with
 * {@link AnimationOptions#isDeriveTimeline() deriveTimeline} on, so its world-time dispatch tree sets a
 * one-day game-time strip of 64 frames, one per clock face, each resolving the tree at its own tick. A
 * still clock or compass lands on the same pipeline-baked fast path as {@code FLAT_SPRITE}, so the
 * derived day is what makes the dispatch a path of its own</li>
 * </ul>
 * Every subject renders at {@code 256} px on {@link ItemOptions#DEFAULT_OUTPUT} - the
 * {@code VANILLA_GUI_ITEM} projection with no supersampling and no FXAA - so the timings carry no
 * anti-alias cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ItemRenderBenchmark extends AbstractRendererBenchmark {

    /** Item shape under test - one JMH sub-benchmark per shape in the class-doc spread. */
    @Param({"FLAT_SPRITE", "BLOCK_ICON", "GLINT", "DISPATCH"})
    public String subject;

    /** Item renderer bound to the trial's pipeline context. */
    private ItemRenderer renderer;

    /** Render options for the current {@link #subject}, rebuilt once per trial. */
    private ItemOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new ItemRenderer(context());
        this.options = Subject.valueOf(this.subject).shape.apply(ItemOptions.builder()
                .output(ItemOptions.DEFAULT_OUTPUT.mutate()
                    .canvasSize(256)
                    .build()))
            .build();
    }

    @Benchmark
    public void renderItem(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

    /**
     * The item id, render type and timing behind each {@link #subject} value.
     */
    @RequiredArgsConstructor
    private enum Subject {

        FLAT_SPRITE(builder -> builder
            .itemId("minecraft:diamond_sword")
            .type(ItemOptions.Type.GUI_2D)),
        BLOCK_ICON(builder -> builder
            .itemId("minecraft:grass_block")
            .type(ItemOptions.Type.GUI_ICON)),
        GLINT(builder -> builder
            .itemId("minecraft:enchanted_golden_apple")
            .type(ItemOptions.Type.GUI_2D)),
        DISPATCH(builder -> builder
            .itemId("minecraft:clock")
            .type(ItemOptions.Type.GUI_2D)
            .animation(AnimationOptions.builder()
                .deriveTimeline(true)
                .build()));

        /** Applies the subject's item id, type and timing to a builder already carrying the output. */
        private final @NotNull UnaryOperator<ItemOptions.Builder> shape;

    }

}
