package dev.sbs.renderer.kit;

import dev.sbs.renderer.model.asset.AnimationData;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Plays back a vanilla {@code .mcmeta} texture animation against a vertically stacked strip.
 * <p>
 * Strip layout matches vanilla: the source {@link PixelBuffer} is a tall image holding one
 * square (or {@link AnimationData#getWidth() width}-by-{@link AnimationData#getHeight() height}
 * overridden) frame per row, stacked top to bottom. The frame count is derived from the strip's
 * height divided by the frame height.
 * <p>
 * A call to {@link #sampleFrame(PixelBuffer, AnimationData, int) sampleFrame} returns the frame
 * for a specific tick, honoring the animation's per-entry duration overrides and optional linear
 * interpolation between adjacent frames. When {@link AnimationData#isInterpolate()} is
 * {@code true}, the result is a {@link PixelBuffer#lerp blend} between the current entry's
 * frame and the next entry's frame using a progress factor computed from the entry's per-frame
 * ticks.
 */
@UtilityClass
public class AnimationKit {

    /**
     * Returns the animation frame that corresponds to the given integer tick. The tick is
     * resolved modulo the total cycle length so callers can pass a free-running clock and get
     * correct looping behaviour.
     * <p>
     * When the animation has no explicit {@code frames} list, the strip's implicit frames
     * {@code 0..N-1} are used in order with the default {@code frametime}. Otherwise each
     * {@link AnimationData.FrameEntry} contributes its {@code time} (or {@code frametime} when
     * the entry's time is {@code -1}).
     *
     * @param strip the vertically stacked frame strip
     * @param animation the parsed {@code .mcmeta} metadata
     * @param tick the current tick (free-running, signed)
     * @return the sampled frame at the given tick, or the strip unchanged when the animation
     *     cannot be played back (zero frames, zero duration, or mismatched dimensions)
     */
    public static @NotNull PixelBuffer sampleFrame(
        @NotNull PixelBuffer strip,
        @NotNull AnimationData animation,
        int tick
    ) {
        int frameWidth = frameWidth(strip, animation);
        int frameHeight = frameHeight(strip, animation);
        if (frameWidth <= 0 || frameHeight <= 0) return strip;

        int frameCount = strip.height() / frameHeight;
        if (frameCount <= 0) return strip;

        int defaultTicks = Math.max(1, animation.getFrametime());

        int[] indices;
        int[] durations;
        if (animation.getFrames().isEmpty()) {
            indices = new int[frameCount];
            durations = new int[frameCount];
            for (int i = 0; i < frameCount; i++) {
                indices[i] = i;
                durations[i] = defaultTicks;
            }
        } else {
            int size = animation.getFrames().size();
            indices = new int[size];
            durations = new int[size];
            for (int i = 0; i < size; i++) {
                AnimationData.FrameEntry entry = animation.getFrames().get(i);
                indices[i] = Math.clamp(entry.index(), 0, frameCount - 1);
                durations[i] = entry.time() > 0 ? entry.time() : defaultTicks;
            }
        }

        int totalTicks = 0;
        for (int d : durations) totalTicks += d;
        if (totalTicks <= 0) return extractFrame(strip, indices[0], frameWidth, frameHeight);

        int effectiveTick = Math.floorMod(tick, totalTicks);

        int accumulated = 0;
        int currentEntry = 0;
        for (int i = 0; i < durations.length; i++) {
            if (effectiveTick < accumulated + durations[i]) {
                currentEntry = i;
                break;
            }
            accumulated += durations[i];
        }

        PixelBuffer current = extractFrame(strip, indices[currentEntry], frameWidth, frameHeight);
        if (!animation.isInterpolate()) return current;

        int nextEntry = (currentEntry + 1) % durations.length;
        if (indices[nextEntry] == indices[currentEntry]) return current;

        PixelBuffer next = extractFrame(strip, indices[nextEntry], frameWidth, frameHeight);
        float alpha = (effectiveTick - accumulated) / (float) durations[currentEntry];
        return PixelBuffer.lerp(current, next, alpha);
    }

    /**
     * Extracts a single frame from the strip at the given index. Frame 0 occupies the top
     * {@code frameHeight} rows, frame 1 the next, and so on.
     *
     * @param strip the full animation strip
     * @param frameIndex the zero-based frame index
     * @param frameWidth the frame width in pixels
     * @param frameHeight the frame height in pixels
     * @return a new pixel buffer holding only the sampled frame
     */
    public static @NotNull PixelBuffer extractFrame(
        @NotNull PixelBuffer strip,
        int frameIndex,
        int frameWidth,
        int frameHeight
    ) {
        int yOffset = frameIndex * frameHeight;
        int[] pixels = new int[frameWidth * frameHeight];
        for (int y = 0; y < frameHeight; y++) {
            int sy = yOffset + y;
            if (sy < 0 || sy >= strip.height()) continue;
            for (int x = 0; x < frameWidth; x++) {
                if (x >= strip.width()) continue;
                pixels[y * frameWidth + x] = strip.getPixel(x, sy);
            }
        }
        return PixelBuffer.of(pixels, frameWidth, frameHeight);
    }

    /**
     * Returns the explicit frame width override or the strip width when the animation does not
     * declare one.
     */
    public static int frameWidth(@NotNull PixelBuffer strip, @NotNull AnimationData animation) {
        return animation.getWidth() > 0 ? animation.getWidth() : strip.width();
    }

    /**
     * Returns the explicit frame height override or the strip width when the animation does not
     * declare one (vanilla defaults to square frames so the fallback uses the width axis).
     */
    public static int frameHeight(@NotNull PixelBuffer strip, @NotNull AnimationData animation) {
        return animation.getHeight() > 0 ? animation.getHeight() : strip.width();
    }

}
