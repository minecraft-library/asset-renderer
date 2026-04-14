package dev.sbs.renderer.model.asset;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A single face on a block or item model element. Matches the schema under
 * {@code elements[i].faces.<direction>} in vanilla block/item model JSON files.
 */
@Getter
@NoArgsConstructor
public class ModelFace {

    /** The texture variable reference, e.g. {@code "#top"} or {@code "#all"}. */
    private @NotNull String texture = "";

    /**
     * The UV rectangle in 0-16 space: {@code [u0, v0, u1, v1]}. Optional; when absent the face
     * inherits its UVs from its parent element's projection.
     */
    private @NotNull Optional<float[]> uv = Optional.empty();

    /** The face to cull against. Present for solid block faces that hide behind neighbors. */
    @SerializedName("cullface")
    private @NotNull Optional<String> cullface = Optional.empty();

    /**
     * The tint index used by biome-dependent tints (grass, foliage) or leather/potion overlays.
     * A value of {@code -1} means the face is untinted.
     */
    @SerializedName("tintindex")
    private int tintIndex = -1;

    /** The UV rotation in degrees, a multiple of 90. */
    private int rotation = 0;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModelFace that = (ModelFace) o;
        return tintIndex == that.tintIndex
            && rotation == that.rotation
            && Objects.equals(texture, that.texture)
            && Objects.equals(uv, that.uv)
            && Objects.equals(cullface, that.cullface);
    }

    @Override
    public int hashCode() {
        return Objects.hash(texture, uv, cullface, tintIndex, rotation);
    }

}
