package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Pins an item to the cell it was put in.
 * <p>
 * The claim is about rendered ink rather than about a placement, so it is taken as the difference
 * between one render and another: a populated menu and the same menu empty differ in exactly the
 * pixels the item drew, and every one of them has to lie inside its own cell. A populated slot needs
 * item textures, which is what puts this on the slow side.
 */
@Tag("slow")
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("An item drawn in a slot stays inside it")
class MenuRendererItemBoxTest {

    /** what one Minecraft pixel comes to on a side */
    private static final int SCALE = MenuRenderer.PX_SCALE;

    private static ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slot(int index, String itemId) {
        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> map = Concurrent.newMap();
        map.put(index, MenuOptions.MenuSlotContent.of(itemId));
        return map;
    }

    private static PixelBuffer render(MenuOptions options) {
        return new MenuRenderer(ClientAssetsExtension.context()).render(options).getFrames().getFirst().pixels();
    }

    /**
     * Renders a menu twice, once with the slot populated and once without, and returns every pixel
     * the item is responsible for.
     */
    private static List<int[]> inkOf(MenuOptions.Type type, int index, String itemId) {
        MenuOptions empty = MenuOptions.builder().type(type).build();
        MenuOptions populated = empty.mutate().slots(slot(index, itemId)).build();

        PixelBuffer bare = render(empty);
        PixelBuffer drawn = render(populated);
        assertThat("populating a slot does not resize the panel",
            List.of(drawn.width(), drawn.height()), is(equalTo(List.of(bare.width(), bare.height()))));

        return IntStream.range(0, bare.height())
            .boxed()
            .flatMap(y -> IntStream.range(0, bare.width())
                .filter(x -> bare.getPixel(x, y) != drawn.getPixel(x, y))
                .mapToObj(x -> new int[] { x, y }))
            .toList();
    }

    private static void assertInsideCell(List<int[]> ink, MenuLayout.Cell cell, String what) {
        int inset = (cell.size() - 16) / 2;
        int left = (cell.x() + inset) * SCALE;
        int top = (cell.y() + inset) * SCALE;
        int right = left + 16 * SCALE;
        int bottom = top + 16 * SCALE;

        assertThat(what + " drew something", ink.size(), is(greaterThan(0)));
        for (int[] pixel : ink) {
            boolean inside = pixel[0] >= left && pixel[0] < right && pixel[1] >= top && pixel[1] < bottom;
            assertThat(what + " drew at " + pixel[0] + "," + pixel[1]
                + " outside its box " + left + "," + top + " to " + right + "," + bottom, inside, is(true));
        }
    }

    @Test
    @DisplayName("an item in a chest slot draws inside that slot and nowhere else")
    void anItemInAChestSlotStaysInIt() {
        MenuLayout layout = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.CHEST).build());
        assertInsideCell(inkOf(MenuOptions.Type.CHEST, 4, "minecraft:diamond"),
            layout.slotCells().get(4), "a diamond in the fifth chest slot");
    }

    @Test
    @DisplayName("an item in a crafting result draws inside the sixteen its cell of 26 centres")
    void anItemInACraftingResultStaysInTheSixteen() {
        MenuLayout layout = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_CRAFTING).build());
        assertInsideCell(inkOf(MenuOptions.Type.VANILLA_CRAFTING, 9, "minecraft:diamond_sword"),
            layout.slotCells().get(9), "a sword in the crafting result");
    }

    @Test
    @DisplayName("an item in an anvil slot draws where the anvil's own menu declares it")
    void anItemInAnAnvilSlotStaysInIt() {
        MenuLayout layout = MenuRenderer.layoutOf(MenuOptions.builder().type(MenuOptions.Type.VANILLA_ANVIL).build());
        assertInsideCell(inkOf(MenuOptions.Type.VANILLA_ANVIL, 1, "minecraft:iron_ingot"),
            layout.slotCells().get(1), "an ingot in the anvil's second input");
    }

}
