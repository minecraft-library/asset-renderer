package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.option.AtlasTile;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.HidingRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
 * Nothing misses on a vanilla-only stack, so the miss is manufactured: {@link HidingRendererContext}
 * forces one texture id absent while every index and every other texture stays real.
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
        hidden = HidingRendererContext.hiding(context, HIDDEN_TEXTURE);

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
        List<String> unhidden = tileIds(new AtlasRenderer(HidingRendererContext.hiding(context))
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

        RendererContext hiddenItem = HidingRendererContext.hiding(context, HIDDEN_ITEM_TEXTURE);
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(List.of(HIDDEN_ITEM, INTACT_SUBJECT)::contains))
            .tileSize(TILE)
            .build();

        assertThat(tileIds(new AtlasRenderer(hiddenItem).renderAtlas(options).sidecar().tiles()),
            contains(INTACT_SUBJECT));
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
     * Renders the two-id atlas over the hiding context.
     *
     * @param substituteMissing whether the atlas draws what it cannot supply
     * @return the sidecar's tiles
     */
    private static @NotNull List<AtlasTile> atlas(boolean substituteMissing) {
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
     * The ids the sheet laid down, in the order it laid them.
     *
     * @param tiles the sidecar's tiles
     * @return each tile's subject id
     */
    private static @NotNull List<String> tileIds(@NotNull List<AtlasTile> tiles) {
        return tiles.stream().map(AtlasTile::id).toList();
    }

}
