package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.vanilla.gui.Mark;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link RenderResult} and the results that narrow it: {@code of} keeps each stand-in once
 * and in its natural order, and every result that places other renders - {@link AtlasResult},
 * {@link GridResult}, {@link LayoutResult} and {@link MenuResult} - answers the union of its parts'
 * stand-ins, the menu's own chrome among them, while each keeps an unmodifiable copy of the lists it
 * was built from.
 * <p>
 * Every result here is built from records, so no renderer runs and no asset is read.
 */
@DisplayName("A render's result names every stand-in drawn in it, a composite's its parts'")
class RenderResultTest {

    /** A one-pixel still every result here carries. */
    private static final @NotNull ImageData IMAGE = Timeline.still(PixelBuffer.create(1, 1));

    private static final @NotNull Substitution DIRT = Substitution.texture("minecraft:block/dirt", Possible.State.EMPTY);
    private static final @NotNull Substitution STONE = Substitution.texture("minecraft:block/stone", Possible.State.ABSENT);
    private static final @NotNull Substitution NOTHING = Substitution.subject("minecraft:nothing");
    private static final @NotNull Substitution LEAF = Substitution.leafModel("minecraft:item/nothing", "minecraft:stick");

    @Test
    @DisplayName("of keeps each stand-in once, in their natural order, and refuses an edit")
    void ofSortsAndDeduplicates() {
        RenderResult result = RenderResult.of(IMAGE, List.of(LEAF, NOTHING, STONE, DIRT, STONE, NOTHING));

        assertThat("the image is the one handed in", result.image(), is(sameInstance(IMAGE)));
        assertThat(result.substitutions(), contains(DIRT, STONE, NOTHING, LEAF));
        assertThat(result.substituted(), is(true));
        assertThrows(UnsupportedOperationException.class, () -> result.substitutions().add(DIRT));
    }

    @Test
    @DisplayName("an image wrapped alone carries no stand-in")
    void anImageAloneCarriesNoStandIn() {
        RenderResult result = RenderResult.of(IMAGE);

        assertThat(result.image(), is(sameInstance(IMAGE)));
        assertThat(result.substitutions(), is(empty()));
        assertThat(result.substituted(), is(false));
        assertThat("and an empty collection reads the same",
            RenderResult.of(IMAGE, List.of()).substituted(), is(false));
    }

    @Test
    @DisplayName("an atlas names the union of its tiles' stand-ins")
    void anAtlasNamesItsTilesStandIns() {
        AtlasResult atlas = new AtlasResult(IMAGE, new AtlasResult.Sidecar(16, 3, 3, List.of(
            tile("minecraft:stone", 0, List.of(STONE, NOTHING)),
            tile("minecraft:dirt", 1, List.of()),
            tile("minecraft:grass_block", 2, List.of(DIRT, STONE)))));

        assertThat(atlas.substitutions(), contains(DIRT, STONE, NOTHING));
        assertThat("a sheet whose tiles drew none names none",
            new AtlasResult(IMAGE, new AtlasResult.Sidecar(16, 1, 1, List.of(tile("minecraft:dirt", 0, List.of()))))
                .substituted(), is(false));
    }

    @Test
    @DisplayName("a grid names the union of its cells' stand-ins")
    void aGridNamesItsCellsStandIns() {
        GridResult grid = new GridResult(IMAGE, list(
            new GridResult.Cell(0, 0, 0, 0, 0, 16, 16, RenderResult.of(IMAGE, List.of(NOTHING, STONE))),
            new GridResult.Cell(1, 1, 0, 16, 0, 16, 16, RenderResult.of(IMAGE)),
            new GridResult.Cell(2, 0, 1, 0, 16, 16, 16, RenderResult.of(IMAGE, List.of(STONE, DIRT)))));

        assertThat(grid.substitutions(), contains(DIRT, STONE, NOTHING));
    }

    @Test
    @DisplayName("a layout names the union of its children's stand-ins, a nested composite's included")
    void aLayoutNamesItsChildrenStandIns() {
        GridResult nested = new GridResult(IMAGE, list(
            new GridResult.Cell(0, 0, 0, 0, 0, 16, 16, RenderResult.of(IMAGE, List.of(LEAF)))));
        LayoutResult layout = new LayoutResult(IMAGE, list(
            new LayoutResult.Child(0, 8, 8, 16, 16, RenderResult.of(IMAGE, List.of(STONE))),
            new LayoutResult.Child(1, 32, 8, 16, 16, nested),
            new LayoutResult.Child(2, 56, 8, 16, 16, RenderResult.of(IMAGE, List.of(STONE, NOTHING)))));

        assertThat(layout.substitutions(), contains(STONE, NOTHING, LEAF));
    }

