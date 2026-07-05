package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.stream.IntStream;

/**
 * Shared frame-orchestration stage for renderers that bake a tick-driven animation strip.
 * <p>
 * Collapses the duplicated "single frame &rarr; static image, else frame-parallel bake &rarr;
 * animated image" tail. A frame count of {@code 1} (or less) renders one frame at {@code startTick}
 * and returns a static image; a higher count renders each frame in parallel at
 * {@code startTick + f*ticksPerFrame}, preserving encounter order, and wraps the strip with the given
 * per-frame delay. It sits at the terminal end of the pipeline, downstream of {@link FinalizeStage}
 * and {@link GlintStage}: each frame it bakes is itself a full finalise (and optional glint) pass.
 */
public final class AnimationStage {

    /**
     * Callback that renders one animation frame at a given absolute game tick.
     */
    @FunctionalInterface
    public interface FrameRenderer {

        /**
         * Renders the frame at {@code tick}.
         *
         * @param tick the absolute game tick to sample
         * @return the rendered frame
         */
        @NotNull PixelBuffer render(int tick);
    }

    private AnimationStage() {
    }

    /**
     * Renders a static or animated image depending on {@code frameCount}.
     *
     * @param frameCount number of frames; {@code <= 1} produces a static image
     * @param startTick the absolute tick of frame 0
     * @param ticksPerFrame ticks between successive frames
     * @param delayMs per-frame playback delay in milliseconds (animated output only)
     * @param frameRenderer renders one frame at a given tick
     * @return a static or animated image
     */
    public static @NotNull ImageData render(
        int frameCount, int startTick, int ticksPerFrame, int delayMs,
        @NotNull FrameRenderer frameRenderer
    ) {
        if (frameCount <= 1)
            return FrameCompositor.staticFrame(frameRenderer.render(startTick));

        // Frame-parallel bake: each frame is independent; mapToObj().toList() preserves encounter
        // order so the resulting strip stays tick-ordered for GIF/WebP playback.
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.addAll(IntStream.range(0, frameCount).parallel()
            .mapToObj(f -> frameRenderer.render(startTick + f * ticksPerFrame))
            .toList());
        return FrameCompositor.wrapFrames(frames, delayMs);
    }
}
