package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lombok.AccessLevel;
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
 * {@link Camera}'s primitives ({@link Camera#fromPose} / {@link Camera#identity}) plus its own
 * {@link #entityIsoChain} for the entity chirality - so {@code Camera} stays a dependency-free value type.
 *
 * <p>The canonical members carry <b>correct textbook values</b> (true isometric, standard dimetric,
 * cabinet / cavalier / military, one / two / three point). The {@code VANILLA_*} members document the
 * <b>shipped hardcoded baseline</b> - they reproduce the current renders byte-for-byte and are the
 * defaults so existing output never changes. Each member carries a {@link Assembly} strategy that
 * assembles its {@link Resolved} triple: {@link Assembly#DISPLAY_POSE} routes the base pose through
 * {@link Camera#fromPose} ({@code rotationXYZ}) - reproducing the legacy block / player cameras
 * bit-for-bit - and {@link Assembly#ENTITY_ISO} builds the {@link #entityIsoChain} chirality chain
 * for {@link #VANILLA_ENTITY}. An unrotated {@link #resolve()} on a {@code VANILLA_*} member yields the
 * exact shipped {@link Camera} (pose + {@link Lens}) and lighting pair.
 *
 * <p>Three projection families map onto the pipeline as follows: <b>axonometric</b> (isometric /
 * dimetric / trimetric) = orthographic flatten + a pose; <b>perspective</b> (one / two / three point)
 * = perspective flatten + a pose (n = how many principal axes tilt off the view axis); <b>oblique</b>
 * (cavalier / cabinet / military) = a depth-shear flatten. The caller's rotation adds onto the base
 * pose's pitch / yaw / roll, posing the camera and its lighting together - except {@link #VANILLA_ENTITY},
 * whose det=-1 chirality chain is fixed and whose rotation stays a separate model-spin.
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
    ONE_POINT(new EulerRotation(0f, 180f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f), Assembly.DISPLAY_POSE),

    /**
     * Two-point central perspective.
     * <p>
     * Yawed off the view axis with zero pitch, producing two horizontal vanishing points and parallel
     * verticals. Pose {@code (0, 215, 0)}, perspective lens.
     */
    TWO_POINT(new EulerRotation(0f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f), Assembly.DISPLAY_POSE),

    /**
     * Three-point central perspective.
     * <p>
     * Yawed and pitched off the view axis, producing three vanishing points including the vertical.
     * Pose {@code (30, 215, 0)}, perspective lens.
     */
    THREE_POINT(new EulerRotation(30f, 215f, 0f), Lens.perspective(0.6f, 8f, 8f, 0.45f), Assembly.DISPLAY_POSE),

    /**
     * Isometric axonometric projection.
     * <p>
     * Pitch {@code atan(1/√2) = 35.264°} gives equal foreshortening on all three axes (ISO 5456-3).
     * Orthographic lens.
     */
    ISOMETRIC(new EulerRotation(35.264f, 225f, 0f), Lens.orthographic(0.45f), Assembly.DISPLAY_POSE),

    /**
     * Dimetric axonometric projection.
     * <p>
     * The 2:1 pixel-art convention - pitch {@code atan(0.5) = 26.565°} foreshortens two axes equally.
     * Orthographic lens.
     */
    DIMETRIC(new EulerRotation(26.565f, 225f, 0f), Lens.orthographic(0.5f), Assembly.DISPLAY_POSE),

    /**
     * Trimetric axonometric projection.
     * <p>
     * All three axes foreshortened differently (ISO 5456-3 asymmetric example). Pose
     * {@code (20, 250, 0)}, orthographic lens.
     */
    TRIMETRIC(new EulerRotation(20f, 250f, 0f), Lens.orthographic(0.5f), Assembly.DISPLAY_POSE),

    /**
     * Cavalier oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° to full depth, with no
     * foreshortening. Oblique lens {@code L = 1.0}.
     */
    CAVALIER(new EulerRotation(0f, 180f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f), Assembly.DISPLAY_POSE),

    /**
     * Cabinet oblique projection.
     * <p>
     * The front face is true-shape and the receding axis is drawn at 45° with depth halved for a
     * natural look - the de-facto cabinet standard. Oblique lens {@code L = 0.5}.
     */
    CABINET(new EulerRotation(0f, 180f, 0f), Lens.oblique(0.5f, (float) Math.toRadians(-45), 0.5f), Assembly.DISPLAY_POSE),

    /**
     * Military (planometric) oblique projection.
     * <p>
     * The top plan is shown true-shape rotated 45° with verticals drawn to true length (ISO 5456-3).
     * Pose {@code (90, 225, 0)} plan, oblique lens {@code L = 1.0}. The least-standard mapping;
     * verify visually.
     */
    MILITARY(new EulerRotation(90f, 225f, 0f), Lens.oblique(1.0f, (float) Math.toRadians(-45), 0.5f), Assembly.DISPLAY_POSE),

    /**
     * Shipped block, fluid, and portal baseline.
     * <p>
     * Vanilla's {@code [30, 225, 0]} {@code display.gui} pose baked into the root
     * {@code block/block.json} model - technically a dimetric, not true isometric - at scale
     * {@code 0.625}. The default three-quarter block-icon view (block atlases, skulls, busts,
     * full-body skin renders) used whenever a block model does not override its own GUI pose;
     * reproduces the block / fluid / portal renders byte-for-byte.
     */
    VANILLA_BLOCK(new EulerRotation(30f, 225f, 0f), Lens.ISOMETRIC_BLOCK, Assembly.DISPLAY_POSE),

    /**
     * Shipped player baseline.
     * <p>
     * The {@link #VANILLA_BLOCK} pose with the yaw flipped 180&deg; ({@code [30, 45, 0]}) so a
     * humanoid model's front (its {@code +Z} {@code SOUTH} face) turns toward the camera - the
     * block-icon pose presents the model's {@code -Z} side, which on a humanoid is its back. A det=+1
     * GUI pose at the conservative scale, carrying none of {@link #VANILLA_ENTITY}'s LER chirality.
     * Reproduces the player renders byte-for-byte; the default for the player renderer.
     */
    VANILLA_PLAYER(new EulerRotation(30f, 45f, 0f), Lens.NONE, Assembly.DISPLAY_POSE),

    /**
     * Shipped 3D held-item baseline.
     * <p>
     * The moderate {@code GUI_ITEM} perspective; the item pose lives in the model's own
     * {@code display} matrix. Reproduces the held-item renders byte-for-byte; the default for the item
     * renderer.
     */
    VANILLA_GUI_ITEM(EulerRotation.NONE, Lens.GUI_ITEM, Assembly.DISPLAY_POSE),

    /**
     * Shipped entity baseline.
     * <p>
     * Vanilla's {@code EntityFrameRenderer.ISO_ROTATION = rotationXYZ(210°, 45°, 0°)}, itself derived
     * from the empirical 24-step yaw + 576-frame pitch/roll sweep that locked vanilla's entity-preview
     * pipeline camera. Built as the {@link #entityIsoChain} chirality chain - a det=-1 transform
     * carrying the LER chirality + reflection the vanilla-reference harness applies, so entity output
     * aligns with the harness ground-truth PNGs. NOT equivalent to {@link #VANILLA_BLOCK} /
     * {@link #VANILLA_PLAYER}, which are det=+1 GUI display poses: this pose differs from the block one
     * by a yaw mirror + transpose-like permutation, so entity rendering must use it rather than
     * borrowing the block-icon pose. The caller's rotation stays a separate model-spin rather than
     * composing into the camera (the {@link Assembly#ENTITY_ISO} strategy). The default for the
     * entity renderer.
     */
    VANILLA_ENTITY(new EulerRotation(210f, 45f, 0f), Lens.ISOMETRIC_BLOCK, Assembly.ENTITY_ISO);

    /**
     * Resolved camera (pose + lens) and lighting pose for one {@link Projection} at a chosen rotation -
     * the pose-locked pair a renderer feeds to its engine and inventory relight in lock-step. The lens
     * rides inside the {@link Camera}, so the engine holds the whole view + projection.
     *
     * @param camera the baked camera - pose and lens
     * @param lightingPose the Euler pose the inventory relight must mirror to track the camera
     */
    public record Resolved(@NotNull Camera camera, @NotNull EulerRotation lightingPose) {}

    /**
     * This projection's unrotated base pose - the {@code (pitch, yaw, roll)} the camera and lighting
     * sit at before the caller's rotation, in degrees. The canonical home for the vanilla iso angles
     * (block {@code [30, 225, 0]}, player {@code [30, 45, 0]}, entity {@code [210, 45, 0]}); callers
     * that need the raw rotation - the entity pipeline's bounds/anchor inverse - pull it from here.
     */
    private final @NotNull EulerRotation basePose;

    /**
     * This projection's flatten family - the 3D-to-2D {@link Lens} paired with the pose. Rotation-
     * independent; the {@link Assembly} strategy passes it straight through to
     * {@link Resolved#lens()}.
     */
    private final @NotNull Lens lens;

    /**
     * The strategy that assembles this projection's {@link Resolved} triple: {@link Assembly#DISPLAY_POSE}
     * for the det=+1 display-pose members, {@link Assembly#ENTITY_ISO} for the entity chirality chain.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull Assembly assembly;

    /**
     * Resolves this projection at its base pose - the unrotated camera / lens / lighting-pose
     * triple. Equivalent to {@link #resolve(EulerRotation)} with {@link EulerRotation#NONE}, so for a
     * {@code VANILLA_*} member it yields the exact shipped baseline.
     *
     * @return the resolved triple at the base pose
     */
    public @NotNull Resolved resolve() {
        return resolve(EulerRotation.NONE);
    }

    /**
     * Resolves this projection into the camera / lens / lighting-pose triple by delegating to this
     * constant's {@link Assembly} strategy. For a {@link Assembly#DISPLAY_POSE} member the rotation
     * adds to the base pitch / yaw / roll, so it poses the camera and the lighting pose together (the
     * lens is rotation-independent) through the parity-pinned {@link Camera#fromPose}
     * {@code rotationXYZ} path, which reproduces the legacy {@code VANILLA_*} cameras bit-for-bit;
     * {@link EulerRotation#NONE} yields the base pose unchanged, keeping the default render path
     * byte-identical.
     *
     * <p>{@link #VANILLA_ENTITY} ({@link Assembly#ENTITY_ISO}) is the exception: its det=-1
     * {@link #entityIsoChain} is fixed, so the {@code rotation} is ignored here and the entity renderer
     * applies it (plus its {@code setupRotations} addends) as a separate model-spin at rasterize time.
     *
     * @param rotation the rotation composed onto the base pose, in degrees (ignored for
     *     {@link #VANILLA_ENTITY})
     * @return the resolved triple
     */
    public @NotNull Resolved resolve(@NotNull EulerRotation rotation) {
        return this.assembly.resolve(this.basePose, this.lens, rotation);
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

    /**
     * Builds the vanilla entity iso chain - the fixed camera for the entity inventory-preview angle
     * (the harness's {@code ISO_ROTATION}). It is the full entity-preview transform expressed as the
     * column-vector matrix our column-form rasterizer consumes, after accounting for the kit's
     * pre-applied {@code FLIP_Y} on positions. Assembled for {@link #VANILLA_ENTITY} by the
     * {@link Assembly#ENTITY_ISO} strategy, and reached by the entity renderer's bounds / anchor
     * projection through {@link #resolve()} so both stay a single source of truth.
     * <p>
     * The harness applies (col form, applied to a Y-down model vertex right-to-left):
     * <pre>
     * scale(1,1,-1) outer chirality
     *   &times; R_X(210&deg;)   iso pitch
     *   &times; R_Y(45&deg;)    iso yaw
     *   &times; R_X(180&deg;)   LER chirality (scale(-1,-1,1)) + setupRotations (rotateY(180&deg;))
     * </pre>
     * Our pipeline applies kit {@code FLIP_Y = diag(1,-1,1)} to positions before the engine sees
     * them. The trailing {@code scale(1,-1,1)} absorbs the kit's flip into the camera matrix (and
     * simplifies {@code scale(1,-1,1) &times; R_X(180&deg;) = diag(1,1,-1)} so two outer
     * {@code scale(1,1,-1)} factors remain).
     * <p>
     * The two outer {@code scale(1,1,-1)} factors give a det=-1 transform total - matching the
     * harness's odd-reflection-count chirality. The simpler {@code Quaternionf.rotationXYZ(210&deg;,
     * 45&deg;, 0&deg;)} alone (det=+1) is INSUFFICIENT - it produces the iso rotation but omits the
     * LER chirality and reflection components, which Round 2 confirmed regresses every entity ~6x.
     * <p>
     * Column-vector application order is rightmost-first:
     * {@code scale(1,1,-1) * isoQuat * scale(1,1,-1) * scale(1,-1,1)}. Each fluent op matches JOML's
     * in-place translate/scale/rotate bit-for-bit with default {@code joml.useMathFma=false}.
     *
     * @param isoPose the entity iso pose driving the central rotation - {@link #VANILLA_ENTITY}'s base
     *     pose, vanilla's {@code [210, 45, 0]}
     * @return the entity iso chain matrix
     */
    private static @NotNull Matrix4f entityIsoChain(@NotNull EulerRotation isoPose) {
        return Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(isoPose.pitchRadians(), isoPose.yawRadians(), isoPose.rollRadians()))
            .scale(1f, 1f, -1f);
    }

    /**
     * How a {@link Projection} assembles its base pose and the caller's rotation into a
     * {@link Resolved} triple. Each constant overrides {@link #resolve} with its own assembly, so
     * {@link Projection#resolve(EulerRotation)} dispatches polymorphically instead of branching on a
     * tag.
     */
    private enum Assembly {

        /**
         * The default assembly - a rotation display pose. {@link Camera#fromPose} builds the
         * {@code rotationXYZ(pose)} matrix (det=+1) and the caller's rotation composes into it, so
         * camera and lighting move together. Backs every member except the entity preview: blocks,
         * players, held items, and the textbook axonometric / perspective / oblique projections.
         */
        DISPLAY_POSE {
            @Override
            @NotNull Resolved resolve(@NotNull EulerRotation basePose, @NotNull Lens lens, @NotNull EulerRotation rotation) {
                EulerRotation pose = compose(basePose, rotation);
                return new Resolved(Camera.fromPose(pose, lens), pose);
            }
        },

        /**
         * The vanilla entity-preview iso chain ({@link Projection#entityIsoChain}) - a det=-1,
         * odd-reflection chirality transform matching the harness. The chain is fixed; the caller
         * applies its rotation (plus the per-entity {@code setupRotations} addends) as a separate
         * model-spin at rasterize time, so the {@code rotation} is ignored here and the base pose
         * doubles as the lighting pose.
         */
        ENTITY_ISO {
            @Override
            @NotNull Resolved resolve(@NotNull EulerRotation basePose, @NotNull Lens lens, @NotNull EulerRotation rotation) {
                return new Resolved(new Camera(entityIsoChain(basePose), lens), basePose);
            }
        };

        /**
         * Assembles the resolved triple for a projection carrying the given base pose and lens,
         * under the caller's rotation.
         *
         * @param basePose the projection's unrotated base pose
         * @param lens the projection's lens
         * @param rotation the caller's rotation (composed into the pose by {@link #DISPLAY_POSE}, ignored
         *     by {@link #ENTITY_ISO})
         * @return the resolved triple
         */
        abstract @NotNull Resolved resolve(@NotNull EulerRotation basePose, @NotNull Lens lens, @NotNull EulerRotation rotation);

    }

}
