package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;
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
 * <p>A model's pose has a second half this does not hold. Where the body applies an authored clip,
 * the clip's channels stay in the clip table and only the application is recorded here, so the two
 * compose at render rather than one being folded into the other. A model can have both, one, or
 * neither.
 *
 * <p>A body can also pose the container its mesh was built around, which the mesh flattens away and
 * names nowhere. That is a parent transform above every bone the mesh holds at top level rather than
 * a bone of its own, so it is carried apart from them and composes above them at render.
 *
 * <p>The container is an ORDERED list of steps, each a part pose applied the way a {@code ModelPart}
 * is. A body poses a flattened root once and writes just the one; a sequence is what a renderer's own
 * {@code setupRotations} composes instead, and there the order is the whole meaning.
 *
 * @param model the model class's simple name, the same spelling the pose table keys a model by
 * @param container the steps the container is composed of, outermost first, each carrying the
 *     expression its touched channels evaluate to
 * @param bones bone name to the expression each touched channel evaluates to, in first-write order
 * @param clipSites the authored clips the body applies, in the order it applies them
 */
public record PoseProgram(
    @NotNull String model,
    @NotNull List<Map<PoseChannel, PoseExpr>> container,
    @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
    @NotNull List<PoseClipSite> clipSites
) {

    /**
     * Whether this model poses nothing at all.
     *
     * @return {@code true} when no channel of any bone or of the container is written and no clip is
     *     applied
     */
    public boolean isEmpty() {
        return this.container.isEmpty() && this.bones.isEmpty() && this.clipSites.isEmpty();
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
