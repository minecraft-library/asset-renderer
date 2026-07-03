package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The animation metadata parsed from a vanilla {@code .png.mcmeta} sidecar. Describes how a
 * vertically-stacked animation strip should be played back as a sequence of frames.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class AnimationData {

    /**
     * The default display duration per frame, in ticks (1 tick = 50ms).
     */
    private final int frametime;

    /**
     * Whether to linearly interpolate between adjacent frames.
     */
    private final boolean interpolate;

    /**
     * The ordered playback sequence, one {@link FrameEntry} per frame. The vanilla {@code frames}
     * array is heterogeneous (bare strip indices mixed with explicit {@code index}/{@code time}
     * objects); it is normalised so every entry is a {@code FrameEntry}, bare indices carrying the
     * {@code -1} duration marker that defers to {@link #frametime}. Empty when the sidecar declares
     * no explicit sequence and playback walks the strip in order.
     */
    private final @NotNull ConcurrentList<FrameEntry> frames;

    /**
     * An explicit frame width override, or {@code -1} when the animation inherits it from the texture.
     */
    private final int width;

    /**
     * An explicit frame height override, or {@code -1} when the animation inherits it from the texture.
     */
    private final int height;

    /**
     * A single entry in an animation {@link #frames} sequence.
     *
     * @param index the zero-based frame index into the vertically-stacked animation strip
     * @param time the per-frame duration override in ticks, or {@code -1} to defer to the
     *     animation-level {@link AnimationData#frametime}
     */
    public record FrameEntry(int index, int time) {}

}
