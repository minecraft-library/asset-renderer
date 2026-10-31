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

import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Holds a depth buffer a thread kept from an earlier frame to drawing what a new one draws. A nearer
 * square drawn first leaves its depths in the buffer, so a farther square drawn next over a kept
 * buffer the frame did not clear would lose every pixel the nearer one covered. A small frame drawn on
 * the calling thread and a tall frame whose bands run on pool workers are each held to the same frame
 * drawn by threads that have kept nothing.
 */
@DisplayName("A kept depth buffer draws what a new one draws")
class RasterizerDepthReuseTest {

    /** A square-on orthographic camera. */
    private static final @NotNull Camera CAMERA = Camera.identity(Lens.orthographic(0.5f));

    /** The transform that sets a square nearer the camera, larger depth being nearer. */
    private static final @NotNull Matrix4f NEAR = Matrix4f.IDENTITY.translate(0.05f, 0f, 0.3f);

    /** The transform that sets a square farther from the camera. */
    private static final @NotNull Matrix4f FAR = Matrix4f.IDENTITY.translate(-0.05f, 0f, -0.3f);

    @Test
    @DisplayName("a frame drawn on the calling thread after a nearer one draws as on a thread that drew nothing")
    void aSmallFrameAfterANearerOneDrawsAsAlone() throws Exception {
        PixelBuffer alone = onFreshThread(() -> draw(64, FAR));

        draw(64, NEAR);
        PixelBuffer after = draw(64, FAR);
        assertThat("the far square covers the centre", ColorMath.alpha(after.getPixel(32, 32)), is(0xFF));
        assertThat(after.data(), is(alone.data()));

        draw(64, NEAR);
        PixelBuffer smaller = draw(40, FAR);
        assertThat("a smaller frame reusing a longer buffer", smaller.data(), is(onFreshThread(() -> draw(40, FAR)).data()));
    }

    @Test
    @DisplayName("a tiled frame drawn after a nearer one draws as on pool workers that drew nothing")
    void aTiledFrameAfterANearerOneDrawsAsAlone() throws Exception {
        PixelBuffer alone = onFreshPool(() -> draw(320, FAR));
        assertThat("the far square covers the centre", ColorMath.alpha(alone.getPixel(160, 160)), is(0xFF));
        assertThat("and differs from the near one there", alone.getPixel(160, 160), is(not(draw(320, NEAR).getPixel(160, 160))));

        draw(320, NEAR);
        assertThat(draw(320, FAR).data(), is(alone.data()));
    }

    /**
     * Draws the square into a fresh canvas.
     *
     * @param size the canvas edge
     * @param transform where the square sits
     * @return the canvas
     */
    private static @NotNull PixelBuffer draw(int size, @NotNull Matrix4f transform) {
        PixelBuffer buffer = PixelBuffer.canvas(size, size);
        new Rasterizer(CAMERA).rasterize(square(transform == NEAR ? 0xFFFF0000 : 0xFF0000FF), buffer, transform);
        return buffer;
    }

    /**
     * Draws on a new thread, which has kept no depth buffer.
     *
     * @param drawing the draw
     * @return what it drew
     */
    private static @NotNull PixelBuffer onFreshThread(@NotNull Callable<PixelBuffer> drawing) throws Exception {
        PixelBuffer[] drawn = new PixelBuffer[1];
        Exception[] failed = new Exception[1];
        Thread thread = new Thread(() -> {
            try {
                drawn[0] = drawing.call();
            } catch (Exception exception) {
                failed[0] = exception;
            }
        });
        thread.start();
        thread.join();
        if (failed[0] != null) throw failed[0];
        return drawn[0];
    }

    /**
     * Draws inside a new pool, whose workers have kept no depth buffer and run every band.
     *
     * @param drawing the draw
     * @return what it drew
     */
    private static @NotNull PixelBuffer onFreshPool(@NotNull Callable<PixelBuffer> drawing) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(4);
        try {
            return pool.submit(drawing).get();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Builds a square of one opaque colour - two triangles across the origin in the {@code z = 0}
     * plane, seen from either side.
     *
     * @param argb the colour
     * @return the square's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> square(int argb) {
        PixelBuffer texture = PixelBuffer.straight(2, 2);
        texture.fill(argb);
        SurfaceTraits traits = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false);
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        Vector3f topLeft = new Vector3f(-0.2f, 0.2f, 0f);
        Vector3f bottomLeft = new Vector3f(-0.2f, -0.2f, 0f);
        Vector3f bottomRight = new Vector3f(0.2f, -0.2f, 0f);
        Vector3f topRight = new Vector3f(0.2f, 0.2f, 0f);
        return Concurrent.newUnmodifiableList(
            new VisibleTriangle(topLeft, bottomLeft, bottomRight,
                new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), texture, ColorMath.WHITE, normal, 1f, traits),
            new VisibleTriangle(topLeft, bottomRight, topRight,
                new Vector2f(0f, 0f), new Vector2f(1f, 1f), new Vector2f(1f, 0f), texture, ColorMath.WHITE, normal, 1f, traits));
    }

}
