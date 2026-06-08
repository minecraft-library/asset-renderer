package lib.minecraft.renderer.asset.pack;

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
     * The ordered list of frame entries. Each entry is either a simple frame index or a
     * {@link FrameEntry} with an explicit per-frame duration override.
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
     * A single entry in an animation frames list.
     *
     * @param index the frame index into the animation strip
     * @param time the per-frame duration override in ticks, or {@code -1} to use {@code frametime}
     */
    public record FrameEntry(int index, int time) {}

}
