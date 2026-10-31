package lib.minecraft.renderer.engine.raster;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * Holds raster depth to the triangle coverage tests. Coverage reads each corner rounded to the
 * {@code 1/256} grid, and depth reads the plane through those same snapped corners, so two faces hinged
 * on one edge meet in depth on the edge both cover up to, and the face in front holds every sample both
 * cover. That includes a sample between the snapped edge and the line the corners draw before the snap,
 * where a plane through the unsnapped corners, read past its own edge, puts the face behind in front.
 * Two coplanar faces with different corners are each tilted by their own corners' snap, so the snap
 * rather than draw order decides between them; two that share their corners still tie, which
 * {@link RasterizerDrawsTest} holds.
 *
 * <p>The canvas is drawn at one pixel per model unit, so a corner at screen position {@code (sx, sy)}
 * is the model point {@code (sx - 32, 32 - sy)}, and every position below is exact in {@code float}.
 */
@DisplayName("Raster depth reads the plane through the snapped corners coverage reads")
class RasterizerDepthPlaneTest {

    /** The canvas edge. */
    private static final int SIZE = 64;

    /** A square-on orthographic camera at one pixel per model unit. */
    private static final @NotNull Camera CAMERA = Camera.identity(Lens.orthographic(1f / SIZE));

    /** Opaque two-sided surface traits, so a face wound away from the camera still draws. */
    private static final @NotNull SurfaceTraits TWO_SIDED = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false);

    /**
     * How far left of a grid point an off-grid corner sits, in pixels - {@code 15/8192}, under the half
     * step of {@code 1/512}, so the snap carries the corner right by exactly this much.
     */
    private static final float OFF_GRID = 15f / 8192f;

    /** The colour of the face the snapped corners put nearer. */
    private static final int NEARER = 0xFFFF0000;

    /** The colour of the face they put farther. */
    private static final int FARTHER = 0xFF0000FF;

    /** The screen X of the fold's upper shared corner, on the grid. */
    private static final float TOP_X = 30f;

    /** The screen Y of the fold's upper shared corner, on the grid. */
    private static final float TOP_Y = 10f;

    /** The screen X of the fold's lower shared corner, on the grid. */
    private static final float BOTTOM_X = 44.859375f;

    /** The screen Y of the fold's lower shared corner, on the grid. */
    private static final float BOTTOM_Y = 50f;

    /** The column of the pixel the fold is read at, whose centre lies within a step of the shared edge. */
    private static final int FOLD_X = 36;

    /** The row of the pixel the fold is read at. */
    private static final int FOLD_Y = 27;

    @Test
    @DisplayName("a sample both faces of a fold cover goes to the face in front, whichever is drawn first")
    void aFoldsSharedEdgeGoesToTheFaceInFront() {
        // Two faces hinged on one edge, both covering the side of it the third corners lie on: the face
        // in front rises toward the camera away from the edge and the face behind falls away from it.
        // Both shared corners sit OFF_GRID left of the grid, so the snap carries the edge right and the
        // sample's centre falls between the edge before the snap and the edge after it.
        float sampleX = FOLD_X + 0.5f;
        float sampleY = FOLD_Y + 0.5f;
        assertThat("the sample lies inside the shared edge the snapped corners draw",
            side(TOP_X, BOTTOM_X, sampleX, sampleY), is(greaterThan(0d)));
        assertThat("and outside the one the corners draw before the snap",
            side(TOP_X - OFF_GRID, BOTTOM_X - OFF_GRID, sampleX, sampleY), is(lessThan(0d)));

        Vector3f top = corner(TOP_X - OFF_GRID, TOP_Y, 100f);
        Vector3f bottom = corner(BOTTOM_X - OFF_GRID, BOTTOM_Y, 100f);
        VisibleTriangle inFront = triangle(top, bottom, corner(12f, 36f, 140f), NEARER);
        VisibleTriangle behind = triangle(bottom, top, corner(16f, 24f, 60f), FARTHER);

        int inFrontAlone = draw(inFront).getPixel(FOLD_X, FOLD_Y);
        int behindAlone = draw(behind).getPixel(FOLD_X, FOLD_Y);
        assertThat("the face in front covers the sample", ColorMath.alpha(inFrontAlone), is(0xFF));
        assertThat("the face behind covers it too", ColorMath.alpha(behindAlone), is(0xFF));
        assertThat("the two faces draw in different colours", inFrontAlone, is(not(behindAlone)));

        assertThat("drawn after the face behind, the face in front holds the sample",
            draw(behind, inFront).getPixel(FOLD_X, FOLD_Y), is(inFrontAlone));
        assertThat("drawn before the face behind, the face in front holds the sample",
            draw(inFront, behind).getPixel(FOLD_X, FOLD_Y), is(inFrontAlone));
    }

    @Test
    @DisplayName("two coplanar faces with different corners are decided by their snapped corners, whichever is drawn first")
    void coplanarFacesWithDifferentCornersAreDecidedByTheSnap() {
        // One plane, its depth rising four units a pixel to the right. The first face's corners sit on
        // the grid, so it reads the plane itself. Every corner of the second sits OFF_GRID left of the
        // grid, so the snap carries its plane OFF_GRID right, and at any sample it reads 4 * OFF_GRID
        // farther - over a hundred steps of the window grid depth is compared on.
        VisibleTriangle onGrid = triangle(onPlane(12f, 50f), onPlane(52f, 44f), onPlane(16f, 8f), NEARER);
        VisibleTriangle offGrid = triangle(
            onPlane(10f - OFF_GRID, 10f), onPlane(54f - OFF_GRID, 14f), onPlane(22f - OFF_GRID, 54f), FARTHER);

        int onGridAlone = draw(onGrid).getPixel(32, 30);
        int offGridAlone = draw(offGrid).getPixel(32, 30);
        assertThat("the face on the grid covers the sample", ColorMath.alpha(onGridAlone), is(0xFF));
        assertThat("the face off it covers it too", ColorMath.alpha(offGridAlone), is(0xFF));
        assertThat("the two faces draw in different colours", onGridAlone, is(not(offGridAlone)));

        assertThat("drawn after the face off the grid, the face on it holds the sample",
            draw(offGrid, onGrid).getPixel(32, 30), is(onGridAlone));
        assertThat("drawn before the face off the grid, the face on it holds the sample",
            draw(onGrid, offGrid).getPixel(32, 30), is(onGridAlone));
    }

    /**
     * Draws triangles into a fresh canvas through one depth pass.
     *
     * @param triangles the triangles, in draw order
     * @return the canvas
     */
    private static @NotNull PixelBuffer draw(@NotNull VisibleTriangle... triangles) {
        ConcurrentList<VisibleTriangle> list = Concurrent.newList();
        for (VisibleTriangle triangle : triangles)
            list.add(triangle);

        PixelBuffer buffer = PixelBuffer.canvas(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(list, buffer, Matrix4f.IDENTITY);
        return buffer;
    }

    /**
     * Returns which side of the edge from the fold's upper shared corner to its lower one a point lies
     * on, the corners at the given screen X and their rows fixed - positive on the side the fold's faces
     * cover. Every input is a short binary fraction, so the products are exact in {@code double}.
     *
     * @param topX the screen X of the upper shared corner
     * @param bottomX the screen X of the lower shared corner
     * @param x the point's screen X
     * @param y the point's screen Y
     * @return the edge function's value at the point
     */
    private static double side(float topX, float bottomX, float x, float y) {
        return ((double) bottomX - topX) * ((double) y - TOP_Y) - ((double) BOTTOM_Y - TOP_Y) * ((double) x - topX);
    }

    /**
     * Places a corner at a screen position and depth.
     *
     * @param screenX the screen X
     * @param screenY the screen Y
     * @param depth the camera-space depth, larger being nearer
     * @return the model-space corner
     */
    private static @NotNull Vector3f corner(float screenX, float screenY, float depth) {
        return new Vector3f(screenX - SIZE / 2f, SIZE / 2f - screenY, depth);
    }

    /**
     * Places a corner on the plane whose depth is {@code 100} at the canvas centre and rises four units
     * a pixel to the right, exactly in {@code float} for a screen X a short binary fraction.
     *
     * @param screenX the screen X
     * @param screenY the screen Y
     * @return the model-space corner
     */
    private static @NotNull Vector3f onPlane(float screenX, float screenY) {
        return corner(screenX, screenY, 100f + 4f * (screenX - SIZE / 2f));
    }

    /**
     * Builds a two-sided triangle of one opaque colour.
     *
     * @param a the first corner
     * @param b the second corner
     * @param c the third corner
     * @param argb the colour
     * @return the triangle
     */
    private static @NotNull VisibleTriangle triangle(@NotNull Vector3f a, @NotNull Vector3f b, @NotNull Vector3f c, int argb) {
        PixelBuffer texture = PixelBuffer.straight(2, 2);
        texture.fill(argb);
        return new VisibleTriangle(a, b, c,
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f),
            texture, ColorMath.WHITE, new Vector3f(0f, 0f, 1f), 1f, TWO_SIDED);
    }

}
