package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.options.PortalOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Portal bake benchmark - the single hottest CPU workload in the renderer (per-pixel SSAA over
 * 15-16 parallax layers). Primary measurement target for Task 3 (row-parallel bake) and Task 11
 * (SIMD layer transform).
 * <p>
 * Parameterised across both portal variants and both render types so Tasks 3/11 can validate
 * wins on the full code-path matrix.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PortalBakeBenchmark extends AbstractRendererBenchmark {

    @Param({"END_PORTAL", "END_GATEWAY"})
    public PortalOptions.Portal portal;

    @Param({"ISOMETRIC_3D", "PORTAL_FACE_2D"})
    public PortalOptions.Type type;

    private PortalRenderer renderer;
    private PortalOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new PortalRenderer(context());
        // Matches TestPortalMain's static-output footprint (512 px). Animated-frame bake is
        // intentionally out of scope - measurement variance across frames is better captured in
        // a dedicated multi-frame benchmark once Task 3 lands.
        this.options = PortalOptions.builder()
            .portal(this.portal)
            .type(this.type)
            .outputSize(512)
            .build();
    }

    @Benchmark
    public void bakeFace(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
