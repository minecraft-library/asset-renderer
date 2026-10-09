package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.bake.gui.MenuLayout;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.result.MenuResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins a fill to the cells it actually reaches.
 * <p>
 * A fill draws in the cells a caller populated none of, so a menu that left none of them draws no
 * filler and has no reason to resolve the item one names. That is taken on an item nothing can
 * resolve, which draws the missing square and is named in the menu's result wherever it is drawn - a
 * menu with room for the fill names it, and the same fill on a full menu is never asked for at all.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A fill resolves for the cells it reached")
class MenuRendererFillTest {

    /**
     * A chest of one row whose fill names an id no pack in the stack registers, so drawing the filler
     * reports it.
     *
     * @param unresolvable the id the fill names
     * @return the menu options
     */
    private static @NotNull MenuOptions chestWithUnresolvableFill(@NotNull String unresolvable) {
        return MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(1)
            .fill(MenuOptions.Fill.of(unresolvable))
            .build();
    }

    /** Populates {@code count} slots, so the cells a fill would reach can be taken away. */
    private static ConcurrentMap<Integer, MenuOptions.MenuSlotContent> populate(int count) {
        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slots = Concurrent.newMap();

        for (int index = 0; index < count; index++)
            slots.put(index, MenuOptions.MenuSlotContent.of("minecraft:diamond"));

        return slots;
    }

    private static int slotCount(MenuOptions options) {
        return MenuRenderer.layoutOf(options).slotCells().size();
    }

    private static MenuResult render(MenuOptions options) {
        return new MenuRenderer(ClientAssetsExtension.context()).render(options);
    }

    @Test
    @DisplayName("a fill with a cell to draw in draws the missing square for an item it cannot resolve, and names it")
    void aFillWithRoomNamesAnUnresolvableItem() {
        String unresolvable = "minecraft:menu_renderer_fill_test_with_room";

        MenuResult menu = render(chestWithUnresolvableFill(unresolvable));

        assertThat(menu.substitutions(), contains(Substitution.subject(unresolvable)));
    }

    /**
     * The same fill on a menu with room names the id, so the full menu names nothing because it never
     * resolved the fill, and not because the id resolves.
     */
    @Test
    @DisplayName("a fill with no vacant cell never resolves its item")
    void aFillWithNoVacantCellNeverResolvesIt() {
        String unresolvable = "minecraft:menu_renderer_fill_test_full";
        MenuOptions chest = chestWithUnresolvableFill(unresolvable);
        MenuOptions full = chest.mutate().slots(populate(slotCount(chest))).build();

        MenuResult menu = render(full);

        assertThat("a fill with nowhere to draw has no item to resolve", menu.substitutions(), is(empty()));
        assertThat("the menu still drew its panel", menu.image().getFrames().getFirst().pixels().width(),
            is(greaterThan(0)));
        assertThat("the same fill with room resolves it", render(chest).substitutions(),
            contains(Substitution.subject(unresolvable)));
    }

    /**
     * The other side of it, so the skip is the vacant count and not the fill being ignored wholesale:
     * one cell left open is still one cell the fill has to draw in.
     */
    @Test
    @DisplayName("one vacant cell is still a cell the fill resolves for")
    void oneVacantCellStillResolvesTheFill() {
        String unresolvable = "minecraft:menu_renderer_fill_test_one_vacant";
        MenuOptions chest = chestWithUnresolvableFill(unresolvable);
        MenuOptions allButOne = chest.mutate().slots(populate(slotCount(chest) - 1)).build();

        assertThat("a single vacant cell is still a draw the fill owes", render(allButOne).substitutions(),
            contains(Substitution.subject(unresolvable)));
    }

    @Test
    @DisplayName("the fill is named under the content layer in every vacant cell, each holding the one fill render")
    void theFillIsNamedInEveryVacantCell() {
        String unresolvable = "minecraft:menu_renderer_fill_test_placed";
        MenuOptions chest = chestWithUnresolvableFill(unresolvable);
        MenuOptions twoTaken = chest.mutate().slots(populate(2)).build();
        int cells = slotCount(chest);

        MenuResult menu = render(twoTaken);

        assertThat("one placement per cell, in slot-index order",
            menu.slots().stream().map(MenuResult.Slot::index).toList(), is(IntStream.range(0, cells).boxed().toList()));
        assertThat("the caller's two under the slot layer and the fill under the content layer in the rest",
            menu.slots().stream().map(MenuResult.Slot::layer).toList(),
            is(Stream.concat(Stream.of(MenuSlot.SLOT, MenuSlot.SLOT), Stream.generate(() -> MenuSlot.CONTENT).limit(cells - 2)).toList()));

        MenuLayout layout = MenuRenderer.layoutOf(twoTaken);
        List<MenuResult.Slot> filled = menu.slots().stream().filter(slot -> slot.layer() == MenuSlot.CONTENT).toList();
        RenderResult fill = filled.getFirst().result();
        for (MenuResult.Slot slot : filled) {
            FramePlacement corner = MenuRenderer.inCell(layout.slotCells().get(slot.index()), fill.image());
            assertThat("cell " + slot.index() + " holds the one fill render", slot.result(), is(sameInstance(fill)));
            assertThat("at the corner its cell centres it on", List.of(slot.x(), slot.y()), is(List.of(corner.x(), corner.y())));
        }

        assertThat("which names the item it could not resolve", fill.substitutions(), contains(Substitution.subject(unresolvable)));
        assertThat("and the menu names it once", menu.substitutions(), contains(Substitution.subject(unresolvable)));
    }

}
