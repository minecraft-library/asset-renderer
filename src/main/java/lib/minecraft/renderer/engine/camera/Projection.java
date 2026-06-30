package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import org.jetbrains.annotations.NotNull;

/**
 * The graphical-projection taxonomy a caller selects per render - the consolidated front door to the
 * camera {@link Camera pose} and {@link Lens flatten}. Each constant bundles a canonical base pose
 * (pitch / yaw / roll) with its flatten family; {@link #resolve(EulerRotation)} composes the caller's
 * rotation onto that base pose and returns the pose-locked triple the renderers consume. The named
 * vanilla cameras live here as the {@code VANILLA_*} members - {@link Camera} itself is now just the
 * value type plus the {@link Camera#fromPose} / {@link Camera#entityIsoChain} / {@link Camera#identity}
 * primitives this catalog assembles.
 *
 * <p>The canonical members carry <b>correct textbook values</b> (true isometric, standard dimetric,
 * cabinet / cavalier / military, one / two / three point). The {@code VANILLA_*} members document the
 * <b>shipped hardcoded baseline</b> - they reproduce the current renders byte-for-byte and are the
 * defaults so existing output never changes. Resolution routes the base pose through
 * {@link Camera#fromPose} ({@code rotationXYZ}) for the GUI-pose members - reproducing the legacy block
 * / player cameras bit-for-bit - and through the {@link Camera#entityIsoChain} chirality chain for
 * {@link #VANILLA_ENTITY}. An unrotated {@link #resolve()} on a {@code VANILLA_*} member yields the
 * exact shipped {@link Camera} / {@link Lens} / lighting triple.
 *
 * <p>Three projection families map onto the pipeline as follows: <b>axonometric</b> (isometric /
 * dimetric / trimetric) = orthographic flatten + a pose; <b>perspective</b> (one / two / three point)
 * = perspective flatten + a pose (n = how many principal axes tilt off the view axis); <b>oblique</b>
 * (cavalier / cabinet / military) = a depth-shear flatten. The caller's rotation adds onto the base
 * pose's pitch / yaw / roll, posing the camera and its lighting together - except {@link #VANILLA_ENTITY},
 * whose det=-1 chirality chain is fixed and whose rotation stays a separate model-spin.
 */
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
     * Vanilla's {@code [30, 225, 0]} {@code display.gui} pose from the root {@code block/block.json}
     * model ({@link EulerRotation#STANDARD_ISO_BLOCK}) - technically a dimetric, not true isometric -
     * at scale {@code 0.625}. The default three-quarter block-icon view (block atlases, skulls, busts,
     * full-body skin renders); reproduces the block / fluid / portal renders byte-for-byte.
     */
    VANILLA_BLOCK(EulerRotation.STANDARD_ISO_BLOCK, Lens.ISOMETRIC_BLOCK),

    /**
     * Shipped player baseline.
     * <p>
     * The block pose with the yaw flipped 180&deg; so a humanoid's front faces the camera
     * ({@code [30, 45, 0]}, {@link EulerRotation#STANDARD_ISO_PLAYER}) at the conservative scale - a
     * det=+1 GUI pose carrying none of {@link #VANILLA_ENTITY}'s LER chirality. Reproduces the player
     * renders byte-for-byte; the default for the player renderer.
     */
    VANILLA_PLAYER(EulerRotation.STANDARD_ISO_PLAYER, Lens.NONE),

    /**
     * Shipped 3D held-item baseline.
     * <p>
     * The moderate {@code GUI_ITEM} perspective; the item pose lives in the model's own
     * {@code display} matrix. Reproduces the held-item renders byte-for-byte; the default for the item
     * renderer.
     */
    VANILLA_GUI_ITEM(EulerRotation.NONE, Lens.GUI_ITEM),

    /**
     * Shipped entity-preview baseline.
     * <p>
     * Vanilla's {@code EntityFrameRenderer.ISO_ROTATION} ({@code [210, 45, 0]},
     * {@link EulerRotation#STANDARD_ISO_ENTITY}) built as the {@link Camera#entityIsoChain} chirality
     * chain - a det=-1 transform carrying the LER chirality + reflection the vanilla-reference harness
     * applies, so entity output aligns with the harness ground-truth PNGs. Distinct from
     * {@link #VANILLA_BLOCK} / {@link #VANILLA_PLAYER}, which are det=+1 GUI display poses: this is the
     * reflected entity chain ({@code resolve}'s {@link CameraChain#ENTITY_ISO} branch), and the
     * caller's rotation stays a separate model-spin rather than composing into the camera. The default
     * for the entity renderer.
     */
    VANILLA_ENTITY(EulerRotation.STANDARD_ISO_ENTITY, Lens.ISOMETRIC_BLOCK, CameraChain.ENTITY_ISO);

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

    /**
     * How a {@link Projection} turns its base pose into a {@link Camera}.
     */
    private enum CameraChain {

        /**
         * A {@code display.*} GUI pose: {@link Camera#fromPose} builds the {@code rotationXYZ(pose)}
         * matrix and the caller's rotation composes into that pose. Used by every member except the
         * entity preview.
         */
        GUI_POSE,

        /**
         * The vanilla entity-preview iso chain ({@link Camera#entityIsoChain}) - a det=-1,
         * odd-reflection chirality transform matching the harness. The chain is fixed; the caller
         * applies its rotation (plus the per-entity {@code setupRotations} addends) as a separate
         * model-spin at rasterize time, so the rotation is NOT composed into this camera.
         */
        ENTITY_ISO

    }

    private final @NotNull EulerRotation basePose;
    private final @NotNull Lens baseFlatten;
    private final @NotNull CameraChain cameraChain;

    Projection(@NotNull EulerRotation basePose, @NotNull Lens baseFlatten) {
        this(basePose, baseFlatten, CameraChain.GUI_POSE);
    }

    Projection(@NotNull EulerRotation basePose, @NotNull Lens baseFlatten, @NotNull CameraChain cameraChain) {
        this.basePose = basePose;
        this.baseFlatten = baseFlatten;
        this.cameraChain = cameraChain;
    }

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
     * rotation onto this constant's base pose. For a GUI-pose member the rotation adds to the base
     * pitch / yaw / roll, so it poses the camera and the lighting pose together (the flatten is
     * rotation-independent) through the parity-pinned {@link Camera#fromPose} {@code rotationXYZ} path,
     * which reproduces the legacy {@code VANILLA_*} cameras bit-for-bit; {@link EulerRotation#NONE}
     * yields the base pose unchanged, keeping the default render path byte-identical.
     *
     * <p>{@link #VANILLA_ENTITY} is the exception: its det=-1 {@link Camera#entityIsoChain} is fixed,
     * so the {@code rotation} is ignored here and the entity renderer applies it (plus its
     * {@code setupRotations} addends) as a separate model-spin at rasterize time.
     *
     * @param rotation the rotation composed onto the base pose, in degrees (ignored for
     *     {@link #VANILLA_ENTITY})
     * @return the resolved triple
     */
    public @NotNull Resolved resolve(@NotNull EulerRotation rotation) {
        if (this.cameraChain == CameraChain.ENTITY_ISO)
            return new Resolved(new Camera(Camera.entityIsoChain()), this.baseFlatten, this.basePose);
        EulerRotation pose = compose(this.basePose, rotation);
        return new Resolved(Camera.fromPose(pose), this.baseFlatten, pose);
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
