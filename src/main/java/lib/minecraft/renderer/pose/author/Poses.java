package lib.minecraft.renderer.pose.author;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The entry point of pose authoring - one factory per tier vocabulary.
 *
 * <p>Each factory opens a builder whose selectors speak that tier's bone roster: the humanoid
 * tier the canonical seven of bipeds, the legged tier the head-body-legs-tail roster of walkers,
 * and the custom tier no anatomy at all, for every roster the other two do not fit - a fused
 * pair, an extra wing, an eight-legged crawler.
 *
 * <p>Every tier also reaches a bone by its mesh name through {@link PoseBuilder#bone}, so a part
 * outside a vocabulary's roster is one verb away rather than a move to another tier.
 */
@UtilityClass
@Parity(subject = Subject.ENTITY)
public final class Poses {

    /**
     * Opens a humanoid builder - the canonical seven-bone vocabulary with paired-limb stamps,
     * mirror verbs and whole-silhouette presets.
     *
     * @param styleId the id a caller selects the built style by
     * @return the humanoid builder
     */
    public static @NotNull HumanoidPose.Builder humanoid(@NotNull String styleId) {
        return new HumanoidPose.Builder(styleId);
    }

    /**
     * Opens a legged builder - head, body, legs by row and side, and tail, with paired-leg
     * stamps.
     *
     * @param styleId the id a caller selects the built style by
     * @return the legged builder
     */
    public static @NotNull LeggedPose.Builder legged(@NotNull String styleId) {
        return new LeggedPose.Builder(styleId);
    }

    /**
     * Opens a custom builder - the shared bone-name verbs over any roster at all, plus the raw
     * expression escape hatch.
     *
     * @param styleId the id a caller selects the built style by
     * @return the custom builder
     */
    public static @NotNull CustomPose.Builder custom(@NotNull String styleId) {
        return new CustomPose.Builder(styleId);
    }

}
