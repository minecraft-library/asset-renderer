package lib.minecraft.renderer.asset.pose;

import lib.minecraft.renderer.asset.model.EntityModelData;
import org.jetbrains.annotations.NotNull;

/**
 * One mesh a subject draws, paired with the pose that moves it.
 *
 * <p>A subject is more than one posed mesh: each overlay pass carries geometry of its own and poses
 * it with its own model class, so what a subject draws is a list of these pairs rather than one
 * mesh under one pose - the body's, each pass's, and a suppressed pass's alternate under the pose
 * of the pass it stands in for.
 *
 * @param pose the pose belonging to this mesh's own model class
 * @param model the mesh it poses
 */
public record Drawn(
    @NotNull EntityPose pose,
    @NotNull EntityModelData model
) {}
