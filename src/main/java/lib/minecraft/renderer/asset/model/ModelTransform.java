package lib.minecraft.renderer.asset.model;

import dev.simplified.annotations.AllArgsConstructor;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.ModelUnits;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import org.jetbrains.annotations.NotNull;

/**
 * A single display transform entry parsed from the {@code display} section of an item or block
 * model JSON.
 * <p>
 * The three properties compose as vanilla's item transform composes them: {@code T * R * S} over
 * column vectors, so a vertex is scaled first, then rotated, then translated, and the translation
 * lands neither scaled nor rotated. The rotation is {@code rotationXYZ} of the three angles,
 * {@code Rx * Ry * Rz}, so a vertex turns about Z, then Y, then X.
 * <p>
 * Vanilla stores each property as a three-element JSON array keyed {@code rotation},
 * {@code translation}, and {@code scale}. The array indices correspond to the X, Y, and Z
 * components in that order.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ModelTransform {

    /**
     * The identity transform - zero rotation, zero translation, unit scale.
     */
    public static final @NotNull ModelTransform IDENTITY = new ModelTransform(
        EulerRotation.NONE,
        new float[]{ 0f, 0f, 0f },
        new float[]{ 1f, 1f, 1f }
    );

    /**
     * The Euler-angle rotation in degrees, composed as {@code rotationXYZ} - {@code Rx * Ry * Rz}, so
     * a vertex turns about Z first - and applied to a vertex after {@link #scale} and before
     * {@link #translation}.
     */
    @Getter
    private @NotNull EulerRotation rotation = EulerRotation.NONE;

    /**
     * The translation offset as {@code [x, y, z]} in sixteenths of a block, applied to a vertex last,
     * after {@link #scale} and {@link #rotation}.
     */
    private float @NotNull [] translation = { 0f, 0f, 0f };

    /**
     * The per-axis scale factors as {@code [x, y, z]}, applied to a vertex first, before
     * {@link #rotation} and {@link #translation}.
     */
    private float @NotNull [] scale = { 1f, 1f, 1f };

    /**
     * The X component of the translation offset.
     */
    public float getTranslationX() { return this.translation[0]; }

    /**
     * The Y component of the translation offset.
     */
    public float getTranslationY() { return this.translation[1]; }

    /**
     * The Z component of the translation offset.
     */
    public float getTranslationZ() { return this.translation[2]; }

    /**
     * The X-axis scale factor.
     */
    public float getScaleX() { return this.scale[0]; }

    /**
     * The Y-axis scale factor.
     */
    public float getScaleY() { return this.scale[1]; }

    /**
     * The Z-axis scale factor.
     */
    public float getScaleZ() { return this.scale[2]; }

    /**
     * Composes this transform into a model-space {@link Matrix4f}, in the order vanilla's item
     * transform applies it.
     * <p>
     * Composed as {@code T * R * S} over column vectors, the product the PoseStack sequence
     * {@code poseStack.translate(); poseStack.mulPose(rXYZ); poseStack.scale();} builds: the
     * rightmost factor applies first, so a vertex is <b>scaled, then rotated, then
     * translated</b>, and the translation lands neither scaled nor rotated. Vanilla closes the
     * sequence with {@code translate(-0.5, -0.5, -0.5)}; the geometry's own centring stands in
     * for it, an element model subtracting half a block after the {@code /16} and the flat
     * slab sitting on the origin.
     *
     * @return the transform's model-space matrix
     */
    public @NotNull Matrix4f toMatrix() {
        EulerRotation angles = this.rotation;
        // The translation is authored in sixteenths of a block and the geometry is in blocks.
        // The fluent translate/rotate/scale path is bit-identical to vanilla's PoseStack, where
        // the createX().multiply(...) form drifts 1-4 ULPs per entry.
        return Matrix4f.IDENTITY
            .translate(
                this.getTranslationX() / ModelUnits.PIXELS_PER_BLOCK,
                this.getTranslationY() / ModelUnits.PIXELS_PER_BLOCK,
                this.getTranslationZ() / ModelUnits.PIXELS_PER_BLOCK
            )
            .rotate(Quaternionf.rotationXYZ(angles.pitchRadians(), angles.yawRadians(), angles.rollRadians()))
            .scale(this.getScaleX(), this.getScaleY(), this.getScaleZ());
    }

}
