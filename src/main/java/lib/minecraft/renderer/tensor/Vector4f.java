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
 * An immutable four-component float vector.
 * <p>
 * Used primarily to carry UV coordinate rectangles, where {@code (x, y)} is the min corner and
 * {@code (z, w)} is the max corner. See {@link #createUvMap(int)} for the face-rotation-aware
 * expansion into four per-vertex {@link Vector2f} UV coordinates.
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 * @param w the w component
 *
 * @see Vector2f
 * @see Vector3f
 */
public record Vector4f(float x, float y, float z, float w) {

    /** The zero vector. */
    public static final @NotNull Vector4f ZERO = new Vector4f(0, 0, 0, 0);

    /**
     * Returns the sum of this vector and the given vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector4f add(@NotNull Vector4f other) {
        return new Vector4f(this.x + other.x, this.y + other.y, this.z + other.z, this.w + other.w);
    }

    /**
     * Returns this vector scaled by the given factor.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector4f multiply(float scalar) {
        return new Vector4f(this.x * scalar, this.y * scalar, this.z * scalar, this.w * scalar);
    }

    /**
     * Returns the difference between this vector and the given vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector4f subtract(@NotNull Vector4f other) {
        return new Vector4f(this.x - other.x, this.y - other.y, this.z - other.z, this.w - other.w);
    }

    /**
     * Builds a four-element UV coordinate map from this UV rectangle, applying the given face
     * rotation.
     * <p>
     * Treats {@code (x, y)} as the UV min and {@code (z, w)} as the UV max in vanilla's 0-16
     * space. Each resulting {@link Vector2f} is normalized to {@code [0, 1]} by dividing both
     * components by 16. The rotation must be a multiple of 90 degrees; any other value is
     * treated as 0.
     *
     * @param faceRotationDegrees the face rotation in degrees - must be a multiple of 90
     * @return an array of four {@link Vector2f} UV coordinates, one per vertex
     */
    public @NotNull Vector2f @NotNull [] createUvMap(int faceRotationDegrees) {
        int normalizedAngle = ((faceRotationDegrees % 360) + 360) % 360;
        int quadrant = switch (normalizedAngle) {
            case 90 -> 1;
            case 180 -> 2;
            case 270 -> 3;
            default -> 0;
        };

        Vector2f[] map = new Vector2f[4];

        for (int i = 0; i < 4; i++) {
            float u = uvU(quadrant, i) / 16f;
            float v = uvV(quadrant, i) / 16f;
            map[i] = new Vector2f(u, v);
        }

        return map;
    }

    private float uvU(int rotationQuadrant, int vertexIndex) {
        int shifted = (vertexIndex + rotationQuadrant) % 4;
        return (shifted != 0 && shifted != 1) ? this.z : this.x;
    }

    private float uvV(int rotationQuadrant, int vertexIndex) {
        int shifted = (vertexIndex + rotationQuadrant) % 4;
        return (shifted != 0 && shifted != 3) ? this.w : this.y;
    }

    /**
     * Gson adapter that serializes a {@link Vector4f} as a four-element JSON array
     * {@code [x, y, z, w]} and deserializes from the same format.
     */
    @NoArgsConstructor
    public static final class Adapter extends TypeAdapter<Vector4f> {

        @Override
        public void write(@NotNull JsonWriter out, @Nullable Vector4f value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginArray();
            out.value(value.x());
            out.value(value.y());
            out.value(value.z());
            out.value(value.w());
            out.endArray();
        }

        @Override
        public @Nullable Vector4f read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            in.beginArray();
            float x = (float) in.nextDouble();
            float y = (float) in.nextDouble();
            float z = (float) in.nextDouble();
            float w = (float) in.nextDouble();
            in.endArray();

            return new Vector4f(x, y, z, w);
        }

    }

}
