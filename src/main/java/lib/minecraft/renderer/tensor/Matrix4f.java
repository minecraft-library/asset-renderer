package lib.minecraft.renderer.tensor;

import org.jetbrains.annotations.NotNull;

/**
 * An immutable 4x4 float matrix in column-major storage with column-vector application semantics
 * matching JOML / vanilla Minecraft's {@code org.joml.Matrix4f}.
 * <p>
 * Backed by a single {@code float[16]} laid out column by column - element
 * {@code m[(col - 1) * 4 + (row - 1)]} (1-indexed columns and rows) is the matrix entry at
 * {@code (column, row)}. The constructor takes its sixteen arguments in the same column-major
 * order, mirroring vanilla's
 * {@code new org.joml.Matrix4f(m00, m01, m02, m03, m10, m11, ...)} signature. The underlying
 * array can be passed straight to {@code glUniformMatrix4fv} or {@code FloatBuffer.put}.
 * <p>
 * Access is via {@link #get(int, int)} (column, row - JOML's argument order).
 * {@link #column(int)} returns the four floats of one column - useful for SIMD column loads.
 * <p>
 * A vector is transformed as {@code M * v_col}; in a chain
 * {@code A.multiply(B).multiply(C)}, {@code C} is innermost and applies to the vector first,
 * then {@code B}, then {@code A}. Translation lives in column 4 (entries
 * {@code get(4, 1)}, {@code get(4, 2)}, {@code get(4, 3)}). Built basis matrices
 * ({@code createRotationX/Y/Z}, {@code createTranslation}, {@code createFromAxisAngle}) are
 * bit-identical to JOML's corresponding {@code org.joml.Matrix4f} entries so chains built here
 * produce the same float results as vanilla's {@code PoseStack} chains.
 * <p>
 * Stays a class (rather than being converted to the mutable-scratch pattern used by
 * {@link Vector3f} / {@link Vector2f}) because matrices are built once per render - there is no
 * per-vertex or per-pixel allocation pressure to optimise. {@link #multiply} silently dispatches
 * to a JDK Vector API implementation when the incubator module is loaded; otherwise it runs a
 * bit-identical scalar fallback. Callers never see the difference.
 */
public final class Matrix4f {

    /** The 4x4 identity matrix. */
    public static final @NotNull Matrix4f IDENTITY = new Matrix4f(
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1
    );

    /**
     * Column-major storage of all sixteen entries. Index layout (each table column is one
     * matrix column):
     * <pre>
     *   m[ 0]=m11  m[ 4]=m21  m[ 8]=m31  m[12]=m41
     *   m[ 1]=m12  m[ 5]=m22  m[ 9]=m32  m[13]=m42
     *   m[ 2]=m13  m[ 6]=m23  m[10]=m33  m[14]=m43
     *   m[ 3]=m14  m[ 7]=m24  m[11]=m34  m[15]=m44
     * </pre>
     * Package-private so {@link SimdOps} can do direct SIMD column loads via
     * {@code FloatVector.fromArray(SPECIES, m, colStart)} without going through accessor calls.
     * Treat as read-only - the array is never mutated after construction.
     */
    final float[] m;

    /**
     * Constructs a matrix from its sixteen entries in column-major order: the first four
     * arguments are column 1 (rows 1 to 4 of that column), the next four are column 2, and so
     * on. Matches JOML's {@code org.joml.Matrix4f(m00, m01, m02, m03, m10, ...)} argument
     * layout.
     *
     * @param m11 column 1, row 1
     * @param m12 column 1, row 2
     * @param m13 column 1, row 3
     * @param m14 column 1, row 4
     * @param m21 column 2, row 1
     * @param m22 column 2, row 2
     * @param m23 column 2, row 3
     * @param m24 column 2, row 4
     * @param m31 column 3, row 1
     * @param m32 column 3, row 2
     * @param m33 column 3, row 3
     * @param m34 column 3, row 4
     * @param m41 column 4, row 1
     * @param m42 column 4, row 2
     * @param m43 column 4, row 3
     * @param m44 column 4, row 4
     */
    public Matrix4f(
        float m11, float m12, float m13, float m14,
        float m21, float m22, float m23, float m24,
        float m31, float m32, float m33, float m34,
        float m41, float m42, float m43, float m44
    ) {
        this.m = new float[]{
            m11, m12, m13, m14,
            m21, m22, m23, m24,
            m31, m32, m33, m34,
            m41, m42, m43, m44
        };
    }

    /**
     * Package-private constructor that wraps an already-built column-major float array.
     * Caller transfers ownership - the array is stored by reference and must not be
     * mutated afterwards. Used by {@link #multiply} / {@link SimdOps} to avoid re-copying
     * the sixteen elements through the public constructor.
     */
    Matrix4f(float @NotNull [] columnMajor) {
        this.m = columnMajor;
    }

