package lib.minecraft.renderer.bench;

import dev.simplified.image.frame.ImageFrame;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.GridRenderer;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jol.info.GraphLayout;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end atlas bake benchmark - the dominant CLI workload. Renders the full block + item set
 * ({@link AtlasOptions.Scope#BOTH}) into one grid image, exercising the parallel
 * {@link AtlasRenderer} per-model dispatch and its {@link GridRenderer} tile blitting under one
 * measurement.
 * <p>
 * Reported in {@link Mode#AverageTime} milliseconds. This is a whole-atlas timing, not a per-tile
 * one, so it captures the outer parallelism as well as the inner per-model render.
 * <p>
 * What a bake costs beside its wall time comes from {@link CpuMemoryProfiler}: the processor time it
 * burns across every thread, how many cores that keeps busy and the heap it allocates, beside the heap
 * that stays in use between bakes and the most an iteration of back-to-back bakes occupies, garbage
 * included. The atlas a bake hands back is measured once per trial and printed as a {@code # footprint}
 * line - its size in pixels and tiles, and the heap its pixels keep - so the allocation reads against
 * what the bake produced. That measuring bake runs in the trial's setup, which JMH runs inside the first
 * iteration, so the profiler needs the warmup iterations the build sets by default. Run:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=AtlasBakeBenchmark -PjmhProfilers=lib.minecraft.renderer.bench.CpuMemoryProfiler
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AtlasBakeBenchmark extends AbstractRendererBenchmark {

    /** Atlas renderer bound to the trial's pipeline context. */
    private AtlasRenderer renderer;

    /** Bake options - {@link AtlasOptions#defaults()}, held across all invocations. */
    private AtlasOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new AtlasRenderer(context());
        // Defaults: 128 px tiles, both sources - mirrors the generateAtlas Gradle task baseline. To
        // sweep tile size, edit the AtlasOptions.builder().tileSize(...) call here; there is no
        // dedicated -P override wired for this benchmark.
        this.options = AtlasOptions.defaults();

        // JOL sizes the frames' pixel buffers rather than the image: a frame is a record, whose field
        // offsets Unsafe refuses, and the buffers are where every byte of the image is.
        AtlasResult atlas = this.renderer.render(this.options);
        Object[] pixels = atlas.image().getFrames().stream().map(ImageFrame::pixels).toArray();
        System.out.printf("# footprint atlas=%dx%d tiles=%d skipped=%d pixels=%.1f MB%n",
            atlas.image().getWidth(), atlas.image().getHeight(), atlas.sidecar().count(),
            atlas.sidecar().skipped().size(), GraphLayout.parseInstance(pixels).totalSize() / CpuMemoryProfiler.MB);
    }

    @Benchmark
    public void bakeFullAtlas(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