    @Test
    @DisplayName("a menu names its chrome's stand-ins with its slots' and its icons'")
    void aMenuNamesItsChromeSlotsAndIcons() {
        MenuResult menu = new MenuResult(IMAGE, list(STONE),
            list(new MenuResult.Slot(0, MenuSlot.SLOT, 0, 0, 16, 16, RenderResult.of(IMAGE, List.of(NOTHING, STONE)))),
            list(new MenuResult.Icon(0, Mark.BUTTON, 0, 0, 16, 16, RenderResult.of(IMAGE, List.of(DIRT)))));

        assertThat(menu.substitutions(), contains(DIRT, STONE, NOTHING));
        assertThat("a menu whose chrome alone drew one names it",
            new MenuResult(IMAGE, list(LEAF), list(), list()).substitutions(), contains(LEAF));
    }

    @Test
    @DisplayName("a menu keeps its slots in slot-index order and its chrome's stand-ins once, in their natural order")
    void aMenuSortsItsSlotsAndChrome() {
        MenuResult menu = new MenuResult(IMAGE, list(NOTHING, STONE, DIRT, STONE),
            list(slot(5, MenuSlot.CONTENT), slot(0, MenuSlot.SLOT), slot(3, MenuSlot.CONTENT)),
            list(icon(1), icon(0)));

        assertThat(menu.slots().stream().map(MenuResult.Slot::index).toList(), contains(0, 3, 5));
        assertThat(menu.chrome(), contains(DIRT, STONE, NOTHING));
        assertThat("icons keep the order they were placed in",
            menu.icons().stream().map(MenuResult.Icon::index).toList(), contains(1, 0));
    }

    @Test
    @DisplayName("each composite keeps an unmodifiable copy of the lists it was built from")
    void eachCompositeCopiesItsLists() {
        GridResult.Cell cell = new GridResult.Cell(0, 0, 0, 0, 0, 16, 16, RenderResult.of(IMAGE));
        ConcurrentList<GridResult.Cell> cells = list(cell);
        GridResult grid = new GridResult(IMAGE, cells);
        cells.add(cell);
        assertThat("a grid's cells", grid.cells().size(), is(1));
        assertThrows(UnsupportedOperationException.class, () -> grid.cells().add(cell));

        LayoutResult.Child child = new LayoutResult.Child(0, 0, 0, 16, 16, RenderResult.of(IMAGE));
        ConcurrentList<LayoutResult.Child> children = list(child);
        LayoutResult layout = new LayoutResult(IMAGE, children);
        children.add(child);
        assertThat("a layout's children", layout.children().size(), is(1));
        assertThrows(UnsupportedOperationException.class, () -> layout.children().add(child));

        ConcurrentList<Substitution> chrome = list(STONE);
        ConcurrentList<MenuResult.Slot> slots = list(slot(0, MenuSlot.SLOT));
        ConcurrentList<MenuResult.Icon> icons = list(icon(0));
        MenuResult menu = new MenuResult(IMAGE, chrome, slots, icons);
        chrome.add(DIRT);
        slots.add(slot(1, MenuSlot.SLOT));
        icons.add(icon(1));
        assertThat("a menu's chrome", menu.chrome(), contains(STONE));
        assertThat("a menu's slots", menu.slots().size(), is(1));
        assertThat("a menu's icons", menu.icons().size(), is(1));
        assertThrows(UnsupportedOperationException.class, () -> menu.chrome().add(DIRT));
        assertThrows(UnsupportedOperationException.class, () -> menu.slots().add(slot(1, MenuSlot.SLOT)));
        assertThrows(UnsupportedOperationException.class, () -> menu.icons().add(icon(1)));
    }

    /**
     * Builds an atlas row for an item at one cell of a three-column sheet of 16-pixel tiles.
     *
     * @param id the item id
     * @param col the cell's column
     * @param substitutions the stand-ins its render drew
     * @return the row
     */
    private static @NotNull AtlasResult.Tile tile(@NotNull String id, int col, @NotNull List<Substitution> substitutions) {
        return new AtlasResult.Tile(id, AtlasResult.Tile.Kind.ITEM, AtlasResult.Tile.Source.ITEM_MODEL,
            col, 0, col * 16, 0, 16, 16, substitutions);
    }

    /**
     * Builds a slot at the given index holding a render with no stand-in.
     *
     * @param index the slot index
     * @param layer the layer it was placed under
     * @return the slot
     */
    private static @NotNull MenuResult.Slot slot(int index, @NotNull MenuSlot layer) {
        return new MenuResult.Slot(index, layer, 0, 0, 16, 16, RenderResult.of(IMAGE));
    }

    /**
     * Builds a button's icon at the given place in the mark list, holding a render with no stand-in.
     *
     * @param index the mark's place in the screen's mark list
     * @return the icon
     */
    private static @NotNull MenuResult.Icon icon(int index) {
        return new MenuResult.Icon(index, Mark.BUTTON, 0, 0, 16, 16, RenderResult.of(IMAGE));
    }

    /**
     * Collects the given elements into a modifiable list, the shape every composite is built from.
     *
     * @param elements the elements, in order
     * @param <T> the element type
     * @return a modifiable list holding them
     */
    @SafeVarargs
    private static <T> @NotNull ConcurrentList<T> list(@NotNull T... elements) {
        ConcurrentList<T> list = Concurrent.newList();
        list.addAll(List.of(elements));
        return list;
    }

}
