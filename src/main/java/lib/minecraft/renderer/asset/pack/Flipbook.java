package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.engine.frame.Timeline;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The resolved playback table of an {@link MCMeta.Animation animation} sidecar against the strip it
 * plays over: the frame rectangle, the entry sequence with every deferred duration substituted, the
 * cycle length and the interpolation flag.
 * <p>
 * Resolution is a function of the strip as much as of the sidecar - a side of the frame rectangle the
 * animation does not declare comes from the strip's own size, as {@link FrameSize} sizes it, and how
 * many implicit entries there are is how many whole frames the strip holds across both axes - so the
 * table is pack state and is built where a pack's textures resolve rather than at generation. A pack
 * that swaps a {@code .png.mcmeta} or the PNG beside it swaps the table with it.
 * <p>
 * The strip's frames are numbered row by row, left to right and then top to bottom, so a strip one
 * frame wide plays from the top down and a strip one frame tall plays from left to right.
 *
 * @param frameWidth the frame width in pixels, as {@link FrameSize} sizes it
 * @param frameHeight the frame height in pixels, as {@link FrameSize} sizes it
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
     * The frames counted are the whole ones the strip holds on both axes. A strip its frame size does
     * not divide is one vanilla's sprite loader refuses, and the pack stack serves such a texture with
     * no pixels rather than handing its strip here; a ragged strip handed over directly plays the whole
     * frames it holds. A strip holding no whole frame answers empty - a non-positive frame rectangle,
     * or one wider or taller than the strip itself - which is what a caller renders as the strip
     * unchanged.
     *
     * @param strip the frame strip
     * @param animation the parsed {@code .mcmeta} animation section
     * @return the resolved table, or empty when the strip holds no playable frame
     */
    public static @NotNull Optional<Flipbook> of(@NotNull PixelBuffer strip, @NotNull MCMeta.Animation animation) {
        FrameSize frame = FrameSize.of(animation, strip);
        if (frame.width() <= 0 || frame.height() <= 0) return Optional.empty();

        int frameCount = (strip.width() / frame.width()) * (strip.height() / frame.height());
        if (frameCount <= 0) return Optional.empty();

        int defaultTicks = Math.max(1, animation.frametime());
        Stream<MCMeta.Frame> authored = animation.frames().isEmpty()
            ? IntStream.range(0, frameCount).mapToObj(index -> new MCMeta.Frame(index, defaultTicks))
            : animation.frames().stream().map(entry -> new MCMeta.Frame(
                Math.clamp(entry.index(), 0, frameCount - 1),
                entry.time() > 0 ? entry.time() : defaultTicks));

        ConcurrentList<MCMeta.Frame> entries = authored.collect(Concurrent.toUnmodifiableList());
        return Optional.of(new Flipbook(
            frame.width(), frame.height(), entries,
            entries.stream().mapToInt(MCMeta.Frame::time).sum(), animation.interpolate()));
    }

    /**
     * Resolves a texture's animation sidecar against its strip, where either may hold nothing. The
     * sidecar is asked for first and the strip only once there is one, so a texture that ships no
     * animation - which is nearly all of them - decodes nothing. An animation with no strip under it
     * answers in the strip's state: a texture that cannot be read has no frames to play, and a texture
     * no pack supplies has no table at all.
     *
     * @param animation the texture's animation section - empty when the texture plays none, absent when
     *     the texture is not served
     * @param strip supplies the texture's frame strip - empty when the texture cannot be read, absent
     *     when no pack supplies it
     * @return the resolved table - empty when the texture plays nothing (no sidecar, no animation
     *     section, a strip that cannot be read, or no whole frame), absent when the texture or its
     *     strip is not served
     */
    public static @NotNull Possible<Flipbook> of(
        @NotNull Possible<MCMeta.Animation> animation, @NotNull Supplier<Possible<PixelBuffer>> strip) {
        return animation.flatMap(section -> strip.get().flatMap(pixels -> Possible.ofOptional(of(pixels, section))));
    }

    /**
     * The frame a texture displays at a tick. A texture with no playback table answers its strip
     * unchanged, so tick {@code 0} of a still texture is its strip; an animated one answers
     * {@link #frameAt the strip frame} for the tick, blended with the next when the table
     * {@link #interpolate() interpolates}. A strip holding no pixels answers in its own state.
     *
     * @param strip the texture's frame strip - empty when the texture cannot be read, absent when no
     *     pack supplies it
     * @param flipbook the texture's playback table - empty when it plays nothing, absent when the
     *     texture is not served; either reads as a still texture
     * @param tick the animation tick (free-running, signed)
     * @return the frame to draw at the tick - empty when the texture cannot be read, absent when no
     *     pack supplies it
     */
    public static @NotNull Possible<PixelBuffer> atTick(
        @NotNull Possible<PixelBuffer> strip, @NotNull Possible<Flipbook> flipbook, int tick) {
        return strip.map(pixels -> flipbook.map(table -> table.frameAt(pixels, tick)).orElse(pixels));
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

    /**
     * GCD of every entry duration across all flipbooks, floored at 1.
     */
    private static int gcdCadence(@NotNull List<Flipbook> flipbooks) {
        long g = 0;
        for (Flipbook flipbook : flipbooks)
            for (MCMeta.Frame entry : flipbook.entries()) g = Timeline.gcd(g, entry.time());
        return (int) Math.max(1, g);
    }

    /**
     * LCM of every flipbook's cycle length, capped at {@link Timeline#MAX_LOOP_TICKS}.
     */
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
     * Crops one frame out of the strip. Frames are numbered row by row: frame 0 occupies the top-left
     * {@link #frameWidth()} by {@link #frameHeight()} rectangle, the next frame the rectangle to its
     * right, and a row the strip's width ends carries on at the left of the row below.
     *
     * @param strip the full animation strip
     * @param frameIndex the zero-based frame index
     * @return a new pixel buffer holding only that frame
     */
    public @NotNull PixelBuffer extractFrame(@NotNull PixelBuffer strip, int frameIndex) {
        int columns = Math.max(1, strip.width() / this.frameWidth);
        int xOffset = (frameIndex % columns) * this.frameWidth;
        int yOffset = (frameIndex / columns) * this.frameHeight;
        int[] pixels = new int[this.frameWidth * this.frameHeight];
        for (int y = 0; y < this.frameHeight; y++) {
            int sy = yOffset + y;
            if (sy < 0 || sy >= strip.height()) continue;
            for (int x = 0; x < this.frameWidth; x++) {
                int sx = xOffset + x;
                if (sx < 0 || sx >= strip.width()) continue;
                pixels[y * this.frameWidth + x] = strip.getPixel(sx, sy);
            }
        }
        return PixelBuffer.of(pixels, this.frameWidth, this.frameHeight);
    }

    /**
     * The rectangle one frame of an animation occupies in the strip it plays over, sized as vanilla's
     * sprite loader sizes it. A side the animation declares is taken as declared. A side it leaves
     * undeclared is the strip's own where the other side is declared, and where neither is, the frame
     * is a square of the strip's shorter side.
     *
     * @param width the frame width in pixels
     * @param height the frame height in pixels
     */
    public record FrameSize(int width, int height) {

        /**
         * Sizes the frame an animation plays over a strip.
         *
         * @param animation the parsed {@code .mcmeta} animation section
         * @param strip the strip the animation plays over
         * @return the frame rectangle
         */
        public static @NotNull FrameSize of(@NotNull MCMeta.Animation animation, @NotNull PixelBuffer strip) {
            boolean declaresWidth = animation.width() > 0;
            boolean declaresHeight = animation.height() > 0;
            int side = Math.min(strip.width(), strip.height());
            return new FrameSize(
                declaresWidth ? animation.width() : declaresHeight ? strip.width() : side,
                declaresHeight ? animation.height() : declaresWidth ? strip.height() : side);
        }

        /**
         * Whether a strip divides into whole frames of this size on both axes - the condition vanilla's
         * sprite loader sets an animated texture, drawing its missing sprite for one that fails it.
         *
         * @param strip the strip the frames are cut from
         * @return {@code true} when this size is positive and the strip's width and height are each a
         *     whole multiple of it
         */
        public boolean divides(@NotNull PixelBuffer strip) {
            return this.width > 0 && this.height > 0
                && strip.width() % this.width == 0 && strip.height() % this.height == 0;
        }

    }

}
