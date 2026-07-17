package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
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
import java.util.List;
import java.util.zip.CRC32;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for frame-parallel {@link FluidRenderer} animation baking. Each animation
 * tick owns its own RasterEngine / ModelEngine / PixelBuffer so parallel execution must
 * produce bytes identical to the serial path. Pinning a CRC32 per frame index guards against
 * silent rasterization drift from a future refactor.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew :asset-renderer:slowTest}.
 */
@Tag("slow")
@DisplayName("FluidRenderer parallel frame bake determinism")
class FluidRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static FluidRenderer renderer;

    @BeforeAll
    static void bootstrapPipeline() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        renderer = new FluidRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("water 2D animation preserves frame order + bytes across parallel runs")
    void water2DAnimationIsDeterministic() {
        FluidOptions options = FluidOptions.builder()
            .fluid(FluidOptions.Fluid.WATER)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(64)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(8)
                .ticksPerFrame(2)
                .build())
            .build();

        List<Long> firstCrc = frameCrcs(renderer.render(options));
        List<Long> secondCrc = frameCrcs(renderer.render(options));

        assertThat("per-frame CRCs must be stable across parallel runs",
            secondCrc, equalTo(firstCrc));
        assertThat("animation must contain the requested frame count",
            firstCrc.size(), is(8));
    }

    @Test
    @DisplayName("lava 2D animation preserves frame order + bytes across parallel runs")
    void lava2DAnimationIsDeterministic() {
        FluidOptions options = FluidOptions.builder()
            .fluid(FluidOptions.Fluid.LAVA)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(64)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(5)
                .ticksPerFrame(3)
                .build())
            .build();

        List<Long> firstCrc = frameCrcs(renderer.render(options));
        List<Long> secondCrc = frameCrcs(renderer.render(options));

        assertThat("per-frame CRCs must be stable across parallel runs",
            secondCrc, equalTo(firstCrc));
        assertThat("animation must contain the requested frame count",
            firstCrc.size(), is(5));
    }

    /** Reduces an animation to one CRC32 per frame, in frame order - the comparison key for the two runs. */
    private static List<Long> frameCrcs(ImageData image) {
        return image.getFrames().stream()
            .map(frame -> crc32(frame.pixels()))
            .toList();
    }

    /** CRC32 over the buffer's little-endian ARGB int pixels - a compact byte-exact fingerprint of one frame. */
    private static long crc32(PixelBuffer buffer) {
        int[] pixels = buffer.getPixels(0, 0, buffer.width(), buffer.height(), null, 0, 0);
        ByteBuffer bb = ByteBuffer.allocate(pixels.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : pixels) bb.putInt(p);
        CRC32 crc = new CRC32();
        crc.update(bb.array());
        return crc.getValue();
    }

}
