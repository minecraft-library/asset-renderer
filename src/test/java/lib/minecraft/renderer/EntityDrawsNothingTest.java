package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage of what the entity renderer draws for an id whose row draws nothing: the registered types
 * vanilla binds to its no-op renderer, which the shipped table carries as rows whose body mesh holds no
 * bone, and such a row a caller supplies.
 * <p>
 * Each is known and holds nothing, so a render of it draws an empty frame over the caller's background
 * and its style discovery answers the bind-only catalog - refusing it would report a defect that is not
 * there. What tells it from a miss is the lookup's empty answer, so an id that is no entity type, a type
 * that carries content vanilla draws, and a type vanilla draws that this renderer holds no row for still
 * refuse.
 * <p>
 * The in-memory context over every shipped row needs no client, so most of the class runs anywhere; the
 * two methods asking the production context take the presence gate instead, since installing
 * {@link ClientAssetsExtension} would abandon the rest of the class along with them.
 */
@DisplayName("An entity whose row draws nothing draws an empty frame")
class EntityDrawsNothingTest {

    /** The three ids vanilla 26.1 binds to its no-op renderer. */
    private static final List<String> DRAWING_NOTHING = List.of(
        "minecraft:area_effect_cloud", "minecraft:interaction", "minecraft:marker");

    /**
     * Ids no context here holds a row for: one that is no entity type, two types that carry content
     * vanilla draws, and one vanilla draws by a model this renderer has no row for.
     */
    private static final List<String> REFUSED = List.of(
        "minecraft:definitely_not_a_real_id", "minecraft:item", "minecraft:block_display", "minecraft:oak_boat");

    /** The id a caller's own row is supplied under. */
    private static final String CUSTOM = "custom:nothing";

    /** The opaque colour the coloured background fills with. */
    private static final int COLOUR = 0xFF3366CC;

    /** The group subject whose member's row is swapped for one drawing nothing. */
    private static final String PIGLIN = "minecraft:piglin";

    /** A member of the piglin's canvas group, whose raised arms reach furthest of the three. */
    private static final String ZOMBIFIED_PIGLIN = "minecraft:zombified_piglin";

    /** The in-memory context over every shipped row, the three drawing nothing included. */
    private static RendererContext shipped;

    /** The shipped row of {@code minecraft:marker}, whose body mesh holds no bone. */
    private static Entity nothing;

    @BeforeAll
    static void load() {
        ConcurrentMap<String, Entity> all = EntityModelLoader.loadAll();
        shipped = RendererContext.builder().entities(all).build();
        nothing = all.get("minecraft:marker");
        assertThat("the shipped table carries the marker's row", nothing, is(notNullValue()));
        assertThat("and it draws nothing", nothing.drawsNothing(), is(true));
    }

    @Test
    @DisplayName("each type vanilla draws nothing for answers empty and draws an empty frame over the background")
    void aTypeDrawingNothingDrawsAnEmptyFrame() {
        EntityRenderer renderer = new EntityRenderer(shipped);

        for (String id : DRAWING_NOTHING) {
            assertThat(id + " is known and draws nothing", shipped.findEntity(id).getState(), is(Possible.State.EMPTY));
            assertDrawsNothing(renderer, id);
        }
    }

    @Test
    @DisplayName("style discovery answers the bind-only catalog, and a render resolves its style there")
    void aTypeDrawingNothingResolvesItsStyleAgainstBindOnly() {
        EntityRenderer renderer = new EntityRenderer(shipped);

        for (String id : DRAWING_NOTHING) {
            assertThat(id + " answers the one shared catalog", renderer.styles(id),
                is(sameInstance(StyleCatalog.BIND_ONLY)));

            for (String style : List.of(PoseStyle.IDLE, PoseStyle.STRIDE, PoseStyle.ANIMATED)) {
                ImageData image = assertDoesNotThrow(() -> renderer.render(options(id).style(style).build()).image(),
                    id + " " + style + " refused");
                assertSinglePixel(id + " " + style, image, 0);
            }

            RendererException refused = assertThrows(RendererException.class,
                () -> renderer.render(options(id).style("croak").build()), id + " croak");
            assertThat(refused.getMessage(), containsString("has no style 'croak'"));
        }
    }

    @Test
    @DisplayName("an id no context holds a row for still refuses, a render and style discovery alike")
    void anIdWithNoRowStillRefuses() {
        EntityRenderer renderer = new EntityRenderer(shipped);

        for (String id : REFUSED) {
            assertThat(id + " has no row", shipped.findEntity(id).getState(), is(Possible.State.ABSENT));

            RendererException rendered = assertThrows(RendererException.class,
                () -> renderer.render(EntityOptions.of(id)), id + " render");
            assertThat(rendered.getMessage(), is("Entity '" + id + "' is not an entity the index resolves"));
            RendererException styles = assertThrows(RendererException.class, () -> renderer.styles(id), id + " styles");
            assertThat(styles.getMessage(), is(rendered.getMessage()));
        }
    }

