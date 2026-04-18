package dev.sbs.renderer;

import dev.sbs.renderer.options.PortalOptions;
import dev.sbs.renderer.pipeline.AssetPipeline;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for Task 3 - row-parallel {@link PortalRenderer#bakeFace}. Confirms the
 * parallel path is pixel-deterministic (two consecutive renders produce identical bytes) and
 * pins a CRC32 of each output so a future rasterization-math regression (e.g. during Task 11's
 * SIMD rewrite) fails loud rather than silently drifting output.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}.
 */
@Tag("slow")
@DisplayName("PortalRenderer parallel bake determinism")
class PortalRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static PortalRenderer renderer;

    @BeforeAll
    static void bootstrapPipeline() {
        AssetPipeline.Result result = new AssetPipeline(new HttpFetcher()).run(
            AssetPipelineOptions.builder()
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
            .outputSize(128)
            .build();
        assertIdenticalAndMatchesHash(options, 0x102F6A01L);
    }

    @Test
    @DisplayName("END_GATEWAY 2D face bake is byte-for-byte stable across parallel runs")
    void endGateway2DIsDeterministic() {
        PortalOptions options = PortalOptions.builder()
            .portal(PortalOptions.Portal.END_GATEWAY)
            .type(PortalOptions.Type.PORTAL_FACE_2D)
            .outputSize(128)
            .build();
        assertIdenticalAndMatchesHash(options, 0x6A1994F8L);
    }

    /**
     * Renders the portal twice with the given options and asserts:
     * <ol>
     * <li>The two renders produce byte-identical pixel buffers (parallel-stream determinism).</li>
     * <li>The first render's CRC32 matches the supplied expected value (protects against future
     *     math changes that would silently alter output without tripping the determinism check).</li>
     * </ol>
     * When adding a new case, set {@code expectedCrc32} to {@code 0L} on first run, read the
     * actual CRC from the failure message, then pin it.
     */
    private void assertIdenticalAndMatchesHash(PortalOptions options, long expectedCrc32) {
        int[] first = firstFramePixels(renderer.render(options));
        int[] second = firstFramePixels(renderer.render(options));

        assertThat("parallel render must be deterministic across invocations",
            second, equalTo(first));

        long actualCrc32 = crc32(first);
        assertThat("rasterization output CRC32 (update test with 0x%sL if intentional)"
                .formatted(Long.toHexString(actualCrc32).toUpperCase()),
            actualCrc32, is(expectedCrc32));
    }

    private static int[] firstFramePixels(ImageData image) {
        PixelBuffer buffer = image.getFrames().getFirst().pixels();
        return buffer.pixels().clone();
    }

    private static long crc32(int[] pixels) {
        ByteBuffer bb = ByteBuffer.allocate(pixels.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : pixels) bb.putInt(p);
        CRC32 crc = new CRC32();
        crc.update(bb.array());
        return crc.getValue();
    }

}
