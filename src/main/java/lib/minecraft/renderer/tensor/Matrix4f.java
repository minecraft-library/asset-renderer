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

    /**
     * The 4x4 identity matrix.
     */
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
        // Route through Quaternionf to match vanilla bit-for-bit. Vanilla's
        // {@code PoseStack.mulPose(Axis.XP.rotation(radians))} builds a quaternion via
        // {@code Quaternionf.rotateX(radians)} then converts to a matrix. Direct
        // {@code Math.cos/sin} of the radians (the previous path here) drifts by 1-4 ULPs
        // per matrix entry vs vanilla's quat-derived matrix - notably at 180-degree-class
        // angles where {@code (float) Math.sin((float) Math.PI) ~= -8.74e-8} (not 0) while
        // {@code Quaternionf.rotationXYZ(Math.PI, 0, 0)} yields a quat that converts to a
        // matrix with EXACT zeros.
        return Quaternionf.rotationXYZ(radians, 0f, 0f).toMatrix4f();
    }

    /**
     * Creates a rotation matrix around the Y axis. Positive angles rotate {@code +Z} toward
     * {@code +X} (right-hand rule) when applied as {@code M * v_col}.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationY(float radians) {
        return Quaternionf.rotationXYZ(0f, radians, 0f).toMatrix4f();
    }

    /**
     * Creates a rotation matrix around the Z axis. Positive angles rotate {@code +X} toward
     * {@code +Y} (right-hand rule) when applied as {@code M * v_col}.
     *
     * @param radians the rotation angle in radians
     * @return a new rotation matrix
     */
    public static @NotNull Matrix4f createRotationZ(float radians) {
        return Quaternionf.rotationXYZ(0f, 0f, radians).toMatrix4f();
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
        // Right-associated mul-add chain matching JOML's {@code Matrix4f.mul} with
        // {@code joml.useMathFma=false} (vanilla Minecraft's default). JOML's source uses
        // {@code Math.fma(a, b, fma(c, d, fma(e, f, g*h)))}; with FMA off each fma collapses
        // to {@code a*b + c}, producing the right-associated grouping
        // {@code a[0]*b1 + (a[4]*b2 + (a[8]*b3 + a[12]*b4))}.
        float[] a = this.m;
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            float b1 = b.m[col * 4];
            float b2 = b.m[col * 4 + 1];
            float b3 = b.m[col * 4 + 2];
            float b4 = b.m[col * 4 + 3];
            r[col * 4    ] = a[0] * b1 + (a[4] * b2 + (a[ 8] * b3 + a[12] * b4));
            r[col * 4 + 1] = a[1] * b1 + (a[5] * b2 + (a[ 9] * b3 + a[13] * b4));
            r[col * 4 + 2] = a[2] * b1 + (a[6] * b2 + (a[10] * b3 + a[14] * b4));
            r[col * 4 + 3] = a[3] * b1 + (a[7] * b2 + (a[11] * b3 + a[15] * b4));
        }
        return new Matrix4f(r);
    }

    // --- Fluent post-multiply ops mirroring JOML / vanilla PoseStack semantics ---
    //
    // Vanilla's PoseStack applies operations as in-place modifications of a single
    // org.joml.Matrix4f: pose.translate(x, y, z); pose.scale(...); pose.rotate(quat).
    // JOML's implementations are right-associated mul-add chains (its source uses Math.fma
    // syntactically, but the `joml.useMathFma` option is OFF by default, so each fma(a, b, c)
    // collapses to `a * b + c` - matching what vanilla Minecraft ships with).
    //
    // These fluent methods produce bit-identical output to JOML's in-place PoseStack ops with
    // default settings: each is a `this * T_op` post-multiply with the SAME right-associated
    // mul-add chains JOML uses. Validated by JomlSideBySideTest.JOML_FLUENT_FULL (0 ULPs
    // across matrix entries) and JOML_FLUENT_VERT (0 ULPs across vertex coords).
    //
    // Existing matrix-via-multiply chains (e.g. {@code createTranslation(...).multiply(...)})
    // drift by 1-4 ULPs per entry because full 4x4 matrix-matrix multiply rounds differently
    // than the specialized translate/scale/rotate ops. New code should prefer the fluent path.

    /**
     * Returns {@code this * T(x, y, z)} - this matrix post-multiplied by a translation matrix.
     * Bit-identical to JOML's {@code Matrix4f.translate(x, y, z)} in-place op (with
     * {@code joml.useMathFma=false}, vanilla's default). Only the translation column changes;
     * the 3x3 rotation/scale block is preserved.
     *
     * @param x the X translation in pre-pose units (applied to a vertex first under
     *          {@code (this * T) * v_col})
     * @param y the Y translation
     * @param z the Z translation
     * @return a new matrix representing the post-translated transform
     */
    public @NotNull Matrix4f translate(float x, float y, float z) {
        // JOML's translateGeneric writes the translation column as
        //   new_m30 = fma(m00, x, fma(m10, y, fma(m20, z, m30)))
        // With FMA disabled (JOML default), this collapses to
        //   new_m30 = m00 * x + (m10 * y + (m20 * z + m30))
        // Right-associated to match exactly.
        float[] s = this.m;
        float[] r = new float[]{
            s[0], s[1], s[2], s[3],   // col 0 unchanged
            s[4], s[5], s[6], s[7],   // col 1 unchanged
            s[8], s[9], s[10], s[11], // col 2 unchanged
            s[0] * x + (s[4] * y + (s[8]  * z + s[12])),
            s[1] * x + (s[5] * y + (s[9]  * z + s[13])),
            s[2] * x + (s[6] * y + (s[10] * z + s[14])),
            s[3] * x + (s[7] * y + (s[11] * z + s[15]))
        };
        return new Matrix4f(r);
    }

    /**
     * Vector overload of {@link #translate(float, float, float)}.
     */
    public @NotNull Matrix4f translate(@NotNull Vector3f v) {
        return translate(v.x(), v.y(), v.z());
    }

    /**
     * Returns {@code this * S(x, y, z)} - this matrix post-multiplied by a non-uniform scale.
     * Bit-identical to JOML's {@code Matrix4f.scale(x, y, z)} in-place op. Cols 0, 1, 2 of the
     * matrix are multiplied by x, y, z respectively. The translation column is preserved.
     *
     * @param x the X-axis scale factor
     * @param y the Y-axis scale factor
     * @param z the Z-axis scale factor
     * @return a new matrix representing the post-scaled transform
     */
    public @NotNull Matrix4f scale(float x, float y, float z) {
        float[] s = this.m;
        float[] r = new float[]{
            s[0] * x, s[1] * x, s[2] * x, s[3] * x,   // col 0 scaled by x
            s[4] * y, s[5] * y, s[6] * y, s[7] * y,   // col 1 scaled by y
            s[8] * z, s[9] * z, s[10] * z, s[11] * z, // col 2 scaled by z
            s[12], s[13], s[14], s[15]                // col 3 (translation) unchanged
        };
        return new Matrix4f(r);
    }

    /**
     * Uniform scale overload of {@link #scale(float, float, float)}.
     */
    public @NotNull Matrix4f scale(float uniform) {
        return scale(uniform, uniform, uniform);
    }

    /**
     * Returns {@code this * R(q)} - this matrix post-multiplied by a rotation built from the
     * quaternion. Bit-identical to JOML's {@code Matrix4f.rotate(Quaternionfc q)} in-place op
     * (with {@code joml.useMathFma=false}, vanilla's default): computes the 9 rotation-matrix
     * entries from the quaternion components (same formulas as {@link Quaternionf#toMatrix4f}),
     * then composes against this matrix's first three columns via right-associated mul-add.
     * The translation column is preserved.
     *
     * <p>Vanilla's {@code PoseStack.mulPose(Quaternionfc q)} delegates to JOML's same path; a
     * chain built via this method matches vanilla's vertex output bit-for-bit when given the
     * same quaternion sequence.
     *
     * @param q the quaternion encoding the rotation
     * @return a new matrix representing the post-rotated transform
     */
    public @NotNull Matrix4f rotate(@NotNull Quaternionf q) {
        // Quaternion-to-rotation-matrix coefficients (same formulas as Quaternionf.toMatrix4f
        // and JOML's Matrix4f.rotateGeneric). Stored in JOML's m_{col}{row} naming.
        float w = q.w(), x = q.x(), y = q.y(), z = q.z();
        float w2 = w * w, x2 = x * x, y2 = y * y, z2 = z * z;
        float zw = z * w, dzw = zw + zw;
        float xy = x * y, dxy = xy + xy;
        float xz = x * z, dxz = xz + xz;
        float yw = y * w, dyw = yw + yw;
        float yz = y * z, dyz = yz + yz;
        float xw = x * w, dxw = xw + xw;
        float rm00 = w2 + x2 - z2 - y2, rm01 = dxy + dzw,         rm02 = dxz - dyw;
        float rm10 = -dzw + dxy,        rm11 = y2 - z2 + w2 - x2, rm12 = dyz + dxw;
        float rm20 = dyw + dxz,         rm21 = dyz - dxw,         rm22 = z2 - y2 - x2 + w2;

        float[] s = this.m;
        // Read this matrix's cols 0, 1, 2 (per JOML naming: m00=get(1,1)=s[0]; m10=get(2,1)=s[4]; m20=get(3,1)=s[8])
        float m00 = s[0],  m01 = s[1],  m02 = s[2],  m03 = s[3];
        float m10 = s[4],  m11 = s[5],  m12 = s[6],  m13 = s[7];
        float m20 = s[8],  m21 = s[9],  m22 = s[10], m23 = s[11];

        // Right-associated mul-add (JOML's fma-collapsed-to-mul-add with default options).
        // new_col_j = m_col_0 * rm_0j + (m_col_1 * rm_1j + (m_col_2 * rm_2j))
        float nm00 = m00 * rm00 + (m10 * rm01 + m20 * rm02);
        float nm01 = m01 * rm00 + (m11 * rm01 + m21 * rm02);
        float nm02 = m02 * rm00 + (m12 * rm01 + m22 * rm02);
        float nm03 = m03 * rm00 + (m13 * rm01 + m23 * rm02);
        float nm10 = m00 * rm10 + (m10 * rm11 + m20 * rm12);
        float nm11 = m01 * rm10 + (m11 * rm11 + m21 * rm12);
        float nm12 = m02 * rm10 + (m12 * rm11 + m22 * rm12);
        float nm13 = m03 * rm10 + (m13 * rm11 + m23 * rm12);
        float nm20 = m00 * rm20 + (m10 * rm21 + m20 * rm22);
        float nm21 = m01 * rm20 + (m11 * rm21 + m21 * rm22);
        float nm22 = m02 * rm20 + (m12 * rm21 + m22 * rm22);
        float nm23 = m03 * rm20 + (m13 * rm21 + m23 * rm22);

        return new Matrix4f(new float[]{
            nm00, nm01, nm02, nm03,
            nm10, nm11, nm12, nm13,
            nm20, nm21, nm22, nm23,
            s[12], s[13], s[14], s[15] // col 3 (translation) preserved
        });
    }

}
