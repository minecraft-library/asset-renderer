package lib.minecraft.renderer.tensor;

import org.jetbrains.annotations.NotNull;

/**
 * An immutable axis-aligned bounding box in any coordinate space.
 * <p>
 * The canonical min/max pair wherever a renderer needs to reason about a cube's extent - element
 * bounds from a block model's {@code from}/{@code to}, transformed-cube AABBs from the
 * {@code ToolingBlockModels} parse chain, bone footprints in entity rendering, etc. Replaces
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

    /**
     * The box one cube occupies: an origin and a size, grown on every side by a per-axis amount.
     * <p>
     * <b>The order of the three operands is vanilla's own and is not free to re-associate.</b>
     * {@code ModelPart$Cube}'s constructor computes each upper corner as {@code (origin + size) + grow}
     * from the <em>un-grown</em> origin, and each lower corner as {@code origin - grow}; float addition
     * does not associate, so growing the lower corner first and adding the growth back twice is a
     * different number in the last bits. Every cube box in the renderer - a worn shell's rows and the
     * entity bounds walk alike - is formed here so the two cannot drift apart.
     *
     * @param origin the cube's lower corner before growth
     * @param size the cube's extent before growth
     * @param grow the per-axis growth applied to every side
     * @return the grown cube box
     */
    public static @NotNull Box grown(
        @NotNull Vector3f origin, @NotNull Vector3f size, @NotNull Vector3f grow) {
        return new Box(
            origin.x() - grow.x(), origin.y() - grow.y(), origin.z() - grow.z(),
            origin.x() + size.x() + grow.x(),
            origin.y() + size.y() + grow.y(),
            origin.z() + size.z() + grow.z());
    }

    /**
     * The largest extent across all three axes - {@code max(maxX-minX, maxY-minY, maxZ-minZ)}.
     * <p>
     * Used by entity rendering to size the camera so the model fits regardless of which axis
     * happens to be the dominant one.
     */
    public float maxExtent() {
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    /**
     * This box grown by the same amount on every side - each minimum moved down by {@code amount} and
     * each maximum up by it, so the box gains {@code 2 * amount} of extent on each axis.
     * <p>
     * The one expansion primitive the renderer inflates a box through: a worn shell's layer growth, an
     * armour piece's clearance over the player's own skin geometry, and the hat overlay's clearance
     * over the head. Each caller keeps its own constant - the amounts are calibrated per frame and per
     * scope and are not interchangeable - and shares only the arithmetic.
     * <p>
     * Six unguarded adds, and deliberately so. Shrinking by more than half a span carries each minimum
     * past its own maximum, after which {@link #maxExtent()} answers a negative number and
     * {@link #union(Box)} mixes the inverted corners into whatever it is composed with. That is not
     * clamped, because an inverted box is legitimate input to this type rather than a corrupt one - a
     * block model's inner-faces cube authors {@code from > to} to reverse its winding, and
     * {@link #of(float[], float[])} builds it as authored. A clamp here would be a second, silent
     * convention for what an inverted box means. Every renderer caller passes a small positive
     * clearance; a caller that shrinks owns the check.
     *
     * @param amount the per-side growth, negative to shrink, unclamped in both directions
     * @return the grown box
     */
    public @NotNull Box expand(float amount) {
        return new Box(
            minX - amount, minY - amount, minZ - amount,
            maxX + amount, maxY + amount, maxZ + amount);
    }

    /**
     * The axis-aligned union of this box and another - the smallest box containing both.
     *
     * @param other the box to union with
     * @return the smallest box containing both
     */
    public @NotNull Box union(@NotNull Box other) {
        return new Box(
            Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

}
