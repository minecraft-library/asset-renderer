package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Seam-1 (R9) probe: the per-frame block rebuild inside {@code BlockRenderer.Isometric3D}. The
 * isometric block path runs {@code buildRelitTriangles(tick, ...)} - variant resolution, face-texture
 * resolution, geometry assembly, and the inventory relight - <b>inside</b> the raster callback that
 * {@code Timeline.bake} invokes once per output frame, so an animated block (magma / sea_lantern -
 * flipbook {@code .mcmeta} strips) re-runs the entire build for every frame even though only the
 * face-texture re-sample truly varies per tick. R9 asks whether hoisting the frame-invariant build
 * (variant / geometry / relight once, texture re-sample per frame) would meaningfully cut render time.
 * <p>
 * This benchmark isolates that cost by rendering the same animated block at {@link #frameCount} 1 vs
 * {@code N}, holding every other input fixed. The marginal per-frame cost - both {@code (ms@N - ms@1)
 * / (N - 1)} and, under the {@code gc} profiler, {@code (alloc@N - alloc@1) / (N - 1)} - is the
 * per-frame rebuild-plus-raster the hoist would target; the {@code stack} profiler attributes that
 * marginal between the rebuild ({@code buildRelitTriangles} / {@code BlockGeometryKit.buildFromElements}
 * / {@code Textures.loadElementFaceTextures}) and the raster ({@code ModelEngine.rasterizeInternal}).
 * <p>
 * Rendered at {@code 128} px with SSAA and FXAA <b>off</b> so the raster is as cheap as possible and
 * the rebuild's share of each marginal frame is at its most visible - the conservative setting where
 * hoisting would help most. {@code ticksPerFrame = 1} makes consecutive frames sample distinct strip
 * frames, so the per-frame texture resolution is genuinely exercised (not a repeated cache hit). Run:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=AnimatedBlockRebuildBenchmark -PjmhProfilers=gc,stack
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AnimatedBlockRebuildBenchmark extends AbstractRendererBenchmark {

    /**
     * Animated full-cube block under test - both ship a flipbook {@code .mcmeta} strip, so the
     * per-tick face re-sample resolves a distinct frame each tick.
     */
    @Param({
        "minecraft:magma_block",
        "minecraft:sea_lantern"
    })
    public String blockId;

    /**
     * Output frame count. {@code 1} bakes a single static frame (one rebuild, the default block-icon
     * cost); {@code 16} bakes 16 frames, re-running the full per-frame rebuild 16 times - the seam the
     * marginal {@code (frameCount=16) - (frameCount=1)} isolates.
     */
    @Param({"1", "16"})
    public int frameCount;

    /** Block renderer bound to the trial's pipeline context. */
    private BlockRenderer renderer;

    /** Render options for the current {@link #blockId} / {@link #frameCount}, rebuilt once per trial. */
    private BlockOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new BlockRenderer(context());
        this.options = BlockOptions.builder()
            .blockId(this.blockId)
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .output(OutputOptions.builder()
                .canvasSize(128)
                .supersample(1)
                .antiAlias(false)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(this.frameCount)
                .ticksPerFrame(1)
                .build())
            .build();
    }

    @Benchmark
    public void renderAnimatedBlock(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
