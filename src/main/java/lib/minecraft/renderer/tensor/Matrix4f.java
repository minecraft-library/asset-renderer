package lib.minecraft.renderer.tensor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * An immutable 4x4 float matrix in row-major layout.
 * <p>
 * Fields are named {@code m{row}{col}} (1-indexed) to match the System.Numerics.Matrix4x4
 * convention, which in turn matches the field ordering of typical Minecraft and OpenGL shader
 * math. Constructed via the {@code create*} factories or the all-args constructor; once built,
 * a {@link Matrix4f} is never mutated.
 * <p>
 * Stays a class (rather than being converted to the mutable-scratch pattern used by
 * {@link Vector3f} / {@link Vector2f}) because matrices are built once per render - there is no
 * per-vertex or per-pixel allocation pressure to optimise.
 *
 * @see Matrix4fOps
 */
@Getter
@RequiredArgsConstructor
public final class Matrix4f {

    /** The 4x4 identity matrix. */
    public static final @NotNull Matrix4f IDENTITY = new Matrix4f(
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1
    );

    /** Row 1, column 1 component. */
    private final float m11;
    /** Row 1, column 2 component. */
    private final float m12;
    /** Row 1, column 3 component. */
    private final float m13;
    /** Row 1, column 4 component. */
    private final float m14;

    /** Row 2, column 1 component. */
    private final float m21;
    /** Row 2, column 2 component. */
    private final float m22;
    /** Row 2, column 3 component. */
    private final float m23;
    /** Row 2, column 4 component. */
    private final float m24;

    /** Row 3, column 1 component. */
    private final float m31;
    /** Row 3, column 2 component. */
    private final float m32;
    /** Row 3, column 3 component. */
    private final float m33;
    /** Row 3, column 4 component. */
    private final float m34;

    /** Row 4, column 1 component. */
    private final float m41;
    /** Row 4, column 2 component. */
    private final float m42;
    /** Row 4, column 3 component. */
    private final float m43;
    /** Row 4, column 4 component. */
    private final float m44;

    /**
     * Creates a rotation matrix from an axis and angle using Rodrigues' rotation formula.
     *
     * @param axis the rotation axis - must be normalized
     * @param angle the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createFromAxisAngle(@NotNull Vector3f axis, float angle) {
        float x = axis.x();
        float y = axis.y();
        float z = axis.z();
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float oneMinusCos = 1f - cos;

        float xx = x * x;
        float yy = y * y;
        float zz = z * z;
        float xy = x * y;
        float xz = x * z;
        float yz = y * z;

        return new Matrix4f(
            xx * oneMinusCos + cos,     xy * oneMinusCos + z * sin, xz * oneMinusCos - y * sin,  0,
            xy * oneMinusCos - z * sin, yy * oneMinusCos + cos,     yz * oneMinusCos + x * sin, 0,
            xz * oneMinusCos + y * sin, yz * oneMinusCos - x * sin, zz * oneMinusCos + cos,     0,
            0,                          0,                          0,                          1
        );
    }

    /**
     * Creates a rotation matrix around the X axis.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationX(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        return new Matrix4f(
            1, 0,     0,   0,
            0, cos,  sin, 0,
            0, -sin, cos, 0,
            0, 0,    0,   1
        );
    }

    /**
     * Creates a rotation matrix around the Y axis.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationY(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        return new Matrix4f(
            cos, 0,  -sin, 0,
            0,   1, 0,    0,
            sin, 0, cos,  0,
            0,   0, 0,    1
        );
    }

    /**
     * Creates a rotation matrix around the Z axis.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationZ(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        return new Matrix4f(
            cos,  sin,  0, 0,
            -sin, cos, 0, 0,
            0,    0,   1, 0,
            0,    0,   0, 1
        );
    }

    /**
     * Creates a uniform scale matrix.
     *
     * @param uniform the scale factor applied to all three axes
     * @return a new scale matrix
     */
    public static @NotNull Matrix4f createScale(float uniform) {
        return createScale(uniform, uniform, uniform);
    }

