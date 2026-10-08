package lib.minecraft.renderer;

import dev.simplified.util.Possible;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.request.AtlasOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the atlas dropping a tile it cannot draw faithfully, and of the one copy that decides
 * whether it can.
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
@DisplayName("The atlas drops a tile whose texture no pack supplies")
@ExtendWith(ClientAssetsExtension.class)
class AtlasRendererMissingTextureTest {

    /** A block whose icon is the isometric render, so it enters the atlas through the block pass. */
    private static final String HIDDEN_SUBJECT = "minecraft:stone";

    /** The one texture id {@link #HIDDEN_SUBJECT} draws with. */
    private static final String HIDDEN_TEXTURE = "minecraft:block/stone";

    /** A second block, left intact, so the sheet never renders zero tiles and refuses to compose. */
    private static final String INTACT_SUBJECT = "minecraft:oak_planks";

    /** A flat item, so the sheet's OTHER pass is exercised - the two partition on item-index membership. */
    private static final String HIDDEN_ITEM = "minecraft:stick";

    /** The one texture id {@link #HIDDEN_ITEM}'s layer stack draws with. */
    private static final String HIDDEN_ITEM_TEXTURE = "minecraft:item/stick";

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
    @DisplayName("by default the unrenderable tile is dropped and the rest of the sheet survives")
    void theTileIsDropped() {
        // The per-tile catch is what drops it, and it was already there - turning the substitution off
        // is what gives it something to catch again.
        assertThat(tileIds(atlas(false)), contains(INTACT_SUBJECT));
    }

    @Test
    @DisplayName("asking the atlas to substitute keeps the tile instead")
    void substitutingKeepsTheTile() {
        // The other half: the drop is the flag's doing rather than the id being unrenderable outright.
        // The block pass walks its ids sorted, so the survivor's position is the same either way and a
        // drop never reorders what is left.
        assertThat(tileIds(atlas(true)), contains(INTACT_SUBJECT, HIDDEN_SUBJECT));
    }

    @Test
    @DisplayName("hiding nothing drops nothing, so the harness itself loses no tile")
    void theHarnessIsInert() {
        AtlasOptions options = filtered(false);
        List<String> unhidden = tileIds(new AtlasRenderer(context.hiding())
            .renderAtlas(options).sidecar().tiles());

        assertThat(unhidden, contains(INTACT_SUBJECT, HIDDEN_SUBJECT));
    }

    @Test
    @DisplayName("the animated atlas drops it too, so both renderer pairs read the flag")
    void bothRendererPairsHonourIt() {
        // A static atlas rebuilds its four sub-renderers over StaticTextureContext; an animated one
        // uses the pair built in the constructor. The flag rides the per-tile options rather than
        // either pair, which is what this row is here to prove - one of them reading a stale default
        // would keep the tile.
        AtlasOptions animated = AtlasOptions.builder()
            .filter(Optional.of(filter()))
            .tileSize(TILE)
            .animated(true)
            .build();

        assertThat(tileIds(new AtlasRenderer(hidden).renderAtlas(animated).sidecar().tiles()),
            contains(INTACT_SUBJECT));
    }

