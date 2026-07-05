package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Portal bake benchmark - the single hottest CPU workload in the renderer, CPU-baking the vanilla
 * end-portal star-field shader per pixel. {@link PortalOptions.Portal#END_PORTAL} runs 15 parallax
 * layers, {@link PortalOptions.Portal#END_GATEWAY} runs 16, both under supersampling at {@code 512}
 * px. Measures the row-parallel bake and SIMD layer transform.
 * <p>
 * The {@code portal x type} parameter cross-product exercises the full code-path matrix
 * ({@link PortalOptions.Type#ISOMETRIC_3D} 3D geometry vs {@link PortalOptions.Type#PORTAL_FACE_2D}
 * flat face) so wins can be validated on every branch. Animated-frame bakes are out of scope here
 * (see {@link #onSetupTrial()}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PortalBakeBenchmark extends AbstractRendererBenchmark {

    /** Portal variant under test - selects the 15- vs 16-layer parallax stack. */
    @Param({"END_PORTAL", "END_GATEWAY"})
    public PortalOptions.Portal portal;

    /** Render type under test - 3D geometry vs flat portal face. */
    @Param({"ISOMETRIC_3D", "PORTAL_FACE_2D"})
    public PortalOptions.Type type;

    /** Portal renderer bound to the trial's pipeline context. */
    private PortalRenderer renderer;

    /** Bake options for the current {@link #portal} / {@link #type} pair. */
    private PortalOptions options;

    @Override
    protected void onSetupTrial() {
        this.renderer = new PortalRenderer(context());
        // Matches TestPortalRenderer's static-output footprint (512 px). Animated-frame bake is
        // intentionally out of scope - measurement variance across frames is better captured in
        // a dedicated multi-frame benchmark.
        this.options = PortalOptions.builder()
            .portal(this.portal)
            .type(this.type)
            .output(OutputOptions.builder()
                .canvasSize(512)
                .build())
            .build();
    }

    @Benchmark
    public void bakeFace(Blackhole bh) {
        bh.consume(this.renderer.render(this.options));
    }

}
