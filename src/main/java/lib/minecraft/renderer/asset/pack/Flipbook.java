package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

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

}
