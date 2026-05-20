package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.geometry.ProjectionMath;
import lib.minecraft.renderer.tensor.Vector2f;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Focused micro for {@link ProjectionMath#isInsideTriangle}, the per-pixel coverage test in
 * {@code ModelEngine.rasterizeTile}. Constructs three synthetic triangle fixtures with shuffled
 * sample point lists so each invocation isolates the cost of the inside test from the surrounding
 * rasterizer machinery (UV interp, depth test, texture sample, blend, write).
 * <p>
 * Phase 1 of the Pineda-edge-function migration: enabler for subsequent perf phases. The
 * fixtures are picked to span the realistic input distribution the rasterizer sees:
 * <ul>
 * <li>{@link #insideSmall} - 16x16 bbox triangle, typical UI / inventory icon.</li>
 * <li>{@link #insideMedium} - 64x64 bbox triangle, typical entity body face.</li>
 * <li>{@link #insideSliver} - 100x2 bbox triangle, worst-case bounds-to-area ratio that drives
 *     the SIMD coverage path's win signal (most samples reject, so wasted scalar tests dominate
 *     before SIMD masking lands).</li>
 * <li>{@link #insideMediumScan} - same 64x64 triangle as {@link #insideMedium} but with samples
 *     in raster-scan order (top-left to bottom-right). Phase 3's incremental edge update needs
 *     this adjacency to show its win; phases 1-2 see no difference vs the shuffled variant.</li>
 * </ul>
 * <p>
 * Each {@code @Benchmark} method loops the 1024 pre-shuffled sample points once and consumes the
 * boolean result via {@link Blackhole}. {@link Mode#AverageTime} + nanosecond output keeps the
 * per-test score readable without trailing scientific notation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IsInsideTriangleBenchmark {

    /** Number of sample points evaluated per {@code @Benchmark} invocation. */
    private static final int SAMPLES = 1024;

    /** Deterministic RNG seed so trial-to-trial sample distributions stay reproducible. */
    private static final long SEED = 0x1234_5678_9ABC_DEF0L;

    private Vector2f smallV0, smallV1, smallV2;
    private float[] smallPx, smallPy;

    private Vector2f medV0, medV1, medV2;
    private float[] medPx, medPy;

    private Vector2f sliverV0, sliverV1, sliverV2;
    private float[] sliverPx, sliverPy;

    /** Sequential raster-scan samples over the medium triangle's bbox - reused by Phase 3. */
    private float[] medScanPx, medScanPy;

    @Setup(Level.Trial)
    public void setupFixtures() {
        // Small triangle: 16x16 bbox, CCW Y-down winding so front-facing per ModelEngine
        // conventions. Vertices chosen so the triangle covers roughly half the bbox.
        this.smallV0 = new Vector2f(8.0f, 0.5f);
        this.smallV1 = new Vector2f(0.5f, 15.5f);
        this.smallV2 = new Vector2f(15.5f, 15.5f);
        Sample s = shuffledSamples(SAMPLES, 0f, 0f, 16f, 16f, SEED);
        this.smallPx = s.xs;
        this.smallPy = s.ys;

        // Medium triangle: 64x64 bbox, typical entity face dimensions in the iso projection.
        this.medV0 = new Vector2f(32.0f, 1.0f);
        this.medV1 = new Vector2f(1.0f, 62.5f);
        this.medV2 = new Vector2f(62.5f, 62.5f);
        Sample m = shuffledSamples(SAMPLES, 0f, 0f, 64f, 64f, SEED + 1);
        this.medPx = m.xs;
        this.medPy = m.ys;

        // Sliver: 100x2 long thin triangle, worst case for bbox-vs-coverage. Most samples will
        // reject - this is the case where SIMD masking has the most to skip.
        this.sliverV0 = new Vector2f(0.5f, 0.5f);
        this.sliverV1 = new Vector2f(99.5f, 1.0f);
        this.sliverV2 = new Vector2f(50.0f, 1.5f);
        Sample sl = shuffledSamples(SAMPLES, 0f, 0f, 100f, 2f, SEED + 2);
        this.sliverPx = sl.xs;
        this.sliverPy = sl.ys;

        // Sequential raster-scan samples over the medium triangle's bbox. Walks row-by-row,
        // pixel-by-pixel - matches the adjacency Phase 3's incremental edge update exploits.
        // 32x32 = 1024 samples to keep the count consistent with the shuffled variants.
        this.medScanPx = new float[SAMPLES];
        this.medScanPy = new float[SAMPLES];
        int i = 0;
        for (int py = 0; py < 32 && i < SAMPLES; py++) {
            for (int px = 0; px < 32 && i < SAMPLES; px++) {
                this.medScanPx[i] = px + 16f + 0.5f;  // center in the triangle's middle band
                this.medScanPy[i] = py + 16f + 0.5f;
                i++;
            }
        }
    }

    @Benchmark
    public void insideSmall(Blackhole bh) {
        for (int i = 0; i < SAMPLES; i++) {
            bh.consume(ProjectionMath.isInsideTriangle(
                this.smallV0, this.smallV1, this.smallV2, this.smallPx[i], this.smallPy[i]));
        }
    }

    @Benchmark
    public void insideMedium(Blackhole bh) {
        for (int i = 0; i < SAMPLES; i++) {
            bh.consume(ProjectionMath.isInsideTriangle(
                this.medV0, this.medV1, this.medV2, this.medPx[i], this.medPy[i]));
        }
    }

    @Benchmark
    public void insideSliver(Blackhole bh) {
        for (int i = 0; i < SAMPLES; i++) {
            bh.consume(ProjectionMath.isInsideTriangle(
                this.sliverV0, this.sliverV1, this.sliverV2, this.sliverPx[i], this.sliverPy[i]));
        }
    }

    @Benchmark
    public void insideMediumScan(Blackhole bh) {
        for (int i = 0; i < SAMPLES; i++) {
            bh.consume(ProjectionMath.isInsideTriangle(
                this.medV0, this.medV1, this.medV2, this.medScanPx[i], this.medScanPy[i]));
        }
    }

    private record Sample(float[] xs, float[] ys) {}

    private static Sample shuffledSamples(int count, float x0, float y0, float x1, float y1, long seed) {
        Random rng = new Random(seed);
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = x0 + rng.nextFloat() * (x1 - x0);
            ys[i] = y0 + rng.nextFloat() * (y1 - y0);
        }
        return new Sample(xs, ys);
    }

}
