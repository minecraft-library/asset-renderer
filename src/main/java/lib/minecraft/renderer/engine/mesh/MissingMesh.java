package lib.minecraft.renderer.engine.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import org.jetbrains.annotations.NotNull;

/**
 * The cube, the flat face and the inventory picture an id neither index carries draws.
 * <p>
 * {@link #cube()} is a full unit cube wearing {@link MissingSprite#sprite()} on all six faces at the
 * whole UV rectangle, untinted - one missing shape for a block, a held item and a block part alike,
 * there being nothing about the subject left to distinguish them by. {@link #face(int)} is that same
 * sprite filling a square canvas by nearest sampling, unlit, as a single face is drawn flat.
 * {@link #icon(int)} is what one face of the cube shows a slot looking at it square-on, which is the face
 * lit as the slot lights it. The missing model names no {@code gui_light}, so a slot draws it under
 * {@code Lighting.ITEMS_3D}, and the face pointing at the viewer takes {@link Shading#ITEMS_3D_FACING}:
 * the checkerboard's magenta {@code (248, 0, 248)} reaches the slot as {@code (127, 0, 127)}, which is
 * what the client draws for an item whose definition it cannot bake.
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
     * Builds the flat face on a square canvas, the checkerboard scaled by nearest sampling so each
     * quadrant stays a hard-edged block of one colour, and unlit.
     *
     * @param canvasSize the edge length of the square canvas
     * @return a fresh canvas carrying the upscaled checkerboard
     */
    public static @NotNull PixelBuffer face(int canvasSize) {
        return square(MissingSprite.sprite(), canvasSize);
    }

    /**
     * Builds the inventory picture on a square canvas: the checkerboard shaded as the slot's
     * {@code ITEMS_3D} light shades the cube's facing side, then scaled as {@link #face(int)} scales it.
     *
     * @param canvasSize the edge length of the square canvas
     * @return a fresh canvas carrying the shaded, upscaled checkerboard
     */
    public static @NotNull PixelBuffer icon(int canvasSize) {
        return square(Shading.apply(MissingSprite.sprite(), ColorMath.WHITE, Shading.ITEMS_3D_FACING), canvasSize);
    }

    /** A sprite filling a fresh square canvas by nearest sampling. */
    private static @NotNull PixelBuffer square(@NotNull PixelBuffer sprite, int canvasSize) {
        PixelBuffer canvas = PixelBuffer.create(canvasSize, canvasSize);
        canvas.blitScaled(sprite, 0, 0, canvasSize, canvasSize);
        return canvas;
    }

}
