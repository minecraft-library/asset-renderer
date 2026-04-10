package dev.sbs.renderer.model.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.persistence.type.GsonType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The animation metadata parsed from a vanilla {@code .png.mcmeta} sidecar. Describes how a
 * vertically-stacked animation strip should be played back as a sequence of frames.
 */
@Getter
@GsonType
@NoArgsConstructor
public class AnimationData {

    /** The default display duration per frame, in ticks (1 tick = 50ms). */
    private int frametime = 1;

    /** Whether to linearly interpolate between adjacent frames. */
    private boolean interpolate = false;

    /**
     * The ordered list of frame entries. Each entry is either a simple frame index or a
     * {@link FrameEntry} with an explicit per-frame duration override.
     */
    private @NotNull ConcurrentList<FrameEntry> frames = Concurrent.newList();

    /** An explicit frame width override, or {@code -1} when the animation inherits it from the texture. */
    private int width = -1;

    /** An explicit frame height override, or {@code -1} when the animation inherits it from the texture. */
    private int height = -1;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AnimationData that = (AnimationData) o;
        return frametime == that.frametime
            && interpolate == that.interpolate
            && width == that.width
            && height == that.height
            && Objects.equals(frames, that.frames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frametime, interpolate, frames, width, height);
    }

    /**
     * A single entry in an animation frames list.
     *
     * @param index the frame index into the animation strip
     * @param time the per-frame duration override in ticks, or {@code -1} to use {@code frametime}
     */
    @GsonType
    public record FrameEntry(int index, int time) {}

}
