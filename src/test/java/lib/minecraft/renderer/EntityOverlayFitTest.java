package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Canvas-fit coverage for an entity's conditional overlays - worn equipment and elytra wings - which
 * are folded into the fit as what they DRAW, so the fit reserves no room for geometry that never
 * appears.
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
@ExtendWith(ClientAssetsExtension.class)
class EntityOverlayFitTest {

    /**
     * Canvas size. Deliberately generous: the leftover slack a mismeasured union leaves scales with the
     * canvas while the rasterizer rounding it is measured against does not, so a larger canvas separates
     * the two by a wider margin.
     */
    private static final int SIZE = 256;
    /** Unpadded, so a correctly measured silhouette fills its fitted axis exactly. */
    private static final int PADDING = 0;
    /**
     * Leeway for rasterizer edge coverage on the filling axis. Every subject here measures 0 when the
     * overlays are folded in correctly, so this is pure headroom; the mismeasurements it must catch are
     * an order of magnitude larger - 8 to 16px for a mesh-bounded equipment overlay, 72 to 87px for an
     * unseated baby elytra, which leaves roughly a quarter of the canvas blank.
     */
    private static final int SLACK_TOLERANCE = 2;

    /**
     * The equipped subjects, in a fixed order so a failure names the same subject on every run.
     *
     * <p>Horse body armor is deliberately absent. Alone among the equipment subjects it leaves a stable
     * ~3px of residual slack: its armor mesh is grow-inflated past the body it covers, and the outermost
     * band of that inflated surface draws transparent, so the measured union sits a little outside the
     * visible silhouette. That is a measurement-versus-visibility skew rather than the phantom-geometry
     * bug under test, and admitting it would cost the tolerance the headroom that makes these
     * assertions worth having. Every subject below measures 0.
     */
    private static final @NotNull List<Equipped> EQUIPPED = List.of(
        new Equipped("minecraft:skeleton_horse", "saddle"),
        new Equipped("minecraft:zombie_horse", "saddle"),
        new Equipped("minecraft:pig", "saddle"),
        new Equipped("minecraft:camel", "saddle"),
        new Equipped("minecraft:strider", "saddle"),
        new Equipped("minecraft:wolf", "body"),
        new Equipped("minecraft:nautilus", "body"),
        new Equipped("minecraft:llama", "body"));

    private static EntityRenderer entityRenderer;

    /**
     * One equipped subject.
     *
     * @param entityId the entity this row renders
     * @param slot the equipment slot this row fills with its default material
     */
    private record Equipped(@NotNull String entityId, @NotNull String slot) {
        @Override
        public @NotNull String toString() {
            return this.entityId + "=" + this.slot;
        }
    }

    @BeforeAll
    static void bootstrap() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        entityRenderer = new EntityRenderer(ClientAssetsExtension.context(), entities);
    }

    @Test
    @DisplayName("a baby wearing an elytra fits its canvas - the wings are measured where they are seated")
    void babyElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:piglin"}) {
            PixelBuffer buf = render(entityId, AppearanceOptions.builder().age(Age.BABY).elytra(true).build());
            assertThat(entityId + " baby elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(entityId + " baby elytra: the fit must reserve no room above the seated wings",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    @Test
    @DisplayName("an adult wearing an elytra fits its canvas - the control the baby seat must not disturb")
    void adultElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:skeleton"}) {
            PixelBuffer buf = render(entityId, AppearanceOptions.builder().elytra(true).build());
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
        for (Equipped equipped : EQUIPPED) {
            PixelBuffer buf = render(equipped.entityId(), AppearanceOptions.builder()
                .equipment(Map.of(equipped.slot(), ""))
                .build());
            assertThat(equipped + " should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(equipped + ": the fit must reserve no room for the overlay's transparent mesh",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    private static @NotNull PixelBuffer render(@NotNull String entityId, @NotNull AppearanceOptions appearance) {
        return entityRenderer.render(EntityOptions.builder()
            .entityId(entityId)
            .appearance(appearance)
            .output(OutputOptions.builder().canvasSize(SIZE).supersample(1).antiAlias(false).build())
            .padding(PADDING)
            .fitMode(EntityOptions.FitMode.OUTPUT_SIZE)
            .build()).getFrames().getFirst().pixels();
    }

    /**
     * Counts the non-transparent pixels in a rendered frame.
     *
     * @param buffer the rendered frame
     * @return the number of pixels carrying a non-zero alpha
     */
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
