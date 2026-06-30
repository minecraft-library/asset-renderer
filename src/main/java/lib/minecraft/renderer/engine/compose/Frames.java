package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.exception.RenderException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Wraps rendered {@link PixelBuffer} frames into the final {@link ImageData} a renderer returns -
 * the terminal output step shared by every renderer and compose stage. A single frame becomes a
 * {@link StaticImageData}; multiple frames become an {@link AnimatedImageData}.
 */
@UtilityClass
public class Frames {

    /**
     * Wraps a list of rendered frames as an {@link ImageData} instance.
     * <p>
     * A single-frame list becomes a {@link StaticImageData}. Multi-frame lists become an
     * {@link AnimatedImageData} where every frame shares the same delay.
     *
     * @param frames the ordered frame list
     * @param frameDelayMs the per-frame display duration in milliseconds
     * @return the wrapped image data
     */
    public static @NotNull ImageData wrapFrames(@NotNull ConcurrentList<PixelBuffer> frames, int frameDelayMs) {
        if (frames.isEmpty())
            throw new RenderException("Frame list must contain at least one frame");

        if (frames.size() == 1)
            return StaticImageData.of(frames.getFirst().toBufferedImage());

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (PixelBuffer frame : frames)
            builder.withFrame(ImageFrame.of(frame, frameDelayMs));

        return builder.build();
    }

    /**
     * Wraps a pixel buffer as a single-frame static {@link ImageData}. Shared convenience for
     * every renderer that needs to emit exactly one frame without glint or animation.
     *
     * @param buffer the pixel buffer that becomes the static frame
     * @return the wrapped image data
     */
    public static @NotNull ImageData staticFrame(@NotNull PixelBuffer buffer) {
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return wrapFrames(frames, 0);
    }

    /**
     * Returns a minimal 1x1 transparent static frame - the canonical "nothing to render" result for
     * renderers short-circuiting on missing or empty input.
     *
     * @return a 1x1 transparent static image
     */
    public static @NotNull ImageData emptyFrame() {
        return staticFrame(PixelBuffer.create(1, 1));
    }

}
