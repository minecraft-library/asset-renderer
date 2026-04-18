package dev.sbs.renderer.tensor;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * An immutable three-component float vector.
 * <p>
 * Used for 3D model-space positions, surface normals, and three-channel linear values such as
 * scale factors. Static helpers handle the common rendering operations ({@link #transform},
 * {@link #transformNormal}, {@link #normalize}, {@link #cross}, {@link #dot}, {@link #lerp});
 * the SIMD-accelerated variants of {@code transform} and {@code transformNormal} live in
 * {@link Vector3fOps}.
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 *
 * @see Vector2f
 * @see Vector4f
 * @see Matrix4f
 * @see Vector3fOps
 */
public record Vector3f(float x, float y, float z) {

    /** The zero vector. */
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
     * Returns this vector with all three components negated.
     *
     * @return a new negated vector
     */
    public @NotNull Vector3f negate() {
        return new Vector3f(-this.x, -this.y, -this.z);
    }

    /**
     * Returns the Euclidean length of this vector.
     *
     * @return the length
     */
    public float length() {
        return (float) Math.sqrt(this.lengthSquared());
    }

    /**
     * Returns the squared Euclidean length of this vector. Cheaper than {@link #length()} when
     * only magnitude comparisons are needed.
     *
     * @return the squared length
     */
    public float lengthSquared() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
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
        return a.x * b.x + a.y * b.y + a.z * b.z;
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
     * Transforms {@code v} by {@code m} as a point ({@code w=1}). See
     * {@link Vector3fOps#transform} for a SIMD-accelerated variant.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        float tx = v.x * m.getM11() + v.y * m.getM21() + v.z * m.getM31() + m.getM41();
        float ty = v.x * m.getM12() + v.y * m.getM22() + v.z * m.getM32() + m.getM42();
        float tz = v.x * m.getM13() + v.y * m.getM23() + v.z * m.getM33() + m.getM43();
        return new Vector3f(tx, ty, tz);
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), ignoring the translation
     * row. See {@link Vector3fOps#transformNormal} for a SIMD-accelerated variant.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        float tx = v.x * m.getM11() + v.y * m.getM21() + v.z * m.getM31();
        float ty = v.x * m.getM12() + v.y * m.getM22() + v.z * m.getM32();
        float tz = v.x * m.getM13() + v.y * m.getM23() + v.z * m.getM33();
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
