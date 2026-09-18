package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins a fill to the cells it actually reaches.
 * <p>
 * A fill draws in the cells a caller populated none of, so a menu that left none of them draws no
 * filler and has no reason to resolve the item one names. That is taken on an item nothing can
 * resolve, with substitution turned off so the resolve refuses out loud - a menu with room for the
 * fill refuses, and the same fill on a full menu is never asked for at all.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A fill resolves for the cells it reached")
class MenuRendererFillTest {

    /** An id no pack in the stack registers, so resolving it is what refuses rather than drawing it. */
    private static final String UNRESOLVABLE = "minecraft:not_an_item_any_pack_ships";

    /**
     * A chest of one row whose fill cannot be resolved and whose substitution is off, so any attempt
     * to draw the filler refuses instead of standing in for it.
     */
    private static MenuOptions chestWithUnresolvableFill() {
        return MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(1)
            .substituteMissing(false)
            .fill(MenuOptions.Fill.of(UNRESOLVABLE))
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

    private static ImageData render(MenuOptions options) {
        return new MenuRenderer(ClientAssetsExtension.context()).render(options);
    }

    /**
     * The premise the claim below rests on. Were the id resolvable after all, or the substitution
     * still standing in for it, a full menu would pass for reasons that have nothing to do with the
     * fill being skipped.
     */
    @Test
    @DisplayName("a fill with a cell to draw in refuses an item it cannot resolve")
    void aFillWithRoomRefusesAnUnresolvableItem() {
        MenuOptions chest = chestWithUnresolvableFill();

        assertThrows(RenderException.class, () -> render(chest),
            "a fill with every cell to draw in has to resolve its item");
    }

    @Test
    @DisplayName("a fill with no vacant cell never resolves its item")
    void aFillWithNoVacantCellNeverResolvesIt() {
        MenuOptions chest = chestWithUnresolvableFill();
        MenuOptions full = chest.mutate().slots(populate(slotCount(chest))).build();

        ImageData rendered = assertDoesNotThrow(() -> render(full),
            "a fill with nowhere to draw has no item to resolve");
        assertThat("the menu still drew its panel", rendered.getFrames().getFirst().pixels().width(),
            is(greaterThan(0)));
    }

    /**
     * The other side of it, so the skip is the vacant count and not the fill being ignored wholesale:
     * one cell left open is still one cell the fill has to draw in.
     */
    @Test
    @DisplayName("one vacant cell is still a cell the fill resolves for")
    void oneVacantCellStillResolvesTheFill() {
        MenuOptions chest = chestWithUnresolvableFill();
        MenuOptions allButOne = chest.mutate().slots(populate(slotCount(chest) - 1)).build();

        assertThrows(RenderException.class, () -> render(allButOne),
            "a single vacant cell is still a draw the fill owes");
    }

}
