package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import org.jetbrains.annotations.NotNull;

/**
 * A camera - the {@link #pose} (extrinsics), its {@link Lens lens} (intrinsics), and its
 * {@link #lightingPose} (the Euler orientation the inventory relight mirrors): the complete
 * view + projection a {@link ModelEngine} renders through. The pose is a baked column-vector matrix
 * applied after the caller's model transform (right-to-left to a model vertex); the lens is the
 * 3D-to-2D flatten applied per vertex after that.
 *
 * <p>A camera carries two orientations. The {@code pose} matrix maps geometry to screen; the
 * {@code lightingPose} Euler orients the inventory light. For a display pose they coincide - the
 * lighting mirrors the pose - so {@link #fromPose} sets both from one Euler. For the entity iso chain
 * they DIVERGE: the pose is a det=-1 chirality chain (not a plain rotation) while the lighting uses the
 * base {@code [210, 45, 0]} Euler, which is why the two are stored separately rather than one derived
 * from the other.
 *
 * <p>The named vanilla cameras live on {@link Projection}, which assembles them from these primitives.
 * Callers reach for a factory rather than the constructor:
 * <ul>
 *   <li><b>{@link #fromPose(EulerRotation, Lens)}</b> - a {@code display.*} GUI pose from the supplied
 *       Euler angles (via vanilla's {@code rotationXYZ}) paired with a lens; the Euler doubles as the
 *       lighting pose. Backs every {@link Projection} display-pose member and any ad-hoc pose (the item
 *       shield's {@code [15, -25, -5]}, a block model's {@code display.gui} override).</li>
 *   <li><b>{@link #identity(Lens)}</b> - no pre-rotation; geometry viewed straight down {@code -Z}.
 *       Used by the held-item path, whose pose lives in the model's own display matrix.</li>
 * </ul>
 *
 * @param pose the camera pose composed into every rasterization, in column-vector form
 * @param lens the 3D-to-2D flatten applied after the pose
 * @param lightingPose the Euler orientation the inventory relight mirrors to track the camera
 */
public record Camera(@NotNull Matrix4f pose, @NotNull Lens lens, @NotNull EulerRotation lightingPose) {

    /**
     * Returns a camera whose pose is a vanilla {@code display.*} GUI pose built from the supplied
     * Euler-angle rotation, paired with the given lens; the same Euler becomes the {@link #lightingPose}
     * (a display pose's lighting mirrors its pose). Use it for every {@link Projection} display-pose
     * member and for ad-hoc poses (the item shield's {@code [15, -25, -5]}, a block model's
     * {@code display.gui} override such as stairs' {@code [30, 135, 0]}).
     *
     * @param rotation the Euler-angle pose (in degrees), baked into the camera transform and the lighting pose
     * @param lens the flatten paired with the pose
     * @return a camera with the requested pose, lens, and lighting pose
     */
    public static @NotNull Camera fromPose(@NotNull EulerRotation rotation, @NotNull Lens lens) {
        return new Camera(buildGuiDisplayTransform(rotation), lens, rotation);
    }

    /**
     * Returns a camera with no pre-rotation - geometry is viewed directly down the negative Z axis -
     * paired with the given lens and an identity ({@link EulerRotation#NONE}) lighting pose. Used by the
     * held-item path, whose pose lives entirely in the model's own {@code display} matrix, so only the
     * lens is taken from the projection.
     *
     * @param lens the flatten paired with the identity pose
     * @return an identity-pose camera with the requested lens
     */
    public static @NotNull Camera identity(@NotNull Lens lens) {
        return new Camera(Matrix4f.IDENTITY, lens, EulerRotation.NONE);
    }

    /**
     * Returns a copy of this camera with the same pose and lighting pose but a different lens. Lets a
     * caller reuse a projection's resolved camera under a different flatten - e.g. a 2-block bed icon
     * that wants the {@link Projection#VANILLA_BLOCK} pose but a more conservative lens for margin -
     * without rebuilding the pose or reaching for the raw constructor.
     *
     * @param lens the replacement lens
     * @return a camera with this pose and lighting pose and the given lens
     */
    public @NotNull Camera withLens(@NotNull Lens lens) {
        return new Camera(this.pose, lens, this.lightingPose);
    }

    /**
     * Builds the matrix equivalent of vanilla's {@code Quaternionf.rotationXYZ(x, y, z)} for a
     * {@code display.*} transform's Euler angles in degrees. Bit-identical to vanilla's
     * {@code new Matrix4f().rotation(new Quaternionf().rotationXYZ(...))} by routing through the same
     * {@link Quaternionf} quaternion-to-matrix conversion.
     */
    private static @NotNull Matrix4f buildGuiDisplayTransform(@NotNull EulerRotation rotation) {
        return Quaternionf
            .rotationXYZ(rotation.pitchRadians(), rotation.yawRadians(), rotation.rollRadians())
            .toMatrix4f();
    }

}