    @Test
    @DisplayName("a row a caller supplies with no bone answers empty and draws an empty frame, in the builder's maps and through withEntities")
    void aCallerRowWithNoBoneDrawsAnEmptyFrame() {
        RendererContext built = RendererContext.builder().entities(Map.of(CUSTOM, nothing)).build();
        RendererContext wrapped = RendererContext.builder().build().withEntities(Map.of(CUSTOM, nothing));

        for (RendererContext context : List.of(built, wrapped)) {
            assertThat(context.findEntity(CUSTOM).getState(), is(Possible.State.EMPTY));
            EntityRenderer renderer = new EntityRenderer(context);
            assertThat(renderer.styles(CUSTOM), is(sameInstance(StyleCatalog.BIND_ONLY)));
            assertDrawsNothing(renderer, CUSTOM);
        }

        // A held row answers for its id whatever the context beneath answers for it.
        String zombie = "minecraft:zombie";
        RendererContext over = shipped.withEntities(Map.of(zombie, nothing));
        assertThat("the shipped zombie draws", shipped.findEntity(zombie).getState(), is(Possible.State.PRESENT));
        assertThat("a caller row drawing nothing stands in for it", over.findEntity(zombie).getState(),
            is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("the production context draws an empty frame for each type vanilla draws nothing for, and still refuses an id with no row")
    void theProductionContextDrawsNothingForTheThree() {
        // Only this method and the group union below need the client: installing the extension would
        // abandon the rest of the class along with them. The accessor acquires on demand, so the gate is
        // what keeps a fast run off the network.
        assumeTrue(ClientAssetsExtension.isCached(), () -> "no cached client jar at '"
            + ClientAssetsExtension.jar() + "' - ClientJarGuardTest names what writes one");
        RendererContext vanilla = ClientAssetsExtension.context();
        EntityRenderer renderer = new EntityRenderer(vanilla);

        for (String id : DRAWING_NOTHING) {
            assertThat(id + " is known and draws nothing", vanilla.findEntity(id).getState(), is(Possible.State.EMPTY));
            assertThat(renderer.styles(id), is(sameInstance(StyleCatalog.BIND_ONLY)));
            assertDrawsNothing(renderer, id);
        }

        for (String id : REFUSED)
            assertThrows(RendererException.class, () -> renderer.render(EntityOptions.of(id)), id);
    }

    @Test
    @DisplayName("a group member whose row draws nothing adds nothing to the canvas union, as a member with no row does")
    void aGroupMemberDrawingNothingIsSkipped() {
        assumeTrue(ClientAssetsExtension.isCached(), () -> "no cached client jar at '"
            + ClientAssetsExtension.jar() + "' - ClientJarGuardTest names what writes one");
        RendererContext vanilla = ClientAssetsExtension.context();
        RendererContext emptied = vanilla.withEntities(Map.of(ZOMBIFIED_PIGLIN, nothing));
        RendererContext unrowed = new RendererContext.Forwarding() {

            @Override
            public @NotNull RendererContext delegate() {
                return vanilla;
            }

            @Override
            public @NotNull Possible<Entity> findEntity(@NotNull String id) {
                return id.equals(ZOMBIFIED_PIGLIN) ? Possible.absent() : vanilla.findEntity(id);
            }

        };
        assertThat(emptied.findEntity(ZOMBIFIED_PIGLIN).getState(), is(Possible.State.EMPTY));

        // Posed, since the reach that sets the member apart is its raised arms, which the bind pose
        // leaves at its sides.
        EntityOptions group = EntityOptions.builder()
            .entityId(PIGLIN)
            .style(PoseStyle.IDLE)
            .fitMode(EntityOptions.FitMode.GROUP_BOUNDS)
            .pixelsPerBlock(32)
            .build();
        ImageData whole = new EntityRenderer(vanilla).render(group).image();
        ImageData withoutEmpty = new EntityRenderer(emptied).render(group).image();
        ImageData withoutRow = new EntityRenderer(unrowed).render(group).image();

        assertThat("the member drawing nothing is skipped as the unrowed one is",
            frame(withoutEmpty), is(frame(withoutRow)));
        assertThat("and the member it stands in for is measured where it draws",
            frame(whole), is(not(frame(withoutEmpty))));
    }

    // ------------------------------------------------------------------------------------

    /**
     * Renders one id over a transparent and a coloured background, asserting each answers the one
     * transparent pixel the renderer returns for what draws nothing, composited over its background.
     *
     * @param renderer the renderer under test
     * @param id the entity id
     */
    private static void assertDrawsNothing(@NotNull EntityRenderer renderer, @NotNull String id) {
        ImageData clear = assertDoesNotThrow(() -> renderer.render(EntityOptions.of(id)).image(), id + " refused");
        assertSinglePixel(id + " over a transparent background", clear, 0);

        ImageData coloured = assertDoesNotThrow(
            () -> renderer.render(options(id).background(Background.solid(COLOUR)).build()).image(), id + " refused");
        assertSinglePixel(id + " over a coloured background", coloured, COLOUR);
    }

    /**
     * Asserts an image is one 1x1 frame holding the given pixel.
     *
     * @param label what the image is, for the failure message
     * @param image the rendered image
     * @param argb the pixel the frame holds
     */
    private static void assertSinglePixel(@NotNull String label, @NotNull ImageData image, int argb) {
        assertThat(label + " is one frame", image.getFrames().size(), is(1));
        ImageFrame frame = image.getFrames().getFirst();
        assertThat(label + " is one pixel wide", frame.pixels().width(), is(1));
        assertThat(label + " is one pixel high", frame.pixels().height(), is(1));
        assertThat(label + " holds the background alone", RenderDigest.firstFramePixels(image),
            is(new int[] { argb }));
    }

    /**
     * Spells a render's first frame as its size and its pixels, so two renders compare by both.
     *
     * @param image the rendered image
     * @return the first frame's width, height and pixels
     */
    private static @NotNull List<Object> frame(@NotNull ImageData image) {
        ImageFrame first = image.getFrames().getFirst();
        long pixels = RenderDigest.crc32(RenderDigest.firstFramePixels(image));
        return List.of(first.pixels().width(), first.pixels().height(), pixels);
    }

    /**
     * Starts entity options for one id.
     *
     * @param id the entity id
     * @return the options builder
     */
    private static @NotNull EntityOptions.Builder options(@NotNull String id) {
        return EntityOptions.builder().entityId(id);
    }

}
