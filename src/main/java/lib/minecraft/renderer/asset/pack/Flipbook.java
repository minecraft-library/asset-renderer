package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The resolved playback table of an {@link MCMeta.Animation animation} sidecar against the strip it
 * plays over: the frame rectangle, the entry sequence with every deferred duration substituted, the
 * cycle length and the interpolation flag.
 * <p>
 * Resolution is a function of the strip as much as of the sidecar - the frame rectangle falls back to
 * the strip's own width, and how many implicit entries there are is the strip's height divided by
 * that rectangle - so the table is pack state and is built where a pack's textures resolve rather
 * than at generation. A pack that swaps a {@code .png.mcmeta} or the PNG beside it swaps the table
 * with it.
 *
 * @param frameWidth the frame width in pixels - the animation's own override, or the strip's width
 * @param frameHeight the frame height in pixels - the animation's own override, or the strip's
 *     width, vanilla frames defaulting to square
 * @param entries the playback sequence, each entry carrying the strip index it draws clamped into
 *     range and its duration in ticks; the {@code -1} deferral an authored {@link MCMeta.Frame} may
 *     carry is already resolved against {@link MCMeta.Animation#frametime() frametime}
 * @param totalTicks the cycle length - the sum of every entry's duration
 * @param interpolate whether adjacent entries blend
 */
public record Flipbook(
    int frameWidth,
    int frameHeight,
    @NotNull ConcurrentList<MCMeta.Frame> entries,
    int totalTicks,
    boolean interpolate
) {

    /**
     * Resolves an animation sidecar against the strip it plays over. An animation declaring no
     * explicit {@code frames} list takes the strip's implicit frames {@code 0..frameCount-1} in order,
     * each lasting {@code frametime} floored at one tick; otherwise each authored entry contributes
     * its own {@code time}, or {@code frametime} where it declares no positive override. An entry
     * naming a strip index out of range is clamped into {@code 0..frameCount-1}.
     * <p>
     * A strip holding no whole frame answers empty - a non-positive frame rectangle, or one taller
     * than the strip itself - which is what a caller renders as the strip unchanged.
     *
     * @param strip the vertically stacked frame strip
     * @param animation the parsed {@code .mcmeta} animation section
     * @return the resolved table, or empty when the strip holds no playable frame
     */
    public static @NotNull Optional<Flipbook> of(@NotNull PixelBuffer strip, @NotNull MCMeta.Animation animation) {
        int frameWidth = animation.width() > 0 ? animation.width() : strip.width();
        int frameHeight = animation.height() > 0 ? animation.height() : strip.width();
        if (frameWidth <= 0 || frameHeight <= 0) return Optional.empty();

        int frameCount = strip.height() / frameHeight;
        if (frameCount <= 0) return Optional.empty();

        int defaultTicks = Math.max(1, animation.frametime());
        Stream<MCMeta.Frame> authored = animation.frames().isEmpty()
            ? IntStream.range(0, frameCount).mapToObj(index -> new MCMeta.Frame(index, defaultTicks))
            : animation.frames().stream().map(entry -> new MCMeta.Frame(
                Math.clamp(entry.index(), 0, frameCount - 1),
                entry.time() > 0 ? entry.time() : defaultTicks));

        ConcurrentList<MCMeta.Frame> entries = authored.collect(Concurrent.toUnmodifiableList());
        return Optional.of(new Flipbook(
            frameWidth, frameHeight, entries,
            entries.stream().mapToInt(MCMeta.Frame::time).sum(), animation.interpolate()));
    }

    /**
     * Derives a tick-strip schedule from resolved animated textures: the cadence is the GCD of every
     * entry duration (forced to 1 when any flipbook interpolates, so every distinct tick is sampled);
     * the loop length is the LCM of every flipbook's cycle, capped at {@link Timeline#MAX_LOOP_TICKS};
     * the frame count is {@code ceil(loopTicks / ticksPerFrame)}. Answers a still at the start tick
     * when nothing is playable.
     *
     * @param flipbooks the subject's resolved animated textures
     * @param startTick the absolute sample tick of frame 0
     * @return the derived schedule
     */
    public static @NotNull Timeline.TickTimeline deriveTickStrip(@NotNull List<Flipbook> flipbooks, int startTick) {
        List<Flipbook> playable = new ArrayList<>();
        boolean anyInterpolate = false;
        for (Flipbook flipbook : flipbooks) {
            if (flipbook.totalTicks() <= 0) continue;
            playable.add(flipbook);
            if (flipbook.interpolate()) anyInterpolate = true;
        }
        if (playable.isEmpty()) return new Timeline.Static(startTick);

        int ticksPerFrame = anyInterpolate ? 1 : gcdCadence(playable);
        int loopTicks = cappedLoopTicks(playable);
        if (loopTicks <= 0) return new Timeline.Static(startTick);

        int frameCount = Math.max(1, (int) Math.ceil(loopTicks / (double) ticksPerFrame));
        return Timeline.tickStrip(startTick, frameCount, ticksPerFrame);
    }

    /** GCD of every entry duration across all flipbooks, floored at 1. */
    private static int gcdCadence(@NotNull List<Flipbook> flipbooks) {
        long g = 0;
        for (Flipbook flipbook : flipbooks)
            for (MCMeta.Frame entry : flipbook.entries()) g = Timeline.gcd(g, entry.time());
        return (int) Math.max(1, g);
    }

    /** LCM of every flipbook's cycle length, capped at {@link Timeline#MAX_LOOP_TICKS}. */
    private static int cappedLoopTicks(@NotNull List<Flipbook> flipbooks) {
        long loop = 0;
        for (Flipbook flipbook : flipbooks) {
            long total = flipbook.totalTicks();
            loop = loop == 0 ? total : Timeline.lcm(loop, total);
            if (loop >= Timeline.MAX_LOOP_TICKS) return Timeline.MAX_LOOP_TICKS;
        }
        return (int) Math.min(loop, Timeline.MAX_LOOP_TICKS);
    }

    /**
     * Samples the frame this table plays at the given tick, cropped out of the strip it was resolved
     * against. The tick is resolved modulo the cycle length, so a caller may pass a free-running
     * clock and get correct looping.
     *
     * <p>When {@link #interpolate()} is set the result is a linear blend of the current and next
     * entry's frames, weighted by how far the tick has advanced into the current entry's duration.
     * The blend is skipped when the next entry maps to the same strip index, since there is nothing
     * to interpolate towards.
     *
     * @param strip the vertically stacked frame strip this table was resolved against
     * @param tick the current tick, free-running and signed
     * @return the frame at that tick, or the first frame when the cycle carries no duration
     */
    public @NotNull PixelBuffer frameAt(@NotNull PixelBuffer strip, int tick) {
        if (this.totalTicks <= 0)
            return extractFrame(strip, this.entries.getFirst().index());

        int effectiveTick = Math.floorMod(tick, this.totalTicks);

        int accumulated = 0;
        int currentEntry = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            if (effectiveTick < accumulated + this.entries.get(i).time()) {
                currentEntry = i;
                break;
            }
            accumulated += this.entries.get(i).time();
        }

        PixelBuffer current = extractFrame(strip, this.entries.get(currentEntry).index());
        if (!this.interpolate) return current;

        int nextEntry = (currentEntry + 1) % this.entries.size();
        if (this.entries.get(nextEntry).index() == this.entries.get(currentEntry).index()) return current;

        PixelBuffer next = extractFrame(strip, this.entries.get(nextEntry).index());
        float alpha = (effectiveTick - accumulated) / (float) this.entries.get(currentEntry).time();
        return PixelBuffer.lerp(current, next, alpha);
    }

    /**
     * Crops one frame out of the strip. Frame 0 occupies the top {@link #frameHeight()} rows, frame
     * 1 the next, and so on.
     *
     * @param strip the full animation strip
     * @param frameIndex the zero-based frame index
     * @return a new pixel buffer holding only that frame
     */
    public @NotNull PixelBuffer extractFrame(@NotNull PixelBuffer strip, int frameIndex) {
        int yOffset = frameIndex * this.frameHeight;
        int[] pixels = new int[this.frameWidth * this.frameHeight];
        for (int y = 0; y < this.frameHeight; y++) {
            int sy = yOffset + y;
            if (sy < 0 || sy >= strip.height()) continue;
            for (int x = 0; x < this.frameWidth; x++) {
                if (x >= strip.width()) continue;
                pixels[y * this.frameWidth + x] = strip.getPixel(x, sy);
            }
        }
        return PixelBuffer.of(pixels, this.frameWidth, this.frameHeight);
    }

}
