package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Plays back a vanilla {@code .mcmeta} texture animation against a vertically stacked strip.
 * <p>
 * Strip layout matches vanilla: the source {@link PixelBuffer} is a tall image holding one
 * square (or {@link AnimationData#width() width}-by-{@link AnimationData#height() height}
 * overridden) frame per row, stacked top to bottom. The frame count is derived from the strip's
 * height divided by the frame height.
 * <p>
 * A call to {@link #sampleFrame(PixelBuffer, AnimationData, int) sampleFrame} returns the frame
 * for a specific tick, honoring the animation's per-entry duration overrides and optional linear
 * interpolation between adjacent frames. Each entry lasts {@link AnimationData.FrameEntry#time()}
 * ticks, falling back to {@link AnimationData#frametime() frametime} whenever the entry declares
 * no positive override. When {@link AnimationData#interpolate()} is {@code true}, the result is a
 * {@link PixelBuffer#lerp blend} between the current entry's frame and the next entry's frame using
 * a progress factor computed from the current entry's per-frame ticks.
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
     * {@link AnimationData.FrameEntry} contributes its {@code time} ticks, or {@code frametime}
     * when the entry declares no positive override. Out-of-range entry indices are clamped into
     * {@code 0..frameCount-1}.
     * <p>
     * When {@link AnimationData#interpolate() interpolate} is set, the returned frame is a linear
     * blend of the current and next entry's frames weighted by how far the tick has advanced into
     * the current entry's duration. The blend is skipped when the next entry maps to the same strip
     * index as the current one, since there is nothing to interpolate towards.
     *
     * @param strip the vertically stacked frame strip
     * @param animation the parsed {@code .mcmeta} metadata
     * @param tick the current tick (free-running, signed)
     * @return the sampled frame at the given tick, or the strip unchanged when the animation
     *     cannot be played back (zero frames, non-positive frame dimensions, or a zero total
     *     duration - which instead returns the first frame)
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

        int defaultTicks = Math.max(1, animation.frametime());

        int[] indices;
        int[] durations;
        if (animation.frames().isEmpty()) {
            indices = new int[frameCount];
            durations = new int[frameCount];
            for (int i = 0; i < frameCount; i++) {
                indices[i] = i;
                durations[i] = defaultTicks;
            }
        } else {
            int size = animation.frames().size();
            indices = new int[size];
            durations = new int[size];
            for (int i = 0; i < size; i++) {
                AnimationData.FrameEntry entry = animation.frames().get(i);
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
        if (!animation.interpolate()) return current;

        int nextEntry = (currentEntry + 1) % durations.length;
        if (indices[nextEntry] == indices[currentEntry]) return current;

        PixelBuffer next = extractFrame(strip, indices[nextEntry], frameWidth, frameHeight);
        float alpha = (effectiveTick - accumulated) / (float) durations[currentEntry];
        return PixelBuffer.lerp(current, next, alpha);
    }

    /**
     * The per-entry tick durations of an animation's playback sequence - the array
     * {@link #sampleFrame} accumulates against internally, exposed for timeline derivation.
     * When the animation declares no explicit {@code frames} list, the strip's
     * implicit frames {@code 0..frameCount-1} each last {@link AnimationData#frametime() frametime}
     * (floored at 1); otherwise each {@link AnimationData.FrameEntry} contributes its {@code time}
     * ticks, or {@code frametime} when the entry declares no positive override. Mirrors the durations
     * computation in {@link #sampleFrame} - keep the two in sync.
     *
     * @param frameCount the strip's implicit frame count (strip height / frame height), used only
     *     when the animation has no explicit {@code frames} list
     * @param animation the parsed {@code .mcmeta} metadata
     * @return the per-entry durations in ticks; empty when the implicit-frame path has zero frames
     */
    public static int @NotNull [] entryDurations(int frameCount, @NotNull AnimationData animation) {
        int defaultTicks = Math.max(1, animation.frametime());
        if (animation.frames().isEmpty()) {
            int[] durations = new int[Math.max(0, frameCount)];
            Arrays.fill(durations, defaultTicks);
            return durations;
        }
        int size = animation.frames().size();
        int[] durations = new int[size];
        for (int i = 0; i < size; i++) {
            AnimationData.FrameEntry entry = animation.frames().get(i);
            durations[i] = entry.time() > 0 ? entry.time() : defaultTicks;
        }
        return durations;
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
     * Returns the animation's explicit frame-width override, or the strip width when none is
     * declared.
     *
     * @param strip the animation strip supplying the fallback width
     * @param animation the parsed metadata carrying an optional width override
     * @return the frame width in pixels
     */
    public static int frameWidth(@NotNull PixelBuffer strip, @NotNull AnimationData animation) {
        return animation.width() > 0 ? animation.width() : strip.width();
    }

    /**
     * Returns the animation's explicit frame-height override, or - when none is declared - the
     * strip width, since vanilla frames default to square and the width axis is the reliable side
     * to fall back on.
     *
     * @param strip the animation strip supplying the fallback (its width)
     * @param animation the parsed metadata carrying an optional height override
     * @return the frame height in pixels
     */
    public static int frameHeight(@NotNull PixelBuffer strip, @NotNull AnimationData animation) {
        return animation.height() > 0 ? animation.height() : strip.width();
    }

}
