package lib.minecraft.renderer.bench;

import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.bake.pose.PoseStrips;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.vanilla.appearance.villager.VillagerProfession;
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
 * Entity render benchmark across a spread of hot-path shapes, one {@link EntityRenderer#render} per
 * operation:
 * <ul>
 * <li>{@code SMALL} - {@code minecraft:chicken}, a handful of cubes on one texture, so the per-render
 * fixed cost (style and texture resolution, canvas fit, relight) is a large share of the timing</li>
 * <li>{@code LARGE} - {@code minecraft:ender_dragon}, a many-part mob (neck, tail and wing chains) whose
 * triangle build and rasterization dominate the timing</li>
 * <li>{@code MULTI_LAYER} - {@code minecraft:villager} as a librarian: the body plus three model-overlay
 * passes (biome robe, profession clothes and hat, level badge), each building its triangles from its
 * own texture into one shared depth pass</li>
 * <li>{@code ANIMATED} - {@code minecraft:zombie} in its {@code stride} style, its derived walk strip
 * ({@link PoseStrips#of}), whose canvas is measured across every frame and whose posed geometry is
 * rebuilt and rasterized once per frame</li>
 * </ul>
 * Every subject renders at {@code 256} px on {@link EntityOptions#DEFAULT_OUTPUT} - the
 * {@code VANILLA_ISO} projection with no supersampling and no FXAA - under
 * {@link EntityOptions.FitMode#OUTPUT_SIZE}, so the timings carry no anti-alias cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EntityRenderBenchmark extends AbstractRendererBenchmark {

    /** Hot-path shape under test - one JMH sub-benchmark per shape in the class-doc spread. */
    @Param({"SMALL", "LARGE", "MULTI_LAYER", "ANIMATED"})
    public String subject;

    /** Entity renderer bound to the trial's pipeline context. */
    private EntityRenderer renderer;

    /** Render options for the current {@link #subject}, rebuilt once per trial. */
    private EntityOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new EntityRenderer(context());
        this.options = Subject.valueOf(this.subject).shape.apply(EntityOptions.builder()
                .output(EntityOptions.DEFAULT_OUTPUT.mutate()
                    .canvasSize(256)
                    .build()))
            .build();
    }

    @Benchmark
    public void renderEntity(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

    /**
     * The entity and selections behind each {@link #subject} value.
     */
    @RequiredArgsConstructor
    private enum Subject {

        SMALL(builder -> builder.entityId("minecraft:chicken")),
        LARGE(builder -> builder.entityId("minecraft:ender_dragon")),
        MULTI_LAYER(builder -> builder
            .entityId("minecraft:villager")
            .appearance(AppearanceOptions.builder()
                .villagerProfession(VillagerProfession.LIBRARIAN)
                .build())),
        ANIMATED(builder -> builder
            .entityId("minecraft:zombie")
            .style("stride"));

        /** Applies the subject's entity id and selections to a builder already carrying the output. */
        private final @NotNull UnaryOperator<EntityOptions.Builder> shape;

    }

}
