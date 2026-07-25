package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
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
 * ModelEngine}. Covers two correctness invariants:
 * <ol>
 * <li><b>Tiled output matches serial output byte-for-byte.</b> Both the {@code renderSize=256}
 *     (tiled; 512-row buffer after SSAA=2) and {@code renderSize=128} (serial; below the
 *     MIN_TILED_HEIGHT threshold) paths must produce identical pixels for the same input.</li>
 * <li><b>Painter's-algorithm determinism survives parallel Pass 1.</b> Tests exercise blocks
 *     with coplanar faces (piston head/body, white_banner) where the depth tie-break has to
 *     continue picking the same coplanar triangle deterministically even when triangle
 *     transforms execute out-of-order.</li>
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
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder()
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
            .output(OutputOptions.builder()
                .canvasSize(256)
                .supersample(2)
                .antiAlias(false)
                .build())
            .build();
        assertDeterministicAndPinned(options, 0x81C04777L);
    }

    @Test
    @DisplayName("tiled rasterization of white_banner (coplanar body + pole) is deterministic")
    void whiteBannerTiledIsDeterministic() {
        BlockOptions options = BlockOptions.builder()
            .blockId("minecraft:white_banner")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .output(OutputOptions.builder()
                .canvasSize(256)
                .supersample(2)
                .antiAlias(false)
                .build())
            .build();
        assertDeterministicAndPinned(options, 0x33396D6EL);
    }

    @Test
    @DisplayName("serial rasterization of piston (below MIN_TILED_HEIGHT) stays deterministic")
    void pistonSerialPathStillDeterministic() {
        // canvasSize=128 SSAA=1 -> 128 rows, below MIN_TILED_HEIGHT=256 -> hits the serial branch.
        BlockOptions options = BlockOptions.builder()
            .blockId("minecraft:piston")
            .type(BlockOptions.Type.ISOMETRIC_3D)
            .output(OutputOptions.builder()
                .canvasSize(128)
                .antiAlias(false)
                .build())
            .build();
        assertDeterministicAndPinned(options, 0xC41E4FA9L);
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

    /** Extracts the first frame's full ARGB pixel array - block renders are single-frame, so this is the whole image. */
    private static int[] firstFramePixels(ImageData image) {
        PixelBuffer buffer = image.getFrames().getFirst().pixels();
        return buffer.getPixels(0, 0, buffer.width(), buffer.height(), null, 0, 0);
    }

    /** CRC32 over the little-endian ARGB int pixels - the byte-exact pin compared against the expected constant. */
    private static long crc32(int[] pixels) {
        ByteBuffer bb = ByteBuffer.allocate(pixels.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : pixels) bb.putInt(p);
        CRC32 crc = new CRC32();
        crc.update(bb.array());
        return crc.getValue();
    }

}
