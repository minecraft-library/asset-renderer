package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The graphical-projection taxonomy a caller selects per render - the consolidated front door to the
 * camera {@link Camera pose} and {@link Lens lens}. Each constant bundles a canonical base pose
 * (pitch / yaw / roll) with its flatten family; {@link #resolve(EulerRotation)} composes the caller's
 * rotation onto that base pose and returns the pose-locked triple the renderers consume. The named
 * vanilla cameras live here as the {@code VANILLA_*} members, and this catalog assembles them from
 * {@link Camera}'s primitives ({@link Camera#fromPose} / {@link Camera#identity}) - so {@code Camera}
 * stays a dependency-free value type.
 *
 * <p>The canonical members carry <b>correct textbook values</b> (true isometric, standard dimetric,
 * cabinet / cavalier / military, one / two / three point). The {@code VANILLA_*} members document the
 * <b>shipped hardcoded baseline</b> - they reproduce the current renders byte-for-byte and are the
 * defaults so existing output never changes. Every member resolves the same way: its base pose routes
 * through {@link Camera#fromPose} ({@code rotationXYZ}), reproducing the legacy block / player / entity
 * cameras bit-for-bit. An unrotated {@link #resolve()} on a {@code VANILLA_*} member yields the exact
 * shipped {@link Camera} (pose + {@link Lens}) and lighting pair.
 *
 * <p>Three projection families map onto the pipeline as follows: <b>axonometric</b> (isometric /
 * dimetric / trimetric) = orthographic flatten + a pose; <b>perspective</b> (one / two / three point)
 * = perspective flatten + a pose (n = how many principal axes tilt off the view axis); <b>oblique</b>
 * (cavalier / cabinet / military) = a depth-shear flatten. The caller's rotation adds onto the base
 * pose's pitch / yaw / roll, posing the camera and its lighting together. {@link #VANILLA_ENTITY} is a
 * plain iso display pose like the rest; the entity's model-to-world facing / chirality is applied
 * separately by the entity renderer as a {@code Placement} + model-spin.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum Projection {

    /**
     * One-point central perspective.
     * <p>
     * The view axis lies on one principal axis, producing a single vanishing point. Pose
     * {@code (0, 180, 0)}, perspective lens.
     */
    ONE_POINT(new EulerRotation(0f, 180f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Two-point central perspective.
     * <p>
     * Yawed off the view axis with zero pitch, producing two horizontal vanishing points and parallel
     * verticals. Pose {@code (0, 215, 0)}, perspective lens.
     */
    TWO_POINT(new EulerRotation(0f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Three-point central perspective.
     * <p>
     * Yawed and pitched off the view axis, producing three vanishing points including the vertical.
     * Pose {@code (30, 215, 0)}, perspective lens.
     */
    THREE_POINT(new EulerRotation(30f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f)),

    /**
     * Isometric axonometric projection.
     * <p>
     * Pitch {@code atan(1/√2) = 35.264°} gives equal foreshortening on all three axes (ISO 5456-3).
     * Orthographic lens.
     */
    ISOMETRIC(new EulerRotation(35.264f, 225f, 0f), Lens.orthographic(0.45f)),

    /**
     * Dimetric axonometric projection.
     * <p>
     * The 2:1 pixel-art convention - pitch {@code atan(0.5) = 26.565°} foreshortens two axes equally.
     * Orthographic lens.
     */
    DIMETRIC(new EulerRotation(26.565f, 225f, 0f), Lens.orthographic(0.5f)),

    /**
     * Trimetric axonometric projection.
     * <p>
     * All three axes foreshortened differently (ISO 5456-3 asymmetric example). Pose
     * {@code (20, 250, 0)}, orthographic lens.
     */
    TRIMETRIC(new EulerRotation(20f, 250f, 0f), Lens.orthographic(0.5f)),

    /**
     * Cavalier oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° to full depth, with no
     * foreshortening. Oblique lens {@code L = 1.0}.
     */
    CAVALIER(new EulerRotation(0f, 180f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Cabinet oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° with depth halved for a
     * natural look - the de-facto cabinet standard. Oblique lens {@code L = 0.5}.
     */
    CABINET(new EulerRotation(0f, 180f, 0f), Lens.oblique(0.5f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Military (planometric) oblique projection.
     * <p>
     * The top plan is shown true-shape rotated 45° with verticals drawn to true length (ISO 5456-3).
     * Pose {@code (90, 225, 0)} plan, oblique lens {@code L = 1.0}. The least-standard mapping;
     * verify visually.
     */
    MILITARY(new EulerRotation(90f, 225f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f)),

    /**
     * Shipped block, fluid, and portal baseline.
     * <p>
     * Vanilla's {@code [30, 225, 0]} {@code display.gui} pose baked into the root
     * {@code block/block.json} model - technically a dimetric, not true isometric - at scale
     * {@code 0.625}. The default three-quarter block-icon view (block atlases, skulls, busts,
     * full-body skin renders) used whenever a block model does not override its own GUI pose;
     * reproduces the block / fluid / portal renders byte-for-byte.
     */
    VANILLA_BLOCK(new EulerRotation(30f, 225f, 0f), Lens.ISOMETRIC_BLOCK),

    /**
     * Shipped player baseline - a <b>facing-neutral</b> iso pose ({@code [30, 225, 0]}, the same
     * block-icon angle as {@link #VANILLA_BLOCK}) with the player's {@code Lens.NONE} flatten. The
     * humanoid facing is NOT baked here: the player renderer applies its {@code R_Y(180)} facing as a
     * model-to-world {@code Placement} (like the entity's {@code ENTITY_FLIP}), which turns the model's
     * {@code +Z} {@code SOUTH} front toward the camera - {@code [30, 225, 0] · R_Y(180) = [30, 45, 0]},
     * the shipped player pose. Keeping the facing on the renderer lets any projection present the
     * player's front rather than its back. Reproduces the player renders byte-for-byte; the default for
     * the player renderer.
     */
    VANILLA_PLAYER(new EulerRotation(30f, 225f, 0f), Lens.NONE),

    /**
     * Shipped 3D held-item baseline.
     * <p>
     * The moderate {@code GUI_ITEM} perspective; the item pose lives in the model's own
     * {@code display} matrix. Reproduces the held-item renders byte-for-byte; the default for the item
     * renderer.
     */
    VANILLA_GUI_ITEM(EulerRotation.NONE, Lens.GUI_ITEM),

    /**
     * Shipped entity baseline.
     * <p>
     * Vanilla's {@code EntityFrameRenderer.ISO_ROTATION = rotationXYZ(210°, 45°, 0°)}, itself derived
     * from the empirical 24-step yaw + 576-frame pitch/roll sweep that locked vanilla's entity-preview
     * pipeline camera. Resolves to the plain {@code rotationXYZ(210°, 45°, 0°)} iso display pose (det=+1),
     * the same GUI-display-pose family as {@link #VANILLA_BLOCK} / {@link #VANILLA_PLAYER}. The entity's
     * model-to-world facing + chirality (vanilla {@code LivingEntityRenderer.submit}'s
     * {@code rotateY(180°) × scale(-1,-1,1) = flip180}) is applied separately by the entity renderer as a
     * {@code Placement}; it composes onto this pose as {@code flip180 × R(iso) = rotationXYZ(30°, 45°, 0°)}
     * to match the harness ground-truth PNGs. Because the facing lives on the placement, this constant is
     * a plain camera and an entity is a normal projection subject. The caller's rotation stays a separate
     * model-spin. The default for the entity renderer.
     */
    VANILLA_ENTITY(new EulerRotation(210f, 45f, 0f), Lens.ISOMETRIC_BLOCK);

    /**
     * This projection's unrotated base pose - the {@code (pitch, yaw, roll)} the camera and lighting
     * sit at before the caller's rotation, in degrees. The canonical home for the vanilla iso angles
     * (block {@code [30, 225, 0]}, player {@code [30, 45, 0]}, entity {@code [210, 45, 0]}); callers
     * that need the raw rotation - the entity pipeline's bounds/anchor inverse - pull it from here.
     */
    private final @NotNull EulerRotation basePose;

    /**
     * This projection's flatten family - the 3D-to-2D {@link Lens} paired with the pose. Rotation-
     * independent; passed straight through into the resolved {@link Camera#lens()}.
     */
    private final @NotNull Lens lens;

    /**
     * Resolves this projection at its base pose into the unrotated {@link Camera} (pose + lens +
     * lighting pose). Equivalent to {@link #resolve(EulerRotation)} with {@link EulerRotation#NONE}, so
     * for a {@code VANILLA_*} member it yields the exact shipped baseline camera.
     *
     * @return the camera at the base pose
     */
    public @NotNull Camera resolve() {
        return resolve(EulerRotation.NONE);
    }

    /**
     * Resolves this projection into a {@link Camera} (pose + lens + lighting pose). The rotation adds to
     * the base pitch / yaw / roll, so it poses the camera and its lighting together (the lens is
     * rotation-independent) through the parity-pinned {@link Camera#fromPose} {@code rotationXYZ} path,
     * which reproduces the legacy {@code VANILLA_*} cameras bit-for-bit; {@link EulerRotation#NONE} yields
     * the base pose unchanged, keeping the default render path byte-identical.
     *
     * <p>{@link #VANILLA_ENTITY} resolves to the plain {@code rotationXYZ(210, 45, 0)} iso pose like any
     * other display-pose member; the entity's model-to-world facing / chirality is applied separately by
     * the entity renderer as a {@code Placement} (with the caller's rotation + {@code setupRotations}
     * addends as a model-spin), so the entity is a normal projection subject.
     *
     * @param rotation the rotation composed onto the base pose, in degrees
     * @return the resolved camera
     */
    public @NotNull Camera resolve(@NotNull EulerRotation rotation) {
        return Camera.fromPose(compose(this.basePose, rotation), this.lens);
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
