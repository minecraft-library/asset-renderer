package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage for the {@link Facing} view toggles on entity rendering. There is no vanilla ground truth
 * for mirrored / flipped entity poses (the reference harness only rendered the default view), so this
 * checks what CAN be proven without a reference image:
 * <ol>
 * <li><b>{@link Facing#DEFAULT} is a no-op</b> - the regression guard that entity
 *     rendering is unchanged for existing callers.</li>
 * <li><b>Facing variants render present and uncropped</b> - the entity-specific risk is that facing
 *     desyncs the three projection-resolve sites (render camera / bounds / anchor) and the entity
 *     clips or shifts off-canvas; a padded fit that never touches the border proves they stay synced.</li>
 * <li><b>A mirror preserves silhouette area</b> - a horizontal-camera mirror of a bilaterally symmetric
 *     subject is congruent, so coverage stays within tolerance; a fit/scale desync would clip or
 *     rescale and drop it.</li>
 * </ol>
 * "Looks mirrored / flipped" (which side, correct orientation) is left to the manual sweep
 * ({@code entityProjections} facing grid). Tagged {@code slow} - boots the full pipeline.
 */
@Tag("slow")
@DisplayName("Entity Facing view toggles")
@ExtendWith(ClientAssetsExtension.class)
class EntityRendererFacingTest {

    /** Bilaterally symmetric humanoid - its silhouette mirror is congruent. */
    private static final String ENTITY = "minecraft:zombie";
    private static final int SIZE = 128;
    private static final int PADDING = 12;

    private static EntityRenderer entityRenderer;

    @BeforeAll
    static void bootstrap() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        entityRenderer = new EntityRenderer(ClientAssetsExtension.context(), entities);
    }

    private static EntityOptions.Builder base() {
        return EntityOptions.builder()
            .entityId(ENTITY)
            .output(baseRender())
            .padding(PADDING)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE);
    }

    /**
     * Builds the shared entity render frame - {@link #SIZE}px, no supersampling, no FXAA.
     *
     * @return the output options every case starts from
     */
    private static OutputOptions baseRender() {
        return OutputOptions.builder()
            .canvasSize(SIZE)
            .supersample(1)
            .antiAlias(false)
            .build();
    }

    private static PixelBuffer render(EntityOptions options) {
        return entityRenderer.render(options).getFrames().getFirst().pixels();
    }

    @Test
    @DisplayName("facing(DEFAULT) equals no facing")
    void defaultIsNoOp() {
        int[] noFacing = pixels(render(base().build()));
        int[] explicitDefault = pixels(render(base().output(baseRender().mutate().facing(Facing.DEFAULT).build()).build()));
        assertArrayEquals(noFacing, explicitDefault);
    }

    @Test
    @DisplayName("MIRRORED / FLIPPED / MIRRORED_FLIPPED render present and uncropped (the 3 resolve sites stay synced)")
    void facingVariantsRenderUnclipped() {
        for (Facing f : new Facing[]{Facing.MIRRORED, Facing.FLIPPED, Facing.MIRRORED_FLIPPED}) {
            PixelBuffer buf = render(base().output(baseRender().mutate().facing(f).build()).build());
            assertThat(f + " should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(f + " must not touch the canvas border (fit desync would clip)",
                borderCoverage(buf), equalTo(0));
        }
    }

    @Test
    @DisplayName("Long entity (cod) fits uncropped under the oblique projections (+mirror) - the lens-aware fit")
    void obliqueLongEntityUncropped() {
        // cod is long on its z-axis; oblique's depth-shear (cavalier/cabinet diagonal, military vertical)
        // blows the silhouette past the raw rotated bounds - routing oblique through rasterizeFitted's
        // 2D auto-fit keeps it in-canvas.
        for (Projection p : new Projection[]{Projection.CABINET, Projection.CAVALIER, Projection.MILITARY})
            for (Facing f : new Facing[]{Facing.DEFAULT, Facing.MIRRORED}) {
                PixelBuffer buf = entityRenderer.render(EntityOptions.builder()
                    .entityId("minecraft:cod")
                    .output(OutputOptions.builder()
                        .canvasSize(SIZE)
                        .supersample(1)
                        .antiAlias(false)
                        .projection(p)
                        .facing(f)
                        .build())
                    .padding(PADDING).fitMode(EntityOptions.FitMode.OUTPUT_SIZE)
                    .build()).getFrames().getFirst().pixels();
                assertThat(p + " " + f + " should render a non-empty silhouette", coverage(buf), greaterThan(0));
                assertThat(p + " " + f + " must fit uncropped (lens-aware bounds)", borderCoverage(buf), equalTo(0));
            }
    }

    @Test
    @DisplayName("Long entity (cod) fits uncropped under strong-perspective PORTRAIT (+mirror) - the unified Fit2D")
    void perspectiveLongEntityUncropped() {
        // PORTRAIT is a full pinhole (amount 1, cameraDistance 2.375). A 3D model-scale fit cannot
        // correct foreshortening in one pass, so the perspective path routes through rasterizeFitted's
        // 2D fit, which measures the actual foreshortened silhouette and fills the canvas - which is
        // what keeps cod and its mirror uncropped.
        for (Facing f : new Facing[]{Facing.DEFAULT, Facing.MIRRORED}) {
            PixelBuffer buf = entityRenderer.render(EntityOptions.builder()
                .entityId("minecraft:cod")
                .output(OutputOptions.builder()
                    .canvasSize(SIZE)
                    .supersample(1)
                    .antiAlias(false)
                    .projection(Projection.PORTRAIT)
                    .facing(f)
                    .build())
                .padding(PADDING).fitMode(EntityOptions.FitMode.OUTPUT_SIZE)
                .build()).getFrames().getFirst().pixels();
            assertThat("PORTRAIT " + f + " should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat("PORTRAIT " + f + " must fit uncropped (unified Fit2D)", borderCoverage(buf), equalTo(0));
        }
    }

    @Test
    @DisplayName("MIRRORED preserves silhouette area (congruent mirror) - fit/scale stays synced")
    void mirrorPreservesCoverage() {
        int covDefault = coverage(render(base().output(baseRender().mutate().facing(Facing.DEFAULT).build()).build()));
        int covMirrored = coverage(render(base().output(baseRender().mutate().facing(Facing.MIRRORED).build()).build()));
        float ratio = Math.abs(covMirrored - covDefault) / (float) covDefault;
        assertThat("mirror coverage drift (fit desync would clip / rescale)", (double) ratio, lessThan(0.15));
    }

    /**
     * Reads a frame's whole pixel array, so two renders can be compared byte for byte.
     *
     * @param b the rendered frame
     * @return the frame's ARGB pixels in row-major order
     */
    private static int[] pixels(PixelBuffer b) {
        return b.getPixels(0, 0, b.width(), b.height(), null, 0, 0);
    }

    /**
     * Counts the non-transparent pixels in a rendered frame.
     *
     * @param b the rendered frame
     * @return the number of pixels carrying a non-zero alpha
     */
    private static int coverage(PixelBuffer b) {
        int n = 0;
        for (int y = 0; y < b.height(); y++)
            for (int x = 0; x < b.width(); x++)
                if ((b.getPixel(x, y) >>> 24) != 0) n++;
        return n;
    }

    /**
     * Counts the non-transparent pixels on the frame's 1px border, which is zero for a padded,
     * uncropped fit.
     *
     * @param b the rendered frame
     * @return the number of non-transparent border pixels
     */
    private static int borderCoverage(PixelBuffer b) {
        int n = 0;
        int w = b.width(), h = b.height();
        for (int x = 0; x < w; x++) {
            if ((b.getPixel(x, 0) >>> 24) != 0) n++;
            if ((b.getPixel(x, h - 1) >>> 24) != 0) n++;
        }
        for (int y = 0; y < h; y++) {
            if ((b.getPixel(0, y) >>> 24) != 0) n++;
            if ((b.getPixel(w - 1, y) >>> 24) != 0) n++;
        }
        return n;
    }

}
