package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import org.jetbrains.annotations.NotNull;

/**
 * A view-facing reflection applied to a {@link Projection} at resolve time - two independent mirror
 * toggles that turn the subject to present a different side without changing the projection itself.
 * <p>
 * Unlike a caller {@link EulerRotation} (which <b>adds</b> to the base pose), a facing is a
 * <b>reflection</b>, so it lives here as its own concern rather than being folded into the rotation.
 * {@link #mirrored} reflects the pose yaw ({@code 360 - yaw}); {@link #flipped} negates the pose pitch;
 * for an {@linkplain Lens.Kind#OBLIQUE oblique} lens each also flips the corresponding component of the
 * depth shear, so the mirror holds even for a front-facing oblique whose yaw reflection is a no-op. The
 * {@link #DEFAULT} identity is a bit-for-bit no-op ({@link #apply(EulerRotation)} / {@link #apply(Lens)}
 * return their argument unchanged), keeping the default render path byte-identical.
 * <p>
 * The {@code with*} helpers derive a new facing from an existing one; they never mutate.
 *
 * @param mirrored whether the view is mirrored horizontally - the pose yaw is reflected about the
 *     camera-facing direction ({@code 360 - yaw}) and an oblique lens's depth-shear cosine is flipped,
 *     presenting the subject's opposite left / right side
 * @param flipped whether the view is flipped vertically - the pose pitch is negated ({@code -pitch}) and
 *     an oblique lens's depth-shear sine is flipped, so the camera views from the opposite elevation
 *     (revealing the subject's underside from a look-down default)
 */
public record Facing(boolean mirrored, boolean flipped) {

    /**
     * The identity facing - neither mirrored nor flipped. A bit-for-bit no-op at resolve time.
     */
    public static final @NotNull Facing DEFAULT = new Facing(false, false);

    /**
     * Mirrored horizontally only - the yaw reflection, presenting the subject's opposite side.
     */
    public static final @NotNull Facing MIRRORED = new Facing(true, false);

    /**
     * Flipped vertically only - the pitch negation, viewing from the opposite elevation.
     */
    public static final @NotNull Facing FLIPPED = new Facing(false, true);

    /**
     * Both mirrored horizontally and flipped vertically.
     */
    public static final @NotNull Facing MIRRORED_FLIPPED = new Facing(true, true);

    /**
     * Returns a copy of this facing with {@link #mirrored()} set to the given value.
     *
     * @param value the mirrored flag for the copy
     * @return a copy with the given mirrored flag
     */
    public @NotNull Facing withMirrored(boolean value) {
        return new Facing(value, this.flipped);
    }

    /**
     * Returns a copy of this facing with {@link #flipped()} set to the given value.
     *
     * @param value the flipped flag for the copy
     * @return a copy with the given flipped flag
     */
    public @NotNull Facing withFlipped(boolean value) {
        return new Facing(this.mirrored, value);
    }

    /**
     * Reflects a pose by this facing: {@link #mirrored} reflects the yaw ({@code 360 - yaw}) and
     * {@link #flipped} negates the pitch; roll is untouched. {@link #DEFAULT} returns the argument
     * unchanged with no arithmetic, so the default resolve path stays byte-identical.
     *
     * @param rotation the composed pose to reflect
     * @return the reflected pose, or {@code rotation} itself when this facing is the identity
     */
    public @NotNull EulerRotation apply(@NotNull EulerRotation rotation) {
        if (!this.mirrored && !this.flipped) return rotation;
        return new EulerRotation(
            this.flipped ? -rotation.pitch() : rotation.pitch(),
            this.mirrored ? 360f - rotation.yaw() : rotation.yaw(),
            rotation.roll()
        );
    }

    /**
     * Flips an {@linkplain Lens.Kind#OBLIQUE oblique} lens's depth-shear so the mirror also applies to
     * the shear direction: {@link #mirrored} flips the shear's cosine (x) and {@link #flipped} flips its
     * sine (y). Non-oblique lenses and the {@link #DEFAULT} identity return the argument unchanged - a
     * horizontal mirror of a front-facing oblique (whose yaw reflection is a no-op) only takes effect
     * through this shear flip.
     *
     * @param lens the lens to reflect
     * @return the shear-flipped lens, or {@code lens} itself when this facing is the identity or the lens
     *     is not oblique
     */
    public @NotNull Lens apply(@NotNull Lens lens) {
        if ((!this.mirrored && !this.flipped) || lens.kind() != Lens.Kind.OBLIQUE) return lens;
        double cos = Math.cos(lens.obliqueAngleRadians()) * (this.mirrored ? -1d : 1d);
        double sin = Math.sin(lens.obliqueAngleRadians()) * (this.flipped ? -1d : 1d);
        return Lens.oblique(lens.obliqueDepthFactor(), (float) Math.atan2(sin, cos), lens.projectionScale());
    }

}
