package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.EulerRotation;
import org.jetbrains.annotations.NotNull;

/**
 * The orientation the inventory relight shades through - a rotation the shading frame is built from,
 * plus an optional screen-space reflection. Decoupled from the {@link Camera} pose so a subject can be
 * posed one way and lit another: the light can track the pose (the default), stay fixed while the pose
 * varies, or mirror left-to-right independently of the geometry.
 *
 * <p>The frame is pure data; {@code Shading.relightForItems3d} interprets it, building
 * {@code [mirror ·] S(1,-1,1) · rotationXYZ(rotation)} and dotting the transformed model normal against
 * the fixed {@code Lighting.ITEMS_3D} light. A {@link Mirror#NONE} frame reproduces the legacy
 * pose-tracking relight bit-for-bit.
 *
 * @param rotation the Euler orientation the shading frame is built from, in degrees
 * @param mirror the screen-space reflection applied after the rotation
 */
public record LightingFrame(@NotNull EulerRotation rotation, @NotNull Mirror mirror) {

    /**
     * A screen-space reflection of the shading frame, applied after the rotation so it flips the light
     * relative to the camera rather than the model. {@link #HORIZONTAL} swaps the left and right lit
     * sides while leaving top / bottom (and depth) shading unchanged.
     */
    public enum Mirror {

        /** No reflection - the frame is the plain rotation. */
        NONE,

        /** Screen-horizontal reflection - left and right lit sides swap; top / bottom are unchanged. */
        HORIZONTAL

    }

    /**
     * A frame that tracks a camera pose - the light mirrors the pose orientation, so bright faces follow
     * the model as it rotates. The default every {@link Projection#resolve} produces, reproducing the
     * legacy inventory relight.
     *
     * @param rotation the camera pose rotation the light tracks, in degrees
     * @return a pose-tracking frame with no reflection
     */
    public static @NotNull LightingFrame trackingPose(@NotNull EulerRotation rotation) {
        return new LightingFrame(rotation, Mirror.NONE);
    }

    /**
     * A frame fixed at a specific orientation, independent of the camera pose - the light stays put while
     * the pose varies. Use it to light a subject from a borrowed or world-fixed direction (e.g. the
     * entity kit's {@code [210, 45, 0]} plane-cube angle).
     *
     * @param rotation the fixed light orientation, in degrees
     * @return a fixed frame with no reflection
     */
    public static @NotNull LightingFrame fixed(@NotNull EulerRotation rotation) {
        return new LightingFrame(rotation, Mirror.NONE);
    }

    /**
     * Returns a copy of this frame reflected {@linkplain Mirror#HORIZONTAL horizontally} in screen space -
     * the left and right lit sides swap, top / bottom stay. Composing it against a model that is itself
     * mirrored keeps the light screen-stable (it cancels the geometry mirror) rather than tracking it.
     *
     * @return this frame with a horizontal screen reflection
     */
    public @NotNull LightingFrame mirroredHorizontally() {
        return new LightingFrame(this.rotation, Mirror.HORIZONTAL);
    }

}
