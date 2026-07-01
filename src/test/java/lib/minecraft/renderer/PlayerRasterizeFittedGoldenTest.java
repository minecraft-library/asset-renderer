package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.options.PlayerOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.zip.CRC32;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Byte-identity pin for the {@link ModelEngine#rasterizeFitted} auto-fit path, whose only production
 * consumer is {@link PlayerRenderer} (its 3D body render at {@code PlayerRenderer.rasterize3D}). No
 * other byte-pinned test covers this method: {@link ModelEngineParallelismTest} exercises the plain
 * {@code rasterize} block path, and {@code TestPlayerRender} is a visual sweep that asserts nothing.
 *
 * <p>Both fitted arms are covered so a refactor that unifies the lens fork can prove it:
 * <ul>
 * <li><b>Orthographic arm</b> - a FULL body under {@link Projection#VANILLA_ISO} (the shipped iso
 *     player pose, {@link Lens.Kind#ORTHOGRAPHIC}), the 3D-fit {@code scale(fit).translate(-centre)}
 *     bake.</li>
 * <li><b>Perspective arm</b> - a SKULL under {@link Projection#PORTRAIT_HIGH}
 *     ({@link Lens.Kind#PERSPECTIVE}), the 2D post-projection {@code Fit2D} path.</li>
 * </ul>
 * Each case asserts render-twice determinism (parallel Pass 1 + tiled Pass 2 must be stable) then
 * pins a CRC32 over the ARGB pixels. A future rasterization-math change that silently drifts the
 * fitted output breaks the pin.
 *
 * <p>Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}. Uses the offline, pack-resolvable vanilla skin so no
 * network is required once the client jar is cached.
 */
@Tag("slow")
@DisplayName("ModelEngine.rasterizeFitted (player auto-fit) byte-identity pin")
class PlayerRasterizeFittedGoldenTest {

    private static final File CACHE_ROOT = new File("cache/it");

    /** Offline, pack-resolvable default skin texture id (matches {@code TestPlayerRender}). */
    private static final String SKIN_ID = "minecraft:entity/player/wide/steve";

    private static PlayerRenderer playerRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        Pipeline.Result result = Pipeline.run(
            PipelineOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        playerRenderer = new PlayerRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("FULL body under VANILLA_ISO (orthographic 3D-fit arm) is deterministic and pinned")
    void orthographicFittedArmIsPinned() {
        PlayerOptions options = PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .projection(Projection.VANILLA_ISO)
            .skinTextureId(Optional.of(SKIN_ID))
            .outputSize(256)
            .supersample(1)
            .antiAlias(false)
            .build();
        assertDeterministicAndPinned(options, 0xF9665E93L);
    }

    @Test
    @DisplayName("SKULL under PORTRAIT_HIGH (perspective Fit2D arm) is deterministic and pinned")
    void perspectiveFittedArmIsPinned() {
        PlayerOptions options = PlayerOptions.builder()
            .type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .projection(Projection.PORTRAIT_HIGH)
            .skinTextureId(Optional.of(SKIN_ID))
            .outputSize(256)
            .supersample(1)
            .antiAlias(false)
            .build();
        assertDeterministicAndPinned(options, 0x88CDA794L);
    }

    private void assertDeterministicAndPinned(PlayerOptions options, long expectedCrc32) {
        int[] first = firstFramePixels(playerRenderer.render(options));
        int[] second = firstFramePixels(playerRenderer.render(options));
        assertThat("fitted raster must be deterministic across invocations",
            second, equalTo(first));

        long actual = crc32(first);
        assertThat("fitted rasterization output CRC32 (update test with 0x%sL if intentional)"
                .formatted(Long.toHexString(actual).toUpperCase()),
            actual, is(expectedCrc32));
    }

    /** Extracts the first frame's full ARGB pixel array - these renders are single-frame (no glint). */
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
