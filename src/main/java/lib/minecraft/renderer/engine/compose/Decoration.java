package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A mark a screen paints beside its cells - the arrow between a crafting grid and its result, the
 * raised button that opens a recipe book, the plus between an anvil's two inputs.
 * <p>
 * Each constant is a kind rather than a placement: what a mark paints and how big it comes out are
 * properties of the shape, so every arrow is one arrow and every button one button. Where one sits
 * on a screen is a {@link MenuLayout.Mark}.
 * <p>
 * Most are geometry, so a mark is painted in the panel's own inks and a re-inked panel carries a
 * re-inked mark. That is the whole reason those are declared rather than sliced out of a texture:
 * the shipped crafting panel draws its arrow in the cell's own fill, so an arrow read off the art
 * would be frozen at vanilla's grey while the panel around it changed colour.
 * <p>
 * Two are not, and each says so by carrying its own inks. {@link #HAMMER} is a picture and the
 * inside of {@link #FIELD} is an input widget, and neither is a shape a palette has anything to say
 * about - the same reason {@link #BUTTON}'s face carries a full-colour item over a bevel that does
 * re-ink. What re-inks is what the panel would have drawn itself.
 * <p>
 * A mark knows how to paint itself and never whether to. A {@link Window} sliced from art paints
 * none, because whatever marks that art carries are already in the panel it slices, and nothing
 * handed to {@link #paint} says which window is asking - so the window keeps that decision.
 * <p>
 * What a decoration is <b>not</b> is a cell. Nothing addresses one by slot index, nothing places
 * content in one, and the layout's own cell list does not carry them.
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
public enum Decoration {

    /**
     * A right-pointing arrow, drawn in the cell's own fill.
     * <p>
     * A shaft of fourteen by three with a triangle on its end, fifteen rows tall and eight deep at
     * the middle, which is a hundred and six Minecraft pixels of ink. The crafting table and the
     * anvil both draw it, at their own positions and to the same pixels.
     */
    ARROW {

        /** the extent every arrow paints */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(22, 15);

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /**
         * {@inheritDoc}
         * <p>
         * A shaft three deep across the left of the box and a triangle on its end, the rows
         * narrowing by one to a point.
         */
        @Override
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

    },

    /**
     * A raised button carrying an item's icon, which is a cell's bevel the other way up - the light
     * on the top and left where a cell puts its shadow, and the shadow on the bottom and right.
     * <p>
     * Its face carries an item rather than a sprite, because the client's own button is that: the
     * sprite is this frame with an item's texture composited onto it, so naming the item hands a
     * pack that redraws it a redrawn button in the same move.
     */
    BUTTON {

        /** the extent every button paints, which is the icon it holds inside its own bevel */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(20, 18);

        /** where the icon sits within the button, clear of the outline and the bevel */
        private static final @NotNull Inset FACE = new Inset(2, 1);

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<Inset> iconInset() {
            return Optional.of(FACE);
        }

        /**
         * {@inheritDoc}
         * <p>
         * An outline chamfered a pixel at each corner, the light along the inside of the top and
         * left, the shadow along the bottom and right, and the panel's own fill between them. This
         * is {@link Window#paintCell} the other way up - a cell is sunk into the panel and a button
         * stands off it, which is one bevel read in two directions rather than two shapes.
         */
        @Override
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

    },

    /**
     * The plus between an anvil's two input cells, drawn in the cell's own fill.
     * <p>
     * Two bars three deep crossing at the middle of a thirteen-pixel square, which is sixty-nine
     * Minecraft pixels of ink once the nine they share are counted once.
     */
    PLUS {

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
         * {@inheritDoc}
         * <p>
         * Two bars crossing at the box's middle, in the cell's own fill as the arrow beside it is.
         */
        @Override
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

    },

    /**
     * The hammer above an anvil's title.
     * <p>
     * This one is a picture rather than a shape, so it carries its own inks and no palette re-inks
     * it - a hammer is a hammer on a panel of any colour, the way the item on {@link #BUTTON}'s face
     * is. Nine of its roles belong to no palette member at all, its handle being wooden.
     * <p>
     * It is authored at fifteen pixels square and drawn at two Minecraft pixels a side, which is how
     * the shipped panel carries it: every one of its nine hundred pixels agrees with the three
     * beside it in its own two-by-two block, so the panel holds a doubled fifteen and not a thirty.
     */
    HAMMER {

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
         * {@inheritDoc}
         * <p>
         * This one reads no palette. A picture is not a shape a panel's inks have anything to say
         * about, which is why a stencil takes its table as an argument rather than taking a palette.
         */
        @Override
        void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette) {
            PICTURE.stamp(dest, box, 0, 0, INK);
        }

    },

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
     */
    FIELD {

        /** the extent every field paints */
        private static final @NotNull Window.Extent EXTENT = new Window.Extent(110, 16);

        /** where the text opens, how far it runs, how much it holds and what draws it */
        private static final @NotNull TextWell WELL = new TextWell(new Inset(3, 4), 103, 50, 0xFFFFFFFF);

        /** the inner line of the well, on its top and left */
        private static final int INNER_SHADOW = 0xFF6D634D;

        /** the well's interior, which is what the text is drawn over */
        private static final int FILL = 0xFF4E4737;

        /** the inner line of the well, on its bottom and right */
        private static final int INNER_LIGHT = 0xFF29251C;

        /** {@inheritDoc} */
        @Override
        public @NotNull Window.Extent extent() {
            return EXTENT;
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<TextWell> textWell() {
            return Optional.of(WELL);
        }

        /**
         * {@inheritDoc}
         * <p>
         * A well sunk two pixels into the panel and filled with the widget's own olive. The two
         * rings are one bevel rule read at two depths and they differ in where their inks come
         * from: the outer takes the palette's, because that ring is a cell's own, and the inner
         * takes the field's. The two corners the bevel hands over are left untouched, so the panel
         * shows through as it does under the frame's own chamfers.
         */
        @Override
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

    };

    /**
     * The extent this mark paints, which is a property of the shape rather than of the screen
     * carrying it.
     *
     * @return the extent in Minecraft pixels
     */
    public abstract @NotNull Window.Extent extent();

    /**
     * Paints this mark over the box, in the panel's inks wherever the mark is one the panel owns.
     * <p>
     * It is not public, because whether a mark is painted at all is the window's decision and not
     * the mark's - a window sliced from art declines every one of them.
     *
     * @param dest the buffer to paint into
     * @param box the mark's rect in Minecraft pixels, with its output scale
     * @param palette the inks of the panel it is being painted onto
     */
    abstract void paint(@NotNull PixelBuffer dest, @NotNull Window.Box box, @NotNull Window.Palette palette);

    /**
     * Where an icon opens on this mark's face, empty where the mark is drawn whole.
     *
     * @return the inset, empty where this mark carries no icon
     */
    public @NotNull Optional<Inset> iconInset() {
        return Optional.empty();
    }

    /**
     * The text run this mark holds, empty where it holds none.
     *
     * @return the well, empty where this mark holds no text
     */
    public @NotNull Optional<TextWell> textWell() {
        return Optional.empty();
    }

    /**
     * Places this mark on a screen.
     *
     * @param x the left edge of its box, in Minecraft pixels from the panel's own corner
     * @param y the top edge of its box
     * @return the placed mark
     */
    public @NotNull MenuLayout.Mark at(int x, int y) {
        return new MenuLayout.Mark(this, x, y, Optional.empty());
    }

    /**
     * Places this mark on a screen with an item on its face.
     *
     * @param x the left edge of its box, in Minecraft pixels from the panel's own corner
     * @param y the top edge of its box
     * @param icon the item drawn on its face
     * @return the placed mark
     */
    public @NotNull MenuLayout.Mark at(int x, int y, @NotNull ResourceId icon) {
        return new MenuLayout.Mark(this, x, y, Optional.of(icon));
    }

    /**
     * Where content opens within a mark, in Minecraft pixels from the mark's own corner.
     *
     * @param x the left edge
     * @param y the top edge
     */
    public record Inset(int x, int y) {}

    /**
     * Where a mark's text opens, how far it runs before it scrolls, how much it holds and what draws
     * it.
     *
     * @param inset where the text's own glyph cells open, clear of the well's bevel
     * @param innerWidth how wide the text may run before it scrolls, which is the well's inside
     * @param maxLength how many characters the field accepts, which is also what decides which of
     * the caret's two forms is drawn
     * @param argb the ink the text and both forms of the caret are drawn in
     */
    public record TextWell(@NotNull Inset inset, int innerWidth, int maxLength, int argb) {}

}
