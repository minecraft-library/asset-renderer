package lib.minecraft.renderer.engine.raster;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of {@link Rasterizer#rasterizeAll}, which draws several triangle lists, each through its own
 * model transform, in one depth pass: one part answers what the single-list overload answers for it,
 * two parts depth-test against each other whichever is drawn first - where two separate calls paint
 * the later over the earlier, each starting a depth buffer of its own - and a coplanar tie goes to the
 * later part.
 */
@DisplayName("Rasterizer draws several parts in one depth pass")
class RasterizerDrawsTest {

    /** The canvas edge every pass draws at. */
    private static final int SIZE = 64;

    /** The centre pixel, which both squares cover. */
    private static final int CENTRE = SIZE / 2;

    /** A square-on orthographic camera. */
    private static final @NotNull Camera CAMERA = Camera.identity(Lens.orthographic(0.5f));

    /** The half-width of the square every part draws. */
    private static final float HALF = 0.2f;

    /** The transform that sets a square nearer the camera, larger depth being nearer. */
    private static final @NotNull Matrix4f NEAR = Matrix4f.IDENTITY.translate(0.05f, 0f, 0.3f);

    /** The transform that sets a square farther from the camera, offset so both silhouettes show. */
    private static final @NotNull Matrix4f FAR = Matrix4f.IDENTITY.translate(-0.05f, 0f, -0.3f);

    @Test
    @DisplayName("one part draws exactly what the single-list overload draws")
    void onePartAnswersWhatRasterizeAnswers() {
        ConcurrentList<VisibleTriangle> square = square(0xFF336699);
        Matrix4f transform = Matrix4f.IDENTITY
            .translate(0.1f, -0.05f, 0f)
            .rotate(Quaternionf.rotationXYZ(0.4f, 0.7f, 0.1f))
            .scale(1.5f, 1.5f, 1.5f);

        PixelBuffer single = PixelBuffer.create(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(square, single, transform);

        assertThat(draw(List.of(new Rasterizer.Draw(square, transform))).data(), is(single.data()));
    }

    @Test
    @DisplayName("two parts depth-test against each other whichever is drawn first, where two calls paint the later over")
    void partsDepthTestAgainstEachOther() {
        ConcurrentList<VisibleTriangle> near = square(0xFFFF0000);
        ConcurrentList<VisibleTriangle> far = square(0xFF0000FF);
        PixelBuffer nearAlone = draw(List.of(new Rasterizer.Draw(near, NEAR)));
        PixelBuffer farAlone = draw(List.of(new Rasterizer.Draw(far, FAR)));
        assertThat("the two squares cover the centre in different colours",
            farAlone.getPixel(CENTRE, CENTRE), is(not(nearAlone.getPixel(CENTRE, CENTRE))));

        PixelBuffer nearFirst = draw(List.of(new Rasterizer.Draw(near, NEAR), new Rasterizer.Draw(far, FAR)));
        PixelBuffer farFirst = draw(List.of(new Rasterizer.Draw(far, FAR), new Rasterizer.Draw(near, NEAR)));
        assertThat(nearFirst.data(), is(farFirst.data()));
        assertThat(nearFirst.getPixel(CENTRE, CENTRE), is(nearAlone.getPixel(CENTRE, CENTRE)));

        PixelBuffer separate = PixelBuffer.create(SIZE, SIZE);
        Rasterizer engine = new Rasterizer(CAMERA);
        engine.rasterize(near, separate, NEAR);
        engine.rasterize(far, separate, FAR);
        assertThat("a second call paints over the first", separate.getPixel(CENTRE, CENTRE), is(farAlone.getPixel(CENTRE, CENTRE)));
    }

    @Test
    @DisplayName("a coplanar tie between parts goes to the later part")
    void aCoplanarTieGoesToTheLaterPart() {
        ConcurrentList<VisibleTriangle> red = square(0xFFFF0000);
        ConcurrentList<VisibleTriangle> blue = square(0xFF0000FF);

        PixelBuffer tied = draw(List.of(new Rasterizer.Draw(red, NEAR), new Rasterizer.Draw(blue, NEAR)));
        assertThat(tied.data(), is(draw(List.of(new Rasterizer.Draw(blue, NEAR))).data()));
    }

    /**
     * Draws parts into a fresh canvas through one pass.
     *
     * @param draws the parts, in draw order
     * @return the canvas
     */
    private static @NotNull PixelBuffer draw(@NotNull List<Rasterizer.Draw> draws) {
        PixelBuffer buffer = PixelBuffer.create(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterizeAll(draws, buffer);
        return buffer;
    }

    /**
     * Builds the square every part draws - two triangles across the origin in the {@code z = 0} plane,
     * one opaque colour, seen from either side.
     *
     * @param argb the colour
     * @return the square's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> square(int argb) {
        PixelBuffer texture = PixelBuffer.create(2, 2);
        texture.fill(argb);
        SurfaceTraits traits = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false);
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        Vector3f topLeft = new Vector3f(-HALF, HALF, 0f);
        Vector3f bottomLeft = new Vector3f(-HALF, -HALF, 0f);
        Vector3f bottomRight = new Vector3f(HALF, -HALF, 0f);
        Vector3f topRight = new Vector3f(HALF, HALF, 0f);
        return Concurrent.newUnmodifiableList(
            new VisibleTriangle(topLeft, bottomLeft, bottomRight,
                new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), texture, ColorMath.WHITE, normal, 1f, traits),
            new VisibleTriangle(topLeft, bottomRight, topRight,
                new Vector2f(0f, 0f), new Vector2f(1f, 1f), new Vector2f(1f, 0f), texture, ColorMath.WHITE, normal, 1f, traits));
    }

}
