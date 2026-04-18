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
 * A two-component float vector, mutable by default so hot rendering loops can reuse a single
 * scratch instance instead of allocating one per pixel.
 * <p>
 * Cold callers and public API boundaries should prefer the immutable-style methods - {@link #add},
 * {@link #subtract}, {@link #multiply} all return a freshly-allocated vector. Hot callers
 * (the rasterizer's per-pixel barycentric probe) instead lease a scratch vector, write into it
 * via {@link #set} / {@link #setX} / {@link #setY}, and never let the scratch reference escape
 * the method that leased it.
 * <p>
 * Any vector that crosses a thread boundary or is stored as a shared constant must first be
 * frozen via {@link #toImmutable()}. The returned instance is an internal subclass whose setters
 * throw {@link UnsupportedOperationException}, so accidental writes on the consumer side fail
 * loud instead of silently racing. {@link #ZERO} uses the same mechanism.
 */
@EqualsAndHashCode
@ToString
public class Vector2f {

    /** The zero vector - safe to share because it is frozen via {@link #toImmutable()}. */
    public static final @NotNull Vector2f ZERO = new Vector2f(0, 0).toImmutable();

    /** The x component. */
    protected float x;

    /** The y component. */
    protected float y;

    /**
     * Constructs a zero vector. Intended for scratch instances that will be populated via
     * {@link #set} / {@link #setX} / {@link #setY} before first use.
     */
    public Vector2f() {
    }

    /**
     * Constructs a vector with the given components.
     * <p>
     * Assigns the fields directly rather than delegating to {@link #set(float, float)} so the
     * {@link Immutable} subclass - whose {@code set} override throws - can reach this
     * constructor via {@code super(x, y)} without tripping its own guard.
     *
     * @param x the initial x component
     * @param y the initial y component
     */
    public Vector2f(float x, float y) {
        this.x = x;
        this.y = y;
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
     * Overwrites both components in a single call and returns {@code this} for chaining.
     *
     * @param x the new x component
     * @param y the new y component
     * @return this vector, for chaining
     */
    public @NotNull Vector2f set(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /**
     * Returns a frozen copy of this vector - a private {@link Immutable} instance whose setters
     * throw {@link UnsupportedOperationException}. Idempotent: calling {@code toImmutable()} on
     * an already-frozen vector returns the same reference.
     *
     * @return a frozen copy of this vector
     */
    public @NotNull Vector2f toImmutable() {
        return new Immutable(this.x, this.y);
    }

    /**
     * Returns the sum of this vector and the given vector. Allocates a new vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector2f add(@NotNull Vector2f other) {
        return new Vector2f(this.x + other.x, this.y + other.y);
    }

    /**
     * Returns a new vector with the given scalar added to both components.
     *
     * @param scalar the value to add
     * @return a new vector with the scalar added to each component
     */
    public @NotNull Vector2f add(float scalar) {
        return new Vector2f(this.x + scalar, this.y + scalar);
    }

    /**
     * Returns the difference between this vector and the given vector. Allocates a new vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector2f subtract(@NotNull Vector2f other) {
        return new Vector2f(this.x - other.x, this.y - other.y);
    }

    /**
     * Returns this vector scaled by the given factor. Allocates a new vector.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector2f multiply(float scalar) {
        return new Vector2f(this.x * scalar, this.y * scalar);
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
        return this.x * this.x + this.y * this.y;
    }

    /**
     * Computes the dot product of two vectors.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the dot product
     */
    public static float dot(@NotNull Vector2f a, @NotNull Vector2f b) {
        return a.x * b.x + a.y * b.y;
    }

    /**
     * Frozen view of a {@link Vector2f}. Overrides every setter to throw
     * {@link UnsupportedOperationException} so that static constants and vectors published
     * across thread boundaries cannot be accidentally mutated by a downstream consumer.
     * <p>
     * Only reachable via {@link Vector2f#toImmutable()}.
     */
    @EqualsAndHashCode(callSuper = true)
    private static final class Immutable extends Vector2f {

        private Immutable(float x, float y) {
            super(x, y);
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
        public @NotNull Vector2f set(float x, float y) {
            throw unmodifiable();
        }

        @Override
        public @NotNull Vector2f toImmutable() {
            return this;
        }

        private static @NotNull UnsupportedOperationException unmodifiable() {
            return new UnsupportedOperationException("Vector2f.Immutable is read-only");
        }

    }

    /**
     * Gson adapter that serializes a {@link Vector2f} as a two-element JSON array
     * {@code [x, y]} and deserializes from the same format. Deserialized values are frozen
     * via {@link #toImmutable()} so JSON-sourced vectors behave like the old record form.
     */
    @NoArgsConstructor
    public static final class Adapter extends TypeAdapter<Vector2f> {

        @Override
        public void write(@NotNull JsonWriter out, @Nullable Vector2f value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginArray();
            out.value(value.x());
            out.value(value.y());
            out.endArray();
        }

        @Override
        public @Nullable Vector2f read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            in.beginArray();
            float x = (float) in.nextDouble();
            float y = (float) in.nextDouble();
            in.endArray();

            return new Vector2f(x, y).toImmutable();
        }

    }

}
