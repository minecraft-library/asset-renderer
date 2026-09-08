package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.MissingTexture;
import lib.minecraft.renderer.face.FaceTextures;
import org.jetbrains.annotations.NotNull;

/**
 * The cube and the inventory picture an id neither index carries draws.
 * <p>
 * {@link #cube()} is a full unit cube wearing {@link MissingTexture#sprite()} on all six faces at the
 * whole UV rectangle, untinted - one missing shape for a block, a held item and a block part alike,
 * there being nothing about the subject left to distinguish them by. {@link #icon(int)} is that same
 * sprite filling a square canvas by nearest sampling, which is what one face of the cube shows a slot
 * looking at it square-on.
 * <p>
 * A texture miss never reaches here. A model that resolves keeps its own geometry and substitutes only
 * the texels of the face that failed, so the cube stands in for a subject nothing resolved for at all.
 */
@UtilityClass
public class MissingModelKit {

    /** The ids already reported, so one unresolved subject logs once rather than once per render. */
    private static final @NotNull ConcurrentSet<String> REPORTED = Concurrent.newSet();

    /**
     * Builds the missing-model cube - twelve triangles over the unit box, the checkerboard on every
     * face at the full UV rectangle, untinted.
     *
     * @return the cube's triangles, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> cube() {
        return GeometryKit.unitCube(FaceTextures.uniform(MissingTexture.sprite()), ColorMath.WHITE);
    }

    /**
     * Builds the inventory picture on a square canvas, the checkerboard scaled by nearest sampling so
     * each quadrant stays a hard-edged block of one colour.
     *
     * @param canvasSize the edge length of the square canvas
     * @return a fresh canvas carrying the upscaled checkerboard
     */
    public static @NotNull PixelBuffer icon(int canvasSize) {
        PixelBuffer canvas = PixelBuffer.create(canvasSize, canvasSize);
        canvas.blitScaled(MissingTexture.sprite(), 0, 0, canvasSize, canvasSize);
        return canvas;
    }

    /**
     * Reports a subject id nothing resolved for, the first time it is seen.
     *
     * @param subjectId the block or item id neither index carries
     */
    public static void reportSubstitution(@NotNull String subjectId) {
        if (REPORTED.add(subjectId))
            System.err.printf("Missing model for '%s' - drawing the missing-model cube%n", subjectId);
    }

}
