package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A mark a screen paints beside its cells - the arrow between a crafting grid and its result, the
 * raised button that opens a recipe book, the plus between an anvil's two inputs.
 * <p>
 * Most are geometry, so a {@link Window} paints one in its own inks and a re-inked panel carries a
 * re-inked mark. That is the whole reason those are declared rather than sliced out of a texture:
 * the shipped crafting panel draws its arrow in the cell's own fill, so an arrow read off the art
 * would be frozen at vanilla's grey while the panel around it changed colour.
 * <p>
 * Two are not, and each says so by carrying its own inks. A {@link Hammer} is a picture and a
 * {@link Field} is an input widget, and neither is a shape the panel's palette has anything to say
 * about - the same reason a {@link Button}'s face carries a full-colour item over a bevel that does
 * re-ink. What re-inks is what the panel would have drawn itself.
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

        /**
         * Paints a shaft three deep across the left of the box and a triangle on its end, the rows
         * narrowing by one to a point.
         */
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            int w = box.width();
            int h = box.height();
            int head = (h + 1) / 2;
            int shaft = w - head;
            int middle = h / 2;
            int ink = palette.cellFill();

            for (int y = middle - 1; y <= middle + 1; y++)
                for (int x = 0; x < shaft; x++)
                    box.put(dest, x, y, ink);

            for (int y = 0; y < h; y++)
                for (int x = 0; x < head - Math.abs(y - middle); x++)
                    box.put(dest, shaft + x, y, ink);
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

        /**
         * Paints an outline chamfered a pixel at each corner, the light along the inside of the top
         * and left, the shadow along the bottom and right, and the panel's own fill between them.
         * <p>
         * This is {@link Window#paintCell} the other way up. A cell is sunk into the panel and a
         * button stands off it, which is one bevel read in two directions rather than two shapes.
         */
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            int w = box.width();
            int h = box.height();
            if (w < 4 || h < 4) return;

            for (int y = 2; y < h - 2; y++)
                for (int x = 2; x < w - 2; x++)
                    box.put(dest, x, y, palette.panel());

            for (int x = 2; x < w - 2; x++) {
                box.put(dest, x, 0, palette.outline());
                box.put(dest, x, 1, palette.light());
                box.put(dest, x, h - 2, palette.shadow());
                box.put(dest, x, h - 1, palette.outline());
            }

            for (int y = 2; y < h - 2; y++) {
                box.put(dest, 0, y, palette.outline());
                box.put(dest, 1, y, palette.light());
                box.put(dest, w - 2, y, palette.shadow());
                box.put(dest, w - 1, y, palette.outline());
            }

            // The chamfer: one pixel of outline closing each corner, which is what leaves the corner
            // itself unpainted so the panel shows through as it does under the frame's own corners.
            box.put(dest, 1, 1, palette.outline());
            box.put(dest, w - 2, 1, palette.outline());
            box.put(dest, 1, h - 2, palette.outline());
            box.put(dest, w - 2, h - 2, palette.outline());
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<ResourceId> icon() {
            return Optional.of(this.item);
        }

    }

    /**
     * The plus between an anvil's two input cells, drawn in the cell's own fill.
     * <p>
     * Two bars three deep crossing at the middle of a thirteen-pixel square, which is sixty-nine
     * Minecraft pixels of ink once the nine they share are counted once.
     *
     * @param x the left edge of its box
     * @param y the top edge of its box
     */
    record Plus(int x, int y) implements Decoration {

        /** the extent every plus paints */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(13, 13);

        /** how deep each of the two crossing bars is */
        private static final int BAR = 3;

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /**
         * Paints two bars crossing at the box's middle, in the cell's own fill as the arrow beside
         * it is.
         */
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            int w = box.width();
            int h = box.height();
            int ink = palette.cellFill();
            int left = (w - BAR) / 2;
            int top = (h - BAR) / 2;

            for (int y = 0; y < h; y++)
                for (int i = 0; i < BAR; i++)
                    box.put(dest, left + i, y, ink);

            for (int x = 0; x < w; x++)
                for (int i = 0; i < BAR; i++)
                    box.put(dest, x, top + i, ink);
        }

    }

    /**
     * The hammer above an anvil's title.
     * <p>
     * This one is a picture rather than a shape, so it carries its own inks and no palette re-inks
     * it - a hammer is a hammer on a panel of any colour, the way the item on a {@link Button}'s
     * face is. Nine of its ten roles belong to no palette member at all, its handle being wooden.
     * <p>
     * It is authored at fifteen pixels square and drawn at two Minecraft pixels a side, which is how
     * the shipped panel carries it: every one of its nine hundred pixels agrees with the three
     * beside it in its own two-by-two block, so the panel holds a doubled fifteen and not a thirty.
     *
     * @param x the left edge of its box
     * @param y the top edge of its box
     */
    record Hammer(int x, int y) implements Decoration {

        /**
         * The picture, one character per authored pixel, drawn at two Minecraft pixels a side. The
         * metal runs {@code W H G D K} lightest to darkest and the wood {@code T R N M}, so a row
         * reads as the thing it draws.
         */
        private static final @NotNull Stencil PICTURE = Stencil.of(2,
            ".......D.......",
            "......DWD......",
            ".....DWWHDN....",
            "....DWWWWGDR...",
            ".....KHWWWGDM..",
            "......KWWWWGD..",
            ".......NWWWWHD.",
            "......NTMWWWWWK",
            ".....NRM.KHWWK.",
            "....NTM...KWK..",
            "...NRM.....K...",
            "..NTM..........",
            ".NRM...........",
            "NTM............",
            "RM.............");

        /** The table the picture's codes resolve through, which reads no palette. */
        private static final @NotNull Stencil.Ink INK = code -> switch (code) {
            case 'W' -> 0xFFFFFFFF;
            case 'H' -> 0xFFD8D8D8;
            case 'G' -> 0xFFC1C1C1;
            case 'D' -> 0xFF444444;
            case 'K' -> 0xFF181818;
            case 'T' -> 0xFF896727;
            case 'R' -> 0xFF684E1E;
            case 'N' -> 0xFF493615;
            case 'M' -> 0xFF281E0B;
            default -> throw new IllegalArgumentException("Unknown hammer role '%c'".formatted(code));
        };

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return PICTURE.extent();
        }

        /**
         * Stamps the picture.
         * <p>
         * It reads no palette. A picture is not a shape a panel's inks have anything to say about,
         * so this is the one mark whose colours come from the mark itself - which is why a stencil
         * takes its table as an argument rather than taking a palette.
         */
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            PICTURE.stamp(dest, box, 0, 0, INK);
        }

    }

    /**
     * The text field an anvil renames through.
     * <p>
     * A sunken well two pixels deep, which is {@link Window#paintCell}'s bevel applied twice - once
     * at the box's own edge and once a pixel inside it.
     * <p>
     * The two rings answer to different owners, and the shipped sprite is what says so: its outer
     * ring is a cell's own two inks bit for bit, so the well sinks into whatever panel it sits on
     * and re-inks with it, while the olive of the inner ring and the interior belongs to no palette
     * member and is the widget's own on a panel of any colour.
     * <p>
     * The shipped panel does not carry this. What the panel has at these coordinates is a rectangle
     * of flat red the client covers on every draw, so the field is owed here exactly because the art
     * cannot supply it.
     *
     * @param x the left edge of its box
     * @param y the top edge of its box
     */
    record Field(int x, int y) implements Decoration {

        /** the extent every field paints */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(110, 16);

        /** where the text's own glyph cells open within the field, clear of the well's bevel */
        public static final int TEXT_INSET_X = 3, TEXT_INSET_Y = 4;

        /** how wide the text may run before it scrolls, which is the well's inside */
        public static final int INNER_WIDTH = 103;

        /** how many characters the anvil accepts, which is what decides the caret's own form */
        public static final int MAX_LENGTH = 50;

        /** the inner line of the well, on its top and left */
        private static final int INNER_SHADOW = 0xFF6D634D;

        /** the well's interior, which is what the text is drawn over */
        private static final int FILL = 0xFF4E4737;

        /** the inner line of the well, on its bottom and right */
        private static final int INNER_LIGHT = 0xFF29251C;

        /** the ink the text and both forms of the caret are drawn in */
        public static final int TEXT_ARGB = 0xFFFFFFFF;

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /**
         * Paints a well sunk two pixels into the panel, filled with the widget's own olive.
         * <p>
         * The two rings are one bevel rule read at two depths, and they differ in where their inks
         * come from: the outer takes the palette's, because that ring is a cell's own and the well
         * sinks into whatever panel it sits on, and the inner takes the field's, because the olive
         * belongs to no palette member. The two corners the bevel hands over are left untouched, so
         * the panel shows through as it does under the frame's own chamfers.
         */
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            int w = box.width();
            int h = box.height();
            if (w < 4 || h < 4) return;

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((x == w - 1 && y == 0) || (x == 0 && y == h - 1)) continue;
                    box.put(dest, x, y, FILL);
                }
            }

            box.sink(dest, 0, palette.cellShadow(), palette.light());
            box.sink(dest, 1, INNER_SHADOW, INNER_LIGHT);
        }

    }

}