    @Test
    @DisplayName("the item pass drops too, so both halves of the sheet honour the flag")
    void theItemPassDropsAsWell() {
        // The two passes partition on item-index membership, so a block id never proves anything about
        // the item one. This filter straddles them: the stick enters through the item pass and the
        // planks through the block pass, and only the stick's texture is hidden.
        assertThat(HIDDEN_ITEM + " is carried by the item index",
            context.findItem(HIDDEN_ITEM).isPresent(), is(true));

        RendererContext hiddenItem = context.hiding(HIDDEN_ITEM_TEXTURE);
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(HIDDEN_ITEM, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .build();

        assertThat(tileIds(new AtlasRenderer(hiddenItem).renderAtlas(options).sidecar().tiles()),
            contains(INTACT_SUBJECT));
    }

    @Test
    @DisplayName("an unreadable texture drops its tile too, rather than aborting the sheet")
    void anUnreadableTextureDropsItsTile() {
        // A served file that does not decode is refused as a renderer exception, which the same
        // per-tile catch skips; nothing else on the sheet notices it.
        RendererContext unreadableItem = unreadable(HIDDEN_ITEM_TEXTURE);
        assertThat(HIDDEN_ITEM_TEXTURE + " is served and holds no pixels",
            unreadableItem.resolveTexture(HIDDEN_ITEM_TEXTURE).getState(), is(Possible.State.EMPTY));

        assertThat(tileIds(new AtlasRenderer(unreadableItem).renderAtlas(itemAndIntact(false)).sidecar().tiles()),
            contains(INTACT_SUBJECT));
    }

    @Test
    @DisplayName("asking the atlas to substitute keeps the unreadable tile instead")
    void substitutingKeepsTheUnreadableTile() {
        assertThat(tileIds(new AtlasRenderer(unreadable(HIDDEN_ITEM_TEXTURE)).renderAtlas(itemAndIntact(true)).sidecar().tiles()),
            containsInAnyOrder(INTACT_SUBJECT, HIDDEN_ITEM));
    }

    @Test
    @DisplayName("a render that does not substitute refuses an unreadable texture as undecodable")
    void anUnreadableTextureIsRefusedInItsOwnWords() {
        RendererContext unreadableItem = unreadable(HIDDEN_ITEM_TEXTURE);

        RenderException refusal = assertThrows(RenderException.class,
            () -> new ItemRenderer(unreadableItem).render(flat(false)));
        assertThat(refusal.getMessage(), containsString("could not be decoded"));
        assertThat(refusal.getMessage(), containsString("item/stick"));
        assertDoesNotThrow(() -> new ItemRenderer(unreadableItem).render(flat(true)),
            "substituting, the same icon draws the checkerboard rather than refusing");
    }

    @Test
    @DisplayName("a block-backed faithful icon carries the flag through the hand-copied block options")
    void adaptToBlockCarriesTheFlag() {
        // GuiIcon routes a block-backed id to the isometric BlockRenderer through adaptToBlock, which
        // copies ItemOptions into BlockOptions FIELD BY FIELD. A field left out of that copy is not a
        // compile error - the builder answers with its own default - so this is the row that fails if
        // and only if the flag stops being copied. Nothing else in the suite would notice.
        assertThat("the id must take GuiIcon's block branch rather than its item branch",
            context.findItem(HIDDEN_SUBJECT).isPresent(), is(false));

        assertThrows(RenderException.class, () -> new ItemRenderer(hidden).render(icon(false)),
            "the flag must survive adaptToBlock and refuse in the block render");
        assertDoesNotThrow(() -> new ItemRenderer(hidden).render(icon(true)),
            "substituting, the same icon draws rather than refusing");
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
            .substituteMissing(true)
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
     * Renders the two-id atlas over the hiding context.
     *
     * @param substituteMissing whether the atlas draws what it cannot supply
     * @return the sidecar's tiles
     */
    private static @NotNull List<AtlasRenderer.Tile> atlas(boolean substituteMissing) {
        return new AtlasRenderer(hidden).renderAtlas(filtered(substituteMissing)).sidecar().tiles();
    }

    /**
     * Builds atlas options filtered to the two subjects at the small test tile.
     *
     * @param substituteMissing whether the atlas draws what it cannot supply
     * @return the atlas options
     */
    private static @NotNull AtlasOptions filtered(boolean substituteMissing) {
        return AtlasOptions.builder()
            .filter(Optional.of(filter()))
            .tileSize(TILE)
            .substituteMissing(substituteMissing)
            .build();
    }

    /**
     * The filter admitting only the two subjects, so the sheet stays cheap and never composes zero
     * tiles.
     *
     * @return the id filter
     */
    private static @NotNull Predicate<String> filter() {
        return List.of(HIDDEN_SUBJECT, INTACT_SUBJECT)::contains;
    }

    /**
     * Builds a faithful-icon render of the hidden subject.
     *
     * @param substituteMissing whether an absent texture is drawn rather than refused
     * @return the item options
     */
    private static @NotNull ItemOptions icon(boolean substituteMissing) {
        return ItemOptions.builder()
            .itemId(HIDDEN_SUBJECT)
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(TILE).build())
            .substituteMissing(substituteMissing)
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
     * Builds atlas options filtered to the flat item and the intact block, one per pass.
     *
     * @param substituteMissing whether the atlas draws what it cannot supply
     * @return the atlas options
     */
    private static @NotNull AtlasOptions itemAndIntact(boolean substituteMissing) {
        return AtlasOptions.builder()
            .filter(Optional.of(List.of(HIDDEN_ITEM, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .substituteMissing(substituteMissing)
            .build();
    }

    /**
     * Builds a flat icon render of the flat item, which reads its layer sprite directly.
     *
     * @param substituteMissing whether a texture with no pixels is drawn rather than refused
     * @return the item options
     */
    private static @NotNull ItemOptions flat(boolean substituteMissing) {
        return ItemOptions.builder()
            .itemId(HIDDEN_ITEM)
            .type(ItemOptions.Type.GUI_2D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(TILE).build())
            .substituteMissing(substituteMissing)
            .build();
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
