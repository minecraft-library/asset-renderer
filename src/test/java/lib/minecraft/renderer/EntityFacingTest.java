package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link Facing} view toggles on entity rendering. There is no vanilla ground truth for
 * mirrored / flipped entity poses (the reference harness only rendered the default view), so this
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
class EntityFacingTest {

    private static final File CACHE_ROOT = new File("cache/it");
    /** Bilaterally symmetric humanoid - its silhouette mirror is congruent. */
    private static final String ENTITY = "minecraft:zombie";
    private static final int SIZE = 128;
    private static final int PADDING = 12;

    private static EntityRenderer entityRenderer;

    @BeforeAll
    static void bootstrap() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder().version("26.1").cacheRoot(CACHE_ROOT).build());
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        entityRenderer = new EntityRenderer(PipelineRendererContext.of(result), entities);
    }

    private static EntityOptions.EntityOptionsBuilder base() {
        return EntityOptions.builder()
            .entityId(Optional.of(ENTITY))
            .output(baseRender())
            .padding(PADDING)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE);
    }

    /** The shared entity render frame - {@link #SIZE}px, no supersampling, no FXAA. */
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
                    .entityId(Optional.of("minecraft:cod"))
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
        // The Option 2 win: PORTRAIT is a full pinhole (amount 1, cameraDistance 2.375). A 3D model-scale
        // fit could not correct the foreshortening in one pass, so cod overflowed the canvas. Routing the
        // perspective path through rasterizeFitted's 2D Fit2D measures the actual foreshortened
        // silhouette and fills the canvas - so cod (and its mirror) now fit uncropped.
        for (Facing f : new Facing[]{Facing.DEFAULT, Facing.MIRRORED}) {
            PixelBuffer buf = entityRenderer.render(EntityOptions.builder()
                .entityId(Optional.of("minecraft:cod"))
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

    private static int[] pixels(PixelBuffer b) {
        return b.getPixels(0, 0, b.width(), b.height(), null, 0, 0);
    }

    /** Count of non-transparent pixels. */
    private static int coverage(PixelBuffer b) {
        int n = 0;
        for (int y = 0; y < b.height(); y++)
            for (int x = 0; x < b.width(); x++)
                if ((b.getPixel(x, y) >>> 24) != 0) n++;
        return n;
    }

    /** Count of non-transparent pixels on the 1px canvas border (should be 0 for a padded, uncropped fit). */
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
