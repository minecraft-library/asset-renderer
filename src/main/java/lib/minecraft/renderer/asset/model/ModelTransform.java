package lib.minecraft.renderer.asset.model;

import com.google.gson.annotations.JsonAdapter;
import lib.minecraft.renderer.geometry.EulerRotation;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single display transform entry parsed from the {@code display} section of an item or block
 * model JSON. Each transform is applied in the order translation, then rotation (XYZ Euler), then
 * scale, matching the vanilla Minecraft convention.
 * <p>
 * Vanilla stores each property as a three-element JSON array keyed {@code rotation},
 * {@code translation}, and {@code scale}. The array indices correspond to the X, Y, and Z
 * components in that order.
 */
@NoArgsConstructor
public class ModelTransform {

    /** The identity transform - zero rotation, zero translation, unit scale. */
    public static final @NotNull ModelTransform IDENTITY = new ModelTransform(
        EulerRotation.NONE,
        new float[]{ 0f, 0f, 0f },
        new float[]{ 1f, 1f, 1f }
    );

    @JsonAdapter(EulerRotation.Adapter.class)
    private @NotNull EulerRotation rotation = EulerRotation.NONE;
    private float @NotNull [] translation = { 0f, 0f, 0f };
    private float @NotNull [] scale = { 1f, 1f, 1f };

    public ModelTransform(@NotNull EulerRotation rotation, float @NotNull [] translation, float @NotNull [] scale) {
        this.rotation = rotation;
        this.translation = translation;
        this.scale = scale;
    }

    /** The Euler-angle rotation in degrees, applied about X/Y/Z in that order. */
    public @NotNull EulerRotation getRotation() { return this.rotation; }

    public float getTranslationX() { return this.translation[0]; }

    public float getTranslationY() { return this.translation[1]; }

    public float getTranslationZ() { return this.translation[2]; }

    public float getScaleX() { return this.scale[0]; }

    public float getScaleY() { return this.scale[1]; }

    public float getScaleZ() { return this.scale[2]; }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModelTransform that = (ModelTransform) o;
        return Objects.equals(rotation, that.rotation)
            && Arrays.equals(translation, that.translation)
            && Arrays.equals(scale, that.scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rotation, Arrays.hashCode(translation), Arrays.hashCode(scale));
    }

}
