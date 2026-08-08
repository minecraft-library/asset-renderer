package lib.minecraft.renderer.asset.pack.item;

import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * A {@code special}-node {@code transformation} - vanilla's {@code com.mojang.math.Transformation}
 * decomposition ({@code items/player_head.json} et al.), a {@code T . Rleft . S . Rright} pose
 * carried in model space. Identity when the node declares no transformation.
 *
 * <p>It is parsed and held rather than applied, and that is deliberate: this renderer already poses
 * every subject the shipped non-identity transformations cover through a second, independent channel
 * - the {@code inventory} node on {@code block_models.json}, which reaches the render as a block
 * entity's presentation transform. The two carry the same pose in different units ({@code [0, 1]}
 * block units here against the {@code [0, 16]} authoring frame there), so applying this one as well
 * would pose every bed, banner, shulker box and head twice. Unifying the channels is a real change
 * with a real gate - the block sum - not a hookup.
 *
 * <p>The quaternions carry no Tait-Bryan factory ambiguity (raw {@code [x, y, z, w]} components),
 * but the row-form matrix this decomposes to must be transposed to this codebase's {@code v_row x M}
 * convention (CLAUDE.md JOML section).
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
     * Whether this transform is the identity. Not the common case in shipped data: of the 123
     * {@code special} nodes vanilla 26.1 ships, 73 declare a non-identity transformation and the
     * other 50 declare none at all.
     *
     * @return whether every component equals {@link #IDENTITY}
     */
    public boolean isIdentity() {
        return this.equals(IDENTITY);
    }

    /**
     * Composes the vanilla {@code T . Rleft . S . Rright} decomposition into this codebase's row-form
     * ({@code v_row x M}) matrix. The fluent method sequence mirrors the equivalent vanilla
     * {@code PoseStack} call order ({@code translate; mulPose(left); scale; mulPose(right)}) - the same
     * pattern the item {@code display} transform uses ({@code ItemRenderer.Held3D.resolveDisplayTransform}
     * builds {@code scale; rotate; translate}) - which is the transpose the {@code v_row x M} convention
     * requires (CLAUDE.md JOML section). Composes the decomposition for a caller that applies one;
     * nothing on the render path does today, for the reason on the class doc.
     *
     * @return the composed model-space pre-transform matrix
     */
    public @NotNull Matrix4f toMatrix() {
        return Matrix4f.IDENTITY
            .translate(this.translation[0], this.translation[1], this.translation[2])
            .rotate(quaternion(this.leftRotation))
            .scale(this.scale[0], this.scale[1], this.scale[2])
            .rotate(quaternion(this.rightRotation));
    }

    /** Builds a {@link Quaternionf} from a {@code [x, y, z, w]} component array. */
    private static @NotNull Quaternionf quaternion(float @NotNull [] q) {
        return new Quaternionf(q[0], q[1], q[2], q[3]);
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
