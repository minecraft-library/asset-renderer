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
 * pose's pitch / yaw / roll, posing the camera and its lighting together. {@link #VANILLA_ISO} is a
 * plain facing-neutral iso display pose shared by blocks, players, and entities; each renderer applies
 * its subject's facing separately as a model-to-world {@code Placement}.
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
     * Military oblique projection.
     * <p>
     * A three-quarter {@code (0, 225, 0)} pose - front face toward the camera with the top and one
     * side visible - with the receding depth axis sheared <b>vertically</b> ({@code -90°}, unlike
     * cavalier / cabinet's {@code -45°} diagonal recede) so the receding edges stay upright rather than
     * slanting diagonally. Oblique lens {@code L = 1.0} (no depth foreshortening).
     */
    MILITARY(new EulerRotation(0f, 225f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-90), 0.5f)),

    /**
     * Perspective portrait - a close, gently downward three-quarter head / bust framing. Full
     * perspective ({@code fov ~= 45}, camera {@code 2.375} subject-heights out) at a {@code 15} downward
     * pitch and a soft {@code 25} yaw off head-on, so the camera sits slightly <b>above</b> the subject
     * and looks down onto it (a high-angle shot) - the flattering avatar / hero head pose. Base pose
     * {@code [15, 205, 0]} is facing-neutral; a player render's {@code R_Y(180)} facing turns it to the
     * effective {@code [15, 25, 0]}. Resolve with {@link Facing#FLIPPED} for the low-angle mirrored
     * (camera below, looking up at the underside) HERO pose.
     * <p>
     * Ported from NMSR's {@code /head} avatar camera ({@code nmsr-rs} {@code RenderRequestMode::Head}):
     * {@code CameraRotation { yaw: 25, pitch: 15 }}, {@code Perspective { fov: 45 }}, orbital distance
     * {@code 19} on an {@code 8}px head. NMSR's {@code minecraft_rotation_matrix} bakes the same
     * {@code diag(-1, 1, -1)} facing flip this renderer applies, folding into {@code rotationXYZ(15, 205,
     * 0)} (cross-checked: NMSR's iso mode {@code yaw 45 / pitch 35.26} maps the same way onto
     * {@link #ISOMETRIC}); the {@code 19 / 8 = 2.375} distance sets the camera-distance and focal length
     * that reproduce its near / far foreshortening.
     */
    PORTRAIT(new EulerRotation(15f, 205f, 0f), Lens.perspective(1f, 2.375f, 2.375f, 0.45f)),

    /**
     * The shipped vanilla iso baseline for blocks, fluids, portals, players, and entities - vanilla's
     * {@code [30, 225, 0]} {@code display.gui} pose baked into the root {@code block/block.json} model
     * (technically a dimetric, not true isometric) at scale {@code 0.625} ({@link Lens#ISOMETRIC_BLOCK}).
     * The default three-quarter block-icon view (block atlases, skulls, busts, full-body skin renders,
     * entity previews) used whenever a model does not override its own GUI pose; reproduces the block /
     * fluid / portal / player / entity renders byte-for-byte.
     * <p>
     * This pose is <b>facing-neutral</b>: it presents the model's {@code -Z} side, which for a block is its
     * canonical icon face but for a humanoid is its back (a humanoid's front is its {@code +Z}
     * {@code SOUTH} face). Each renderer turns its subject to face the camera with its own model-to-world
     * {@code Placement}:
     * <ul>
     *   <li><b>block / fluid / portal</b> - identity; the authored orientation <b>is</b> the icon.</li>
     *   <li><b>player</b> - {@code R_Y(180)}, so {@code [30,225,0] · R_Y(180) = [30,45,0]}, the shipped
     *       player pose.</li>
     *   <li><b>entity</b> - {@code R_Y(180) × flip180 = R_Z(180) = diag(-1,-1,1)}, the humanoid yaw flip
     *       plus the Y-down-to-Y-up flip, so {@code R(30,225,0) × diag(-1,-1,1) = R(30,45,0) × flip180}
     *       reproduces the harness ground truth, where {@code flip180 = } vanilla
     *       {@code LivingEntityRenderer.submit}'s {@code rotateY(180°) × scale(-1,-1,1)}.</li>
     * </ul>
     * Keeping the facing on the renderer lets any projection present the subject's front. The three iso
     * subjects collapse onto this single {@link Lens#ISOMETRIC_BLOCK} constant because the player's lens is
     * irrelevant - its {@code rasterizeFitted} path cancels the projection scale.
     * <p>
     * The entity's harness lighting angle {@code [210, 45, 0]} lives on only as
     * {@code EntityGeometryKit.ENTITY_ISO_LIGHTING} (the plane-cube lighting frame), decoupled from this
     * camera pose. The caller's rotation composes onto this pose (blocks / players) or stays a separate
     * model-spin (entities). The default for the block, fluid, portal, player, and entity renderers.
     */
    VANILLA_ISO(new EulerRotation(30f, 225f, 0f), Lens.ISOMETRIC_BLOCK),

    /**
     * Shipped 3D held-item baseline.
     * <p>
     * The moderate {@code GUI_ITEM} perspective. The base pose is the facing-neutral {@code [0, 180, 0]}
     * head-on angle (presenting the model's {@code -Z} side, consistent with {@link #VANILLA_ISO} so a
     * subject's renderer facing presents its front); the item renderer ignores it, using
     * {@link Camera#identity} with only this projection's {@link Lens} since the held-item pose lives in
     * the model's own {@code display} matrix. Reproduces the held-item renders byte-for-byte; the default
     * for the item renderer.
     */
    VANILLA_GUI_ITEM(new EulerRotation(0f, 180f, 0f), Lens.GUI_ITEM);

    /**
     * This projection's unrotated base pose - the {@code (pitch, yaw, roll)} the camera and lighting
     * sit at before the caller's rotation, in degrees. For {@link #VANILLA_ISO} this is the single
     * facing-neutral {@code [30, 225, 0]} iso angle; the shipped player {@code [30, 45, 0]} and entity
     * {@code [210, 45, 0]} orientations are what that base pose becomes <b>after</b> each renderer's
     * model-to-world {@code Placement} composes onto it, not distinct base poses stored here. Callers
     * that need the raw rotation - the entity pipeline's bounds / anchor inverse - pull it from here.
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
        return resolve(EulerRotation.NONE, Facing.DEFAULT);
    }

    /**
     * Resolves this projection into a {@link Camera} (pose + lens + lighting pose). The rotation adds to
     * the base pitch / yaw / roll, so it poses the camera and its lighting together (the lens is
     * rotation-independent) through the parity-pinned {@link Camera#fromPose} {@code rotationXYZ} path,
     * which reproduces the legacy {@code VANILLA_*} cameras bit-for-bit; {@link EulerRotation#NONE} yields
     * the base pose unchanged, keeping the default render path byte-identical.
     *
     * <p>{@link #VANILLA_ISO} resolves to the plain {@code rotationXYZ(30, 225, 0)} iso pose like any
     * other display-pose member; each renderer's model-to-world facing / chirality is applied separately
     * as a {@code Placement} (for the entity, with the caller's rotation + {@code setupRotations} addends
     * as a model-spin), so blocks, players, and entities are all normal projection subjects.
     *
     * @param rotation the rotation composed onto the base pose, in degrees
     * @return the resolved camera
     */
    public @NotNull Camera resolve(@NotNull EulerRotation rotation) {
        return resolve(rotation, Facing.DEFAULT);
    }

    /**
     * Resolves this projection into a {@link Camera} with a view {@link Facing} reflection applied. The
     * rotation adds to the base pose (as {@link #resolve(EulerRotation)}); the facing then reflects the
     * composed pose - {@link Facing#mirrored()} mirrors the yaw, {@link Facing#flipped()} negates the
     * pitch - and, for an {@linkplain Lens.Kind#OBLIQUE oblique} lens, flips the depth-shear so the
     * mirror holds even where the yaw reflection is a no-op (a front-facing oblique). {@link Facing#DEFAULT}
     * is a bit-for-bit no-op, so a default resolve stays byte-identical.
     *
     * @param rotation the rotation composed onto the base pose, in degrees
     * @param facing the view-facing reflection applied after composition
     * @return the resolved camera
     */
    public @NotNull Camera resolve(@NotNull EulerRotation rotation, @NotNull Facing facing) {
        return Camera.fromPose(facing.apply(compose(this.basePose, rotation)), facing.apply(this.lens));
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