    /**
     * Creates a non-uniform scale matrix.
     *
     * @param x the scale factor along the X axis
     * @param y the scale factor along the Y axis
     * @param z the scale factor along the Z axis
     * @return a new scale matrix
     */
    public static @NotNull Matrix4f createScale(float x, float y, float z) {
        return new Matrix4f(
            x, 0, 0, 0,
            0, y, 0, 0,
            0, 0, z, 0,
            0, 0, 0, 1
        );
    }

    /**
     * Creates a non-uniform scale matrix from a vector.
     *
     * @param v the scale factors for each axis
     * @return a new scale matrix
     */
    public static @NotNull Matrix4f createScale(@NotNull Vector3f v) {
        return createScale(v.x(), v.y(), v.z());
    }

    /**
     * Creates a translation matrix.
     *
     * @param x the translation along the X axis
     * @param y the translation along the Y axis
     * @param z the translation along the Z axis
     * @return a new translation matrix
     */
    public static @NotNull Matrix4f createTranslation(float x, float y, float z) {
        return new Matrix4f(
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            x, y, z, 1
        );
    }

    /**
     * Creates a translation matrix from a vector.
     *
     * @param v the translation vector
     * @return a new translation matrix
     */
    public static @NotNull Matrix4f createTranslation(@NotNull Vector3f v) {
        return createTranslation(v.x(), v.y(), v.z());
    }

    /**
     * Returns the product {@code this * b} as a new matrix. See {@link Matrix4fOps#multiply} for
     * a SIMD-accelerated variant used when the caller is already multiplying many matrices per
     * render.
     *
     * @param b the right-hand matrix
     * @return a new matrix representing the product
     */
    public @NotNull Matrix4f multiply(@NotNull Matrix4f b) {
        return new Matrix4f(
            this.m11 * b.m11 + this.m12 * b.m21 + this.m13 * b.m31 + this.m14 * b.m41,
            this.m11 * b.m12 + this.m12 * b.m22 + this.m13 * b.m32 + this.m14 * b.m42,
            this.m11 * b.m13 + this.m12 * b.m23 + this.m13 * b.m33 + this.m14 * b.m43,
            this.m11 * b.m14 + this.m12 * b.m24 + this.m13 * b.m34 + this.m14 * b.m44,

            this.m21 * b.m11 + this.m22 * b.m21 + this.m23 * b.m31 + this.m24 * b.m41,
            this.m21 * b.m12 + this.m22 * b.m22 + this.m23 * b.m32 + this.m24 * b.m42,
            this.m21 * b.m13 + this.m22 * b.m23 + this.m23 * b.m33 + this.m24 * b.m43,
            this.m21 * b.m14 + this.m22 * b.m24 + this.m23 * b.m34 + this.m24 * b.m44,

            this.m31 * b.m11 + this.m32 * b.m21 + this.m33 * b.m31 + this.m34 * b.m41,
            this.m31 * b.m12 + this.m32 * b.m22 + this.m33 * b.m32 + this.m34 * b.m42,
            this.m31 * b.m13 + this.m32 * b.m23 + this.m33 * b.m33 + this.m34 * b.m43,
            this.m31 * b.m14 + this.m32 * b.m24 + this.m33 * b.m34 + this.m34 * b.m44,

            this.m41 * b.m11 + this.m42 * b.m21 + this.m43 * b.m31 + this.m44 * b.m41,
            this.m41 * b.m12 + this.m42 * b.m22 + this.m43 * b.m32 + this.m44 * b.m42,
            this.m41 * b.m13 + this.m42 * b.m23 + this.m43 * b.m33 + this.m44 * b.m43,
            this.m41 * b.m14 + this.m42 * b.m24 + this.m43 * b.m34 + this.m44 * b.m44
        );
    }

}