    /**
     * Returns the entry at the given 1-indexed column and row. Argument order matches JOML's
     * {@code Matrix4fc.get(int column, int row)}.
     *
     * @param col the column (1 to 4)
     * @param row the row (1 to 4)
     * @return the matrix entry at {@code (column, row)}
     * @throws ArrayIndexOutOfBoundsException if {@code col} or {@code row} is outside {@code [1, 4]}
     */
    public float get(int col, int row) {
        return this.m[(col - 1) * 4 + (row - 1)];
    }

    /**
     * Copies the four entries of one column into a fresh {@code float[4]}: rows 1 to 4 of
     * the requested column. Useful for SIMD loads via {@code FloatVector.fromArray}.
     *
     * @param col the column (1 to 4)
     * @return a new four-element array containing rows 1 to 4 of {@code col}
     */
    public float @NotNull [] column(int col) {
        int offset = (col - 1) * 4;
        return new float[]{this.m[offset], this.m[offset + 1], this.m[offset + 2], this.m[offset + 3]};
    }

    /**
     * Creates a rotation matrix from an axis and angle using Rodrigues' rotation formula. The
     * result is the column-vector convention rotation matrix: positive angles rotate following
     * the right-hand rule around the given axis when applied as {@code M * v_col}.
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

        // Each row below is one matrix column (4 entries per column, 4 columns).
        return new Matrix4f(
            xx * oneMinusCos + cos,     xy * oneMinusCos + z * sin, xz * oneMinusCos - y * sin, 0,
            xy * oneMinusCos - z * sin, yy * oneMinusCos + cos,     yz * oneMinusCos + x * sin, 0,
            xz * oneMinusCos + y * sin, yz * oneMinusCos - x * sin, zz * oneMinusCos + cos,     0,
            0,                          0,                          0,                          1
        );
    }

    /**
     * Creates a rotation matrix around the X axis. Positive angles rotate {@code +Y} toward
     * {@code +Z} (right-hand rule) when applied as {@code M * v_col}.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationX(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // Each row below is one matrix column (4 entries per column, 4 columns).
        return new Matrix4f(
            1, 0,    0,   0,
            0, cos,  sin, 0,
            0, -sin, cos, 0,
            0, 0,    0,   1
        );
    }

    /**
     * Creates a rotation matrix around the Y axis. Positive angles rotate {@code +Z} toward
     * {@code +X} (right-hand rule) when applied as {@code M * v_col}.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationY(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // Each row below is one matrix column (4 entries per column, 4 columns).
        return new Matrix4f(
            cos, 0, -sin, 0,
            0,   1, 0,    0,
            sin, 0, cos,  0,
            0,   0, 0,    1
        );
    }

    /**
     * Creates a rotation matrix around the Z axis. Positive angles rotate {@code +X} toward
     * {@code +Y} (right-hand rule) when applied as {@code M * v_col}.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationZ(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // Each row below is one matrix column (4 entries per column, 4 columns).
        return new Matrix4f(
            cos,  sin, 0, 0,
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
     * Creates a translation matrix with the offset stored in column 4 (entries
     * {@code get(4, 1)}, {@code get(4, 2)}, {@code get(4, 3)}) per the column-vector convention.
     *
     * @param x the translation along the X axis
     * @param y the translation along the Y axis
     * @param z the translation along the Z axis
     * @return a new translation matrix
     */
    public static @NotNull Matrix4f createTranslation(float x, float y, float z) {
        // Each row below is one matrix column (4 entries per column, 4 columns).
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
     * Returns the standard matrix product {@code this * b} as a new matrix. In a chain
     * {@code a.multiply(b).multiply(c)} the rightmost factor {@code c} is innermost - it applies
     * to a column vector first under {@code (a*b*c) * v}, matching JOML / vanilla
     * {@code PoseStack} convention. Auto-dispatches to a SIMD implementation when the JDK
     * Vector API module is available; otherwise computes the 16 output elements scalar.
     *
     * @param b the right-hand matrix
     * @return a new matrix representing the product
     */
    public @NotNull Matrix4f multiply(@NotNull Matrix4f b) {
        if (SimdSupport.ENABLED) return SimdOps.multiply(this, b);
        // Scalar fallback: each output column j is `this * column_j(b)`, so the inner loop reads
        // one column of b at a time and accumulates it against this's four columns. Results pack
        // back into a fresh column-major float[16].
        float[] a = this.m;
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            float b1 = b.m[col * 4];
            float b2 = b.m[col * 4 + 1];
            float b3 = b.m[col * 4 + 2];
            float b4 = b.m[col * 4 + 3];
            r[col * 4    ] = a[0] * b1 + a[4] * b2 + a[ 8] * b3 + a[12] * b4;
            r[col * 4 + 1] = a[1] * b1 + a[5] * b2 + a[ 9] * b3 + a[13] * b4;
            r[col * 4 + 2] = a[2] * b1 + a[6] * b2 + a[10] * b3 + a[14] * b4;
            r[col * 4 + 3] = a[3] * b1 + a[7] * b2 + a[11] * b3 + a[15] * b4;
        }
        return new Matrix4f(r);
    }

}
