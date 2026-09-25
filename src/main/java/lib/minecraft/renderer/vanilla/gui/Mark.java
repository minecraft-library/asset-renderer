package lib.minecraft.renderer.vanilla.gui;

import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A mark a screen places beside its cells - the arrow between a crafting grid and its result, the
 * raised button that opens a recipe book, the plus between an anvil's two inputs.
 * <p>
 * Each constant is a kind rather than a placement: what a mark holds - where an icon opens on its
 * face, the text run its well takes - is a property of the shape, so every arrow is one arrow and
 * every button one button. Where one sits on a screen is a {@link Placement}, and the screens that
 * carry marks declare theirs in {@link ScreenMetrics}.
 * <p>
 * This is which mark and what it measures, never how it is drawn. What a mark paints and how big it
 * comes out belong to the window painting it, keyed by this identity, so the table placing a mark
 * names nothing that paints one.
 * <p>
 * What a mark is <b>not</b> is a cell. Nothing addresses one by slot index, nothing places content
 * in one, and a layout's own cell list does not carry them.
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public enum Mark {

    /**
     * A right-pointing arrow. The crafting table and the anvil both draw it, at their own positions
     * and to the same pixels.
     */
    ARROW,

    /**
     * A raised button carrying an item's icon.
     * <p>
     * Its face carries an item rather than a sprite, because the client's own button is that: the
     * sprite is a frame with an item's texture composited onto it, so naming the item hands a pack
     * that redraws it a redrawn button in the same move.
     */
    BUTTON {

        /** where the icon sits within the button, clear of the outline and the bevel */
        private static final @NotNull Inset FACE = new Inset(2, 1);

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<Inset> iconInset() {
            return Optional.of(FACE);
        }

    },

    /** The plus between an anvil's two input cells. */
    PLUS,

    /** The hammer above an anvil's title. */
    HAMMER,

    /** The text field an anvil renames through. */
    FIELD {

        /** where the text opens, how far it runs, how much it holds and what draws it */
        private static final @NotNull TextWell WELL = new TextWell(new Inset(3, 4), 103, 50, 0xFFFFFFFF);

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<TextWell> textWell() {
            return Optional.of(WELL);
        }

    };

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
    public @NotNull Placement at(int x, int y) {
        return new Placement(this, x, y, Optional.empty());
    }

    /**
     * Places this mark on a screen with an item on its face.
     *
     * @param x the left edge of its box, in Minecraft pixels from the panel's own corner
     * @param y the top edge of its box
     * @param icon the item drawn on its face
     * @return the placed mark
     */
    public @NotNull Placement at(int x, int y, @NotNull ResourceId icon) {
        return new Placement(this, x, y, Optional.of(icon));
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

    /**
     * One mark on one screen - which mark it is, where it sits, and what it holds.
     * <p>
     * A position and an identity, which is the split a {@link ScreenMetrics.Cell} already spells:
     * what a mark paints and how big it comes out belong to the kind, and only the position belongs
     * here.
     *
     * @param kind which mark
     * @param x the left edge of its box, in Minecraft pixels from the panel's own corner
     * @param y the top edge of its box
     * @param icon the item drawn on its face, empty where the mark is drawn whole
     */
    public record Placement(@NotNull Mark kind, int x, int y, @NotNull Optional<ResourceId> icon) {

        public Placement {
            if (icon.isPresent() != kind.iconInset().isPresent())
                throw new IllegalArgumentException(
                    "Mark of '%s' carries an icon exactly when its kind draws one".formatted(kind));
        }

    }

}
