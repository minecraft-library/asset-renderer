package dev.sbs.renderer.asset.model;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A single axis-aligned box element within a block or item model. Matches the schema under
 * {@code elements[i]} in vanilla block/item model JSON files.
 * <p>
 * The six faces are indexed by the vanilla direction strings ({@code down}, {@code up},
 * {@code north}, {@code south}, {@code west}, {@code east}) - faces map entries that are missing
 * from the source JSON represent culled or invisible faces.
 */
@Getter
@NoArgsConstructor
public class ModelElement {

    /** The minimum corner in 0-16 space {@code [x, y, z]}. */
    private float @NotNull [] from = new float[]{ 0f, 0f, 0f };

    /** The maximum corner in 0-16 space {@code [x, y, z]}. */
    private float @NotNull [] to = new float[]{ 16f, 16f, 16f };

    /** The faces keyed by direction: {@code down}, {@code up}, {@code north}, {@code south}, {@code west}, {@code east}. */
    private @NotNull ConcurrentMap<String, ModelFace> faces = Concurrent.newMap();

    /** An optional element-level rotation, matching vanilla's {@code rotation} object. */
    private @NotNull Optional<ElementRotation> rotation = Optional.empty();

    /** Whether the element casts shadow. Defaults to {@code true} in vanilla. */
    private boolean shade = true;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModelElement that = (ModelElement) o;
        return shade == that.shade
            && java.util.Arrays.equals(from, that.from)
            && java.util.Arrays.equals(to, that.to)
            && Objects.equals(faces, that.faces)
            && Objects.equals(rotation, that.rotation);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(faces, rotation, shade);
        result = 31 * result + java.util.Arrays.hashCode(from);
        result = 31 * result + java.util.Arrays.hashCode(to);
        return result;
    }

    /**
     * Element-level rotation applied before the face UV projection. Matches vanilla's
     * {@code elements[i].rotation} schema.
     *
     * @param origin the rotation pivot in 0-16 space
     * @param axis the rotation axis: {@code "x"}, {@code "y"}, or {@code "z"}
     * @param angle the rotation angle in degrees: one of {@code -45}, {@code -22.5}, {@code 0}, {@code 22.5}, {@code 45}
     * @param rescale whether to rescale the element to preserve its footprint after rotation
     */
    public record ElementRotation(
        float @NotNull [] origin,
        @NotNull String axis,
        float angle,
        boolean rescale
    ) {

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            ElementRotation that = (ElementRotation) o;
            return Float.compare(angle, that.angle) == 0
                && rescale == that.rescale
                && Objects.equals(axis, that.axis)
                && java.util.Arrays.equals(origin, that.origin);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(axis, angle, rescale);
            result = 31 * result + java.util.Arrays.hashCode(origin);
            return result;
        }

    }

}
