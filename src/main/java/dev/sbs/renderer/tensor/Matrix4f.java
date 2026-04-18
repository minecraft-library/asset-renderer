package dev.sbs.renderer.tensor;

import dev.sbs.renderer.util.TrigLUT;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * An immutable 4x4 float matrix using row-major layout.
 * <p>
 * Fields are named {@code m{row}{col}} (1-indexed) to match the System.Numerics.Matrix4x4 conventions.
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

    private final float m11, m12, m13, m14; // Row 1
    private final float m21, m22, m23, m24; // Row 2
    private final float m31, m32, m33, m34; // Row 3
    private final float m41, m42, m43, m44; // Row 4

    /**
     * Creates a rotation matrix from an axis and angle using Rodrigues' rotation formula.
     *
     * @param axis the rotation axis, which should be normalized
     * @param angle the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createFromAxisAngle(@NotNull Vector3f axis, float angle) {
        float x = axis.x();
        float y = axis.y();
        float z = axis.z();
        float cos = TrigLUT.cos(angle);
        float sin = TrigLUT.sin(angle);
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
        float cos = TrigLUT.cos(radians);
        float sin = TrigLUT.sin(radians);

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
        float cos = TrigLUT.cos(radians);
        float sin = TrigLUT.sin(radians);

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
        float cos = TrigLUT.cos(radians);
        float sin = TrigLUT.sin(radians);

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
     * @param uniform the scale factor applied to all axes
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
     * Returns the product of this matrix and the given matrix.
     *
     * @param b the right-hand matrix
     * @return a new matrix representing the product
     */
    public @NotNull Matrix4f multiply(@NotNull Matrix4f b) {
        return new Matrix4f(
            m11 * b.m11 + m12 * b.m21 + m13 * b.m31 + m14 * b.m41,
            m11 * b.m12 + m12 * b.m22 + m13 * b.m32 + m14 * b.m42,
            m11 * b.m13 + m12 * b.m23 + m13 * b.m33 + m14 * b.m43,
            m11 * b.m14 + m12 * b.m24 + m13 * b.m34 + m14 * b.m44,

            m21 * b.m11 + m22 * b.m21 + m23 * b.m31 + m24 * b.m41,
            m21 * b.m12 + m22 * b.m22 + m23 * b.m32 + m24 * b.m42,
            m21 * b.m13 + m22 * b.m23 + m23 * b.m33 + m24 * b.m43,
            m21 * b.m14 + m22 * b.m24 + m23 * b.m34 + m24 * b.m44,

            m31 * b.m11 + m32 * b.m21 + m33 * b.m31 + m34 * b.m41,
            m31 * b.m12 + m32 * b.m22 + m33 * b.m32 + m34 * b.m42,
            m31 * b.m13 + m32 * b.m23 + m33 * b.m33 + m34 * b.m43,
            m31 * b.m14 + m32 * b.m24 + m33 * b.m34 + m34 * b.m44,

            m41 * b.m11 + m42 * b.m21 + m43 * b.m31 + m44 * b.m41,
            m41 * b.m12 + m42 * b.m22 + m43 * b.m32 + m44 * b.m42,
            m41 * b.m13 + m42 * b.m23 + m43 * b.m33 + m44 * b.m43,
            m41 * b.m14 + m42 * b.m24 + m43 * b.m34 + m44 * b.m44
        );
    }

}
