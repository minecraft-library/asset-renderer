package lib.minecraft.renderer.tensor;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * An immutable two-component float vector.
 * <p>
 * Used for 2D screen-space coordinates (projected triangle vertices, per-pixel probes) and UV
 * coordinates in the {@code [0, 1]} range.
 *
 * @param x the x component
 * @param y the y component
 *
 * @see Vector3f
 * @see Vector4f
 */
public record Vector2f(float x, float y) {

    /** The zero vector. */
    public static final @NotNull Vector2f ZERO = new Vector2f(0, 0);

    /**
     * Returns the sum of this vector and the given vector.
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
     * Returns the difference between this vector and the given vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector2f subtract(@NotNull Vector2f other) {
        return new Vector2f(this.x - other.x, this.y - other.y);
    }

    /**
     * Returns this vector scaled by the given factor.
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

            return new Vector2f(x, y);
        }

    }

}
