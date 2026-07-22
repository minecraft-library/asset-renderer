package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.option.Age;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that an entity's conditional overlays - worn equipment and elytra wings - are folded into
 * the canvas fit as what they DRAW, so the fit reserves no room for geometry that never appears.
 *
 * <p>Both failure modes this guards are invisible to the parity sweep, which renders one default
 * appearance per entity and therefore equips nothing and renders no babies:
 * <ol>
 * <li><b>Measured too large</b> - an overlay bounded by its raw mesh rather than its texture. Equipment
 *     textures are mostly transparent (a saddle is a few straps over a whole equine body), so the fit
 *     shrinks the subject to make room for a silhouette that never appears.</li>
 * <li><b>Measured in the wrong place</b> - the baby elytra seat. The wings drop onto the baby's lower
 *     shoulders; sized where they are authored instead, the fitted box sits too high by exactly the
 *     drop and the subject is pushed to the bottom of it.</li>
 * </ol>
 *
 * <p>The probe is SLACK, not clipping. An unpadded {@link EntityOptions.FitMode#OUTPUT_SIZE} fit scales
 * the measured union to fill the canvas, so a correct measurement leaves the drawn silhouette touching
 * both borders on whichever axis it fills - zero slack on that axis. Phantom bounds cannot crop
 * anything (the fit shrinks to accommodate them); they show up as leftover empty space on BOTH axes at
 * once, which is what these assertions detect. Tagged {@code slow} - boots the full pipeline.
 */
@Tag("slow")
@DisplayName("Entity overlay canvas fit")
class EntityOverlayFitTest {

    private static final File CACHE_ROOT = new File("cache/it");
    /**
     * Canvas size. Deliberately generous: the leftover slack a mismeasured union leaves scales with the
     * canvas while the rasterizer rounding it is measured against does not, so a larger canvas separates
     * the two by a wider margin.
     */
    private static final int SIZE = 256;
    /** Unpadded, so a correctly measured silhouette fills its fitted axis exactly. */
    private static final int PADDING = 0;
    /**
     * Leeway for rasterizer edge coverage and fit rounding on a {@link #SIZE}px canvas - a correct fit
     * lands within a pixel or two of filling its axis. The mismeasurement this catches is an order of
     * magnitude larger: an unseated baby elytra leaves roughly a quarter of the canvas blank.
     */
    private static final int SLACK_TOLERANCE = 4;

    private static EntityRenderer entityRenderer;

    @BeforeAll
    static void bootstrap() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder().version("26.1").cacheRoot(CACHE_ROOT).build());
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        entityRenderer = new EntityRenderer(PipelineRendererContext.of(result), entities);
    }

    @Test
    @DisplayName("a baby wearing an elytra fits its canvas - the wings are measured where they are seated")
    void babyElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:piglin"}) {
            PixelBuffer buf = render(entityId, EntityAppearance.builder().age(Age.BABY).elytra(true).build());
            assertThat(entityId + " baby elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(entityId + " baby elytra: the fit must reserve no room above the seated wings",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    @Test
    @DisplayName("an adult wearing an elytra fits its canvas - the control the baby seat must not disturb")
    void adultElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:skeleton"}) {
            PixelBuffer buf = render(entityId, EntityAppearance.builder().elytra(true).build());
            assertThat(entityId + " adult elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(entityId + " adult elytra: the fit must reserve no room for unauthored wing space",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    @Test
    @DisplayName("equipped mob armor and saddles fit their canvas - measured by their texture, not their mesh")
    void equippedOverlaysFitTheirCanvas() {
        // skeleton_horse is the sharp case on both counts: its own texture is full of gaps, so its
        // alpha-tight body silhouette is far smaller than its geometry, and a saddle mesh spans the whole
        // equine body including head and ears while drawing only a few straps.
        for (Map.Entry<String, String> equipped : Map.of(
            "minecraft:skeleton_horse", "saddle",
            "minecraft:horse", "body",
            "minecraft:pig", "saddle",
            "minecraft:wolf", "body"
        ).entrySet()) {
            PixelBuffer buf = render(equipped.getKey(), EntityAppearance.builder()
                .equipment(Map.of(equipped.getValue(), ""))
                .build());
            assertThat(equipped + " should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(equipped + ": the fit must reserve no room for the overlay's transparent mesh",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    private static @NotNull PixelBuffer render(@NotNull String entityId, @NotNull EntityAppearance appearance) {
        return entityRenderer.render(EntityOptions.builder()
            .entityId(Optional.of(entityId))
            .appearance(appearance)
            .output(OutputOptions.builder().canvasSize(SIZE).supersample(1).antiAlias(false).build())
            .padding(PADDING)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE)
            .build()).getFrames().getFirst().pixels();
    }

    /** Count of non-transparent pixels. */
    private static int coverage(@NotNull PixelBuffer buffer) {
        int n = 0;
        for (int y = 0; y < buffer.height(); y++)
            for (int x = 0; x < buffer.width(); x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) n++;
        return n;
    }

    /**
     * The empty margin left over on the tighter axis - the number of blank rows above plus below, or
     * blank columns left plus right, whichever is smaller. An unpadded fit scales the measured union to
     * fill the canvas, so a union equal to what is drawn leaves ZERO slack on the axis it fills; a union
     * inflated by geometry that never draws leaves slack on both axes at once.
     *
     * @param buffer the rendered frame
     * @return the smaller of the vertical and horizontal leftover margins, in pixels
     */
    private static int unusedSlack(@NotNull PixelBuffer buffer) {
        int w = buffer.width();
        int h = buffer.height();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((buffer.getPixel(x, y) >>> 24) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX < 0) return Math.max(w, h);
        return Math.min(minY + (h - 1 - maxY), minX + (w - 1 - maxX));
    }

}
