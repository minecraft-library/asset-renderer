package lib.minecraft.renderer.engine.raster;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.DrawPart;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Coverage of {@link Rasterizer#rasterizeAll}, which draws several triangle lists, each through its own
 * model transform, in one depth pass: one part answers what the single-list overload answers for it,
 * two parts depth-test against each other whichever is drawn first - where two separate calls paint
 * the later over the earlier, each starting a depth buffer of its own - and a coplanar tie goes to the
 * later part.
 *
 * <p>It also holds a frame drawn as one band on the calling thread to the same frame drawn in parallel
 * bands, at band counts fixed by the test rather than read from the host's processor count. A frame
 * that fits the depth buffer a thread keeps takes either arm by the area its triangles cover, so the
 * arm a frame takes depends on its content, and nothing else would see the two drift apart.
 *
 * <p>No pixel shows how a frame is split, since one band and any number of bands draw the same bytes,
 * so the split is held to its own answers as well: the processor figure a tall frame's band count is
 * read from, the rows each band covers for a height and a processor count, and the arm a frame takes
 * for its size and the area its triangles cover.
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

    /**
     * The canvas edge the one-band frame draws at - tall enough to draw in bands, and no larger than the
     * depth buffer a thread keeps, so the floor alone picks the arm.
     */
    private static final int BANDED = 256;

    /** A floor no frame's bounding-box area reaches, so a frame that fits the kept buffer draws as one band. */
    private static final int ONE_BAND = Integer.MAX_VALUE;

    /** A floor every frame reaches, so a frame {@link #BANDED} tall draws in parallel bands. */
    private static final int IN_BANDS = 0;

    /**
     * The processor counts the banded frame splits by - bands of 86, 37 and 32 rows over {@link #BANDED}
     * rows - so the comparison covers band boundaries off a multiple of 32 on any host.
     */
    private static final int @NotNull [] BAND_COUNTS = { 3, 7, 8 };

    /** Opaque two-sided surface traits. */
    private static final @NotNull SurfaceTraits OPAQUE = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false);

    /** Two-sided traits for partial-alpha geometry, which the rasterizer draws back to front. */
    private static final @NotNull SurfaceTraits TRANSLUCENT = new SurfaceTraits(false, true, false, true, PassDeclaration.DEFAULT);

    /** The most pixels a frame drawn as one band for its small area may hold - a {@link #BANDED} square frame. */
    private static final int KEPT = BANDED * BANDED;

    /**
     * One row of the table of arms a frame takes.
     *
     * @param name what the row holds, for a failure message
     * @param triangles the triangles the frame draws, in emission order
     * @param width the frame width
     * @param height the frame height
     * @param serialWorkFloor the summed bounding-box area under which a frame that fits the kept depth
     *     buffer draws as one band
     * @param oneBand whether the frame draws as one band rather than in parallel bands
     */
    private record Arm(
        @NotNull String name,
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        int width,
        int height,
        int serialWorkFloor,
        boolean oneBand
    ) {}

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

        assertThat(draw(List.of(new DrawPart(square, transform))).data(), is(single.data()));
    }

    @Test
    @DisplayName("two parts depth-test against each other whichever is drawn first, where two calls paint the later over")
    void partsDepthTestAgainstEachOther() {
        ConcurrentList<VisibleTriangle> near = square(0xFFFF0000);
        ConcurrentList<VisibleTriangle> far = square(0xFF0000FF);
        PixelBuffer nearAlone = draw(List.of(new DrawPart(near, NEAR)));
        PixelBuffer farAlone = draw(List.of(new DrawPart(far, FAR)));
        assertThat("the two squares cover the centre in different colours",
            farAlone.getPixel(CENTRE, CENTRE), is(not(nearAlone.getPixel(CENTRE, CENTRE))));

        PixelBuffer nearFirst = draw(List.of(new DrawPart(near, NEAR), new DrawPart(far, FAR)));
        PixelBuffer farFirst = draw(List.of(new DrawPart(far, FAR), new DrawPart(near, NEAR)));
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

        PixelBuffer tied = draw(List.of(new DrawPart(red, NEAR), new DrawPart(blue, NEAR)));
        assertThat(tied.data(), is(draw(List.of(new DrawPart(blue, NEAR))).data()));
    }

    @Test
    @DisplayName("a coplanar tie within one list goes to the triangle drawn later")
    void aCoplanarTieWithinAListGoesToTheLaterTriangle() {
        ConcurrentList<VisibleTriangle> both = Concurrent.newList();
        both.addAll(square(0xFFFF0000));
        both.addAll(square(0xFF0000FF));

        PixelBuffer tied = PixelBuffer.create(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(both, tied, NEAR);
        assertThat(tied.data(), is(draw(List.of(new DrawPart(square(0xFF0000FF), NEAR))).data()));
    }

    @Test
    @DisplayName("a tall frame's band count is read from the processor count the runtime reports")
    void theBandCountReadsTheRuntimesProcessorCount() throws ReflectiveOperationException {
        Field cores = Rasterizer.class.getDeclaredField("CORES");
        cores.setAccessible(true);
        assertThat(cores.getInt(null), is(Runtime.getRuntime().availableProcessors()));
    }

    @Test
    @DisplayName("a frame drawn as one band draws exactly what it draws in parallel bands of any height")
    void oneBandDrawsWhatTheBandsDraw() {
        ConcurrentList<VisibleTriangle> scene = bandedScene();

        // A nearer frame drawn as one band leaves its depths in the buffer this thread keeps, and the
        // scene's own one-band frame is handed that buffer next.
        drawBanded(quad(-1.1f, -1.1f, 1.1f, 1.1f, 0.9f, 0xFFFFFFFF, OPAQUE), ONE_BAND, 1);
        PixelBuffer oneBand = drawBanded(scene, ONE_BAND, 1);

        assertThat("the triangle crossing the canvas edge reaches the last row",
            ColorMath.alpha(oneBand.getPixel(10, BANDED - 1)), is(0xFF));
        assertThat("the coplanar tie goes to the square drawn later",
            oneBand.getPixel(120, 128), is(oneBand.getPixel(160, 170)));
        assertThat("the translucent pair blends the nearer over the farther",
            ColorMath.alpha(oneBand.getPixel(210, 60)), is(greaterThan(0x80)));
        for (int cores : BAND_COUNTS) {
            PixelBuffer inBands = drawBanded(scene, IN_BANDS, cores);
            assertArrayEquals(inBands.data(), oneBand.data(), "one band draws what " + cores + " bands draw");
        }
    }

    @Test
    @DisplayName("a tall frame splits into bands of its height over the band count rounded up, the last taking the rest")
    void bandsSplitTheFrameByItsHeightRoundedUp() {
        assertThat("256 rows over 3 processors", bandRows(256, 3), is(List.of(86, 86, 84)));
        assertThat("256 rows over 7 processors", bandRows(256, 7), is(List.of(37, 37, 37, 37, 37, 37, 34)));
        assertThat("256 rows over 8 processors", bandRows(256, 8), is(Collections.nCopies(8, 32)));
        assertThat("256 rows over 16 processors", bandRows(256, 16), is(Collections.nCopies(8, 32)));
        assertThat("256 rows over one processor", bandRows(256, 1), is(List.of(256)));
        assertThat("256 rows over no processor", bandRows(256, 0), is(List.of(256)));
        assertThat("300 rows over 16 processors", bandRows(300, 16), is(List.of(34, 34, 34, 34, 34, 34, 34, 34, 28)));
        assertThat("512 rows over 16 processors", bandRows(512, 16), is(Collections.nCopies(16, 32)));
    }

    @Test
    @DisplayName("a frame draws as one band when short, or when it fits the kept depth buffer and covers under the floor")
    void theArmAFrameTakesFollowsItsSizeAndTheAreaItCovers() {
        ConcurrentList<VisibleTriangle> small = quad(-0.1f, -0.1f, 0.1f, 0.1f, 0f, 0xFF336699, OPAQUE);
        VisibleTriangle whole = quad(-1.1f, -1.1f, 1.1f, 1.1f, 0f, 0xFF336699, OPAQUE).getFirst();
        // Wholly past the canvas on both axes: each end of its box clamps past the other on both, and the
        // two inverted extents multiply to a large positive area.
        VisibleTriangle offBothAxes = quad(7f, 7f, 7.5f, 7.5f, 0f, 0xFF336699, OPAQUE).getFirst();
        // Wholly past the right edge alone: its inverted width times its height is a negative area.
        VisibleTriangle offOneAxis = quad(7f, -0.05f, 7.5f, 0.05f, 0f, 0xFF336699, OPAQUE).getFirst();

        List<Arm> table = List.of(
            new Arm("a frame a row short of 256, larger than the kept buffer, at a floor of zero",
                joined(List.of(whole)), 2 * BANDED, BANDED - 1, IN_BANDS, true),
            new Arm("a small square at the kept size under the floor", small, BANDED, BANDED, ONE_BAND, true),
            new Arm("a small square at the kept size over a floor of one", small, BANDED, BANDED, 1, false),
            new Arm("a small square at the kept size at a floor of zero", small, BANDED, BANDED, IN_BANDS, false),
            new Arm("a small square a column under the kept size", small, BANDED - 1, BANDED, ONE_BAND, true),
            new Arm("a small square a column past the kept size", small, BANDED + 1, BANDED, ONE_BAND, false),
            new Arm("a small square a row past the kept size", small, BANDED, BANDED + 1, ONE_BAND, false),
            new Arm("a triangle whose box is the whole frame, a pixel under the floor",
                joined(List.of(whole)), BANDED, BANDED, KEPT + 1, true),
            new Arm("a triangle whose box is the whole frame, at the floor",
                joined(List.of(whole)), BANDED, BANDED, KEPT, false),
            new Arm("a box inverted on both axes ahead of a small square",
                joined(List.of(offBothAxes), small), BANDED, BANDED, KEPT, true),
            new Arm("a box inverted on one axis ahead of a triangle whose box is the whole frame",
                joined(List.of(offOneAxis, whole)), BANDED, BANDED, KEPT, false));

        for (Arm arm : table) {
            PixelBuffer buffer = PixelBuffer.create(arm.width(), arm.height());
            Rasterizer rasterizer = new Rasterizer(CAMERA);
            boolean oneBand = rasterizer.drawsInOneBand(arm.triangles(), buffer, Matrix4f.IDENTITY, arm.serialWorkFloor());
            assertThat(arm.name(), oneBand, is(arm.oneBand()));
        }
    }

    /**
     * Draws parts into a fresh canvas through one pass.
     *
     * @param draws the parts, in draw order
     * @return the canvas
     */
    private static @NotNull PixelBuffer draw(@NotNull List<DrawPart> draws) {
        PixelBuffer buffer = PixelBuffer.create(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterizeAll(draws, buffer);
        return buffer;
    }

    /**
     * Draws a triangle list into a fresh {@link #BANDED} canvas, with the area under which the frame
     * draws as one band and the processor count its bands split by given.
     *
     * @param triangles the triangle list
     * @param serialWorkFloor the summed bounding-box area under which the frame draws as one band
     * @param cores the processor count the frame splits by when it draws in bands
     * @return the canvas
     */
    private static @NotNull PixelBuffer drawBanded(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        int serialWorkFloor,
        int cores
    ) {
        PixelBuffer buffer = PixelBuffer.create(BANDED, BANDED);
        new Rasterizer(CAMERA).rasterize(triangles, buffer, Matrix4f.IDENTITY, serialWorkFloor, cores);
        return buffer;
    }

    /**
     * Returns the rows of each band a frame drawn in bands splits into, walking the bands as the banded
     * draw does: a band starts a whole number of band heights down, ends where the next starts or at the
     * frame's last row, and is not drawn when that leaves it no rows.
     *
     * @param height the frame height
     * @param cores the processor count the frame splits by
     * @return the rows of each band drawn, top to bottom
     */
    private static @NotNull List<Integer> bandRows(int height, int cores) {
        int bandCount = Rasterizer.bandCount(height, cores);
        int bandHeight = Rasterizer.bandHeight(height, bandCount);
        List<Integer> rows = new ArrayList<>();
        for (int band = 0; band < bandCount; band++) {
            int start = band * bandHeight;
            int end = Math.min(height, start + bandHeight);
            if (start < end) rows.add(end - start);
        }
        return rows;
    }

    /**
     * Joins triangle lists into one, in the order given.
     *
     * @param parts the lists
     * @return one list holding every triangle of every part
     */
    @SafeVarargs
    private static @NotNull ConcurrentList<VisibleTriangle> joined(@NotNull List<VisibleTriangle>... parts) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        for (List<VisibleTriangle> part : parts)
            triangles.addAll(part);
        return triangles;
    }

    /**
     * Builds the scene the two arms are held to, its parts spread down the frame so the bands split
     * them: a triangle running off the canvas's left and bottom edges at the back, a red and a blue
     * square tied at one depth with the blue drawn later, and a translucent pair emitted nearer first,
     * which the back-to-front sort reverses.
     *
     * @return the scene's triangles, in emission order
     */
    private static @NotNull ConcurrentList<VisibleTriangle> bandedScene() {
        ConcurrentList<VisibleTriangle> scene = Concurrent.newList();
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        scene.add(new VisibleTriangle(
            new Vector3f(-1.25f, 0.1f, -0.5f), new Vector3f(-1.25f, -1.25f, -0.5f), new Vector3f(0.25f, -1.25f, -0.5f),
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f),
            texture(0xFF22AA44), ColorMath.WHITE, normal, 1f, OPAQUE));
        scene.addAll(quad(-0.5f, -0.2f, 0.1f, 0.4f, 0f, 0xFFFF0000, OPAQUE));
        scene.addAll(quad(-0.2f, -0.4f, 0.4f, 0.2f, 0f, 0xFF0000FF, OPAQUE));
        scene.addAll(quad(0.45f, 0.25f, 0.95f, 0.75f, 0.2f, 0x800088FF, TRANSLUCENT));
        scene.addAll(quad(0.25f, 0.35f, 0.85f, 0.95f, 0.1f, 0x80FF8800, TRANSLUCENT));
        return scene;
    }

    /**
     * Builds an axis-aligned rectangle of one colour - two triangles at one depth.
     *
     * @param minX the left edge, in model units
     * @param minY the bottom edge, in model units
     * @param maxX the right edge, in model units
     * @param maxY the top edge, in model units
     * @param z the depth, larger being nearer
     * @param argb the colour
     * @param traits the surface traits
     * @return the rectangle's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> quad(
        float minX,
        float minY,
        float maxX,
        float maxY,
        float z,
        int argb,
        @NotNull SurfaceTraits traits
    ) {
        PixelBuffer texture = texture(argb);
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        Vector3f topLeft = new Vector3f(minX, maxY, z);
        Vector3f bottomLeft = new Vector3f(minX, minY, z);
        Vector3f bottomRight = new Vector3f(maxX, minY, z);
        Vector3f topRight = new Vector3f(maxX, maxY, z);
        return Concurrent.newUnmodifiableList(
            new VisibleTriangle(topLeft, bottomLeft, bottomRight,
                new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), texture, ColorMath.WHITE, normal, 1f, traits),
            new VisibleTriangle(topLeft, bottomRight, topRight,
                new Vector2f(0f, 0f), new Vector2f(1f, 1f), new Vector2f(1f, 0f), texture, ColorMath.WHITE, normal, 1f, traits));
    }

    /**
     * Builds a 2 by 2 texture of one colour.
     *
     * @param argb the colour
     * @return the texture
     */
    private static @NotNull PixelBuffer texture(int argb) {
        PixelBuffer texture = PixelBuffer.create(2, 2);
        texture.fill(argb);
        return texture;
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
