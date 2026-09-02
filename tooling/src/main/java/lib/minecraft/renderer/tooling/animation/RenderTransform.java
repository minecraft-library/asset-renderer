package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one renderer's {@code setupRotations} puts every mesh it submits under, or the record of why
 * it could not be read.
 *
 * <p>A renderer fact rather than a model one: vanilla applies this to the {@code PoseStack} before
 * it submits the body or any layer, so it composes above every mesh the subject draws where a pose
 * belongs to the single model class that wrote it. That is also why one renderer answers for a
 * subject with several models - a tropical fish is two body meshes and a run of pattern overlays,
 * and one transform reaches all of them.
 *
 * <p>The steps are in the shape a container already is, so nothing downstream needs a second
 * spelling: each is a part pose applied {@code T . rotationZYX} and the order is the whole meaning.
 * They are carried in the MODEL frame rather than the world one, the world transform crossing
 * vanilla's own {@code scale(-1, -1, 1)} to reach a container that sits inside it.
 *
 * @param renderer the renderer class's simple name, which is what the shipped table keys a row by
 * @param facingYaw the constant turn about y a renderer folds into the body rotation it delegates,
 *     in the DEGREES vanilla writes it in - the shulker's {@code + 180f}, and {@code 0} for the
 *     thirteen others. It is not one of the steps and never becomes one: the base applies the body
 *     rotation as the subject's FACING, so it reaches every render, where a step reaches only the
 *     renders that pose. It goes into the mesh and no table member states it
 * @param steps the steps, outermost first, each carrying the expression its written channels hold
 * @param refusal why there are no steps here, or empty when the steps are the whole answer
 */
record RenderTransform(
    @NotNull String renderer,
    float facingYaw,
    @NotNull List<Map<PoseChannel, PoseExpr>> steps,
    @NotNull Optional<String> refusal
) {

    /**
     * A renderer whose {@code setupRotations} could not be read whole.
     *
     * @param renderer the renderer class's simple name
     * @param reason what it does that the grammar does not cover
     * @return the refusal
     */
    static @NotNull RenderTransform refused(@NotNull String renderer, @NotNull String reason) {
        return new RenderTransform(renderer, 0f, List.of(), Optional.of(reason));
    }

    /**
     * The facing turn and steps a renderer composes, read whole.
     *
     * @param renderer the renderer class's simple name
     * @param facingYaw the turn folded into the delegated body rotation, in degrees
     * @param steps the steps, outermost first
     * @return the transform
     */
    static @NotNull RenderTransform of(
        @NotNull String renderer, float facingYaw, @NotNull List<Map<PoseChannel, PoseExpr>> steps) {

        return new RenderTransform(renderer, facingYaw, List.copyOf(steps), Optional.empty());
    }

    /**
     * Whether this is a transform rather than a record of why there is not one.
     *
     * @return {@code true} when the steps are the whole answer
     */
    boolean isReadable() {
        return this.refusal.isEmpty();
    }

}
