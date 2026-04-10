package dev.sbs.renderer.math;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * An immutable two-component float vector.
 */
@Getter
@RequiredArgsConstructor
public final class Vector2f {

    /** The zero vector. */
    public static final @NotNull Vector2f ZERO = new Vector2f(0, 0);

    private final float x;

    private final float y;

    /**
     * Returns the sum of this vector and the given vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector2f add(@NotNull Vector2f other) {
        return new Vector2f(x + other.x, y + other.y);
    }

    /**
     * Returns a new vector with the given scalar added to both components.
     *
     * @param scalar the value to add
     * @return a new vector with the scalar added to each component
     */
    public @NotNull Vector2f add(float scalar) {
        return new Vector2f(x + scalar, y + scalar);
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
     * Returns the Euclidean length of this vector.
     *
     * @return the length
     */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Returns the squared Euclidean length of this vector.
     *
     * @return the squared length
     */
    public float lengthSquared() {
        return x * x + y * y;
    }

    /**
     * Returns this vector scaled by the given factor.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector2f multiply(float scalar) {
        return new Vector2f(x * scalar, y * scalar);
    }

    /**
     * Returns the difference between this vector and the given vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector2f subtract(@NotNull Vector2f other) {
        return new Vector2f(x - other.x, y - other.y);
    }

    /**
     * Gson adapter that serializes a {@link Vector2f} as a two-element JSON array
     * {@code [x, y]} and deserializes from the same format.
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
            out.value(value.getX());
            out.value(value.getY());
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

            return new Vector2f(x, y);
        }

    }

}
