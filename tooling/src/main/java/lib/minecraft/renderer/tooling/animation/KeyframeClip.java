package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One extracted keyframe clip - a named {@code AnimationDefinition} constant with its length, its
 * looping flag and one channel per bone-and-target pair.
 *
 * <p>Keyed by the declaring class as well as the field, because the field name alone does not
 * identify a clip: {@code HOP} and {@code IDLE_HEAD_TILT} are each declared by two classes.
 *
 * @param owner the internal name of the class declaring the constant
 * @param field the constant's field name
 * @param lengthSeconds the clip's declared length
 * @param looping whether the clip restarts rather than holding its last frame
 * @param channels every bone channel in declaration order
 */
public record KeyframeClip(
    @NotNull String owner,
    @NotNull String field,
    float lengthSeconds,
    boolean looping,
    @NotNull List<BoneChannel> channels
) {

    /**
     * The coordinate this clip is named by, in the same {@code Class#member} spelling the
     * geometry table keys a mesh factory with.
     *
     * @return the clip coordinate
     */
    public @NotNull String coordinate() {
        return this.owner.substring(this.owner.lastIndexOf('/') + 1) + "#" + this.field;
    }

    /**
     * One bone's channel within a clip.
     *
     * @param bone the bone name the channel drives
     * @param target the accumulator the channel writes through
     * @param keyframes the keyframes in declaration order
     */
    public record BoneChannel(@NotNull String bone, @NotNull String target, @NotNull List<AnimationValue.Frame> keyframes) {}

}
