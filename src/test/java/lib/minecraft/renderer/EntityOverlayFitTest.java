package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Canvas-fit coverage for an entity's conditional overlays - worn equipment and elytra wings - which
 * are folded into the fit through their own textures rather than their raw meshes, so the fit
 * reserves no room for a transparent mesh.
 *
 * <p>The parity sweep sees either failure only as a canvas that differs from vanilla's, and only on a
 * subject it holds a reference for - every equipped subject below, the zombie's wings at both ages and
 * the small armour stand's. Every other wearer here is one vanilla draws wings on and the sweep holds
 * no wings reference for, so for those these assertions are the only check:
 * <ol>
 * <li><b>Measured too large</b> - an overlay bounded by its raw mesh rather than its texture. Equipment
 *     textures are mostly transparent (a saddle is a few straps over a whole equine body), so the fit
 *     shrinks the subject to make room for a silhouette that never appears.</li>
 * <li><b>Measured in the wrong place</b> - an overlay measured through a mesh other than the one drawn.
 *     A baby's wings are the adult's halved about the feet anchor, so they hang lower and reach less
 *     far; sized as the adult's instead, the fitted box reserves room the drawn wings never fill.</li>
 * </ol>
 *
 * <p>The probe is SLACK, not clipping. An unpadded {@link EntityOptions.FitMode#OUTPUT_SIZE} fit scales
 * the measured union to fill the canvas, so a correct measurement leaves the drawn silhouette touching
 * both borders on whichever axis it fills - zero slack on that axis. Phantom bounds cannot crop
 * anything (the fit shrinks to accommodate them); they show up as leftover empty space on BOTH axes at
 * once, which is what these assertions detect.
 *
 * <p>The adults are probed at their own bounds instead, because an adult's body sets its height and
 * so the fitted axis of a square canvas, whatever its wings measure. On a
 * {@link EntityOptions.FitMode#UNION_BOUNDS} canvas padded by {@link #BOUNDS_PADDING}, each axis is
 * fitted exactly and every margin is the padding - bar the left, which also carries the
 * {@link #WING_BOX_CORNER} columns of wing box the drawn wings leave empty. Measured through the
 * baby's mesh, the wings draw past the union and that margin falls to nothing. Bounded by their raw
 * mesh, the wings measure exactly what their texture measures, so for them the first failure cannot
 * be told apart from the fit itself: the walk keeps each face's opaque-texel sub-rectangle, and the
 * wing's outward face, UV {@code 36-45 x 2-21}, is opaque on all four edges of its box along a
 * diagonal, so its sub-rectangle is the whole box and its transparent lower-outer corner is measured.
 *
 * <p>The wings draw only where the row's vanilla renderer builds the wings layer. On any other row an
 * elytra selection must leave the render exactly as bare - no wings drawn, and no room measured for
 * them.
 */
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
     * an order of magnitude larger - 8 to 16px for a mesh-bounded equipment overlay, 90 to 103px for a
     * baby's wings measured as the adult's, which leaves over a third of the canvas blank.
     */
    private static final int SLACK_TOLERANCE = 2;
    /**
     * Padding on each side of a {@link EntityOptions.FitMode#UNION_BOUNDS} canvas, which a correct
     * measurement leaves exactly empty, so wings drawn past a union measured too small eat into it.
     */
    private static final int BOUNDS_PADDING = 16;
    /**
     * Blank columns an adult's measured wing box leaves left of the drawn wings, at the default pixels
     * per block. The wing's outward face, UV {@code 36-45 x 2-21}, is opaque on all four edges of its
     * box along a diagonal, so the walk's opaque-texel sub-rectangle is the whole face and reaches its
     * transparent lower-outer corner. Not slack: vanilla's reference carries the same columns - the
     * zombie's winged reference opens on 33 blank ones - so they are part of the canvas the sweep holds
     * the renderer to.
     */
    private static final int WING_BOX_CORNER = 33;
    /** The four margins {@link #margins} measures, in its order. */
    private static final @NotNull List<String> SIDES = List.of("left", "right", "top", "bottom");

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
        entityRenderer = new EntityRenderer(ClientAssetsExtension.context());
    }

    @Test
    @DisplayName("a baby wearing an elytra fits its canvas - the wings are measured where they hang")
    void babyElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:zombie_villager", "minecraft:zombie", "minecraft:piglin"}) {
            PixelBuffer buf = render(entityId, AppearanceOptions.builder().age(Age.BABY).elytra(true).build());
            assertThat(entityId + " baby elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
            assertThat(entityId + " baby elytra: the fit must reserve no room the drawn wings do not fill",
                unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
        }
    }

    @Test
    @DisplayName("a small armour stand wearing an elytra fits its canvas - it wears the baby wings")
    void smallStandElytraFitsItsCanvas() {
        PixelBuffer buf = render("minecraft:armor_stand",
            AppearanceOptions.builder().size(Optional.of(Size.SMALL)).elytra(true).build());
        assertThat("small armour stand elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
        assertThat("small armour stand elytra: the fit must reserve no room the drawn wings do not fill",
            unusedSlack(buf), lessThanOrEqualTo(SLACK_TOLERANCE));
    }

    @Test
    @DisplayName("an adult wearing an elytra fits its canvas - measured at its own bounds, where the wings set the width")
    void adultElytraFitsItsCanvas() {
        for (String entityId : new String[]{"minecraft:zombie_villager", "minecraft:zombie", "minecraft:skeleton"}) {
            PixelBuffer buf = renderAtItsBounds(entityId, AppearanceOptions.builder().elytra(true).build());
            assertThat(entityId + " adult elytra should render a non-empty silhouette", coverage(buf), greaterThan(0));
            int[] margins = margins(buf);
            for (int side = 0; side < SIDES.size(); side++) {
                int expected = BOUNDS_PADDING + (side == 0 ? WING_BOX_CORNER : 0);
                assertThat(entityId + " adult elytra: the " + SIDES.get(side) + " margin must be " + expected
                        + " - more is room the drawn wings do not fill, less is wing the fit did not measure",
                    Math.abs(margins[side] - expected), lessThanOrEqualTo(SLACK_TOLERANCE));
            }
        }
    }

    @Test
    @DisplayName("an elytra on an entity whose renderer builds no wings layer draws nothing and leaves the canvas as it is")
    void elytraOffTheWingsRosterLeavesTheRenderBare() {
        AppearanceOptions winged = AppearanceOptions.builder().elytra(true).build();
        AppearanceOptions bare = AppearanceOptions.builder().build();
        for (String entityId : new String[]{"minecraft:cow", "minecraft:villager", "minecraft:giant"}) {
            PixelBuffer wingedBuf = renderAtItsBounds(entityId, winged);
            PixelBuffer bareBuf = renderAtItsBounds(entityId, bare);
            assertThat(entityId + " elytra: the canvas width must be the bare one", wingedBuf.width(), is(bareBuf.width()));
            assertThat(entityId + " elytra: the canvas height must be the bare one", wingedBuf.height(), is(bareBuf.height()));
            assertThat(entityId + " elytra: every pixel must be the bare one", differingPixels(wingedBuf, bareBuf), is(0));
        }
        // The control: a row on the wings roster still grows its canvas for the wings it draws.
        assertThat("zombie elytra: a wings wearer's canvas grows for the wings",
            renderAtItsBounds("minecraft:zombie", winged).width(),
            greaterThan(renderAtItsBounds("minecraft:zombie", bare).width()));
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

    @Test
    @DisplayName("a harnessed happy ghast under idle fits its canvas - the goggles are measured where they are posed")
    void harnessedGhastGogglesFitTheirCanvas() {
        // Unridden, vanilla turns the goggles up onto the ghast's brow and lifts their pivot five field
        // units, which carries their top above everything else on the subject. At the default camera
        // the body's far corners still set every edge, so this looks from behind and to the side,
        // where the goggles set the top: measured at rest they draw through the padding to the edge.
        EulerRotation behind = new EulerRotation(0f, -135f, 0f);
        int[] bind = margins(harnessedGhast(PoseStyle.BIND, behind).getFirst().pixels());
        List<ImageFrame> frames = harnessedGhast(PoseStyle.IDLE, behind);
        assertThat("idle moves the ghast, so the schedule has more than one frame", frames.size(), greaterThan(1));
        for (ImageFrame frame : frames) {
            assertThat("the harnessed ghast should render a non-empty silhouette", coverage(frame.pixels()), greaterThan(0));
            int[] margins = margins(frame.pixels());
            for (int side = 0; side < SIDES.size(); side++)
                assertThat("harnessed ghast under idle: the " + SIDES.get(side) + " margin must keep the "
                        + bind[side] + " the still pose keeps - less is goggle the fit did not measure",
                    margins[side], greaterThanOrEqualTo(bind[side] - SLACK_TOLERANCE));
        }
    }

    /** Every frame of an adult happy ghast in its default harness, fitted to its own measured union. */
    private static @NotNull List<ImageFrame> harnessedGhast(@NotNull String style, @NotNull EulerRotation rotation) {
        return entityRenderer.render(EntityOptions.builder()
            .entityId("minecraft:happy_ghast")
            .appearance(AppearanceOptions.builder().equipment(Map.of("body", "")).build())
            .style(style)
            .output(OutputOptions.builder().supersample(1).antiAlias(false).rotation(rotation).build())
            .padding(BOUNDS_PADDING)
            .fitMode(EntityOptions.FitMode.UNION_BOUNDS)
            .build()).getFrames();
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
     * Renders a subject on a canvas sized to its own measured union, {@link #BOUNDS_PADDING} clear on
     * every side - so each axis is fitted exactly and a mismeasure shows on whichever side it is on.
     *
     * @param entityId the entity to render
     * @param appearance the appearance to render it in
     * @return the first frame
     */
    private static @NotNull PixelBuffer renderAtItsBounds(@NotNull String entityId, @NotNull AppearanceOptions appearance) {
        return entityRenderer.render(EntityOptions.builder()
            .entityId(entityId)
            .appearance(appearance)
            .output(OutputOptions.builder().supersample(1).antiAlias(false).build())
            .padding(BOUNDS_PADDING)
            .fitMode(EntityOptions.FitMode.UNION_BOUNDS)
            .build()).getFrames().getFirst().pixels();
    }

    /**
     * Counts the pixels two equally sized frames disagree on.
     *
     * @param a one frame
     * @param b the other frame, of the same size
     * @return the number of positions whose pixels differ
     */
    private static int differingPixels(@NotNull PixelBuffer a, @NotNull PixelBuffer b) {
        int n = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) n++;
        return n;
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

    /**
     * Measures the empty margin on each side of the drawn silhouette.
     *
     * @param buffer the rendered frame
     * @return the blank columns left and right and the blank rows above and below, in {@link #SIDES}
     *     order
     */
    private static int @NotNull [] margins(@NotNull PixelBuffer buffer) {
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
        return new int[]{minX, w - 1 - maxX, minY, h - 1 - maxY};
    }

}
