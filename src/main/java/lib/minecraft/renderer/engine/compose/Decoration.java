package lib.minecraft.renderer.engine.compose;

import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A mark a screen paints beside its cells - the arrow between a crafting grid and its result, the
 * raised button that opens a recipe book.
 * <p>
 * Each is geometry rather than art, so a {@link Window} paints one in its own inks and a re-inked
 * panel carries a re-inked mark. That is the whole reason these are declared rather than sliced out
 * of a texture: the shipped crafting panel draws its arrow in the cell's own fill, so an arrow read
 * off the art would be frozen at vanilla's grey while the panel around it changed colour.
 * <p>
 * What a decoration is <b>not</b> is a cell. Nothing addresses one by slot index, nothing places
 * content in one, and the layout's own cell list does not carry them.
 */
public sealed interface Decoration {

    /**
     * The left edge of the box this mark paints, in Minecraft pixels from the panel's own corner.
     *
     * @return the left edge
     */
    int x();

    /**
     * The top edge of the box this mark paints, in Minecraft pixels from the panel's own corner.
     *
     * @return the top edge
     */
    int y();

    /**
     * The extent this mark paints, which is a property of the shape rather than of the screen
     * carrying it - every arrow is one arrow and every button one button.
     *
     * @return the extent in Minecraft pixels
     */
    @NotNull Window.Extent extent();

    /**
     * The item drawn over this mark, empty where it draws none.
     * <p>
     * A button's face carries one and nothing else does. It is named as an item rather than as a
     * sprite so a pack that redraws the item redraws the button, which is how the client's own
     * button is built - its sprite is the raised frame with that item's texture composited onto it.
     *
     * @return the item id, empty where the mark is drawn whole
     */
    default @NotNull Optional<ResourceId> icon() {
        return Optional.empty();
    }

    /**
     * A right-pointing arrow, drawn in the cell's own fill.
     * <p>
     * A shaft of fourteen by three with a triangle on its end, fifteen rows tall and eight deep at
     * the middle, which is a hundred and six Minecraft pixels of ink.
     *
     * @param x the left edge of its box
     * @param y the top edge of its box
     */
    record Arrow(int x, int y) implements Decoration {

        /** the extent every arrow paints */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(22, 15);

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

    }

    /**
     * A raised button carrying an item's icon, which is a cell's bevel the other way up - the light
     * on the top and left where a cell puts its shadow, and the shadow on the bottom and right.
     *
     * @param x the left edge of its box
     * @param y the top edge of its box
     * @param item the item drawn on its face
     */
    record Button(int x, int y, @NotNull ResourceId item) implements Decoration {

        /** the extent every button paints, which is the icon it holds inside its own bevel */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(20, 18);

        /** where the icon sits within the button, clear of the outline and the bevel */
        public static final int ICON_INSET_X = 2, ICON_INSET_Y = 1;

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<ResourceId> icon() {
            return Optional.of(this.item);
        }

    }

}
