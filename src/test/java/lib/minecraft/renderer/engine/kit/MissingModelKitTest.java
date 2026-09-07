package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.MissingTexture;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link MissingModelKit}: the cube's twelve triangles over six faces, all untinted and all
 * sharing the one sprite instance, the icon's nearest upscale, and the once-per-subject diagnostic.
 * <p>
 * The reporting set is static and lives as long as the process, so every id below is unique to the
 * test that names it.
 */
@DisplayName("MissingModelKit cube and inventory picture")
class MissingModelKitTest {

    private static final int CANVAS = 64;

    @Test
    @DisplayName("the cube is twelve triangles over six faces")
    void cubeIsTwelveTrianglesOverSixFaces() {
        ConcurrentList<VisibleTriangle> cube = MissingModelKit.cube();
        assertThat(cube.size(), is(12));

        Set<Vector3f> normals = cube.stream().map(VisibleTriangle::normal).collect(Collectors.toSet());
        assertThat("one normal per face, two triangles each", normals.size(), is(6));
    }

    @Test
    @DisplayName("every face is untinted, matching the client's tint index of -1")
    void everyFaceIsUntinted() {
        for (VisibleTriangle triangle : MissingModelKit.cube())
            assertThat(triangle.tintArgb(), is(ColorMath.WHITE));
    }

    @Test
    @DisplayName("all six faces share the one sprite rather than copying it")
    void everyFaceSharesTheSprite() {
        // Identity, not equality: the cube handing each face its own copy would render the same and
        // allocate six buffers per call.
        for (VisibleTriangle triangle : MissingModelKit.cube())
            assertThat(triangle.texture() == MissingTexture.sprite(), is(true));
    }

    @Test
    @DisplayName("the icon fills a square canvas by nearest sampling")
    void iconFillsTheCanvasByNearestSampling() {
        PixelBuffer icon = MissingModelKit.icon(CANVAS);
        assertThat(icon.width(), is(CANVAS));
        assertThat(icon.height(), is(CANVAS));

        // 16 to 64 is an exact 4x, so each source texel is a 4x4 block and each quadrant a 32x32 one.
        // Sampling the middle of each quadrant reads source texels (2,2), (10,2), (2,10) and (10,10).
        assertThat("top-left", icon.getPixel(8, 8), is(MissingTexture.BLACK_ARGB));
        assertThat("top-right", icon.getPixel(40, 8), is(MissingTexture.MAGENTA_ARGB));
        assertThat("bottom-left", icon.getPixel(8, 40), is(MissingTexture.MAGENTA_ARGB));
        assertThat("bottom-right", icon.getPixel(40, 40), is(MissingTexture.BLACK_ARGB));
    }

    @Test
    @DisplayName("the icon carries exactly the sprite's two colours")
    void iconCarriesTwoColours() {
        PixelBuffer icon = MissingModelKit.icon(CANVAS);
        Set<Integer> colours = new HashSet<>();

        for (int y = 0; y < CANVAS; y++)
            for (int x = 0; x < CANVAS; x++)
                colours.add(icon.getPixel(x, y));

        assertThat(colours, is(Set.of(MissingTexture.BLACK_ARGB, MissingTexture.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("a subject is reported once however often it is drawn")
    void reportsASubjectOnce() {
        String id = "minecraft:missing_model_kit_test_reported_once";

        String first = errDuring(() -> MissingModelKit.reportSubstitution(id));
        String second = errDuring(() -> MissingModelKit.reportSubstitution(id));

        assertThat(first, containsString("Missing model for '" + id + "' - drawing the missing-model cube"));
        assertThat(second, is(emptyString()));
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static String errDuring(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
