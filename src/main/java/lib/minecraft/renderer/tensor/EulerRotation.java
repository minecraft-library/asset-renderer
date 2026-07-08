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
 * An immutable Euler-angle triple carrying rotations about the X, Y, and Z axes. Values are
 * always in <b>degrees</b> to match how vanilla Minecraft authors {@code display.*}
 * transforms and how every engine method already documents its inputs.
 * <p>
 * The record is deliberately data-only: it carries the three angles and nothing else. Distinct
 * rotation-composition orders coexist in the codebase, so matrix building stays at each call site
 * where the semantics are intentional rather than being baked in here. For example, vanilla
 * {@code display.*} GUI poses build via JOML's {@code rotationXYZ(pitch, yaw, roll)} - which applies
 * roll first, then yaw, then pitch to a vector - while other sites chain per-axis matrices in their
 * own order. Treat this record as a labelled bundle of floats plus a small set of named constants,
 * no behaviour.
 * <p>
 * Rotation directions follow the right-hand rule:
 * <ul>
 *   <li>{@code +pitch} (X-axis): {@code +Y} rotates toward {@code +Z}</li>
 *   <li>{@code +yaw} (Y-axis): {@code +Z} rotates toward {@code +X}</li>
 *   <li>{@code +roll} (Z-axis): {@code +X} rotates toward {@code +Y}</li>
 * </ul>
 * <p>
 * The diagram below illustrates each positive rotation arc on the renderer's standard
 * {@code [30, 225, 0]} block-icon pose. Arcs are drawn for visual clarity rather than 3D-projection
 * accuracy; the arc's plane is screen-perpendicular to its rotation axis.
 * <p>
 * <img src="doc-files/euler_reference.svg" alt="Euler rotation reference diagram" width="535"/>
 *
 * @param pitch the rotation about the X axis, in degrees
 * @param yaw the rotation about the Y axis, in degrees
 * @param roll the rotation about the Z axis, in degrees
 */
public record EulerRotation(float pitch, float yaw, float roll) {

    /**
     * The identity rotation - all three Euler angles set to zero degrees.
     */
    public static final @NotNull EulerRotation NONE = new EulerRotation(0f, 0f, 0f);

    /**
     * Converts the {@link #pitch} (X-axis) angle to radians.
     *
     * @return the pitch in radians
     */
    public float pitchRadians() {
        return toRadians(this.pitch);
    }

    /**
     * Converts the {@link #yaw} (Y-axis) angle to radians.
     *
     * @return the yaw in radians
     */
    public float yawRadians() {
        return toRadians(this.yaw);
    }

    /**
     * Converts the {@link #roll} (Z-axis) angle to radians.
     *
     * @return the roll in radians
     */
    public float rollRadians() {
        return toRadians(this.roll);
    }

    /**
     * Converts an angle from degrees to radians, narrowed back to {@code float}.
     *
     * @param value the angle in degrees
     * @return the angle in radians
     */
    private static float toRadians(float value) {
        return (float) Math.toRadians(value);
    }

    /**
     * Gson adapter that serializes an {@link EulerRotation} as a three-element JSON array
     * {@code [pitch, yaw, roll]} (X/Y/Z) and deserializes from the same format - matching
     * vanilla's {@code display.*.rotation}, entity bone {@code rotation}, and every other
     * three-element Euler-angle array in the model JSON schema.
     */
    @NoArgsConstructor
    public static final class Adapter extends TypeAdapter<EulerRotation> {

        /** {@inheritDoc} */
        @Override
        public void write(@NotNull JsonWriter out, @Nullable EulerRotation value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginArray();
            out.value(value.pitch());
            out.value(value.yaw());
            out.value(value.roll());
            out.endArray();
        }

        /** {@inheritDoc} */
        @Override
        public @Nullable EulerRotation read(@NotNull JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            in.beginArray();
            float pitch = (float) in.nextDouble();
            float yaw = (float) in.nextDouble();
            float roll = (float) in.nextDouble();
            in.endArray();

            return new EulerRotation(pitch, yaw, roll);
        }

    }

}
