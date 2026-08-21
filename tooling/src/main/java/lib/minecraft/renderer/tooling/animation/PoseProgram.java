package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * One model's extracted pose - what every channel it touches evaluates to.
 *
 * <p>The form is a value per channel rather than a program of writes, because vanilla resets every
 * bone before it poses any of them: a channel nothing writes reads its authored value, and a
 * channel written twice reads the first write when the second is computed. Substituting each read
 * where it happens leaves one expression per channel that already carries the order it was built
 * in, so nothing downstream has to replay statements to get the same pose.
 *
 * <p>A channel absent from a bone's map is a channel this model never writes, which is a different
 * statement from one it writes its authored value back to. The first costs nothing at render; the
 * second is an expression that happens to be the identity.
 *
 * @param model the model class's simple name, the same spelling the pose table keys a model by
 * @param bones bone name to the expression each touched channel evaluates to, in first-write order
 */
public record PoseProgram(
    @NotNull String model,
    @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones
) {

    /**
     * Whether this model poses nothing at all.
     *
     * @return {@code true} when no channel of any bone is written
     */
    public boolean isEmpty() {
        return this.bones.isEmpty();
    }

    /**
     * How many channels this model writes across every bone.
     *
     * @return the total channel count
     */
    public int channelCount() {
        return this.bones.values().stream().mapToInt(Map::size).sum();
    }

}
