package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.EulerRotation;
import org.jetbrains.annotations.NotNull;

/**
 * The orientation the inventory relight shades through - a rotation the shading frame is built from,
 * plus an optional screen-space reflection. Decoupled from the {@link Camera} pose so a subject can be
 * posed one way and lit another.
 *
 * <p>A frame carries a <b>tracking policy</b> that decides how a per-render view {@linkplain
 * #rotated(EulerRotation) rotation} affects the light:
 * <ul>
 *   <li><b>{@link Tracking}</b> - the light follows the view: {@link #rotated} composes the rotation
 *       into the angle, so bright faces move with the model as the caller rotates the projection. The
 *       default {@link Projection#resolve} produces.</li>
 *   <li><b>{@link Fixed}</b> - the light stays put: {@link #rotated} ignores the rotation, so the
 *       subject is always lit from the same angle regardless of the view. Used for a borrowed / world
 *       angle like the entity kit's {@code [210, 45, 0]} plane-cube frame.</li>
 * </ul>
 *
 * <p>The frame is pure data; {@code Shading.relightForItems3d} / {@code Lighting.resolveEntity}
 * interpret it, reading {@link #rotation()} and {@link #mirror()} and dotting the transformed model
 * normal against the fixed lights. A {@link Mirror#NONE} frame reproduces the legacy relight bit-for-bit.
 */
public sealed interface LightingFrame permits LightingFrame.Tracking, LightingFrame.Fixed {

    /**
     * A screen-space reflection of the shading frame, applied after the rotation so it flips the light
     * relative to the camera rather than the model. {@link #HORIZONTAL} swaps the left and right lit
     * sides while leaving top / bottom (and depth) shading unchanged.
     */
    enum Mirror {

        /** No reflection - the frame is the plain rotation. */
        NONE,

        /** Screen-horizontal reflection - left and right lit sides swap; top / bottom are unchanged. */
        HORIZONTAL
    }

    /**
     * The Euler orientation the shading frame is built from, in degrees.
     *
     * @return the frame rotation
     */
    @NotNull EulerRotation rotation();

    /**
     * The screen-space reflection applied after the rotation.
     *
     * @return the frame mirror
     */
    @NotNull Mirror mirror();

    /**
     * Applies a per-render view rotation, honouring the frame's tracking policy: a {@link Tracking}
     * frame composes {@code delta} into its rotation (the light follows the view), while a {@link Fixed}
     * frame returns itself unchanged (the light stays put). A zero {@code delta} is a no-op either way.
     *
     * @param delta the view rotation to compose, in degrees
     * @return the rotated frame (or this frame, for a fixed policy or a zero delta)
     */
    @NotNull LightingFrame rotated(@NotNull EulerRotation delta);

    /**
     * Returns a copy of this frame reflected {@linkplain Mirror#HORIZONTAL horizontally} in screen space -
     * the left and right lit sides swap, top / bottom stay - preserving the tracking policy. Composing it
     * against a model that is itself mirrored keeps the light screen-stable (it cancels the geometry
     * mirror) rather than tracking it.
     *
     * @return this frame with a horizontal screen reflection
     */
    @NotNull LightingFrame mirroredHorizontally();

    /**
     * A frame whose light follows the view - a per-render rotation composes into its angle. The default
     * for pose-derived frames ({@link Projection#resolve}, a block's {@code display.gui} pose).
     *
     * @param rotation the base orientation the light tracks from, in degrees
     * @return an unmirrored tracking frame
     */
    static @NotNull Tracking tracking(@NotNull EulerRotation rotation) {
        return new Tracking(rotation, Mirror.NONE);
    }

    /**
     * A frame whose light stays put - a per-render rotation does not move it. Use it for a borrowed or
     * world-fixed angle (e.g. the entity kit's {@code [210, 45, 0]} plane-cube frame).
     *
     * @param rotation the fixed light orientation, in degrees
     * @return an unmirrored fixed frame
     */
    static @NotNull Fixed fixed(@NotNull EulerRotation rotation) {
        return new Fixed(rotation, Mirror.NONE);
    }

    /**
     * A {@link LightingFrame} whose light follows the view - {@link #rotated} composes the rotation in.
     *
     * @param rotation the orientation the shading frame is built from, in degrees
     * @param mirror the screen-space reflection applied after the rotation
     */
    record Tracking(@NotNull EulerRotation rotation, @NotNull Mirror mirror) implements LightingFrame {

        @Override
        public @NotNull LightingFrame rotated(@NotNull EulerRotation delta) {
            if (delta.pitch() == 0f && delta.yaw() == 0f && delta.roll() == 0f) return this;
            return new Tracking(new EulerRotation(
                this.rotation.pitch() + delta.pitch(),
                this.rotation.yaw() + delta.yaw(),
                this.rotation.roll() + delta.roll()), this.mirror);
        }

        @Override
        public @NotNull LightingFrame mirroredHorizontally() {
            return new Tracking(this.rotation, Mirror.HORIZONTAL);
        }
    }

    /**
     * A {@link LightingFrame} whose light stays put - {@link #rotated} ignores the rotation.
     *
     * @param rotation the fixed light orientation, in degrees
     * @param mirror the screen-space reflection applied after the rotation
     */
    record Fixed(@NotNull EulerRotation rotation, @NotNull Mirror mirror) implements LightingFrame {

        @Override
        public @NotNull LightingFrame rotated(@NotNull EulerRotation delta) {
            return this;
        }

        @Override
        public @NotNull LightingFrame mirroredHorizontally() {
            return new Fixed(this.rotation, Mirror.HORIZONTAL);
        }
    }

}
