package lib.minecraft.renderer.asset.model;

import dev.simplified.annotations.AllArgsConstructor;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
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

}
