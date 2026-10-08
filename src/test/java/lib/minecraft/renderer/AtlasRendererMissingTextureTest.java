package lib.minecraft.renderer;

import dev.simplified.image.data.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.request.AtlasOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.FluidTextures;
import lib.minecraft.renderer.vanilla.PortalPalette;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the atlas keeping a tile the pack cannot fully supply and drawing the checkerboard on it,
 * as every render draws it - a block's, an item's, a fluid's and a portal's alike.
 * <p>
 * <b>This suite is the whole gate.</b> {@link AtlasRenderer} reaches no artifact the parity store
 * holds - the composed sheet does not reproduce byte-for-byte, so there is nothing for a digest to
 * hold it to - so no capture will ever catch a regression here and these rows are the only thing that
 * will.
 * <p>
 * Nothing misses on a vanilla-only stack, so the miss is manufactured: {@link RendererContext#hiding(String...)}
 * forces one texture id absent while every index and every other texture stays real. Nothing ships
 * broken either, so an unreadable file is manufactured the same way, {@link RendererContext#withTextures}
 * serving one id with no pixels.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("The atlas keeps a tile whose texture no pack supplies, drawing the checkerboard on it")
@ExtendWith(ClientAssetsExtension.class)
class AtlasRendererMissingTextureTest {

    /** A block whose icon is the isometric render, so it enters the atlas through the block pass. */
    private static final String HIDDEN_SUBJECT = "minecraft:stone";

    /** The one texture id {@link #HIDDEN_SUBJECT} draws with. */
    private static final String HIDDEN_TEXTURE = "minecraft:block/stone";

    /** A second block, left intact, so the sheet always holds a tile whatever the other row does. */
    private static final String INTACT_SUBJECT = "minecraft:oak_planks";

    /** A flat item, so the sheet's OTHER pass is exercised - the two partition on item-index membership. */
    private static final String HIDDEN_ITEM = "minecraft:stick";

    /** The one texture id {@link #HIDDEN_ITEM}'s layer stack draws with. */
    private static final String HIDDEN_ITEM_TEXTURE = "minecraft:item/stick";

    /** A block the atlas hands to the fluid renderer, drawn untinted. */
    private static final String FLUID_SUBJECT = "minecraft:lava";

    /** A block the atlas hands to the portal renderer. */
    private static final String PORTAL_SUBJECT = "minecraft:end_portal";

    private static final int TILE = 64;

    private static RendererContext context;
    private static RendererContext hidden;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
        hidden = context.hiding(HIDDEN_TEXTURE);

        assertThat(HIDDEN_SUBJECT + " is carried by the block index",
            context.findBlock(HIDDEN_SUBJECT).isPresent(), is(true));
        assertThat(HIDDEN_TEXTURE + " resolves before it is hidden",
            context.resolveTexture(HIDDEN_TEXTURE).isPresent(), is(true));
        assertThat(HIDDEN_TEXTURE + " is absent from the context the render sees",
            hidden.resolveTexture(HIDDEN_TEXTURE).isPresent(), is(false));
    }

    @Test
    @DisplayName("the tile whose texture no pack supplies is kept, wearing the checkerboard")
    void theTileIsKeptWithTheCheckerboard() {
        // The block pass walks its ids sorted, so the two tiles come out in that order.
        AtlasRenderer.Result atlas = new AtlasRenderer(hidden).renderAtlas(filtered());

        assertThat(tileIds(atlas.sidecar().tiles()), contains(INTACT_SUBJECT, HIDDEN_SUBJECT));
        assertThat(HIDDEN_SUBJECT + " wears the checkerboard", wearsCheckerboard(atlas, HIDDEN_SUBJECT), is(true));
        assertThat(INTACT_SUBJECT + " does not", wearsCheckerboard(atlas, INTACT_SUBJECT), is(false));
    }

    @Test
    @DisplayName("hiding nothing draws no checkerboard, so the harness itself moves no tile")
    void theHarnessIsInert() {
        AtlasRenderer.Result atlas = new AtlasRenderer(context.hiding()).renderAtlas(filtered());

        assertThat(tileIds(atlas.sidecar().tiles()), contains(INTACT_SUBJECT, HIDDEN_SUBJECT));
        assertThat(HIDDEN_SUBJECT + " wears its own texture", wearsCheckerboard(atlas, HIDDEN_SUBJECT), is(false));
    }

    @Test
    @DisplayName("the animated atlas keeps it too, so both renderer pairs draw the checkerboard")
    void bothRendererPairsDrawIt() {
        // A static atlas rebuilds its sub-renderers over a frame-0 texture context; an animated one uses
        // the ones built in the constructor. Both read through the same per-render wrapper.
        AtlasOptions animated = AtlasOptions.builder()
            .filter(Optional.of(filter()))
            .tileSize(TILE)
            .animated(true)
            .build();
        AtlasRenderer.Result atlas = new AtlasRenderer(hidden).renderAtlas(animated);

        assertThat(tileIds(atlas.sidecar().tiles()), contains(INTACT_SUBJECT, HIDDEN_SUBJECT));
        assertThat(HIDDEN_SUBJECT + " wears the checkerboard", wearsCheckerboard(atlas, HIDDEN_SUBJECT), is(true));
    }

    @Test
    @DisplayName("the item pass keeps its tile too, so both halves of the sheet draw the checkerboard")
    void theItemPassKeepsItsTileToo() {
        // The two passes partition on item-index membership, so a block id never proves anything about
        // the item one. This filter straddles them: the stick enters through the item pass and the
        // planks through the block pass, and only the stick's texture is hidden.
        assertThat(HIDDEN_ITEM + " is carried by the item index",
            context.findItem(HIDDEN_ITEM).isPresent(), is(true));

        AtlasRenderer.Result atlas = new AtlasRenderer(context.hiding(HIDDEN_ITEM_TEXTURE)).renderAtlas(itemAndIntact());

        assertThat(tileIds(atlas.sidecar().tiles()), containsInAnyOrder(INTACT_SUBJECT, HIDDEN_ITEM));
        assertThat(HIDDEN_ITEM + " wears the checkerboard", wearsCheckerboard(atlas, HIDDEN_ITEM), is(true));
    }

    @Test
    @DisplayName("an unreadable texture keeps its tile too, wearing the checkerboard")
    void anUnreadableTextureKeepsItsTile() {
        RendererContext unreadableItem = unreadable(HIDDEN_ITEM_TEXTURE);
        assertThat(HIDDEN_ITEM_TEXTURE + " is served and holds no pixels",
            unreadableItem.resolveTexture(HIDDEN_ITEM_TEXTURE).getState(), is(Possible.State.EMPTY));

        AtlasRenderer.Result atlas = new AtlasRenderer(unreadableItem).renderAtlas(itemAndIntact());

        assertThat(tileIds(atlas.sidecar().tiles()), containsInAnyOrder(INTACT_SUBJECT, HIDDEN_ITEM));
        assertThat(HIDDEN_ITEM + " wears the checkerboard", wearsCheckerboard(atlas, HIDDEN_ITEM), is(true));
    }

    @Test
    @DisplayName("a fluid tile whose texture no pack supplies is kept, wearing the checkerboard")
    void aFluidTileIsKeptWithTheCheckerboard() {
        // Lava takes no tint, so its still face carries the checkerboard's own magenta.
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(FLUID_SUBJECT, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .progressLogging(false)
            .build();

        AtlasRenderer.Result intact = new AtlasRenderer(context).renderAtlas(options);
        assertThat(tileIds(intact.sidecar().tiles()), containsInAnyOrder(INTACT_SUBJECT, FLUID_SUBJECT));
        assertThat("the lava wears its own texture", wearsCheckerboard(intact, FLUID_SUBJECT), is(false));

        AtlasRenderer.Result dry = new AtlasRenderer(context.hiding(FluidTextures.LAVA_STILL_TEXTURE_ID)).renderAtlas(options);
        assertThat(tileIds(dry.sidecar().tiles()), containsInAnyOrder(INTACT_SUBJECT, FLUID_SUBJECT));
        assertThat("the lava wears the checkerboard", wearsCheckerboard(dry, FLUID_SUBJECT), is(true));
    }

    @Test
    @DisplayName("a portal tile whose shader texture no pack supplies is kept, the shader sampling the checkerboard")
    void aPortalTileIsKeptWithTheCheckerboard() {
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(PORTAL_SUBJECT, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .progressLogging(false)
            .build();
        RendererContext dark = context.hiding(PortalPalette.END_SKY_TEXTURE_ID);

        AtlasRenderer.Result intact = new AtlasRenderer(context).renderAtlas(options);
        AtlasRenderer.Result drawn = new AtlasRenderer(dark).renderAtlas(options);

        assertThat(tileIds(drawn.sidecar().tiles()), containsInAnyOrder(INTACT_SUBJECT, PORTAL_SUBJECT));
        assertThat("the shader draws over the stand-in rather than the shipped sky",
            tilePixels(drawn, PORTAL_SUBJECT), is(not(tilePixels(intact, PORTAL_SUBJECT))));
    }

    @Test
    @DisplayName("a pseudo-block whose model leaves a face's reference unresolved ships its tile")
    void anUnresolvedFaceShipsItsTile() {
        // The model walk looks such a face up by its raw reference, which no pack supplies, so the face
        // draws the checkerboard and the tile is kept rather than dropped.
        List<String> unresolved = context.knownBlockIds().stream()
            .filter(id -> context.findBlock(id).map(AtlasRendererMissingTextureTest::leavesAFaceUnresolved).orElse(false))
            .sorted()
            .toList();
        assertThat("the corpus holds a block model leaving a face unresolved", unresolved, is(not(empty())));

        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(unresolved::contains))
            .tileSize(TILE)
            .progressLogging(false)
            .build();
        AtlasRenderer.Result atlas = new AtlasRenderer(context).renderAtlas(options);

        assertThat(tileIds(atlas.sidecar().tiles()), containsInAnyOrder(unresolved.toArray()));
    }

    /**
     * Pins the colour of a block tile: it is the slot icon, whose faces take the item definition's
     * tints. Mangrove leaves' definition names the constant {@code 0xFF92C648}; the plains foliage
     * sample a plain block render takes is {@code 0xFF77AB2F}. Across a magenta texel of the
     * substituted sprite the product's red to blue is the tint's own - {@code 146:72} for the
     * constant, {@code 119:47} for plains - whatever the shade.
     */
    @Test
    @DisplayName("a block tile is the slot icon, tinted by its item definition")
    void aBlockTileIsTheSlotIcon() {
        String subject = "minecraft:mangrove_leaves";
        assertThat(subject + " enters through the block pass", context.findItem(subject).isPresent(), is(false));

        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(subject)::contains))
            .tileSize(TILE)
            .build();
        AtlasRenderer.Result result = new AtlasRenderer(context.hiding("minecraft:block/mangrove_leaves")).renderAtlas(options);
        assertThat(tileIds(result.sidecar().tiles()), contains(subject));

        int tinted = 0;
        for (int pixel : RenderDigest.firstFramePixels(result.image())) {
            int red = pixel >>> 16 & 0xFF;
            int blue = pixel & 0xFF;
            // A black cell, or an edge nearly black, carries no ratio worth reading.
            if ((pixel >>> 24) != 0xFF || blue < 16) continue;
            tinted++;
            assertThat("red to blue of " + Integer.toHexString(pixel),
                (double) red / blue, closeTo(146.0 / 72.0, 0.15));
        }
        assertThat("the tile carries tinted magenta texels", tinted, greaterThan(0));
    }

    /**
     * Builds atlas options filtered to the two blocks at the small test tile.
     *
     * @return the atlas options
     */
    private static @NotNull AtlasOptions filtered() {
        return AtlasOptions.builder()
            .filter(Optional.of(filter()))
            .tileSize(TILE)
            .build();
    }

    /**
     * The filter admitting only the two blocks, so the sheet stays cheap.
     *
     * @return the id filter
     */
    private static @NotNull Predicate<String> filter() {
        return List.of(HIDDEN_SUBJECT, INTACT_SUBJECT)::contains;
    }

    /**
     * Builds atlas options filtered to the flat item and the intact block, one per pass.
     *
     * @return the atlas options
     */
    private static @NotNull AtlasOptions itemAndIntact() {
        return AtlasOptions.builder()
            .filter(Optional.of(List.of(HIDDEN_ITEM, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .build();
    }

    /**
     * Serves one texture id as a file that yields no pixels - the shape a zero-byte or corrupt PNG takes
     * - and every other id as the real context does.
     *
     * @param textureId the texture id answered empty
     * @return the context with that texture unreadable
     */
    private static @NotNull RendererContext unreadable(@NotNull String textureId) {
        return context.withTextures(
            id -> ResourceId.parse(id).id().equals(textureId) ? Possible.empty() : Possible.absent());
    }

    /**
     * Whether a block's own model declares a face whose reference resolves to no texture.
     *
     * @param block the block whose model is read
     * @return whether a face of it is left unresolved
     */
    private static boolean leavesAFaceUnresolved(@NotNull Block block) {
        ModelData model = block.model();
        return model.getElements().stream()
            .flatMap(element -> element.getFaces().values().stream())
            .anyMatch(face -> !face.getTexture().isBlank() && model.resolveTextureReference(face.getTexture()).isEmpty());
    }

    /**
     * Whether a tile carries a texel of the checkerboard's magenta, under any shade: opaque, no green,
     * and red equal to blue, which a shade scales alike. Neither intact subject carries one.
     *
     * @param atlas the composed sheet and its sidecar
     * @param id the subject whose tile is read
     * @return whether the tile carries a magenta texel
     */
    private static boolean wearsCheckerboard(@NotNull AtlasRenderer.Result atlas, @NotNull String id) {
        for (int pixel : tilePixels(atlas, id)) {
            int red = pixel >>> 16 & 0xFF;
            int green = pixel >>> 8 & 0xFF;
            int blue = pixel & 0xFF;
            if ((pixel >>> 24) == 0xFF && green == 0 && red > 0 && red == blue) return true;
        }

        return false;
    }

    /**
     * Reads one subject's tile out of a composed sheet.
     *
     * @param atlas the composed sheet and its sidecar
     * @param id the subject whose tile is read
     * @return the tile's ARGB pixels, row by row
     */
    private static int @NotNull [] tilePixels(@NotNull AtlasRenderer.Result atlas, @NotNull String id) {
        AtlasRenderer.Tile tile = atlas.sidecar().tiles().stream()
            .filter(row -> row.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " has no tile"));
        ImageFrame sheet = atlas.image().getFrames().getFirst();
        int width = sheet.pixels().width();
        int[] data = sheet.pixels().data();
        int[] pixels = new int[tile.width() * tile.height()];
        for (int y = 0; y < tile.height(); y++)
            System.arraycopy(data, (tile.y() + y) * width + tile.x(), pixels, y * tile.width(), tile.width());

        return pixels;
    }

    /**
     * The ids the sheet laid down, in the order it laid them.
     *
     * @param tiles the sidecar's tiles
     * @return each tile's subject id
     */
    private static @NotNull List<String> tileIds(@NotNull List<AtlasRenderer.Tile> tiles) {
        return tiles.stream().map(AtlasRenderer.Tile::id).toList();
    }

}
