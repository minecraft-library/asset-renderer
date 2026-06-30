package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The graphical-projection taxonomy a caller selects per render - the consolidated front door to the
 * camera {@link Camera pose} and {@link Lens flatten}. Each constant bundles a canonical base pose
 * (pitch / yaw / roll for {@link Horizontal#RIGHT} / {@link Vertical#DOWN}) with its flatten family;
 * {@link #resolve} applies the requested facing and returns the pose-locked triple the renderers
 * consume.
 *
 * <p>The canonical members carry <b>correct textbook values</b> (true isometric, standard dimetric,
 * cabinet / cavalier / military, one / two / three point). The {@code VANILLA_*} members document the
 * <b>shipped hardcoded baseline</b> - they reproduce the current renders byte-for-byte and are the
 * defaults so existing output never changes; they short-circuit {@link #resolve} to the exact legacy
 * {@link Camera} / {@link Lens} objects and ignore facing.
 *
 * <p>Three projection families map onto the pipeline as follows: <b>axonometric</b> (isometric /
 * dimetric / trimetric) = orthographic flatten + a pose; <b>perspective</b> (one / two / three point)
 * = perspective flatten + a pose (n = how many principal axes tilt off the view axis); <b>oblique</b>
 * (cavalier / cabinet / military) = a depth-shear flatten. Facing is a sign tweak on the pose's
 * yaw / pitch (and, for oblique, on the shear angle).
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
     * Resolved camera pose, flatten, and lighting pose for one {@link Projection} at a chosen facing -
     * the pose-locked triple a renderer feeds to its engine, projection, and inventory relight in
     * lock-step.
     *
     * @param camera the baked camera pose
     * @param flatten the 3D-to-2D projection
     * @param lightingPose the Euler pose the inventory relight must mirror to track the camera
     */
    public record Resolved(@NotNull Camera camera, @NotNull Lens flatten, @NotNull EulerRotation lightingPose) {}

    /**
     * Horizontal facing of a projection - which side of the subject turns toward the viewer.
     * {@link #RIGHT} is the default, vanilla three-quarter orientation; {@link #LEFT} mirrors it about
     * the front-facing vertical plane.
     */
    public enum Horizontal {

        /**
         * The subject's right-front corner faces the camera - the default.
         */
        RIGHT,

        /**
         * The subject's left-front corner faces the camera - the horizontal mirror of {@link #RIGHT}.
         */
        LEFT

    }

    /**
     * Vertical facing of a projection - whether the camera looks down at the subject's top
     * ({@link #DOWN}, the default bird's-eye view) or up at its underside ({@link #UP}).
     */
    public enum Vertical {

        /**
         * The camera looks down at the subject's top - the default.
         */
        DOWN,

        /**
         * The camera looks up at the subject's underside - the vertical mirror of {@link #DOWN}.
         */
        UP

    }

    private final @NotNull EulerRotation basePose;
    private final @NotNull Lens baseFlatten;

    /**
     * Resolves this projection at the given facing into the camera / flatten / lighting-pose triple.
     * The {@code VANILLA_*} baselines short-circuit to the exact legacy objects and ignore facing, so
     * the default render path stays byte-identical; canonical members tweak the pose (and, for oblique,
     * the shear angle) by facing and build the camera through the parity-pinned
     * {@link Camera#withGuiPose} {@code rotationXYZ} path.
     *
     * @param horizontal the horizontal facing
     * @param vertical the vertical facing
     * @return the resolved triple
     */
    public @NotNull Resolved resolve(@NotNull Horizontal horizontal, @NotNull Vertical vertical) {
        return switch (this) {
            case VANILLA_BLOCK ->
                new Resolved(Camera.forBlockIcon(), Lens.ISOMETRIC_BLOCK, EulerRotation.STANDARD_ISO_BLOCK);
            case VANILLA_PLAYER ->
                new Resolved(Camera.forPlayerIcon(), Lens.NONE, EulerRotation.STANDARD_ISO_PLAYER);
            case VANILLA_GUI_ITEM ->
                new Resolved(Camera.identity(), Lens.GUI_ITEM, EulerRotation.NONE);
            default -> {
                EulerRotation pose = facePose(this.basePose, horizontal, vertical);
                Lens flatten = this.baseFlatten.kind() == Lens.Kind.OBLIQUE
                    ? faceOblique(this.baseFlatten, horizontal, vertical)
                    : this.baseFlatten;
                yield new Resolved(Camera.withGuiPose(pose), flatten, pose);
            }
        };
    }

    /**
     * Applies facing to a pose: {@link Horizontal#LEFT} reflects the yaw about the front-facing
     * direction ({@code 360 - yaw}); {@link Vertical#UP} negates the pitch.
     */
    private static @NotNull EulerRotation facePose(@NotNull EulerRotation base, @NotNull Horizontal h, @NotNull Vertical v) {
        float pitch = v == Vertical.UP ? -base.pitch() : base.pitch();
        float yaw = h == Horizontal.LEFT ? 360f - base.yaw() : base.yaw();
        return new EulerRotation(pitch, yaw, base.roll());
    }

    /**
     * Applies facing to an oblique flatten by flipping the receding-axis angle's cosine for
     * {@link Horizontal#LEFT} and its sine for {@link Vertical#UP}, so the depth shears toward the
     * requested quadrant.
     */
    private static @NotNull Lens faceOblique(@NotNull Lens base, @NotNull Horizontal h, @NotNull Vertical v) {
        double cos = Math.cos(base.obliqueAngleRadians()) * (h == Horizontal.LEFT ? -1d : 1d);
        double sin = Math.sin(base.obliqueAngleRadians()) * (v == Vertical.UP ? -1d : 1d);
        return Lens.oblique(base.obliqueDepthFactor(), (float) Math.atan2(sin, cos), base.projectionScale());
    }

}
