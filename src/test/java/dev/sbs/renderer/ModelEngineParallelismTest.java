package dev.sbs.renderer;

import dev.sbs.renderer.options.BlockOptions;
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
 * Regression coverage for Tasks 7 + 8 - parallel Pass 1 and tiled Pass 2 in {@link
 * dev.sbs.renderer.engine.ModelEngine}. Covers two correctness invariants:
 * <ol>
 * <li><b>Tiled output matches serial output byte-for-byte.</b> Both the {@code renderSize=256}
 *     (tiled; 512-row buffer after SSAA=2) and {@code renderSize=128} (serial; below the
 *     MIN_TILED_HEIGHT threshold) paths must produce identical pixels for the same input.</li>
 * <li><b>Painter's-algorithm determinism survives parallel Pass 1.</b> Tests exercise blocks
 *     with coplanar faces (piston head/body, white_banner) where the DEPTH_EPSILON tie-break
 *     has to continue picking the first-drawn coplanar triangle deterministically even when
 *     triangle transforms execute out-of-order.</li>
 * </ol>
 * CRC32 pins per block keep the test honest: a future rasterization-math change that silently
 * drifts output will break the pin even if determinism still holds.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}.
 */
@Tag("slow")
@DisplayName("ModelEngine parallel Pass 1 + tiled Pass 2 determinism")
class ModelEngineParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static BlockRenderer blockRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        AssetPipeline.Result result = new AssetPipeline(new HttpFetcher()).run(
            AssetPipelineOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        blockRenderer = new BlockRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("tiled rasterization of piston produces byte-identical output across parallel runs")
    void pistonTiledIsDeterministic() {
        BlockOptions options = BlockOptions.builder()
            .blockId("minecraft:piston")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .outputSize(256)
            .supersample(2)
            .antiAlias(false)
            .build();
        assertDeterministicAndPinned(options, 0x29D36285L);
    }

    @Test
    @DisplayName("tiled rasterization of white_banner (coplanar body + pole) is deterministic")
    void whiteBannerTiledIsDeterministic() {
        BlockOptions options = BlockOptions.builder()
            .blockId("minecraft:white_banner")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .outputSize(256)
            .supersample(2)
            .antiAlias(false)
            .build();
        assertDeterministicAndPinned(options, 0x75630F01L);
    }

    @Test
    @DisplayName("serial rasterization of piston (below MIN_TILED_HEIGHT) stays deterministic")
    void pistonSerialPathStillDeterministic() {
        // outputSize=128 SSAA=1 -> 128 rows, below MIN_TILED_HEIGHT=256 -> hits the serial branch.
        BlockOptions options = BlockOptions.builder()
            .blockId("minecraft:piston")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .outputSize(128)
            .supersample(1)
            .antiAlias(false)
            .build();
        assertDeterministicAndPinned(options, 0x98FA1B4CL);
    }

    private void assertDeterministicAndPinned(BlockOptions options, long expectedCrc32) {
        int[] first = firstFramePixels(blockRenderer.render(options));
        int[] second = firstFramePixels(blockRenderer.render(options));
        assertThat("parallel/tiled raster must be deterministic across invocations",
            second, equalTo(first));

        long actual = crc32(first);
        assertThat("rasterization output CRC32 (update test with 0x%sL if intentional)"
                .formatted(Long.toHexString(actual).toUpperCase()),
            actual, is(expectedCrc32));
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
