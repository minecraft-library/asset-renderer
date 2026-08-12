package lib.minecraft.renderer;

import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage for frame-parallel {@link FluidRenderer} animation baking. Each animation
 * tick owns its own RasterEngine / ModelEngine / PixelBuffer so parallel execution must produce
 * bytes identical to the serial path.
 * <p>
 * <b>The per-frame CRC32s are pinned, not merely self-consistent.</b> Comparing run A against run B
 * proves the parallel bake is stable and nothing else: a refactor that changed every frame
 * identically in both runs passed. The thirteen values - eight water frames and five lava - live in
 * {@code pin.fluid-crc}, so a rasterization change that drifts the output fails here even when both
 * runs agree with each other.
 * <p>
 * Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew slowTest}.
 */
@Tag("slow")
@DisplayName("FluidRenderer parallel frame bake determinism")
class FluidRendererParallelismTest {

    private static final File CACHE_ROOT = new File("cache/it");

    private static final String ARTIFACT = "pin.fluid-crc";

    private static final int CANVAS = 64;

    private static final int WATER_FRAMES = 8;
    private static final int WATER_TICKS_PER_FRAME = 2;
    private static final int LAVA_FRAMES = 5;
    private static final int LAVA_TICKS_PER_FRAME = 3;

    /**
     * Every frame of both animations, keyed {@code <fluid>_frame_<index>}.
     *
     * <p>Derived from the two animations' own parameters rather than written out thirteen times, so
     * the declared key set and the frames actually rendered cannot disagree - which is what makes the
     * completeness contract meaningful instead of a second transcription of the same table.
     */
    private static final PinSet PINS = PinSet.of(ARTIFACT, subjects());

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
    @DisplayName("water 2D animation preserves frame order + bytes across parallel runs, and is pinned")
    void water2DAnimationIsDeterministic() {
        assertDeterministicAndPinned(FluidOptions.Fluid.WATER, WATER_FRAMES, WATER_TICKS_PER_FRAME);
    }

    @Test
    @DisplayName("lava 2D animation preserves frame order + bytes across parallel runs, and is pinned")
    void lava2DAnimationIsDeterministic() {
        assertDeterministicAndPinned(FluidOptions.Fluid.LAVA, LAVA_FRAMES, LAVA_TICKS_PER_FRAME);
    }

    /**
     * Bakes the animation twice, asserts the two bakes agree frame for frame and carry the requested
     * frame count, then holds every frame's CRC32 against its pinned value.
     *
     * @param fluid the fluid to bake
     * @param frames how many frames to bake
     * @param ticksPerFrame the tick step between frames
     */
    private void assertDeterministicAndPinned(FluidOptions.Fluid fluid, int frames, int ticksPerFrame) {
        FluidOptions options = options(fluid, frames, ticksPerFrame);
        List<Long> first = RenderDigest.frameCrcs(renderer.render(options));
        List<Long> second = RenderDigest.frameCrcs(renderer.render(options));

        // Before the pin, always: a flaky parallel path must fail on a different message than a
        // drifted value, or a re-baseline gets reached for when the fix is a determinism bug.
        assertThat("per-frame CRCs must be stable across parallel runs", second, equalTo(first));
        assertThat("animation must contain the requested frame count", first.size(), is(frames));

        for (int frame = 0; frame < first.size(); frame++)
            PINS.crc32(key(fluid, frame), first.get(frame));
        PINS.requireBaseline();

        for (int frame = 0; frame < first.size(); frame++) {
            String key = key(fluid, frame);
            assertThat("frame CRC32 " + key + "; if intentional, re-baseline it: "
                    + Pins.rebaselineCommand(ARTIFACT),
                first.get(frame), is(Pins.crc32(ARTIFACT, key)));
        }
    }

    /**
     * Returns the render options one animation is baked under - the same expression the subject
     * strings describe, so a changed parameter moves both together.
     *
     * @param fluid the fluid
     * @param frames how many frames to bake
     * @param ticksPerFrame the tick step between frames
     * @return the options
     */
    private static FluidOptions options(FluidOptions.Fluid fluid, int frames, int ticksPerFrame) {
        return FluidOptions.builder()
            .fluid(fluid)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .output(OutputOptions.builder()
                .canvasSize(CANVAS)
                .build())
            .animation(AnimationOptions.builder()
                .frameCount(frames)
                .ticksPerFrame(ticksPerFrame)
                .build())
            .build();
    }

    /**
     * Spells one frame's pin key, the one place its {@code <fluid>_frame_<index>} shape is formed, so
     * the declared keys and the asserted keys cannot drift apart.
     *
     * @param fluid the fluid the frame belongs to
     * @param frame the frame's index in its animation
     * @return the pin key
     */
    private static String key(FluidOptions.Fluid fluid, int frame) {
        return fluid.name().toLowerCase(Locale.ROOT) + "_frame_" + frame;
    }

    /**
     * Enumerates every frame of both animations, in bake order.
     *
     * @return the pin key to subject description map both fluids contribute to
     */
    private static Map<String, String> subjects() {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, FluidOptions.Fluid.WATER, WATER_FRAMES, WATER_TICKS_PER_FRAME);
        put(out, FluidOptions.Fluid.LAVA, LAVA_FRAMES, LAVA_TICKS_PER_FRAME);
        return out;
    }

    /**
     * Adds one fluid's frames to the subject map, each described by the parameters it is baked under.
     *
     * @param out the map being built
     * @param fluid the fluid to describe
     * @param frames how many frames that fluid bakes
     * @param ticksPerFrame the tick step between its frames
     */
    private static void put(Map<String, String> out, FluidOptions.Fluid fluid, int frames, int ticksPerFrame) {
        for (int frame = 0; frame < frames; frame++)
            out.put(key(fluid, frame), "Fluid.%s, FLUID_FACE_2D, canvas %d, frameCount %d, "
                .formatted(fluid.name(), CANVAS, frames)
                + "ticksPerFrame %d, frame %d".formatted(ticksPerFrame, frame));
    }

}
