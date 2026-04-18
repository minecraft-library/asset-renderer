package dev.sbs.renderer.tensor;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * A three-component float vector, mutable by default so hot rendering loops can reuse a single
 * scratch instance instead of allocating one per vertex.
 * <p>
 * Cold callers and public API boundaries should prefer the immutable-style methods - {@link #add},
 * {@link #subtract}, {@link #multiply}, {@link #cross}, {@link #transform}, and so on all return a
 * freshly-allocated vector. Hot callers (the rasterizer's Pass 1 / Pass 2 loops) instead lease a
 * scratch vector, write into it via {@link #set} / {@link #setX} / {@link #setY} / {@link #setZ}
 * or via the {@code *Into} helpers ({@link #transformInto}, {@link #transformNormalInto}), and
 * never let the scratch reference escape the method that leased it.
 * <p>
 * Any vector that crosses a thread boundary - the classic case being values published into
 * {@code ModelEngine.Projected} during Pass 1 and read from Pass 2's parallel tile workers -
 * must first be frozen via {@link #toImmutable()}. The returned instance is an internal subclass
 * whose setters throw {@link UnsupportedOperationException}, so accidental writes on the
 * consumer side fail loud instead of silently racing. Static constants such as {@link #ZERO}
 * use the same mechanism.
 *
 * @see Matrix4f
 * @see Vector3fOps
 */
@ToString
@EqualsAndHashCode
public class Vector3f {

    /** The zero vector - safe to share because it is frozen via {@link #toImmutable()}. */
    public static final @NotNull Vector3f ZERO = new Vector3f(0, 0, 0).toImmutable();

    /**
     * Minimum length below which {@link #normalize(Vector3f)} treats a vector as degenerate and
     * returns {@link #ZERO} rather than dividing by the (near-zero) magnitude. Guards against
     * infinities and NaN from dividing by a cancellation-rounded magnitude.
     */
    public static final float NORMALIZE_EPSILON = 1e-8f;

    /** The x component. */
    protected float x;

    /** The y component. */
    protected float y;

    /** The z component. */
    protected float z;

    /**
     * Constructs a zero vector. Intended for scratch instances that will be populated via
     * {@link #set} / {@link #setX} / {@link #setY} / {@link #setZ} before first use.
     */
    public Vector3f() {
    }

    /**
     * Constructs a vector with the given components.
     * <p>
     * Assigns the fields directly rather than delegating to {@link #set(float, float, float)}
     * so that the {@link Immutable} subclass - whose {@code set} override throws - can reach
     * this constructor via {@code super(x, y, z)} without tripping its own guard.
     *
     * @param x the initial x component
     * @param y the initial y component
     * @param z the initial z component
     */
    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Returns the x component.
     *
     * @return the x component
     */
    public float x() {
        return this.x;
    }

    /**
     * Returns the y component.
     *
     * @return the y component
     */
    public float y() {
        return this.y;
    }

    /**
     * Returns the z component.
     *
     * @return the z component
     */
    public float z() {
        return this.z;
    }

    /**
     * Sets the x component, overwriting the previous value in place.
     *
     * @param x the new x component
     */
    public void setX(float x) {
        this.x = x;
    }

    /**
     * Sets the y component, overwriting the previous value in place.
     *
     * @param y the new y component
     */
    public void setY(float y) {
        this.y = y;
    }

    /**
     * Sets the z component, overwriting the previous value in place.
     *
     * @param z the new z component
     */
    public void setZ(float z) {
        this.z = z;
    }

    /**
     * Overwrites all three components in a single call and returns {@code this} for chaining.
     *
     * @param x the new x component
     * @param y the new y component
     * @param z the new z component
     * @return this vector, for chaining
     */
    public @NotNull Vector3f set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * Returns a frozen copy of this vector - a private {@link Immutable} instance whose setters
     * throw {@link UnsupportedOperationException}. Callers publishing a vector across a thread
     * boundary or storing it as a shared constant should freeze first; the {@link Immutable}
     * instance is itself idempotent - calling {@code toImmutable()} on an already-frozen vector
     * returns the same reference.
     *
     * @return a frozen copy of this vector
     */
    public @NotNull Vector3f toImmutable() {
        return new Immutable(this.x, this.y, this.z);
    }

    /**
     * Returns the sum of this vector and the given vector. Allocates a new vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector3f add(@NotNull Vector3f other) {
        return new Vector3f(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    /**
     * Returns the difference between this vector and the given vector. Allocates a new vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector3f subtract(@NotNull Vector3f other) {
        return new Vector3f(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    /**
     * Returns this vector scaled by the given factor. Allocates a new vector.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector3f multiply(float scalar) {
        return new Vector3f(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    /**
     * Returns this vector divided by the given scalar. Allocates a new vector.
     *
     * @param scalar the divisor
     * @return a new vector with each component divided
     */
    public @NotNull Vector3f divide(float scalar) {
        return new Vector3f(this.x / scalar, this.y / scalar, this.z / scalar);
    }

    /**
     * Returns this vector with all three components negated. Allocates a new vector.
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
     * Transforms {@code v} by {@code m} as a point ({@code w=1}), allocating a new result.
     * <p>
     * Equivalent to {@link #transformInto} followed by a fresh allocation - use the
     * {@code *Into} variant when running inside a hot loop with a reusable scratch vector.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @return a new transformed vector
     */
    public static @NotNull Vector3f transform(@NotNull Vector3f v, @NotNull Matrix4f m) {
        Vector3f out = new Vector3f();
        transformInto(v, m, out);
        return out;
    }

    /**
     * Transforms {@code v} by {@code m} as a point ({@code w=1}), writing the result into
     * {@code out}. Bit-identical to {@link #transform} under IEEE-754 round-to-nearest-even.
     * <p>
     * {@code v} and {@code out} may be the same instance - all reads from {@code v} happen
     * before any write to {@code out}.
     *
     * @param v the vector to transform
     * @param m the transformation matrix
     * @param out the vector that receives the transformed components
     */
    public static void transformInto(@NotNull Vector3f v, @NotNull Matrix4f m, @NotNull Vector3f out) {
        float tx = v.x * m.getM11() + v.y * m.getM21() + v.z * m.getM31() + m.getM41();
        float ty = v.x * m.getM12() + v.y * m.getM22() + v.z * m.getM32() + m.getM42();
        float tz = v.x * m.getM13() + v.y * m.getM23() + v.z * m.getM33() + m.getM43();
        out.set(tx, ty, tz);
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), ignoring the translation
     * row. Allocates a new result.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @return a new transformed direction vector
     */
    public static @NotNull Vector3f transformNormal(@NotNull Vector3f v, @NotNull Matrix4f m) {
        Vector3f out = new Vector3f();
        transformNormalInto(v, m, out);
        return out;
    }

    /**
     * Transforms {@code v} by {@code m} as a direction ({@code w=0}), writing the result into
     * {@code out}. Ignores the translation row of {@code m}; bit-identical to
     * {@link #transformNormal} under IEEE-754 round-to-nearest-even.
     * <p>
     * {@code v} and {@code out} may be the same instance.
     *
     * @param v the direction vector to transform
     * @param m the transformation matrix
     * @param out the vector that receives the transformed direction
     */
    public static void transformNormalInto(@NotNull Vector3f v, @NotNull Matrix4f m, @NotNull Vector3f out) {
        float tx = v.x * m.getM11() + v.y * m.getM21() + v.z * m.getM31();
        float ty = v.x * m.getM12() + v.y * m.getM22() + v.z * m.getM32();
        float tz = v.x * m.getM13() + v.y * m.getM23() + v.z * m.getM33();
        out.set(tx, ty, tz);
    }

    /**
     * Frozen view of a {@link Vector3f}. Overrides every setter to throw
     * {@link UnsupportedOperationException} so that static constants and vectors published
     * across thread boundaries cannot be accidentally mutated by a downstream consumer.
     * <p>
     * Only reachable via {@link Vector3f#toImmutable()}.
     */
    @EqualsAndHashCode(callSuper = true)
    private static final class Immutable extends Vector3f {

        private Immutable(float x, float y, float z) {
            super(x, y, z);
        }

        @Override
        public void setX(float x) {
            throw unmodifiable();
        }

        @Override
        public void setY(float y) {
            throw unmodifiable();
        }

        @Override
        public void setZ(float z) {
            throw unmodifiable();
        }

        @Override
        public @NotNull Vector3f set(float x, float y, float z) {
            throw unmodifiable();
        }

        @Override
        public @NotNull Vector3f toImmutable() {
            return this;
        }

        private static @NotNull UnsupportedOperationException unmodifiable() {
            return new UnsupportedOperationException("Vector3f.Immutable is read-only");
        }

    }

    /**
     * Gson adapter that serializes a {@link Vector3f} as a three-element JSON array
     * {@code [x, y, z]} and deserializes from the same format. Deserialized values are frozen
     * via {@link #toImmutable()} so JSON-sourced vectors behave like the old record form.
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

            return new Vector3f(x, y, z).toImmutable();
        }

    }

}
