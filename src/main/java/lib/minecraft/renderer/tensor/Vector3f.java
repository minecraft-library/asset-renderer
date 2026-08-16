package lib.minecraft.renderer.tensor;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.simplified.annotations.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * An immutable three-component float vector.
 * <p>
 * Used for 3D model-space positions, surface normals, and three-channel linear values such as
 * scale factors. Static helpers handle the common rendering operations ({@link #transform},
 * {@link #transformNormal}, {@link #normalize}, {@link #cross}, {@link #dot}, {@link #lerp}).
 * {@link #transform} and {@link #transformNormal} silently dispatch to a JDK Vector API
 * implementation when the incubator module is loaded; otherwise they run a bit-identical
 * scalar fallback. Callers never see the difference.
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 *
 * @see Vector2f
 * @see Vector4f
 * @see Matrix4f
 */
public record Vector3f(float x, float y, float z) {

    /**
     * The zero vector.
     */
    public static final @NotNull Vector3f ZERO = new Vector3f(0, 0, 0);

    /**
     * Minimum length below which {@link #normalize(Vector3f)} treats a vector as degenerate and
     * returns {@link #ZERO} rather than dividing by the (near-zero) magnitude. Guards against
     * infinities and NaN from dividing by a cancellation-rounded magnitude.
     */
    public static final float NORMALIZE_EPSILON = 1e-8f;

    /**
     * Returns the sum of this vector and the given vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector3f add(@NotNull Vector3f other) {
        return new Vector3f(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    /**
     * Returns the difference between this vector and the given vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector3f subtract(@NotNull Vector3f other) {
        return new Vector3f(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    /**
     * Returns this vector scaled by the given factor.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector3f multiply(float scalar) {
        return new Vector3f(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    /**
     * Returns this vector divided by the given scalar.
     *
     * @param scalar the divisor
     * @return a new vector with each component divided
     */
    public @NotNull Vector3f divide(float scalar) {
        return new Vector3f(this.x / scalar, this.y / scalar, this.z / scalar);
    }

    /**
     * This vector with all three components negated.
     */
    public @NotNull Vector3f negate() {
        return new Vector3f(-this.x, -this.y, -this.z);
    }

    /**
     * Returns the component at the given axis index ({@code 0=x, 1=y, 2=z}). Useful when an
     * algorithm picks an axis at runtime, e.g. a face's {@code widthAxis} / {@code heightAxis}
     * naming which of the three components an unwrap reads.
     *
     * @param axis the axis index, must be {@code 0}, {@code 1}, or {@code 2}
     * @return the component value
     * @throws IndexOutOfBoundsException if {@code axis} is not in {@code [0, 2]}
     */
    public float get(int axis) {
        return switch (axis) {
            case 0 -> this.x;
            case 1 -> this.y;
            case 2 -> this.z;
            default -> throw new IndexOutOfBoundsException("axis must be 0, 1, or 2 (got " + axis + ")");
        };
    }

    /**
     * The Euclidean length of this vector.
     */
    public float length() {
        return (float) Math.sqrt(this.lengthSquared());
    }

    /**
     * The squared Euclidean length of this vector.
     * <p>
     * Cheaper than {@link #length()} when only magnitude comparisons are needed.
     */
    public float lengthSquared() {
        // Right-associated mul-add matching JOML's Vector3fc.lengthSquared with
        // joml.useMathFma=false.
        return this.x * this.x + (this.y * this.y + this.z * this.z);
    }

    // --- Fluent instance forms of the static operations below ---
    //
    // Each delegates verbatim to its static counterpart, so the float result is bit-identical
    // (same right-associated mul-add chains, same SIMD dispatch). They exist so call sites can
    // read as method chains - {@code a.subtract(b).cross(c).normalize()} - instead of nested
    // chain-parameter static calls. No fused convenience methods: each maps 1:1 to one static op
    // so the composition stays visible at the call site.

    /**
     * Returns the cross product of this vector and {@code other}. Instance form of
     * {@link #cross(Vector3f, Vector3f)}.
     *
     * @param other the right-hand vector
     * @return a new vector perpendicular to both
     */
    public @NotNull Vector3f cross(@NotNull Vector3f other) {
        return cross(this, other);
    }

    /**
     * Returns the dot product of this vector and {@code other}. Instance form of
     * {@link #dot(Vector3f, Vector3f)}.
     *
     * @param other the right-hand vector
     * @return the dot product
     */
    public float dot(@NotNull Vector3f other) {
        return dot(this, other);
    }

    /**
     * Returns a unit-length vector in this vector's direction, or {@link #ZERO} when the length
     * falls below {@link #NORMALIZE_EPSILON}. Instance form of {@link #normalize(Vector3f)}.
     *
     * @return a new normalized vector, or {@link #ZERO}
     */
    public @NotNull Vector3f normalize() {
        return normalize(this);
    }

    /**
     * Linearly interpolates from this vector toward {@code other} by {@code t}. Instance form of
     * {@link #lerp(Vector3f, Vector3f, float)}.
     *
     * @param other the end vector
     * @param t the interpolation factor, typically in {@code [0, 1]}
     * @return a new interpolated vector
     */
    public @NotNull Vector3f lerp(@NotNull Vector3f other, float t) {
        return lerp(this, other, t);
    }

    /**
     * Transforms this vector by {@code m} as a point ({@code w=1}). Instance form of
     * {@link #transform(Vector3f, Matrix4f)}.
     *
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public @NotNull Vector3f transform(@NotNull Matrix4f m) {
        return transform(this, m);
    }

    /**
     * Transforms this vector by {@code m} as a direction ({@code w=0}), ignoring translation.
     * Instance form of {@link #transformNormal(Vector3f, Matrix4f)}.
     *
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public @NotNull Vector3f transformNormal(@NotNull Matrix4f m) {
        return transformNormal(this, m);
    }

    /**
     * Computes the cross product of two vectors.
     *
     * @param a the first vector
     * @param b the second vector
     * @return a new vector perpendicular to both inputs
     */
    public static @NotNull Vector3f cross(@NotNull Vector3f a, @NotNull Vector3f b) {
        return new Vector3f(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        );
    }

    /**
     * Computes the dot product of two vectors.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the dot product
     */
    public static float dot(@NotNull Vector3f a, @NotNull Vector3f b) {
        // Right-associated mul-add matching JOML's Vector3fc.dot with default
        // joml.useMathFma=false: JOML's source calls Math.fma but with FMA disabled,
        // the expression collapses to a.x*b.x + (a.y*b.y + a.z*b.z).
        return a.x * b.x + (a.y * b.y + a.z * b.z);
    }

    /**
     * Returns a unit-length vector in the same direction as {@code v}, or {@link #ZERO} when
     * {@code v}'s length falls below {@link #NORMALIZE_EPSILON}.
     *
     * @param v the vector to normalize
     * @return a new normalized vector, or {@link #ZERO}
     */
    public static @NotNull Vector3f normalize(@NotNull Vector3f v) {
        float len = v.length();
        if (len < NORMALIZE_EPSILON) return ZERO;
        return v.divide(len);
    }

    /**
     * Linearly interpolates between two vectors.
     *
     * @param a the start vector
     * @param b the end vector
     * @param t the interpolation factor, typically in {@code [0, 1]}
     * @return a new interpolated vector
     */
    public static @NotNull Vector3f lerp(@NotNull Vector3f a, @NotNull Vector3f b, float t) {
        return new Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}) under the column-vector
     * convention {@code m * v_col}. Auto-dispatches to a 4-lane SIMD implementation when the
     * JDK Vector API module is available; otherwise computes the three components scalar.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        if (SimdSupport.ENABLED) return SimdOps.transform(v, m);
        // Right-associated mul-add chain matching JOML's Vector3f.mulPositionGeneric with
        // default `joml.useMathFma=false` (vanilla Minecraft's setting). JOML source reads as
        // `Math.fma(m00, x, Math.fma(m10, y, Math.fma(m20, z, m30)))`; with FMA off, each
        // fma(a, b, c) collapses to `a * b + c`, producing the right-associated chain
        // `m00*x + (m10*y + (m20*z + m30))`. Validated bit-identical in JomlSideBySideTest.
        float tx = m.get(1, 1) * v.x + (m.get(2, 1) * v.y + (m.get(3, 1) * v.z + m.get(4, 1)));
        float ty = m.get(1, 2) * v.x + (m.get(2, 2) * v.y + (m.get(3, 2) * v.z + m.get(4, 2)));
        float tz = m.get(1, 3) * v.x + (m.get(2, 3) * v.y + (m.get(3, 3) * v.z + m.get(4, 3)));
        return new Vector3f(tx, ty, tz);
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}) under the column-vector
     * convention {@code m * v_col}, ignoring the translation column. Auto-dispatches to a
     * 4-lane SIMD implementation when the JDK Vector API module is available; otherwise computes
     * the three components scalar.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        if (SimdSupport.ENABLED) return SimdOps.transformNormal(v, m);
        // Right-associated chain matching JOML's Vector3f.mulDirection (w=0; no translation).
        float tx = m.get(1, 1) * v.x + (m.get(2, 1) * v.y + m.get(3, 1) * v.z);
        float ty = m.get(1, 2) * v.x + (m.get(2, 2) * v.y + m.get(3, 2) * v.z);
        float tz = m.get(1, 3) * v.x + (m.get(2, 3) * v.y + m.get(3, 3) * v.z);
        return new Vector3f(tx, ty, tz);
    }

    /**
     * Gson adapter that serializes a {@link Vector3f} as a three-element JSON array
     * {@code [x, y, z]} and deserializes from the same format.
     */
    @NoArgsConstructor
    public static final class Adapter extends TypeAdapter<Vector3f> {

        @Override
        public void write(@NotNull JsonWriter out, @Nullable Vector3f value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginArray();
            out.value(value.x());
            out.value(value.y());
            out.value(value.z());
            out.endArray();
        }

        @Override
        public @Nullable Vector3f read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            in.beginArray();
            float x = (float) in.nextDouble();
            float y = (float) in.nextDouble();
            float z = (float) in.nextDouble();
            in.endArray();

            return new Vector3f(x, y, z);
        }

    }

}
