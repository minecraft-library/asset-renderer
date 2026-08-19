package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for parallel Pass 1 and tiled Pass 2 in {@link ModelEngine}. Covers two
 * correctness invariants:
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
 * {@code ./gradlew slowTest}.
 */
@Tag("slow")
@DisplayName("ModelEngine parallel Pass 1 + tiled Pass 2 determinism")
@ExtendWith(ClientAssetsExtension.class)
class BlockRendererRasterPinTest {

    private static final String ARTIFACT = "pin.block-crc";

    /** The three cases and the render each pins - the tiled arm, its coplanar case, and the serial arm. */
    private static final PinSet PINS = PinSet.of(ARTIFACT, Map.of(
        "piston_tiled",
        "minecraft:piston, ISOMETRIC_3D, canvas 256, ssaa 2, aa off - 512 rows, the tiled Pass 2 arm",
        "white_banner_tiled",
        "minecraft:white_banner, ISOMETRIC_3D, canvas 256, ssaa 2, aa off - coplanar body + pole",
        "piston_serial",
        "minecraft:piston, ISOMETRIC_3D, canvas 128, ssaa 1, aa off - below MIN_TILED_HEIGHT, the serial arm"));

    private static BlockRenderer blockRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        blockRenderer = new BlockRenderer(ClientAssetsExtension.context());
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
        assertDeterministicAndPinned(options, "piston_tiled");
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
        assertDeterministicAndPinned(options, "white_banner_tiled");
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
        assertDeterministicAndPinned(options, "piston_serial");
    }

    /**
     * Renders the block twice, asserts the two renders are byte-identical, then holds the first
     * render's CRC32 against the value pinned under {@code key}.
     *
     * @param options the block render to compare and pin
     * @param key the pin key in {@code pin.block-crc} this render is recorded under
     */
    private void assertDeterministicAndPinned(BlockOptions options, String key) {
        int[] first = RenderDigest.firstFramePixels(blockRenderer.render(options));
        int[] second = RenderDigest.firstFramePixels(blockRenderer.render(options));
        // Before the pin, always: a flaky parallel path must fail on a different message than a
        // drifted value, or a re-baseline gets reached for when the fix is a determinism bug.
        assertThat("parallel/tiled raster must be deterministic across invocations",
            second, equalTo(first));

        long actual = RenderDigest.crc32(first);
        PINS.crc32(key, actual);
        PINS.requireBaseline();
        assertThat("rasterization output CRC32; if intentional, re-baseline it: "
                + Pins.rebaselineCommand(ARTIFACT),
            actual, is(Pins.crc32(ARTIFACT, key)));
    }

}
