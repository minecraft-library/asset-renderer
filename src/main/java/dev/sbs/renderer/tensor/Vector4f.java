package dev.sbs.renderer.tensor;

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
 * An immutable four-component float vector, used primarily for UV coordinate ranges.
 */
@Getter
@RequiredArgsConstructor
public final class Vector4f {

    /** The zero vector. */
    public static final @NotNull Vector4f ZERO = new Vector4f(0, 0, 0, 0);

    private final float x;

    private final float y;

    private final float z;

    private final float w;

    /**
     * Returns the sum of this vector and the given vector.
     *
     * @param other the vector to add
     * @return a new vector representing the sum
     */
    public @NotNull Vector4f add(@NotNull Vector4f other) {
        return new Vector4f(x + other.x, y + other.y, z + other.z, w + other.w);
    }

    /**
     * Returns this vector scaled by the given factor.
     *
     * @param scalar the scale factor
     * @return a new scaled vector
     */
    public @NotNull Vector4f multiply(float scalar) {
        return new Vector4f(x * scalar, y * scalar, z * scalar, w * scalar);
    }

    /**
     * Returns the difference between this vector and the given vector.
     *
     * @param other the vector to subtract
     * @return a new vector representing the difference
     */
    public @NotNull Vector4f subtract(@NotNull Vector4f other) {
        return new Vector4f(x - other.x, y - other.y, z - other.z, w - other.w);
    }

    /**
     * Creates a four-element UV coordinate array from this UV rectangle, applying the given rotation.
     *
     * <p>Treats (x, y) as the UV min and (z, w) as the UV max in 0-16 space.
     * Each resulting {@link Vector2f} contains normalized UV coordinates (divided by 16).
     *
     * @param faceRotationDegrees the rotation in degrees (must be a multiple of 90)
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
        return (shifted != 0 && shifted != 1) ? z : x;
    }

    private float uvV(int rotationQuadrant, int vertexIndex) {
        int shifted = (vertexIndex + rotationQuadrant) % 4;
        return (shifted != 0 && shifted != 3) ? w : y;
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
            out.value(value.getX());
            out.value(value.getY());
            out.value(value.getZ());
            out.value(value.getW());
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
