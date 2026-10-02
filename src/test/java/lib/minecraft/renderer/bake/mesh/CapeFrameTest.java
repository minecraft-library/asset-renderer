package lib.minecraft.renderer.bake.mesh;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.math.Vector4f;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.vanilla.mesh.CapeMesh;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * The cape's read frame, pinned corner by corner against the texel vanilla's own cube walk puts at the
 * same point of the posed cape.
 * <p>
 * Vanilla builds the cape as one {@code ModelPart.Cube} and walks each face as a polygon whose four
 * vertices carry the corners of that face's strip in {@link CornerPhase#POLYGON}'s slot order, over the
 * rectangle {@link Unwrap.Atlas#rect} gives the face. The cube hangs at a yaw of {@code PI} and is read,
 * like every player part, through the {@link AxisSigns#HALF_X HALF_X} upright turn, so a point of the
 * upright box sits at the cube corner {@link #VANILLA_TURN} maps it to. The renderer builds the upright
 * box with {@link CornerPhase#BAKERY} corners over whole-face crops instead, and every corner of every
 * face has to sample the texel vanilla's polygon gives the same corner.
 * <p>
 * The probe sheet paints each texel of the cube's strips with its own {@code (u, v)} - {@code u} in red,
 * {@code v} in green - so a sampled texel names the sheet position it came from, and the sheet reads as
 * two ramps to the eye. {@link #writesTheRearViewsForALook} rasterizes the probe cape from behind into
 * {@code build/cape-frame/}.
 */
@DisplayName("The cape reads every strip where vanilla's posed cube walk puts it")
class CapeFrameTest {

    /** The torso's minimum corner, chosen so every corner of the cape box is a whole number. */
    private static final @NotNull Vector3f TORSO_MIN = new Vector3f(-4f, 0f, -2f);

    /** The torso's maximum corner. */
    private static final @NotNull Vector3f TORSO_MAX = new Vector3f(4f, 12f, 2f);

    /** The turn from vanilla's cape cube to the upright box - the upright turn, then the cape's yaw of {@code PI}. */
    private static final @NotNull AxisSigns VANILLA_TURN = AxisSigns.HALF_X.then(AxisSigns.HALF_Y);

    /** The cape cube's own unwrap, the rectangle each of vanilla's polygons reads. */
    private static final @NotNull Unwrap.Atlas UNWRAP = new Unwrap.Atlas(CapeMesh.CAPE_UV, CapeMesh.CAPE_SIZE, false);

    /** The cape sheet's width. */
    private static final int SHEET_WIDTH = 64;

    /** The cape sheet's height. */
    private static final int SHEET_HEIGHT = 32;

    /** The columns the cube's strips span, {@code u 0..21}. */
    private static final int STRIPS_WIDTH = 22;

    /** The rows the cube's strips span, {@code v 0..16}. */
    private static final int STRIPS_HEIGHT = 17;

    /** Red per column, so the last strip column reads {@code 231}. */
    private static final int U_STEP = 11;

    /** Green per row, so the last strip row reads {@code 224}. */
    private static final int V_STEP = 14;

    /** The blue every strip texel carries, which tells a probe texel from the fill around the strips. */
    private static final int STRIP_BLUE = 0x40;

    /** The fill outside the strips, whose blue is not {@link #STRIP_BLUE}. */
    private static final int OUTSIDE = 0xFF7F7F7F;

    /** Where the look renders are written, under the build directory the suite runs from. */
    private static final @NotNull Path LOOK_DIRECTORY = Path.of("build", "cape-frame");

    /** The yaws the look renders are taken at - the stored rear cell's {@code 180} and a three-quarter view either side. */
    private static final int @NotNull [] LOOK_YAWS = { 135, 180, 225 };

    /** The look renders' canvas edge, in pixels. */
    private static final int LOOK_SIZE = 256;

    /** The fraction of the canvas the fitted cape fills. */
    private static final float LOOK_FILL = 0.9f;

    /** The placement every 3D player scope is rasterized through - the half turn about Y. */
    private static final @NotNull Placement PLAYER_FACING = new Placement(Matrix4f.IDENTITY.scale(-1f, 1f, -1f));

    @TestFactory
    @DisplayName("every corner of every face samples the texel vanilla's walk puts at that corner")
    Stream<DynamicTest> everyCornerSamplesVanillasTexel() {
        ConcurrentList<VisibleTriangle> triangles = cape();
        Box box = bounds(triangles);

        return Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(face.name(), () -> {
            List<String> misses = new ArrayList<>();
            int checked = 0;

            for (VisibleTriangle triangle : triangles) {
                if (!triangle.normal().equals(face.normal()))
                    continue;

                compare(misses, face, box, triangle.position0(), triangle.uv0(), triangle.texture());
                compare(misses, face, box, triangle.position1(), triangle.uv1(), triangle.texture());
                compare(misses, face, box, triangle.position2(), triangle.uv2(), triangle.texture());
                checked += 3;
            }

            assertThat(face + " is built from two triangles", checked, is(6));
            assertThat(face + " reads each corner where vanilla's walk does", misses, is(empty()));
        }));
    }

    @Test
    @DisplayName("the probe sheet and the probe cape's rear views are written under build/cape-frame for a look")
    void writesTheRearViewsForALook() throws IOException {
        Path directory = Files.createDirectories(LOOK_DIRECTORY);
        ImageIO.write(probe().toBufferedImage(), "PNG", directory.resolve("probe.png").toFile());

        ConcurrentList<VisibleTriangle> triangles = cape();
        for (int yaw : LOOK_YAWS) {
            PixelBuffer target = PixelBuffer.create(LOOK_SIZE, LOOK_SIZE);
            new Rasterizer(Projection.VANILLA_ISO.resolve(new EulerRotation(0f, yaw, 0f)).camera(), PLAYER_FACING)
                .rasterizeFitted(triangles, target, EulerRotation.NONE, LOOK_FILL);

            Path render = directory.resolve("rear_" + yaw + ".png");
            ImageIO.write(target.toBufferedImage(), "PNG", render.toFile());
            assertThat(render + " holds an image", Files.size(render), greaterThan(0L));
        }
    }

    /**
     * Builds the probe cape on the test torso.
     *
     * @return the cape box's twelve triangles
     */
    private static @NotNull ConcurrentList<VisibleTriangle> cape() {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        PlayerAssembly.addCape(triangles, probe(), TORSO_MIN, TORSO_MAX);
        return triangles;
    }

    /**
     * Paints the probe sheet - each strip texel its own {@code (u, v)}, and {@link #OUTSIDE} around them.
     *
     * @return a new cape-sized sheet
     */
    private static @NotNull PixelBuffer probe() {
        PixelBuffer sheet = PixelBuffer.create(SHEET_WIDTH, SHEET_HEIGHT);
        for (int v = 0; v < SHEET_HEIGHT; v++) {
            for (int u = 0; u < SHEET_WIDTH; u++)
                sheet.setPixel(u, v, u < STRIPS_WIDTH && v < STRIPS_HEIGHT ? Texel.encode(u, v) : OUTSIDE);
        }
        return sheet;
    }

    /**
     * Measures the box the triangles span.
     *
     * @param triangles the cape's triangles
     * @return the box through their extreme vertices
     */
    private static @NotNull Box bounds(@NotNull List<VisibleTriangle> triangles) {
        List<Vector3f> points = triangles.stream()
            .flatMap(triangle -> Stream.of(triangle.position0(), triangle.position1(), triangle.position2()))
            .toList();
        return new Box(
            extreme(points, Vector3f::x, false), extreme(points, Vector3f::y, false), extreme(points, Vector3f::z, false),
            extreme(points, Vector3f::x, true), extreme(points, Vector3f::y, true), extreme(points, Vector3f::z, true));
    }

    /**
     * Finds the least or greatest of one coordinate over a set of points.
     *
     * @param points the points to measure
     * @param axis the coordinate to read
     * @param max whether to take the greatest rather than the least
     * @return the extreme value, exact because a float widens to a double and back without rounding
     */
    private static float extreme(@NotNull List<Vector3f> points, @NotNull ToDoubleFunction<Vector3f> axis, boolean max) {
        DoubleStream values = points.stream().mapToDouble(axis);
        return (float) (max ? values.max() : values.min()).orElseThrow();
    }

    /**
     * Records a miss when one vertex samples a texel other than the one vanilla's walk puts there.
     *
     * @param misses the misses so far
     * @param face the upright face the vertex belongs to
     * @param box the cape box
     * @param corner the vertex position
     * @param uv the vertex's UV, a corner of the face's crop
     * @param texture the face's crop
     */
    private static void compare(
        @NotNull List<String> misses,
        @NotNull Face face,
        @NotNull Box box,
        @NotNull Vector3f corner,
        @NotNull Vector2f uv,
        @NotNull PixelBuffer texture
    ) {
        Texel sampled = sampled(texture, uv);
        Texel expected = vanilla(face, box, corner);
        if (!sampled.equals(expected))
            misses.add(corner + " samples " + sampled + " where vanilla's walk puts " + expected);
    }

    /**
     * Reads the crop texel a corner UV lands on.
     *
     * @param texture the face's crop
     * @param uv a corner of the unit UV square
     * @return the probe texel the crop holds at that corner
     */
    private static @NotNull Texel sampled(@NotNull PixelBuffer texture, @NotNull Vector2f uv) {
        int x = uv.x() == 0f ? 0 : texture.width() - 1;
        int y = uv.y() == 0f ? 0 : texture.height() - 1;
        return Texel.decode(texture.getPixel(x, y));
    }

    /**
     * Finds the texel vanilla's polygon walk puts at one corner of the upright cape box.
     *
     * @param face the upright face the corner belongs to
     * @param box the cape box
     * @param corner the corner's upright position
     * @return the sheet texel vanilla's cube carries at that corner of that face
     */
    private static @NotNull Texel vanilla(@NotNull Face face, @NotNull Box box, @NotNull Vector3f corner) {
        Face strip = VANILLA_TURN.apply(face);
        Vector3f signs = VANILLA_TURN.apply(new Vector3f(1f, 1f, 1f));
        boolean maxX = (corner.x() == box.maxX()) == (signs.x() > 0f);
        boolean maxY = (corner.y() == box.maxY()) == (signs.y() > 0f);
        boolean maxZ = (corner.z() == box.maxZ()) == (signs.z() > 0f);
        int index = (maxZ ? 4 : 0) + (maxY ? (maxX ? 2 : 3) : (maxX ? 1 : 0));

        int[] walk = CornerPhase.POLYGON.vertexIndices(strip);
        int vertex = 0;
        while (vertex < walk.length && walk[vertex] != index)
            vertex++;
        assertThat("vanilla's " + strip + " polygon holds cube corner " + index, vertex < walk.length, is(true));

        Vector4f rect = UNWRAP.rect(strip);
        int u0 = (int) rect.x();
        int v0 = (int) rect.y();
        int u1 = (int) rect.z() - 1;
        int v1 = (int) rect.w() - 1;
        return switch (CornerPhase.POLYGON.uvSlots(strip)[vertex]) {
            case 0 -> new Texel(u0, v0);
            case 1 -> new Texel(u0, v1);
            case 2 -> new Texel(u1, v1);
            default -> new Texel(u1, v0);
        };
    }

    /**
     * One texel of the cape sheet.
     *
     * @param u the texel's column, or {@code -1} for a colour the probe never paints on a strip
     * @param v the texel's row, or {@code -1} for a colour the probe never paints on a strip
     */
    private record Texel(int u, int v) {

        /**
         * Encodes a strip texel's position as the probe colour painted there.
         *
         * @param u the column
         * @param v the row
         * @return the opaque colour carrying {@code u} in red, {@code v} in green and
         *     {@link CapeFrameTest#STRIP_BLUE} in blue
         */
        static int encode(int u, int v) {
            return 0xFF000000 | (u * U_STEP) << 16 | (v * V_STEP) << 8 | STRIP_BLUE;
        }

        /**
         * Decodes a sampled colour back into the texel the probe painted it at.
         *
         * @param argb the sampled colour
         * @return the texel, or {@code (-1, -1)} for a colour off the strips or a transparent miss
         */
        static @NotNull Texel decode(int argb) {
            if (argb >>> 24 != 0xFF || (argb & 0xFF) != STRIP_BLUE)
                return new Texel(-1, -1);

            return new Texel((argb >> 16 & 0xFF) / U_STEP, (argb >> 8 & 0xFF) / V_STEP);
        }

    }

}
