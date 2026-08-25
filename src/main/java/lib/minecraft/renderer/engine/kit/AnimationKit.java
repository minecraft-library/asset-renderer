package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Plays a resolved {@link Flipbook} back against a vertically stacked strip.
 * <p>
 * Strip layout matches vanilla: the source {@link PixelBuffer} is a tall image holding one
 * square (or {@link MCMeta.Animation#width() width}-by-{@link MCMeta.Animation#height() height}
 * overridden) frame per row, stacked top to bottom. Which rectangle that is and how long each entry
 * lasts are the {@link Flipbook}'s, resolved once where the sidecar and the strip meet; this class
 * owns the pixels alone - which entry a tick lands on, the crop out of the strip, and the blend.
 * <p>
 * A call to {@link #sampleFrame} returns the frame for a specific tick. When
 * {@link Flipbook#interpolate()} is set, the result is a {@link PixelBuffer#lerp blend} between the
 * current entry's frame and the next entry's frame using a progress factor computed from the current
 * entry's duration.
 */
@UtilityClass
public class AnimationKit {

    /**
     * Returns the animation frame that corresponds to the given integer tick. The tick is
     * resolved modulo the table's cycle length so callers can pass a free-running clock and get
     * correct looping behaviour.
     * <p>
     * When {@link Flipbook#interpolate() interpolate} is set, the returned frame is a linear
     * blend of the current and next entry's frames weighted by how far the tick has advanced into
     * the current entry's duration. The blend is skipped when the next entry maps to the same strip
     * index as the current one, since there is nothing to interpolate towards.
     *
     * @param strip the vertically stacked frame strip
     * @param flipbook the resolved playback table
     * @param tick the current tick (free-running, signed)
     * @return the sampled frame at the given tick, or the table's first frame when the cycle carries
     *     no duration at all
     */
    public static @NotNull PixelBuffer sampleFrame(
        @NotNull PixelBuffer strip,
        @NotNull Flipbook flipbook,
        int tick
    ) {
        ConcurrentList<MCMeta.Frame> entries = flipbook.entries();
        int frameWidth = flipbook.frameWidth();
        int frameHeight = flipbook.frameHeight();

        if (flipbook.totalTicks() <= 0)
            return extractFrame(strip, entries.getFirst().index(), frameWidth, frameHeight);

        int effectiveTick = Math.floorMod(tick, flipbook.totalTicks());

        int accumulated = 0;
        int currentEntry = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (effectiveTick < accumulated + entries.get(i).time()) {
                currentEntry = i;
                break;
            }
            accumulated += entries.get(i).time();
        }

        PixelBuffer current = extractFrame(strip, entries.get(currentEntry).index(), frameWidth, frameHeight);
        if (!flipbook.interpolate()) return current;

        int nextEntry = (currentEntry + 1) % entries.size();
        if (entries.get(nextEntry).index() == entries.get(currentEntry).index()) return current;

        PixelBuffer next = extractFrame(strip, entries.get(nextEntry).index(), frameWidth, frameHeight);
        float alpha = (effectiveTick - accumulated) / (float) entries.get(currentEntry).time();
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

}
