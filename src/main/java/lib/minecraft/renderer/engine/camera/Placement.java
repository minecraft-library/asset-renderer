package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.tensor.Matrix4f;
import org.jetbrains.annotations.NotNull;

/**
 * A subject's model-to-world placement - the transform that lifts geometry from its native model space
 * into the shared world frame a {@link Camera} then views. Where {@link Camera} is the world-to-screen
 * half of the pipeline (extrinsics + intrinsics), {@code Placement} is the model-to-world half: the
 * per-subject facing, chirality, scale, and anchor that seat the subject in the world - vanilla's
 * {@code LivingEntityRenderer.submit} chain for entities; the identity no-op for subjects already
 * authored in world orientation (blocks via their blockstate variant rotation, the player via its
 * pre-rotation).
 *
 * <p>{@link ModelEngine} composes it between the camera pose and the caller's model transform
 * ({@code pose x placement x modelTransform}). {@link #IDENTITY} is the no-op placement -
 * byte-identical to the pre-Placement pipeline - used by subjects already authored in world orientation
 * (blocks, fluids, portals via their variant rotation; the player via its pre-rotation). The entity is
 * the one subject with a non-identity placement: {@code EntityRenderer}'s
 * {@code diag(-1, -1, 1)} humanoid-facing + Y-down-to-Y-up flip + chirality, which lets
 * {@link Projection#VANILLA_ISO} stay a facing-neutral pose while any projection still presents the
 * entity's front, upright.
 *
 * @param modelToWorld the model-to-world matrix, in column-vector form
 */
public record Placement(@NotNull Matrix4f modelToWorld) {

    /** The no-op placement - geometry is already in world orientation, so {@link ModelEngine} skips it. */
    public static final @NotNull Placement IDENTITY = new Placement(Matrix4f.IDENTITY);

    /**
     * Whether this placement is the {@link #IDENTITY} no-op, letting {@link ModelEngine} skip the extra
     * matrix multiply and stay byte-identical to the pre-Placement transform chain.
     *
     * @return {@code true} if this is the identity placement
     */
    public boolean isIdentity() {
        return this.modelToWorld == Matrix4f.IDENTITY;
    }

}
