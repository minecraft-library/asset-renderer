package lib.minecraft.renderer.engine.mesh;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.AlphaMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link MissingMesh}: the cube's twelve triangles over six faces, all untinted and all
 * sharing the one sprite instance, the flat face's nearest upscale, and the icon, which is that face
 * lit as a slot lights it.
 */
@DisplayName("MissingMesh cube, flat face and inventory picture")
class MissingMeshTest {

    private static final int CANVAS = 64;

    @Test
    @DisplayName("the cube is twelve triangles over six faces")
    void cubeIsTwelveTrianglesOverSixFaces() {
        ConcurrentList<VisibleTriangle> cube = MissingMesh.cube();
        assertThat(cube.size(), is(12));

        Set<Vector3f> normals = cube.stream().map(VisibleTriangle::normal).collect(Collectors.toSet());
        assertThat("one normal per face, two triangles each", normals.size(), is(6));
    }

    @Test
    @DisplayName("every face is untinted, matching the client's tint index of -1")
    void everyFaceIsUntinted() {
        for (VisibleTriangle triangle : MissingMesh.cube())
            assertThat(triangle.tintArgb(), is(ColorMath.WHITE));
    }

    @Test
    @DisplayName("all six faces share the one sprite rather than copying it")
    void everyFaceSharesTheSprite() {
        // Identity, not equality: the cube handing each face its own copy would render the same and
        // allocate six buffers per call.
        for (VisibleTriangle triangle : MissingMesh.cube())
            assertThat(triangle.texture() == MissingSprite.sprite(), is(true));
    }

    @Test
    @DisplayName("the face fills a square canvas by nearest sampling")
    void faceFillsTheCanvasByNearestSampling() {
        PixelBuffer face = MissingMesh.face(CANVAS);
        assertThat(face.width(), is(CANVAS));
        assertThat(face.height(), is(CANVAS));

        // 16 to 64 is an exact 4x, so each source texel is a 4x4 block and each quadrant a 32x32 one.
        // Sampling the middle of each quadrant reads source texels (2,2), (10,2), (2,10) and (10,10).
        assertThat("top-left", face.getPixel(8, 8), is(MissingSprite.BLACK_ARGB));
        assertThat("top-right", face.getPixel(40, 8), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-left", face.getPixel(8, 40), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-right", face.getPixel(40, 40), is(MissingSprite.BLACK_ARGB));
    }

    @Test
    @DisplayName("the face and the icon are canvases, as the renders they stand for are")
    void theFaceAndTheIconAreCanvases() {
        assertThat("the face", MissingMesh.face(CANVAS).alphaMode(), is(AlphaMode.PREMULTIPLIED));
        assertThat("the icon", MissingMesh.icon(CANVAS).alphaMode(), is(AlphaMode.PREMULTIPLIED));
    }

    @Test
    @DisplayName("the face carries exactly the sprite's two colours, unlit")
    void faceCarriesTheSpritesColours() {
        assertThat(colours(MissingMesh.face(CANVAS)), is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("the icon is the face lit as a slot lights it, the client's (127, 0, 127) in place of magenta")
    void iconIsTheFaceLitAsASlotLightsIt() {
        // Measured, not derived: a 26.1 client drawing the missing item model in a slot at 512 pixels
        // writes exactly these two colours, magenta at about half its brightness.
        assertThat(colours(MissingMesh.icon(CANVAS)), is(Set.of(MissingSprite.BLACK_ARGB, 0xFF7F007F)));
        assertThat("the same quadrants as the face", MissingMesh.icon(CANVAS).getPixel(40, 8), is(0xFF7F007F));
        assertThat(MissingMesh.icon(CANVAS).getPixel(8, 8), is(MissingSprite.BLACK_ARGB));
    }

    /** Every colour a canvas carries. */
    private static Set<Integer> colours(PixelBuffer canvas) {
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < canvas.height(); y++)
            for (int x = 0; x < canvas.width(); x++)
                colours.add(canvas.getPixel(x, y));
        return colours;
    }

}
