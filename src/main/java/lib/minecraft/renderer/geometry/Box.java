package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * An immutable axis-aligned bounding box in any coordinate space.
 * <p>
 * The canonical min/max pair wherever a renderer needs to reason about a cube's extent - element
 * bounds from a block model's {@code from}/{@code to}, transformed-cube AABBs from the
 * {@code ToolingBlockEntities} parse chain, bone footprints in entity rendering, etc. Replaces
 * the ad-hoc 6-float tuples and {@code (Vector3f min, Vector3f max)} pairs that previously lived
 * in several places under different type names.
 *
 * @param minX the minimum X coordinate
 * @param minY the minimum Y coordinate
 * @param minZ the minimum Z coordinate
 * @param maxX the maximum X coordinate
 * @param maxY the maximum Y coordinate
 * @param maxZ the maximum Z coordinate
 */
public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    /**
     * Builds a {@code Box} from a min/max {@link Vector3f} pair.
     *
     * @param min the minimum corner
     * @param max the maximum corner
     * @return a new box spanning {@code min} to {@code max}
     */
    public static @NotNull Box of(@NotNull Vector3f min, @NotNull Vector3f max) {
        return new Box(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    /**
     * Builds a {@code Box} from two length-3 {@code [x, y, z]} float arrays - the shape used by
     * {@code ModelElement} and similar JSON-derived DTOs.
     *
     * @param from the minimum corner as {@code [x, y, z]}
     * @param to the maximum corner as {@code [x, y, z]}
     * @return a new box spanning {@code from} to {@code to}
     */
    public static @NotNull Box of(float @NotNull [] from, float @NotNull [] to) {
        return new Box(from[0], from[1], from[2], to[0], to[1], to[2]);
    }

    /**
     * Computes the tight AABB enclosing a set of 3D points given as {@code [x, y, z]} rows. Used
     * by the block-entity conversion pipeline, which transforms cube corners through bone +
     * inventory matrices and then needs the bounding box of the resulting eight points.
     *
     * @param points the points to enclose; each row must have at least 3 elements
     * @return the tight axis-aligned bounding box of the input points
     */
    public static @NotNull Box of(float @NotNull [] @NotNull [] points) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] p : points) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
            if (p[2] < minZ) minZ = p[2];
            if (p[2] > maxZ) maxZ = p[2];
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Computes the tight AABB enclosing a set of {@link Vector3f} points.
     *
     * @param points the points to enclose
     * @return the tight axis-aligned bounding box of the input points
     */
    public static @NotNull Box of(@NotNull Vector3f @NotNull [] points) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3f p : points) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
            if (p.z() < minZ) minZ = p.z();
            if (p.z() > maxZ) maxZ = p.z();
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

}
