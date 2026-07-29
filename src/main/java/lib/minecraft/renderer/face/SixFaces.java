package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * The six face textures of a cube in canonical {@link Face} declaration order. Replaces the
 * implicit {@code PixelBuffer[6]} convention that several call sites - cube renderers, skin
 * croppers, cape composition, leather/banner item slabs - shared without a shape to lean on.
 * <p>
 * Field names match {@link Face}'s direction names ({@code down}, {@code up}, {@code north},
 * {@code south}, {@code west}, {@code east}) so callers that already think in those terms can
 * build instances positionally and consumers can route through {@link #byFace} when the lookup
 * is driven by a face enum rather than a hard-coded direction.
 *
 * @param down the texture sampled by the {@link Face#DOWN} face
 * @param up the texture sampled by the {@link Face#UP} face
 * @param north the texture sampled by the {@link Face#NORTH} face
 * @param south the texture sampled by the {@link Face#SOUTH} face
 * @param west the texture sampled by the {@link Face#WEST} face
 * @param east the texture sampled by the {@link Face#EAST} face
 */
public record SixFaces(
    @NotNull PixelBuffer down,
    @NotNull PixelBuffer up,
    @NotNull PixelBuffer north,
    @NotNull PixelBuffer south,
    @NotNull PixelBuffer west,
    @NotNull PixelBuffer east
) {

    /**
     * Returns a {@code SixFaces} where every face shares the same texture - used by item slabs
     * (leather overlay, banner composite, flat sprite fallback) that paint a single composite
     * onto all six sides of a thin cube.
     *
     * @param all the texture to assign to every face
     * @return a {@code SixFaces} with all six fields pointing at {@code all}
     */
    public static @NotNull SixFaces uniform(@NotNull PixelBuffer all) {
        return new SixFaces(all, all, all, all, all, all);
    }

    /**
     * Returns the face texture for the given {@link Face} direction.
     *
     * @param face the face direction
     * @return the texture assigned to that face
     */
    public @NotNull PixelBuffer byFace(@NotNull Face face) {
        return switch (face) {
            case DOWN -> this.down;
            case UP -> this.up;
            case NORTH -> this.north;
            case SOUTH -> this.south;
            case WEST -> this.west;
            case EAST -> this.east;
        };
    }

}
