package lib.minecraft.renderer.bake.mesh;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Quaternionf;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The camera a model's authored {@code display.gui} block bakes to - a decoded transform read as a
 * pose and a lens rather than as a camera primitive.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public final class DisplayCamera {

    /** Model units per block - the divisor turning a {@code display.gui} pixel translation into blocks. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    /**
     * Returns a camera that honours a model's authored {@code display.gui} transform in full - its
     * rotation, translation, and per-axis scale - for a block inventory icon. The isotropic scale
     * factor rides on the {@linkplain Lens#orthographic orthographic lens} (the same slot the vanilla
     * iso preset uses), while any residual anisotropy and the {@code /16} translation bake into the
     * pose.
     *
     * <p>A uniform, un-translated gui returns the exact {@link Camera#fromPose} expression, so a
     * standard block whose gui is {@code [30, 225, 0]} + scale {@code 0.625} resolves to the vanilla
     * iso camera bit-for-bit - no float-op reordering for the blocks whose pose does not change.
     *
     * @param gui the authored {@code display.gui} transform
     * @return a camera posing the icon through the full gui transform
     */
    public static @NotNull Camera of(@NotNull ModelTransform gui) {
        float sx = gui.getScaleX(), sy = gui.getScaleY(), sz = gui.getScaleZ();
        float tx = gui.getTranslationX(), ty = gui.getTranslationY(), tz = gui.getTranslationZ();
        EulerRotation rotation = gui.getRotation();

        // A uniform scale with no translation collapses to today's iso expression, so a standard
        // block resolves to the exact vanilla iso camera object with no float-op reordering.
        if (sy == sx && sz == sx && tx == 0f && ty == 0f && tz == 0f)
            return Camera.fromPose(rotation, Lens.orthographic(sx));

        // Vanilla applies the gui as (centre) -> scale -> rotate -> translate; the isotropic scale
        // rides on the lens (applied at projection) while the residual anisotropy sits innermost and
        // the world-frame translation outermost. Fluent T·R·S (translation leftmost) applies scale to
        // the vertex first, then rotation, then translation - matching the harness. The translation is
        // pre-divided by the isotropic scale so the lens multiply restores its authored magnitude
        // (vanilla applies the translation AFTER the scale, so it must not be lens-scaled).
        float t = MODEL_UNITS_PER_BLOCK * sx;
        Matrix4f pose = Matrix4f.IDENTITY
            .translate(tx / t, ty / t, tz / t)
            .rotate(Quaternionf.rotationXYZ(rotation.pitchRadians(), rotation.yawRadians(), rotation.rollRadians()))
            .scale(1f, sy / sx, sz / sx);
        return new Camera(pose, Lens.orthographic(sx));
    }

}
