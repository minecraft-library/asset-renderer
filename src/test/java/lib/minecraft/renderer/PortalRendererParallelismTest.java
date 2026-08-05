package lib.minecraft.renderer;

import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for row-parallel {@link PortalRenderer#bakeFace}. Confirms the parallel
 * path is pixel-deterministic (two consecutive renders produce identical bytes) and pins a
 * CRC32 of each output so a future rasterization-math regression fails loud rather than
 * silently drifting output.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}.
 */
@Tag("slow")
@DisplayName("PortalRenderer parallel bake determinism")
class PortalRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");

    private static final String ARTIFACT = "pin.portal-crc";

    /** The two faces and the render each pins. */
    private static final PinSet PINS = PinSet.of(ARTIFACT, Map.of(
        "end_portal_face_2d", "Portal.END_PORTAL, PORTAL_FACE_2D, canvas 128",
        "end_gateway_face_2d", "Portal.END_GATEWAY, PORTAL_FACE_2D, canvas 128"));

    private static PortalRenderer renderer;

    @BeforeAll
    static void bootstrapPipeline() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        renderer = new PortalRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("END_PORTAL 2D face bake is byte-for-byte stable across parallel runs")
    void endPortal2DIsDeterministic() {
        PortalOptions options = PortalOptions.builder()
            .portal(PortalOptions.Portal.END_PORTAL)
            .type(PortalOptions.Type.PORTAL_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(128)
                .build())
            .build();
        assertIdenticalAndMatchesHash(options, "end_portal_face_2d");
    }

    @Test
    @DisplayName("END_GATEWAY 2D face bake is byte-for-byte stable across parallel runs")
    void endGateway2DIsDeterministic() {
        PortalOptions options = PortalOptions.builder()
            .portal(PortalOptions.Portal.END_GATEWAY)
            .type(PortalOptions.Type.PORTAL_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(128)
                .build())
            .build();
        assertIdenticalAndMatchesHash(options, "end_gateway_face_2d");
    }

    /**
     * Renders the portal twice with the given options and asserts:
     * <ol>
     * <li>The two renders produce byte-identical pixel buffers (parallel-stream determinism).</li>
     * <li>The first render's CRC32 matches the value pinned in {@code pin.portal-crc} (protects
     *     against future math changes that would silently alter output without tripping the
     *     determinism check).</li>
     * </ol>
     * A new case is added by naming it in {@code PINS}, running, and promoting the capture - never by
     * reading a value out of a failure message and pasting it back.
     */
    private void assertIdenticalAndMatchesHash(PortalOptions options, String key) {
        int[] first = RenderDigest.firstFramePixels(renderer.render(options));
        int[] second = RenderDigest.firstFramePixels(renderer.render(options));

        // Before the pin, always: a flaky parallel path must fail on a different message than a
        // drifted value, or a re-baseline gets reached for when the fix is a determinism bug.
        assertThat("parallel render must be deterministic across invocations",
            second, equalTo(first));

        long actual = RenderDigest.crc32(first);
        PINS.crc32(key, actual);
        PINS.requireBaseline();
        assertThat("rasterization output CRC32; if intentional, re-baseline it: "
                + Pins.rebaselineCommand(ARTIFACT),
            actual, is(Pins.crc32(ARTIFACT, key)));
    }

}
