package lib.minecraft.renderer.bench;

import dev.simplified.image.pixel.PixelBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Probe of the per-pixel texture indirection in {@code ModelEngine.rasterizeTile}. Every
 * {@code VisibleTriangle} carries a direct {@code PixelBuffer texture}; the rasterizer inner loop
 * samples it per fragment via {@code texture.width()} / {@code texture.height()} / {@code
 * texture.getPixel(tx, ty)} with no per-triangle flatten or cache. The open question is whether
 * hoisting that to a raw {@code int[]} sample (fetch the backing array + dimensions once per
 * triangle, index it directly per pixel) would meaningfully cut per-pixel cost.
 * <p>
 * This microbenchmark answers it directly with a head-to-head A/B over the <b>exact</b> inner-loop
 * sample arithmetic (interpolated {@code u}/{@code v} → clamp to texel → fetch), holding the sample
 * distribution fixed:
 * <ul>
 * <li>{@link #sampleViaPixelBuffer} - the current path: a {@link PixelBuffer} field, sampled through
 *     {@code width()} / {@code height()} / {@code getPixel(x, y)}.</li>
 * <li>{@link #sampleViaFlatArray} - the "flattened" path: the backing {@code int[]} plus its
 *     {@code w} / {@code h} hoisted into locals, indexed as {@code data[ty * w + tx]}.</li>
 * </ul>
 * {@code PixelBuffer.getPixel} is a single {@code data[y * width + x]} array read and {@code width()} /
 * {@code height()} are plain field getters, so the two paths are expected to JIT-compile to the same
 * code (the field loads are loop-invariant per triangle and hoist automatically) - a statistically
 * indistinguishable result confirms the indirection is free and the seam is a no-op. Texel dimensions
 * span a {@code 16}px block face and a {@code 64}px entity face. No pipeline load - this is pure
 * arithmetic, so it extends {@code Scope.Benchmark} state directly like
 * {@link IsInsideTriangleBenchmark}. Run:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=TextureSampleIndirectionBenchmark -PjmhProfilers=stack
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class TextureSampleIndirectionBenchmark {

    /**
     * Number of texel samples evaluated per {@code @Benchmark} invocation. Large ({@code 2^20}) so the
     * per-op time lands in the multi-significant-figure millisecond range - the JMH result unit is
     * pinned to {@code ms} by the module's {@code jmh {}} config, so a small sample count would round
     * both paths to an indistinguishable {@code 0.001 ms/op} and hide any real difference.
     */
    private static final int SAMPLES = 1 << 20;

    /**
     * Size of the reused {@code (u, v)} sample-coordinate pool - a power of two so the per-sample index
     * is a cheap mask. Kept small (L1-resident) and cycled {@link #SAMPLES} times so the coordinate
     * reads stay cache-hot and the texture fetch is the dominant, visible term of each path.
     */
    private static final int POOL = 1 << 10;

    /** Deterministic RNG seed so the sample distribution stays reproducible across trials. */
    private static final long SEED = 0x0F1E_2D3C_4B5A_6978L;

    /** Square texel dimension under test - a {@code 16}px block face or a {@code 64}px entity face. */
    @Param({"16", "64"})
    public int texSize;

    /** The texture as a {@link PixelBuffer} (the current per-pixel indirection under test). */
    private PixelBuffer texture;

    /** The same pixels as a raw {@code int[]} plus dimensions (the flattened sample). */
    private int[] flatData;
    private int flatWidth;
    private int flatHeight;

    /** The reused pool of interpolated {@code u} / {@code v} sample coordinates in {@code [0, 1)}. */
    private float[] us, vs;

    @Setup(Level.Trial)
    public void setupFixtures() {
        Random rng = new Random(SEED);
        int w = this.texSize;
        int h = this.texSize;

        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = rng.nextInt();
        this.texture = PixelBuffer.of(pixels, w, h);
        this.flatData = pixels.clone();
        this.flatWidth = w;
        this.flatHeight = h;

        this.us = new float[POOL];
        this.vs = new float[POOL];
        for (int i = 0; i < POOL; i++) {
            this.us[i] = rng.nextFloat();
            this.vs[i] = rng.nextFloat();
        }
    }

    /**
     * Current path: sample the {@link PixelBuffer} through its accessors, reproducing the exact
     * {@code ModelEngine.rasterizeTile} clamp-and-fetch for each interpolated {@code (u, v)}.
     */
    @Benchmark
    public void sampleViaPixelBuffer(Blackhole bh) {
        PixelBuffer tex = this.texture;
        float[] uu = this.us;
        float[] vv = this.vs;
        int acc = 0;
        for (int i = 0; i < SAMPLES; i++) {
            int j = i & (POOL - 1);
            float u = uu[j];
            float v = vv[j];
            int tx = Math.clamp((int) (u * tex.width()), 0, tex.width() - 1);
            int ty = Math.clamp((int) (v * tex.height()), 0, tex.height() - 1);
            acc ^= tex.getPixel(tx, ty);
        }
        bh.consume(acc);
    }

    /**
     * Flattened path: hoist the backing {@code int[]} and dimensions into locals and index the
     * array directly, doing otherwise-identical arithmetic to {@link #sampleViaPixelBuffer}.
     */
    @Benchmark
    public void sampleViaFlatArray(Blackhole bh) {
        int[] data = this.flatData;
        int w = this.flatWidth;
        int h = this.flatHeight;
        float[] uu = this.us;
        float[] vv = this.vs;
        int acc = 0;
        for (int i = 0; i < SAMPLES; i++) {
            int j = i & (POOL - 1);
            float u = uu[j];
            float v = vv[j];
            int tx = Math.clamp((int) (u * w), 0, w - 1);
            int ty = Math.clamp((int) (v * h), 0, h - 1);
            acc ^= data[ty * w + tx];
        }
        bh.consume(acc);
    }

}
