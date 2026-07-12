package lib.minecraft.renderer.pipeline.pack.item;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * A {@code special}-node {@code transformation} - vanilla's {@code com.mojang.math.Transformation}
 * decomposition ({@code items/player_head.json} et al., 05-models.md §5.3), applied
 * {@code T . Rleft . S . Rright} as a model-space pre-transform ahead of the render placement.
 * Identity when the node declares no transformation.
 *
 * <p>The quaternions carry no Tait-Bryan factory ambiguity (raw {@code [x, y, z, w]} components),
 * but the row-form matrix this decomposes to must be transposed to this codebase's {@code v_row x M}
 * convention (CLAUDE.md JOML section) - see the render-side conversion.
 *
 * @param leftRotation the left rotation quaternion, {@code [x, y, z, w]}
 * @param rightRotation the right rotation quaternion, {@code [x, y, z, w]}
 * @param scale the per-axis scale, {@code [x, y, z]}
 * @param translation the translation, {@code [x, y, z]}
 */
public record SpecialTransform(
    float @NotNull [] leftRotation,
    float @NotNull [] rightRotation,
    float @NotNull [] scale,
    float @NotNull [] translation
) {

    /** The identity transform - no rotation, unit scale, no translation. */
    public static final @NotNull SpecialTransform IDENTITY = new SpecialTransform(
        new float[]{ 0f, 0f, 0f, 1f },
        new float[]{ 0f, 0f, 0f, 1f },
        new float[]{ 1f, 1f, 1f },
        new float[]{ 0f, 0f, 0f }
    );

    /**
     * Whether this transform is the identity - the common case, letting the render path skip the
     * pre-transform entirely.
     *
     * @return whether every component equals {@link #IDENTITY}
     */
    public boolean isIdentity() {
        return this.equals(IDENTITY);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SpecialTransform that = (SpecialTransform) o;
        return Arrays.equals(leftRotation, that.leftRotation)
            && Arrays.equals(rightRotation, that.rightRotation)
            && Arrays.equals(scale, that.scale)
            && Arrays.equals(translation, that.translation);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(leftRotation);
        result = 31 * result + Arrays.hashCode(rightRotation);
        result = 31 * result + Arrays.hashCode(scale);
        result = 31 * result + Arrays.hashCode(translation);
        return result;
    }

}
