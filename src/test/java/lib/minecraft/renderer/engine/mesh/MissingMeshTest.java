package lib.minecraft.renderer.engine.mesh;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
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
 * sharing the one sprite instance, and the icon's nearest upscale.
 */
@DisplayName("MissingMesh cube and inventory picture")
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
    @DisplayName("the icon fills a square canvas by nearest sampling")
    void iconFillsTheCanvasByNearestSampling() {
        PixelBuffer icon = MissingMesh.icon(CANVAS);
        assertThat(icon.width(), is(CANVAS));
        assertThat(icon.height(), is(CANVAS));

        // 16 to 64 is an exact 4x, so each source texel is a 4x4 block and each quadrant a 32x32 one.
        // Sampling the middle of each quadrant reads source texels (2,2), (10,2), (2,10) and (10,10).
        assertThat("top-left", icon.getPixel(8, 8), is(MissingSprite.BLACK_ARGB));
        assertThat("top-right", icon.getPixel(40, 8), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-left", icon.getPixel(8, 40), is(MissingSprite.MAGENTA_ARGB));
        assertThat("bottom-right", icon.getPixel(40, 40), is(MissingSprite.BLACK_ARGB));
    }

    @Test
    @DisplayName("the icon carries exactly the sprite's two colours")
    void iconCarriesTwoColours() {
        PixelBuffer icon = MissingMesh.icon(CANVAS);
        Set<Integer> colours = new HashSet<>();

        for (int y = 0; y < CANVAS; y++)
            for (int x = 0; x < CANVAS; x++)
                colours.add(icon.getPixel(x, y));

        assertThat(colours, is(Set.of(MissingSprite.BLACK_ARGB, MissingSprite.MAGENTA_ARGB)));
    }

}
