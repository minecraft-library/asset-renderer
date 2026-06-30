package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The graphical-projection taxonomy a caller selects per render - the consolidated front door to the
 * camera {@link Camera pose} and {@link Lens flatten}. Each constant bundles a canonical base pose
 * (pitch / yaw / roll) with its flatten family; {@link #resolve(EulerRotation)} composes the caller's
 * rotation onto that base pose and returns the pose-locked triple the renderers consume.
 *
 * <p>The canonical members carry <b>correct textbook values</b> (true isometric, standard dimetric,
 * cabinet / cavalier / military, one / two / three point). The {@code VANILLA_*} members document the
 * <b>shipped hardcoded baseline</b> - they reproduce the current renders byte-for-byte and are the
 * defaults so existing output never changes. Resolution is uniform for every member: routing the base
 * pose through the {@link Camera#withGuiPose} {@code rotationXYZ} path reproduces the legacy cameras
 * bit-for-bit, so an unrotated {@link #resolve()} on a {@code VANILLA_*} member yields the exact
 * shipped {@link Camera} / {@link Lens} / lighting triple.
 *
 * <p>Three projection families map onto the pipeline as follows: <b>axonometric</b> (isometric /
 * dimetric / trimetric) = orthographic flatten + a pose; <b>perspective</b> (one / two / three point)
 * = perspective flatten + a pose (n = how many principal axes tilt off the view axis); <b>oblique</b>
 * (cavalier / cabinet / military) = a depth-shear flatten. The caller's rotation adds onto the base
 * pose's pitch / yaw / roll, posing the camera and its lighting together.
 */
@RequiredArgsConstructor
public enum Projection {

    /**
     * One-point central perspective.
     * <p>
     * The view axis lies on one principal axis, producing a single vanishing point. Pose
     * {@code (0, 180, 0)}, perspective flatten.
     */
    ONE_POINT(new EulerRotation(0f, 180f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Two-point central perspective.
     * <p>
     * Yawed off the view axis with zero pitch, producing two horizontal vanishing points and parallel
     * verticals. Pose {@code (0, 215, 0)}, perspective flatten.
     */
    TWO_POINT(new EulerRotation(0f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Three-point central perspective.
     * <p>
     * Yawed and pitched off the view axis, producing three vanishing points including the vertical.
     * Pose {@code (30, 215, 0)}, perspective flatten.
     */
    THREE_POINT(new EulerRotation(30f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Isometric axonometric projection.
     * <p>
     * Pitch {@code atan(1/√2) = 35.264°} gives equal foreshortening on all three axes (ISO 5456-3).
     * Orthographic flatten.
     */
    ISOMETRIC(new EulerRotation(35.264f, 225f, 0f), Lens.orthographic(0.45f)),

    /**
     * Dimetric axonometric projection.
     * <p>
     * The 2:1 pixel-art convention - pitch {@code atan(0.5) = 26.565°} foreshortens two axes equally.
     * Orthographic flatten.
     */
    DIMETRIC(new EulerRotation(26.565f, 225f, 0f), Lens.orthographic(0.5f)),

    /**
     * Trimetric axonometric projection.
     * <p>
     * All three axes foreshortened differently (ISO 5456-3 asymmetric example). Pose
     * {@code (20, 250, 0)}, orthographic flatten.
     */
    TRIMETRIC(new EulerRotation(20f, 250f, 0f), Lens.orthographic(0.5f)),

    /**
     * Cavalier oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° to full depth, with no
     * foreshortening. Oblique flatten {@code L = 1.0}.
     */
    CAVALIER(new EulerRotation(0f, 180f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Cabinet oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° with depth halved for a
     * natural look - the de-facto cabinet standard. Oblique flatten {@code L = 0.5}.
     */
    CABINET(new EulerRotation(0f, 180f, 0f), Lens.oblique(0.5f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Military (planometric) oblique projection.
     * <p>
     * The top plan is shown true-shape rotated 45° with verticals drawn to true length (ISO 5456-3).
     * Pose {@code (90, 225, 0)} plan, oblique flatten {@code L = 1.0}. The least-standard mapping;
     * verify visually.
     */
    MILITARY(new EulerRotation(90f, 225f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Shipped block, fluid, and portal baseline.
     * <p>
     * Vanilla's {@code [30, 225, 0]} {@code display.gui} pose - technically a dimetric, not true
     * isometric - at scale {@code 0.625}. Reproduces the block / fluid / portal renders byte-for-byte;
     * the default for those renderers.
     */
    VANILLA_BLOCK(EulerRotation.STANDARD_ISO_BLOCK, Lens.ISOMETRIC_BLOCK),

    /**
     * Shipped player baseline.
     * <p>
     * The front-facing {@code [30, 45, 0]} humanoid pose at the conservative scale. Reproduces the
     * player renders byte-for-byte; the default for the player renderer.
     */
    VANILLA_PLAYER(EulerRotation.STANDARD_ISO_PLAYER, Lens.NONE),

    /**
     * Shipped 3D held-item baseline.
     * <p>
     * The moderate {@code GUI_ITEM} perspective; the item pose lives in the model's own
     * {@code display} matrix. Reproduces the held-item renders byte-for-byte; the default for the item
     * renderer.
     */
    VANILLA_GUI_ITEM(EulerRotation.NONE, Lens.GUI_ITEM);

    /**
     * Resolved camera pose, flatten, and lighting pose for one {@link Projection} at a chosen rotation -
     * the pose-locked triple a renderer feeds to its engine, projection, and inventory relight in
     * lock-step.
     *
     * @param camera the baked camera pose
     * @param flatten the 3D-to-2D projection
     * @param lightingPose the Euler pose the inventory relight must mirror to track the camera
     */
    public record Resolved(@NotNull Camera camera, @NotNull Lens flatten, @NotNull EulerRotation lightingPose) {}

    private final @NotNull EulerRotation basePose;
    private final @NotNull Lens baseFlatten;

    /**
     * Resolves this projection at its base pose - the unrotated camera / flatten / lighting-pose
     * triple. Equivalent to {@link #resolve(EulerRotation)} with {@link EulerRotation#NONE}, so for a
     * {@code VANILLA_*} member it yields the exact shipped baseline.
     *
     * @return the resolved triple at the base pose
     */
    public @NotNull Resolved resolve() {
        return resolve(EulerRotation.NONE);
    }

    /**
     * Resolves this projection into the camera / flatten / lighting-pose triple, composing the given
     * rotation onto this constant's base pose. The rotation adds to the base pitch / yaw / roll, so it
     * poses the camera and the lighting pose together (the flatten is rotation-independent);
     * {@link EulerRotation#NONE} yields the base pose unchanged, keeping the default render path
     * byte-identical. The camera is built through the parity-pinned {@link Camera#withGuiPose}
     * {@code rotationXYZ} path, which reproduces the legacy {@code VANILLA_*} cameras bit-for-bit.
     *
     * @param rotation the rotation composed onto the base pose, in degrees
     * @return the resolved triple
     */
    public @NotNull Resolved resolve(@NotNull EulerRotation rotation) {
        EulerRotation pose = compose(this.basePose, rotation);
        return new Resolved(Camera.withGuiPose(pose), this.baseFlatten, pose);
    }

    /**
     * Adds a rotation onto a base pose component-wise. {@link EulerRotation#NONE} returns the base pose
     * unchanged - no float arithmetic, no drift - so a default render resolves to the exact base pose.
     */
    private static @NotNull EulerRotation compose(@NotNull EulerRotation base, @NotNull EulerRotation rotation) {
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f) return base;
        return new EulerRotation(
            base.pitch() + rotation.pitch(),
            base.yaw() + rotation.yaw(),
            base.roll() + rotation.roll()
        );
    }

}
