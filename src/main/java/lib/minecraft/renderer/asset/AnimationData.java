package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.MCMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The animation metadata parsed from a vanilla {@code .png.mcmeta} sidecar. Describes how a
 * vertically-stacked animation strip should be played back as a sequence of frames.
 *
 * @param frametime the default display duration per frame, in ticks (1 tick = 50ms)
 * @param interpolate whether to linearly interpolate between adjacent frames
 * @param frames the ordered playback sequence, one {@link FrameEntry} per frame. The vanilla
 *     {@code frames} array is heterogeneous (bare strip indices mixed with explicit
 *     {@code index}/{@code time} objects); it is normalised so every entry is a {@code FrameEntry},
 *     bare indices carrying the {@code -1} duration marker that defers to {@link #frametime}. Empty
 *     when the sidecar declares no explicit sequence and playback walks the strip in order
 * @param width an explicit frame width override, or {@code -1} when the animation inherits it from
 *     the texture
 * @param height an explicit frame height override, or {@code -1} when the animation inherits it from
 *     the texture
 */
public record AnimationData(
    int frametime,
    boolean interpolate,
    @NotNull ConcurrentList<FrameEntry> frames,
    int width,
    int height
) {

    /**
     * A single entry in an animation {@link #frames} sequence.
     *
     * @param index the zero-based frame index into the vertically-stacked animation strip
     * @param time the per-frame duration override in ticks, or {@code -1} to defer to the
     *     animation-level {@link AnimationData#frametime}
     */
    public record FrameEntry(int index, int time) {

        /**
         * Constructs a new {@code FrameEntry} from the parsed sidecar frame it is adapted from, carrying
         * that frame's index and duration override across unchanged.
         *
         * @param frame the sidecar frame to adapt
         */
        public FrameEntry(@NotNull MCMeta.Frame frame) {
            this(frame.index(), frame.time());
        }

    }

}
