package lib.minecraft.renderer.engine.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import org.jetbrains.annotations.NotNull;

/**
 * The cube and the inventory picture an id neither index carries draws.
 * <p>
 * {@link #cube()} is a full unit cube wearing {@link MissingSprite#sprite()} on all six faces at the
 * whole UV rectangle, untinted - one missing shape for a block, a held item and a block part alike,
 * there being nothing about the subject left to distinguish them by. {@link #icon(int)} is that same
 * sprite filling a square canvas by nearest sampling, which is what one face of the cube shows a slot
 * looking at it square-on.
 * <p>
 * A texture miss never reaches here. A model that resolves keeps its own geometry and substitutes only
 * the texels of the face that failed, so the cube stands in for a subject nothing resolved for at all.
 */
@UtilityClass
public class MissingMesh {

    /**
     * Builds the missing-model cube - twelve triangles over the unit box, the checkerboard on every
     * face at the full UV rectangle, untinted.
     *
     * @return the cube's triangles, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> cube() {
        return BoxKit.unitCube(FaceTextures.uniform(MissingSprite.sprite()), ColorMath.WHITE);
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
        canvas.blitScaled(MissingSprite.sprite(), 0, 0, canvasSize, canvasSize);
        return canvas;
    }

}
