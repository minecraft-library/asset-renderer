package lib.minecraft.renderer.engine.raster;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.DrawPart;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.draw.Sampler;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Quaternionf;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * <p>
 * It also holds a frame drawn as one band on the calling thread to the same frame drawn in parallel
 * bands, at band counts fixed by the test rather than read from the host's processor count. A frame
 * that fits the depth buffer a thread keeps takes either arm by the area its triangles cover, so the
 * arm a frame takes depends on its content, and nothing else would see the two drift apart.
 * <p>
 * No pixel shows how a frame is split, since one band and any number of bands draw the same bytes,
 * so the split is held to its own answers as well: the rows each band covers for a height and a
 * processor count, and the arm a frame takes for its size and the area its triangles cover.
 * <p>
 * A triangle's texture is fetched through its own sampler: a linear one filters the interpolated
 * coordinate, each tap addressed by its mode, and a nearest one reads below the sheet's first texel, at
 * a face's first-texel boundary and on a scrolled pass, wrapped where the sampler repeats and held at
 * the edge where it clamps. No stored sweep draws a filtered or clamped raster texture, so these
 * cases are the fetch's witness.
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

    /** A nearest sampler clamped to the texture's edge. */
    private static final @NotNull Sampler NEAREST_CLAMP = new Sampler(Sampler.FilterMode.NEAREST, Sampler.AddressMode.CLAMP_TO_EDGE);

    /** A linear sampler that repeats. */
    private static final @NotNull Sampler LINEAR_REPEAT = new Sampler(Sampler.FilterMode.LINEAR, Sampler.AddressMode.REPEAT);

    /** A linear sampler clamped to the texture's edge. */
    private static final @NotNull Sampler LINEAR_CLAMP = new Sampler(Sampler.FilterMode.LINEAR, Sampler.AddressMode.CLAMP_TO_EDGE);

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

        PixelBuffer single = PixelBuffer.canvas(SIZE, SIZE);
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

        PixelBuffer separate = PixelBuffer.canvas(SIZE, SIZE);
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

        PixelBuffer tied = PixelBuffer.canvas(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(both, tied, NEAR);
        assertThat(tied.data(), is(draw(List.of(new DrawPart(square(0xFF0000FF), NEAR))).data()));
    }

    @Test
    @DisplayName("a texture read through a linear sampler is filtered at the interpolated coordinate, through its address mode")
    void aLinearTextureIsFilteredAtTheInterpolatedCoordinate() {
        PixelBuffer blackWhite = PixelBuffer.ofStraight(new int[]{ 0xFF000000, 0xFFFFFFFF }, 2, 1);
        // Four output pixels across the texture's two texels read texel-space 0.25, 0.75, 1.25 and 1.75.
        assertThat("linear, repeating", stripRow(new SampledTexture(blackWhite, LINEAR_REPEAT), 0f, 1f, OPAQUE),
            is(List.of(0xFF404040, 0xFF404040, 0xFFBFBFBF, 0xFFBFBFBF)));
        assertThat("linear, clamped", stripRow(new SampledTexture(blackWhite, LINEAR_CLAMP), 0f, 1f, OPAQUE),
            is(List.of(0xFF000000, 0xFF404040, 0xFFBFBFBF, 0xFFFFFFFF)));
        assertThat("nearest", stripRow(SampledTexture.of(blackWhite), 0f, 1f, OPAQUE),
            is(List.of(0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF)));
    }

    @Test
    @DisplayName("a nearest fetch below the sheet's first texel wraps to its last, and holds at its first where the sampler clamps")
    void aClampingNearestFetchHoldsTheFirstTexelBelowTheSheet() {
        PixelBuffer four = PixelBuffer.ofStraight(new int[]{ 0xFF110000, 0xFF002200, 0xFF000033, 0xFF444444 }, 4, 1);
        // The strip's left edge runs through the first column's pixel centres at u = 0, a texel boundary,
        // which reads the texel below it - outside the sheet.
        List<Integer> repeat = edgeRow(SampledTexture.of(four));
        List<Integer> clamp = edgeRow(new SampledTexture(four, NEAREST_CLAMP));
        assertThat("repeating: the last texel, then the strip's own", repeat, is(List.of(0xFF444444, 0xFF110000, 0xFF002200, 0xFF000033)));
        assertThat("clamped: the first texel, then the strip's own", clamp, is(List.of(0xFF110000, 0xFF110000, 0xFF002200, 0xFF000033)));
    }

    @Test
    @DisplayName("a scrolled pass reads past the sheet as its sampler addresses it: wrapped where it repeats, the edge texel where it clamps")
    void aScrolledPassReadsPastTheSheetAsItsSamplerAddresses() {
        PixelBuffer four = PixelBuffer.ofStraight(new int[]{ 0xFF110000, 0xFF002200, 0xFF000033, 0xFF444444 }, 4, 1);
        SurfaceTraits scrolled = new SurfaceTraits(false, false, false, true,
            new PassDeclaration(false, BlendMode.NORMAL, 1f, true, false, true));
        assertThat("repeating", stripRow(SampledTexture.of(four), 1f, 2f, scrolled),
            is(List.of(0xFF110000, 0xFF002200, 0xFF000033, 0xFF444444)));
        assertThat("clamped", stripRow(new SampledTexture(four, NEAREST_CLAMP), 1f, 2f, scrolled),
            is(List.of(0xFF444444, 0xFF444444, 0xFF444444, 0xFF444444)));
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
            PixelBuffer buffer = PixelBuffer.canvas(arm.width(), arm.height());
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
        PixelBuffer buffer = PixelBuffer.canvas(SIZE, SIZE);
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
        PixelBuffer buffer = PixelBuffer.canvas(BANDED, BANDED);
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
        SampledTexture texture = texture(argb);
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
     * Draws a strip four output pixels wide, its corners on whole pixels, across the canvas's centre column
     * group from {@code u0} on its left to {@code u1} on its right, and answers the four pixels of its middle
     * row.
     *
     * @param texture the texture the strip samples, with its sampler
     * @param u0 the left edge's texture coordinate
     * @param u1 the right edge's texture coordinate
     * @param traits the surface the strip is drawn as, its pass among them
     * @return the four pixels, left to right
     */
    private static @NotNull List<Integer> stripRow(@NotNull SampledTexture texture, float u0, float u1, @NotNull SurfaceTraits traits) {
        // The orthographic camera lays a model unit over half the canvas, so a sixteenth of a unit is four pixels.
        PixelBuffer canvas = PixelBuffer.canvas(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(strip(0f, 0.125f, u0, u1, texture, traits), canvas);
        List<Integer> row = new ArrayList<>();
        for (int x = CENTRE; x < CENTRE + 4; x++)
            row.add(canvas.getPixel(x, CENTRE));
        return row;
    }

    /**
     * Draws a strip four output pixels wide whose left edge runs through the pixel centres of the canvas's centre
     * column, at {@code u = 0}, and answers the four pixels of its middle row from that column.
     *
     * @param texture the texture the strip samples, with its sampler
     * @return the four pixels, left to right
     */
    private static @NotNull List<Integer> edgeRow(@NotNull SampledTexture texture) {
        float half = 0.5f / (SIZE / 2f);
        PixelBuffer canvas = PixelBuffer.canvas(SIZE, SIZE);
        new Rasterizer(CAMERA).rasterize(strip(half, half + 0.125f, 0f, 1f, texture, OPAQUE), canvas);
        List<Integer> row = new ArrayList<>();
        for (int x = CENTRE; x < CENTRE + 4; x++)
            row.add(canvas.getPixel(x, CENTRE));
        return row;
    }

    /**
     * Builds a strip half a model unit tall about the centre line, from {@code minX} to {@code maxX}, its texture
     * coordinate running from {@code u0} on the left to {@code u1} on the right and from 0 to 1 down.
     *
     * @param minX the left edge, in model units
     * @param maxX the right edge, in model units
     * @param u0 the left edge's texture coordinate
     * @param u1 the right edge's texture coordinate
     * @param texture the texture the strip samples, with its sampler
     * @param traits the surface the strip is drawn as
     * @return the strip's two triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> strip(
        float minX, float maxX, float u0, float u1, @NotNull SampledTexture texture, @NotNull SurfaceTraits traits
    ) {
        Vector3f normal = new Vector3f(0f, 0f, 1f);
        Vector3f topLeft = new Vector3f(minX, 0.25f, 0f);
        Vector3f bottomLeft = new Vector3f(minX, -0.25f, 0f);
        Vector3f bottomRight = new Vector3f(maxX, -0.25f, 0f);
        Vector3f topRight = new Vector3f(maxX, 0.25f, 0f);
        return Concurrent.newUnmodifiableList(
            new VisibleTriangle(topLeft, bottomLeft, bottomRight,
                new Vector2f(u0, 0f), new Vector2f(u0, 1f), new Vector2f(u1, 1f), texture, ColorMath.WHITE, normal, 1f, traits),
            new VisibleTriangle(topLeft, bottomRight, topRight,
                new Vector2f(u0, 0f), new Vector2f(u1, 1f), new Vector2f(u1, 0f), texture, ColorMath.WHITE, normal, 1f, traits));
    }

    /**
     * Builds a 2 by 2 texture of one colour, read nearest and repeating.
     *
     * @param argb the colour
     * @return the texture
     */
    private static @NotNull SampledTexture texture(int argb) {
        PixelBuffer texture = PixelBuffer.straight(2, 2);
        texture.fill(argb);
        return SampledTexture.of(texture);
    }

    /**
     * Builds the square every part draws - two triangles across the origin in the {@code z = 0} plane,
     * one opaque colour, seen from either side.
     *
     * @param argb the colour
     * @return the square's triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> square(int argb) {
        SampledTexture texture = texture(argb);
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
