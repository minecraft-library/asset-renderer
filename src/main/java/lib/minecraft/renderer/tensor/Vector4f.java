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
 * {@code (z, w)} is the max corner. See {@link #toUvCorners} for the face-rotation- and
 * mirror-aware expansion into four per-vertex {@link Vector2f} UV coordinates, and
 * {@link #bounds} for the inverse - computing a UV rectangle from a set of corner points.
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

    /**
     * The zero vector.
     */
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
     * Builds the four UV corners (TL, BL, BR, TR) for this rectangle, normalizing each component
     * by the supplied texture scales and applying optional face rotation and U-axis mirroring.
     * <p>
     * Treats {@code (x, y)} as the UV min and {@code (z, w)} as the UV max in pixel space; each
     * resulting {@link Vector2f} is divided by {@code uScale} or {@code vScale} to land in
     * {@code [0, 1]} normalized texture space. Use {@code (16, 16)} for vanilla block-model
     * faces (which always reference the full {@code [0, 16]} cross-section) or
     * {@code (textureWidth, textureHeight)} for entity-cube atlases where each face occupies a
     * variable-sized rectangle of the source image.
     * <p>
     * {@code faceRotationDegrees} must be a multiple of 90; any other value is treated as 0.
     * Rotation is applied as a forward cyclic shift of the corner array, matching vanilla's
     * {@code Quadrant}-based UV rotation. {@code mirror} reflects the U axis by swapping the
     * left and right corners before rotation, matching the classic MCBE cube {@code mirror}
     * flag.
     *
     * @param uScale the divisor applied to {@code x} / {@code z} to normalize U coordinates
     * @param vScale the divisor applied to {@code y} / {@code w} to normalize V coordinates
     * @param faceRotationDegrees the face rotation in degrees - must be a multiple of 90
     * @param mirror whether to reflect the U axis
     * @return an array of four {@link Vector2f} UV coordinates, ordered TL, BL, BR, TR
     */
    public @NotNull Vector2f @NotNull [] toUvCorners(float uScale, float vScale, int faceRotationDegrees, boolean mirror) {
        int normalizedAngle = ((faceRotationDegrees % 360) + 360) % 360;
        int quadrant = switch (normalizedAngle) {
            case 90 -> 1;
            case 180 -> 2;
            case 270 -> 3;
            default -> 0;
        };

        float uMin = (mirror ? this.z : this.x) / uScale;
        float uMax = (mirror ? this.x : this.z) / uScale;
        float vMin = this.y / vScale;
        float vMax = this.w / vScale;

        Vector2f[] map = new Vector2f[4];
        for (int i = 0; i < 4; i++) {
            int shifted = (i + quadrant) % 4;
            float u = (shifted < 2) ? uMin : uMax;
            float v = (shifted == 1 || shifted == 2) ? vMax : vMin;
            map[i] = new Vector2f(u, v);
        }
        return map;
    }

    /**
     * Returns the axis-aligned bounding rectangle of the supplied 2D points as
     * {@code (uMin, vMin, uMax, vMax)}.
     *
     * @param points the points to bound; must contain at least one element
     * @return the bounding rectangle
     */
    public static @NotNull Vector4f bounds(@NotNull Vector2f @NotNull ... points) {
        float uMin = Float.POSITIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        for (Vector2f p : points) {
            if (p.x() < uMin) uMin = p.x();
            if (p.x() > uMax) uMax = p.x();
            if (p.y() < vMin) vMin = p.y();
            if (p.y() > vMax) vMax = p.y();
        }
        return new Vector4f(uMin, vMin, uMax, vMax);
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
