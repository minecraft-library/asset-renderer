package lib.minecraft.renderer.request;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.camera.Camera;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * An immutable Euler-angle triple carrying rotations about the X, Y, and Z axes. Values are
 * always in <b>degrees</b> to match how vanilla Minecraft authors {@code display.*}
 * transforms and how every engine method ({@link ModelEngine#rasterize} /
 * {@link Camera#withGuiPose}) already documents its inputs.
 * <p>
 * The record is deliberately data-only: it carries the three angles and nothing else.
 * Two distinct rotation-composition orders coexist in the codebase - vanilla {@code display}
 * transforms compose as {@code Rz · Ry · Rx} while user-supplied post-rotation composes as
 * {@code Ry · Rx · Rz} - so matrix building stays at each call site where the semantics
 * are intentional. Treat this record like {@link Projection}: a labelled bundle of
 * floats plus a small set of named constants, no behaviour.
 * <p>
 * Rotation directions follow the right-hand rule:
 * <ul>
 *   <li>{@code +pitch} (X-axis): {@code +Y} rotates toward {@code +Z}</li>
 *   <li>{@code +yaw} (Y-axis): {@code +Z} rotates toward {@code +X}</li>
 *   <li>{@code +roll} (Z-axis): {@code +X} rotates toward {@code +Y}</li>
 * </ul>
 * <p>
 * The diagram below illustrates each positive rotation arc on the renderer's standard
 * {@link #STANDARD_ISO_BLOCK} pose. Arcs are drawn for visual clarity rather than 3D-projection
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
     * Vanilla Minecraft's standard block inventory-icon pose: {@code [30, 225, 0]} pitch/yaw/roll.
     * Matches the {@code display.gui} transform baked into the root {@code block/block.json}
     * model and is the default camera used by {@link Camera#forBlockIcon} when a block
     * model does not override its own GUI pose.
     */
    public static final @NotNull EulerRotation STANDARD_ISO_BLOCK = new EulerRotation(30f, 225f, 0f);

    /**
     * Vanilla Minecraft's standard entity inventory-preview pose: {@code [210, 45, 0]}
     * pitch/yaw/roll. Matches {@code vanilla-reference-harness}'s
     * {@code EntityFrameRenderer.ISO_ROTATION = new Quaternionf().rotationXYZ(210°, 45°, 0°)},
     * which is itself derived from the empirical 24-step yaw + 576-frame pitch/roll sweep that
     * locked vanilla's entity-preview pipeline camera. NOT equivalent to
     * {@link #STANDARD_ISO_BLOCK} - they differ by a yaw mirror + transpose-like permutation, so
     * entity rendering must use this constant rather than borrowing the block-icon pose.
     */
    public static final @NotNull EulerRotation STANDARD_ISO_ENTITY = new EulerRotation(210f, 45f, 0f);

    /**
     * The front-facing humanoid GUI pose: {@code [30, 45, 0]} pitch/yaw/roll. Shares the
     * {@link #STANDARD_ISO_BLOCK block-icon} pose's pitch and roll but flips the yaw by 180&deg; so
     * a player model's front (the {@code +Z} {@code SOUTH} face) turns toward the camera. The
     * block-icon pose presents the model's {@code -Z} side, which on a humanoid is its back, so a
     * player render needs this pose rather than borrowing {@link #STANDARD_ISO_BLOCK}.
     */
    public static final @NotNull EulerRotation STANDARD_ISO_PLAYER = new EulerRotation(30f, 45f, 0f);

    /**
     * The rotation about the ({@code X}-axis angle), in radians.
     */
    public float pitchRadians() {
        return toRadians(this.pitch);
    }

    /**
     * The rotation about the ({@code Y}-axis angle), in radians.
     */
    public float yawRadians() {
        return toRadians(this.yaw);
    }

    /**
     * The rotation about the ({@code Z}-axis angle), in radians.
     */
    public float rollRadians() {
        return toRadians(this.roll);
    }

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
