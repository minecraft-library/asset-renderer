package lib.minecraft.renderer.tensor;

import org.jetbrains.annotations.NotNull;

/**
 * A 4-component quaternion (x, y, z, w) used to represent rotations in the same float-precision
 * frame as JOML's {@code org.joml.Quaternionf}. The constructors and matrix conversion mirror
 * JOML's algorithms operation-for-operation so a quaternion built here produces bit-identical
 * float values to one built in the vanilla harness, which is how Minecraft assembles its iso
 * pose ({@code new Quaternionf().rotationXYZ(toRadians(210), toRadians(45), 0)}).
 * <p>
 * Matching JOML's algorithm exactly (not just mathematically) is the point: float arithmetic
 * is non-associative, so the order in which sin/cos values are combined into quaternion components
 * is part of the contract. Building a {@link Matrix4f} from a {@link Quaternionf} via
 * {@link #toMatrix4f} produces bit-identical output to {@code new org.joml.Matrix4f().rotation(q)}
 * because {@link Matrix4f} uses the same column-vector convention JOML does.
 *
 * @param x the imaginary i component
 * @param y the imaginary j component
 * @param z the imaginary k component
 * @param w the real component
 */
public record Quaternionf(float x, float y, float z, float w) {

    private static final float PI_f = (float) java.lang.Math.PI;
    private static final float PI_OVER_2_f = (float) (java.lang.Math.PI * 0.5);
    private static final float PI_TIMES_2_f = PI_f * 2.0f;

    /**
     * Builds a quaternion representing the Tait-Bryan rotation {@code R_X(angleX); R_Y(angleY);
     * R_Z(angleZ)} (intrinsic, X first). Mirrors JOML's
     * {@code Quaternionf.rotationXYZ(angleX, angleY, angleZ)} including the
     * {@code cosFromSin}-via-sqrt cosine derivation (no FASTMATH approximation) so a quaternion
     * built here is bit-identical to JOML's.
     *
     * <p>Vanilla harness builds the iso pose via this exact entry point:
     * {@code new Quaternionf().rotationXYZ(toRadians(210), toRadians(45), 0)}.
     *
     * @param angleX rotation about X in radians
     * @param angleY rotation about Y in radians
     * @param angleZ rotation about Z in radians
     * @return the unit quaternion representing the composite rotation
     */
    public static @NotNull Quaternionf rotationXYZ(float angleX, float angleY, float angleZ) {
        float sx = (float) java.lang.Math.sin(angleX * 0.5f);
        float cx = cosFromSin(sx, angleX * 0.5f);
        float sy = (float) java.lang.Math.sin(angleY * 0.5f);
        float cy = cosFromSin(sy, angleY * 0.5f);
        float sz = (float) java.lang.Math.sin(angleZ * 0.5f);
        float cz = cosFromSin(sz, angleZ * 0.5f);

        float cycz = cy * cz;
        float sysz = sy * sz;
        float sycz = sy * cz;
        float cysz = cy * sz;
        float w = cx * cycz - sx * sysz;
        float x = sx * cycz + cx * sysz;
        float y = cx * sycz - sx * cysz;
        float z = cx * cysz + sx * sycz;
        return new Quaternionf(x, y, z, w);
    }

    /**
     * Builds a quaternion representing the Tait-Bryan rotation {@code R_Z(angleZ); R_Y(angleY);
     * R_X(angleX)} (intrinsic, Z first). Mirrors JOML's
     * {@code Quaternionf.rotationZYX(angleZ, angleY, angleX)} - same algorithm as
     * {@link #rotationXYZ} but with sign-flipped {@code sysz} / {@code cysz} terms.
     *
     * <p>Vanilla's {@code ModelPart.translateAndRotate} composes bone rotations via
     * {@code new Quaternionf().rotationZYX(zRot, yRot, xRot)}, so any bone with non-zero
     * pitch/yaw/roll uses this path on the vanilla side. When applied as {@code M * v_col}
     * via {@link #toMatrix4f}, the {@code X} rotation applies to {@code v} first, then
     * {@code Y}, then {@code Z}.
     *
     * @param angleZ rotation about Z in radians
     * @param angleY rotation about Y in radians
     * @param angleX rotation about X in radians
     * @return the unit quaternion representing the composite rotation
     */
    public static @NotNull Quaternionf rotationZYX(float angleZ, float angleY, float angleX) {
        float sx = (float) java.lang.Math.sin(angleX * 0.5f);
        float cx = cosFromSin(sx, angleX * 0.5f);
        float sy = (float) java.lang.Math.sin(angleY * 0.5f);
        float cy = cosFromSin(sy, angleY * 0.5f);
        float sz = (float) java.lang.Math.sin(angleZ * 0.5f);
        float cz = cosFromSin(sz, angleZ * 0.5f);

        float cycz = cy * cz;
        float sysz = sy * sz;
        float sycz = sy * cz;
        float cysz = cy * sz;
        float w = cx * cycz + sx * sysz;
        float x = sx * cycz - cx * sysz;
        float y = cx * sycz + sx * cysz;
        float z = cx * cysz - sx * sycz;
        return new Quaternionf(x, y, z, w);
    }

    /**
     * Returns the column-vector-convention 4x4 rotation matrix equivalent to this quaternion. The
     * coefficient formulas mirror JOML's {@code Matrix4f.rotation(Quaternionfc)} directly: JOML
     * names entries {@code m_{col}{row}} so its {@code m00=R[0][0]}, {@code m01=R[1][0]} (col 0,
     * row 1) maps onto our {@code m{row}{col}} as {@code m11=R[0][0]}, {@code m21=R[1][0]} -
     * matching cells of the same matrix.
     */
    public @NotNull Matrix4f toMatrix4f() {
        float w2 = w * w;
        float x2 = x * x;
        float y2 = y * y;
        float z2 = z * z;
        float zw = z * w, dzw = zw + zw;
        float xy = x * y, dxy = xy + xy;
        float xz = x * z, dxz = xz + xz;
        float yw = y * w, dyw = yw + yw;
        float yz = y * z, dyz = yz + yz;
        float xw = x * w, dxw = xw + xw;

        // Column-major storage: each row below holds one column of the rotation matrix.
        return new Matrix4f(
            w2 + x2 - z2 - y2, dxy + dzw,         dxz - dyw,         0f,
            -dzw + dxy,        y2 - z2 + w2 - x2, dyz + dxw,         0f,
            dyw + dxz,         dyz - dxw,         z2 - y2 - x2 + w2, 0f,
            0f,                0f,                0f,                1f
        );
    }

    /**
     * Mirrors JOML's {@code Math.cosFromSin(sin, angle)} default branch (no FASTMATH): computes
     * {@code sqrt(1 - sin^2)} and applies the {@code [PI, 2*PI)} sign correction. Bit-identical
     * to JOML when the JOML installation runs without {@code -Djoml.fastmath}.
     */
    private static float cosFromSin(float sin, float angle) {
        float cos = (float) java.lang.Math.sqrt(1.0f - sin * sin);
        float a = angle + PI_OVER_2_f;
        float b = a - (int) (a / PI_TIMES_2_f) * PI_TIMES_2_f;
        if (b < 0.0f) b = PI_TIMES_2_f + b;
        if (b >= PI_f) return -cos;
        return cos;
    }

}
