package dev.sbs.renderer.model.asset;

import dev.simplified.persistence.type.GsonType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A single display transform entry parsed from the {@code display} section of an item or block
 * model JSON. Each transform is applied in the order translation, then rotation (XYZ Euler), then
 * scale, matching the vanilla Minecraft convention.
 */
@Getter
@GsonType
@NoArgsConstructor
@AllArgsConstructor
public class ModelTransform {

    /** The identity transform - zero rotation, zero translation, unit scale. */
    public static final @NotNull ModelTransform IDENTITY = new ModelTransform(
        0f, 0f, 0f,
        0f, 0f, 0f,
        1f, 1f, 1f
    );

    private float rotationX;
    private float rotationY;
    private float rotationZ;
    private float translationX;
    private float translationY;
    private float translationZ;
    private float scaleX;
    private float scaleY;
    private float scaleZ;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModelTransform that = (ModelTransform) o;
        return Float.compare(rotationX, that.rotationX) == 0
            && Float.compare(rotationY, that.rotationY) == 0
            && Float.compare(rotationZ, that.rotationZ) == 0
            && Float.compare(translationX, that.translationX) == 0
            && Float.compare(translationY, that.translationY) == 0
            && Float.compare(translationZ, that.translationZ) == 0
            && Float.compare(scaleX, that.scaleX) == 0
            && Float.compare(scaleY, that.scaleY) == 0
            && Float.compare(scaleZ, that.scaleZ) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rotationX, rotationY, rotationZ, translationX, translationY, translationZ, scaleX, scaleY, scaleZ);
    }

}
