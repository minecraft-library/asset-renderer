package lib.minecraft.renderer.bake.armor;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.bake.mesh.ShieldKit;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.ViewMirror;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.math.Vector4f;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
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
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * The read frame of every box built upright from a vanilla cube strip - the player's six parts on both
 * layers, the 64x32 left-limb fallback, a worn shell cube and the shield - pinned corner by corner
 * against the texel vanilla's own cube walk puts at the same point of the same box.
 * <p>
 * Vanilla builds each of those boxes as one {@code ModelPart.Cube} and walks each face as a polygon
 * whose four vertices carry the corners of that face's strip in {@link CornerPhase#POLYGON}'s slot
 * order, over the rectangle {@link Unwrap.Atlas#rect} gives the face. Every one of them is authored in
 * the Y-down model frame and read through the {@link AxisSigns#HALF_X HALF_X} upright turn, so a point
 * of the upright box sits at the cube corner {@link #VANILLA_TURN} maps it to. A mirrored cube swaps its
 * X extremes before it builds a vertex and reverses each polygon only after pairing its UVs, so the
 * vertex at a corner carries the UV the unmirrored cube gives the corner across the box's X centre, on
 * the polygon {@link AxisSigns#MIRROR_X} names. The renderer builds each upright box with
 * {@link CornerPhase#BAKERY} corners instead - over whole-face crops for the parts and the shell, over
 * paired UV corners for the shield - and every corner of every face has to sample the texel vanilla's
 * polygon gives the same corner.
 * <p>
 * The probe sheet paints each texel with its own {@code (u, v)} - {@code u} in red, {@code v} in green -
 * so a sampled texel names the sheet position it came from. {@link #writesTheUndersideViewsForALook}
 * rasterizes the probe head and torso from below into {@code build/humanoid-frame/}.
 */
@DisplayName("Boxes built upright from a vanilla cube read every strip where vanilla's cube walk puts it")
class HumanoidFrameTest {

    /** The turn from a vanilla cube to the upright box every one of these builds lays. */
    private static final @NotNull AxisSigns VANILLA_TURN = AxisSigns.HALF_X;

    /** Each part's base-layer atlas origin on a 64x64 skin, transcribed from vanilla's player model. */
    private static final @NotNull Map<HumanoidPart, Vector2f> BASE_ORIGINS = Map.of(
        HumanoidPart.HEAD, new Vector2f(0f, 0f),
        HumanoidPart.TORSO, new Vector2f(16f, 16f),
        HumanoidPart.RIGHT_ARM, new Vector2f(40f, 16f),
        HumanoidPart.LEFT_ARM, new Vector2f(32f, 48f),
        HumanoidPart.RIGHT_LEG, new Vector2f(0f, 16f),
        HumanoidPart.LEFT_LEG, new Vector2f(16f, 48f));

    /** Each part's overlay-layer atlas origin on a 64x64 skin, transcribed from vanilla's player model. */
    private static final @NotNull Map<HumanoidPart, Vector2f> OVERLAY_ORIGINS = Map.of(
        HumanoidPart.HEAD, new Vector2f(32f, 0f),
        HumanoidPart.TORSO, new Vector2f(16f, 32f),
        HumanoidPart.RIGHT_ARM, new Vector2f(40f, 32f),
        HumanoidPart.LEFT_ARM, new Vector2f(48f, 48f),
        HumanoidPart.RIGHT_LEG, new Vector2f(0f, 32f),
        HumanoidPart.LEFT_LEG, new Vector2f(0f, 48f));

    /** The right arm's cube on a 64x32 sheet, which vanilla's legacy left arm draws mirrored. */
    private static final @NotNull Unwrap.Atlas LEGACY_ARM = new Unwrap.Atlas(new Vector2f(40f, 16f), new Vector3f(4f, 12f, 4f), false);

    /** The right leg's cube on a 64x32 sheet, which vanilla's legacy left leg draws mirrored. */
    private static final @NotNull Unwrap.Atlas LEGACY_LEG = new Unwrap.Atlas(new Vector2f(0f, 16f), new Vector3f(4f, 12f, 4f), false);

    /** The shield plate's cube, {@code texOffs(0, 0)} with a {@code 12 x 22 x 1} box. */
    private static final @NotNull Unwrap.Atlas SHIELD_PLATE = new Unwrap.Atlas(new Vector2f(0f, 0f), new Vector3f(12f, 22f, 1f), false);

    /** The shield handle's cube, {@code texOffs(26, 0)} with a {@code 2 x 6 x 6} box. */
    private static final @NotNull Unwrap.Atlas SHIELD_HANDLE = new Unwrap.Atlas(new Vector2f(26f, 0f), new Vector3f(2f, 6f, 6f), false);

    /** The triangles one box of the shield is built from, the plate's first and the handle's after. */
    private static final int SHIELD_BOX_TRIANGLES = 12;

    /** The edge of the modern skin, the shell sheet and the shield sheet. */
    private static final int SHEET_SIZE = 64;

    /** The height of the legacy skin and the equipment sheet, which carry only the modern layout's top half. */
    private static final int LEGACY_HEIGHT = 32;

    /** Red per column and green per row, so the last texel of a 64-texel edge reads {@code 252}. */
    private static final int STEP = 4;

    /** The blue every probe texel carries, which tells a probe texel from a transparent miss. */
    private static final int PROBE_BLUE = 0x40;

    /** Where the look renders are written, under the build directory the suite runs from. */
    private static final @NotNull Path LOOK_DIRECTORY = Path.of("build", "humanoid-frame");

    /** The look renders' canvas edge, in pixels. */
    private static final int LOOK_SIZE = 256;

    /** The fraction of the canvas the fitted box fills. */
    private static final float LOOK_FILL = 0.9f;

    /** The skull scope's scale, which makes the head the unit cube and keeps each box clear of the portrait camera. */
    private static final float LOOK_UNITS_PER_PIXEL = 0.125f;

    /** The pitch added to the portrait pose, so the flipped view looks up at the underside rather than past it. */
    private static final @NotNull EulerRotation LOOK_TILT = new EulerRotation(45f, 0f, 0f);

    /** The placement every 3D player scope is rasterized through - the half turn about Y. */
    private static final @NotNull Placement PLAYER_FACING = new Placement(Matrix4f.IDENTITY.scale(-1f, 1f, -1f));

    @TestFactory
    @DisplayName("every corner of every part's faces, on both layers, samples the texel vanilla's walk puts there")
    Stream<DynamicTest> everyPartCornerSamplesVanillasTexel() {
        PixelBuffer probe = probe(SHEET_SIZE, SHEET_SIZE);
        return Stream.of(HumanoidPart.values()).flatMap(part -> Stream.of(false, true).flatMap(overlay -> {
            ConcurrentList<VisibleTriangle> triangles = BoxKit.buildBox(part.box(1f), part.textures(probe, overlay), ColorMath.WHITE);
            Box box = bounds(triangles);
            Vector3f size = new Vector3f(part.pixelWidth(), part.pixelHeight(), box.maxZ() - box.minZ());
            Unwrap.Atlas unwrap = new Unwrap.Atlas((overlay ? OVERLAY_ORIGINS : BASE_ORIGINS).get(part), size, false);
            return Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(
                part + (overlay ? " overlay " : " base ") + face,
                () -> assertFaceReadsVanillasTexels(triangles, face, unwrap, false)));
        }));
    }

    @TestFactory
    @DisplayName("a legacy sheet's left limbs sample the texel vanilla's mirrored right-limb walk puts there")
    Stream<DynamicTest> legacyLeftLimbsSampleTheMirroredRightLimb() {
        PixelBuffer probe = probe(SHEET_SIZE, LEGACY_HEIGHT);
        return Stream.of(HumanoidPart.LEFT_ARM, HumanoidPart.LEFT_LEG).flatMap(part -> {
            ConcurrentList<VisibleTriangle> triangles = BoxKit.buildBox(part.box(1f), part.textures(probe, false), ColorMath.WHITE);
            Unwrap.Atlas unwrap = part == HumanoidPart.LEFT_ARM ? LEGACY_ARM : LEGACY_LEG;
            return Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(
                part + " " + face,
                () -> assertFaceReadsVanillasTexels(triangles, face, unwrap, true)));
        });
    }

    @TestFactory
    @DisplayName("every corner of a worn shell cube, plain or mirrored, samples the texel vanilla's walk puts there")
    Stream<DynamicTest> shellCubesSampleVanillasTexel() {
        PixelBuffer probe = probe(SHEET_SIZE, SHEET_SIZE);
        return Stream.of(false, true).flatMap(mirror -> {
            Unwrap.Atlas unwrap = new Unwrap.Atlas(new Vector2f(40f, 16f), new Vector3f(4f, 12f, 4f), mirror);
            WornBox.Mesh mesh = new WornBox.Mesh("probe", unwrap, Concurrent.newSet(),
                new Vector3f(-2f, -6f, -2f), new Vector3f(4f, 12f, 4f), Vector3f.ZERO, Vector3f.ZERO);
            ConcurrentList<VisibleTriangle> triangles = BoxKit.buildBox(mesh.boxFor(ArmorSlot.CHESTPLATE), mesh.textures(probe), ColorMath.WHITE);
            return Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(
                (mirror ? "mirrored " : "plain ") + face,
                () -> assertFaceReadsVanillasTexels(triangles, face, unwrap, mirror)));
        });
    }

    @TestFactory
    @DisplayName("every corner of the shield's plate and handle samples the texel vanilla's walk puts there")
    Stream<DynamicTest> shieldCornersSampleVanillasTexel() {
        ConcurrentList<VisibleTriangle> shield = ShieldKit.buildShield3D(probe(SHEET_SIZE, SHEET_SIZE));
        List<VisibleTriangle> plate = shield.subList(0, SHIELD_BOX_TRIANGLES);
        List<VisibleTriangle> handle = shield.subList(SHIELD_BOX_TRIANGLES, 2 * SHIELD_BOX_TRIANGLES);
        return Stream.concat(
            Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(
                "plate " + face, () -> assertFaceReadsVanillasTexels(plate, face, SHIELD_PLATE, false))),
            Stream.of(Face.values()).map(face -> DynamicTest.dynamicTest(
                "handle " + face, () -> assertFaceReadsVanillasTexels(handle, face, SHIELD_HANDLE, false))));
    }

    @Test
    @DisplayName("the probe sheet and the probe head and torso seen from below are written under build/humanoid-frame for a look")
    void writesTheUndersideViewsForALook() throws IOException {
        Path directory = Files.createDirectories(LOOK_DIRECTORY);
        PixelBuffer probe = probe(SHEET_SIZE, SHEET_SIZE);
        Path sheet = directory.resolve("probe.png");
        ImageIO.write(probe.toBufferedImage(), "PNG", sheet.toFile());
        assertThat(sheet + " holds an image", Files.size(sheet), greaterThan(0L));

        Rasterizer rasterizer = new Rasterizer(
            Projection.PORTRAIT.resolve(LOOK_TILT, ViewMirror.FLIPPED).camera(), PLAYER_FACING);
        for (HumanoidPart part : new HumanoidPart[]{ HumanoidPart.HEAD, HumanoidPart.TORSO }) {
            PixelBuffer target = PixelBuffer.create(LOOK_SIZE, LOOK_SIZE);
            rasterizer.rasterizeFitted(
                BoxKit.buildBox(part.centred(LOOK_UNITS_PER_PIXEL), part.textures(probe, false), ColorMath.WHITE),
                target, EulerRotation.NONE, LOOK_FILL);

            Path render = directory.resolve("underside_" + part.name().toLowerCase(Locale.ROOT) + ".png");
            ImageIO.write(target.toBufferedImage(), "PNG", render.toFile());
            assertThat(render + " holds an image", Files.size(render), greaterThan(0L));
        }
    }

    /**
     * Walks one face of a built box and asserts that every corner samples the texel vanilla's walk
     * puts there.
     *
     * @param triangles the box's twelve triangles
     * @param face the upright face to walk
     * @param unwrap the vanilla cube's own unwrap, unmirrored
     * @param mirrored whether the vanilla cube is mirrored
     */
    private static void assertFaceReadsVanillasTexels(
        @NotNull List<VisibleTriangle> triangles,
        @NotNull Face face,
        @NotNull Unwrap.Atlas unwrap,
        boolean mirrored
    ) {
        Box box = bounds(triangles);
        List<VisibleTriangle> faceTriangles = triangles.stream()
            .filter(triangle -> triangle.normal().equals(face.normal()))
            .toList();
        Vector4f span = Vector4f.bounds(faceTriangles.stream()
            .flatMap(triangle -> Stream.of(triangle.uv0(), triangle.uv1(), triangle.uv2()))
            .toArray(Vector2f[]::new));

        List<String> misses = new ArrayList<>();
        int checked = 0;
        for (VisibleTriangle triangle : faceTriangles) {
            compare(misses, face, box, triangle.position0(), sampled(triangle.texture(), triangle.uv0(), span), unwrap, mirrored);
            compare(misses, face, box, triangle.position1(), sampled(triangle.texture(), triangle.uv1(), span), unwrap, mirrored);
            compare(misses, face, box, triangle.position2(), sampled(triangle.texture(), triangle.uv2(), span), unwrap, mirrored);
            checked += 3;
        }

        assertThat(face + " is built from two triangles", checked, is(6));
        assertThat(face + " reads each corner where vanilla's walk does", misses, is(empty()));
    }

    /**
     * Paints a probe sheet - each texel its own {@code (u, v)}.
     *
     * @param width the sheet's width
     * @param height the sheet's height
     * @return a new, fully painted sheet
     */
    private static @NotNull PixelBuffer probe(int width, int height) {
        PixelBuffer sheet = PixelBuffer.create(width, height);
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++)
                sheet.setPixel(u, v, Texel.encode(u, v));
        }
        return sheet;
    }

    /**
     * Measures the box the triangles span.
     *
     * @param triangles one box's triangles
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
     * @param box the upright box
     * @param corner the vertex position
     * @param sampled the texel the vertex samples
     * @param unwrap the vanilla cube's own unwrap, unmirrored
     * @param mirrored whether the vanilla cube is mirrored
     */
    private static void compare(
        @NotNull List<String> misses,
        @NotNull Face face,
        @NotNull Box box,
        @NotNull Vector3f corner,
        @NotNull Texel sampled,
        @NotNull Unwrap.Atlas unwrap,
        boolean mirrored
    ) {
        Texel expected = vanilla(face, box, corner, unwrap, mirrored);
        if (!sampled.equals(expected))
            misses.add(corner + " samples " + sampled + " where vanilla's walk puts " + expected);
    }

    /**
     * Reads the texel a corner UV lands on, the texel just inside the face's UV span at that corner.
     *
     * @param texture the texture the vertex samples - a whole-face crop, or the sheet itself
     * @param uv the vertex's UV
     * @param span the least and greatest UV over the face's vertices, as {@code (uMin, vMin, uMax, vMax)}
     * @return the probe texel the texture holds at that corner
     */
    private static @NotNull Texel sampled(@NotNull PixelBuffer texture, @NotNull Vector2f uv, @NotNull Vector4f span) {
        int x = uv.x() == span.x() ? Math.round(span.x() * texture.width()) : Math.round(span.z() * texture.width()) - 1;
        int y = uv.y() == span.y() ? Math.round(span.y() * texture.height()) : Math.round(span.w() * texture.height()) - 1;
        return Texel.decode(texture.getPixel(x, y));
    }

    /**
     * Finds the texel vanilla's polygon walk puts at one corner of an upright box.
     *
     * @param face the upright face the corner belongs to
     * @param box the upright box
     * @param corner the corner's upright position
     * @param unwrap the vanilla cube's own unwrap, unmirrored
     * @param mirrored whether the vanilla cube is mirrored
     * @return the sheet texel vanilla's cube carries at that corner of that face
     */
    private static @NotNull Texel vanilla(
        @NotNull Face face,
        @NotNull Box box,
        @NotNull Vector3f corner,
        @NotNull Unwrap.Atlas unwrap,
        boolean mirrored
    ) {
        Face turned = VANILLA_TURN.apply(face);
        Face strip = mirrored ? AxisSigns.MIRROR_X.apply(turned) : turned;
        Vector3f signs = VANILLA_TURN.apply(new Vector3f(1f, 1f, 1f));
        boolean maxX = ((corner.x() == box.maxX()) == (signs.x() > 0f)) != mirrored;
        boolean maxY = (corner.y() == box.maxY()) == (signs.y() > 0f);
        boolean maxZ = (corner.z() == box.maxZ()) == (signs.z() > 0f);
        int index = (maxZ ? 4 : 0) + (maxY ? (maxX ? 2 : 3) : (maxX ? 1 : 0));

        int[] walk = CornerPhase.POLYGON.vertexIndices(strip);
        int vertex = 0;
        while (vertex < walk.length && walk[vertex] != index)
            vertex++;
        assertThat("vanilla's " + strip + " polygon holds cube corner " + index, vertex < walk.length, is(true));

        Vector4f rect = unwrap.rect(strip);
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
     * One texel of a probe sheet.
     *
     * @param u the texel's column, or {@code -1} for a colour the probe never paints
     * @param v the texel's row, or {@code -1} for a colour the probe never paints
     */
    private record Texel(int u, int v) {

        /**
         * Encodes a texel's position as the probe colour painted there.
         *
         * @param u the column
         * @param v the row
         * @return the opaque colour carrying {@code u} in red, {@code v} in green and
         *     {@link HumanoidFrameTest#PROBE_BLUE} in blue
         */
        static int encode(int u, int v) {
            return 0xFF000000 | (u * STEP) << 16 | (v * STEP) << 8 | PROBE_BLUE;
        }

        /**
         * Decodes a sampled colour back into the texel the probe painted it at.
         *
         * @param argb the sampled colour
         * @return the texel, or {@code (-1, -1)} for a transparent miss or a colour the probe never paints
         */
        static @NotNull Texel decode(int argb) {
            if (argb >>> 24 != 0xFF || (argb & 0xFF) != PROBE_BLUE)
                return new Texel(-1, -1);

            return new Texel((argb >> 16 & 0xFF) / STEP, (argb >> 8 & 0xFF) / STEP);
        }

    }

}
