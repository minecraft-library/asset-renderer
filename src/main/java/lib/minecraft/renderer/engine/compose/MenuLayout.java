package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A laid-out menu panel - how big it is and where every cell in it sits, in Minecraft pixels.
 * <p>
 * Nothing here knows how a cell is painted or what goes in one. It is the arithmetic between a
 * {@link MenuScreen} and a {@link Window}, so the same layout serves a panel drawn from rules and a
 * panel sliced from art.
 *
 * @param width the panel width
 * @param height the panel height
 * @param titleX the rule the container's own title starts by
 * @param inventoryAnchor where the player's label starts, present exactly when the player section is
 * laid out
 * @param cells every cell, in the order they are laid out
 * @param marks the marks the screen paints beside its cells, at the positions it declares
 */
public record MenuLayout(
    int width, int height,
    @NotNull MenuScreen.TitleX titleX, @NotNull Optional<Anchor> inventoryAnchor,
    @NotNull ConcurrentList<Cell> cells,
    @NotNull ConcurrentList<Mark> marks
) {

    /**
     * One mark on one screen - which mark it is, where it sits, and what it holds.
     * <p>
     * A rectangle and an identity, which is the split a {@link Cell} already spells: what a mark
     * paints and how big it comes out belong to the kind, and only the position belongs here.
     *
     * @param kind which mark
     * @param x the left edge of its box, in Minecraft pixels from the panel's own corner
     * @param y the top edge of its box
     * @param icon the item drawn on its face, empty where the mark is drawn whole
     */
    public record Mark(@NotNull Decoration kind, int x, int y, @NotNull Optional<ResourceId> icon) {

        public Mark {
            if (icon.isPresent() != kind.iconInset().isPresent())
                throw new IllegalArgumentException(
                    "Mark of '%s' carries an icon exactly when its kind draws one".formatted(kind));
        }

        /**
         * This mark's square as a {@link Window.Box} at the given output scale.
         *
         * @param scale the output pixels each Minecraft pixel occupies on a side
         * @return the mark's box
         */
        public @NotNull Window.Box box(int scale) {
            Window.Extent extent = this.kind.extent();
            return new Window.Box(this.x, this.y, extent.width(), extent.height(), scale);
        }

    }

    /**
     * Where the container's own title starts.
     * <p>
     * The width is an argument because one screen centres its title, and what a title comes to is a
     * measurement over the glyphs in it rather than anything a layout can derive. A screen that fixes
     * its title ignores the argument.
     *
     * @param titleWidth the title's width in Minecraft pixels
     * @return the title's anchor
     */
    public @NotNull Anchor titleAnchor(int titleWidth) {
        return new Anchor(this.titleX.at(this.width, titleWidth), MenuScreen.TITLE_Y);
    }

    /**
     * Where a label starts, in Minecraft pixels from the panel's own corner. The vertical is the top
     * of the glyph cell rather than the baseline, which is what the client positions a label by.
     *
     * @param x the left edge
     * @param y the top edge
     */
    public record Anchor(int x, int y) {}

    /**
     * What a cell belongs to, which is what decides whether a caller's slot index reaches it.
     */
    public enum Role {

        /** A cell the container itself owns. */
        CONTAINER,
        /** A cell of the player's main inventory. */
        PLAYER_MAIN,
        /** A cell of the player's hotbar. */
        HOTBAR,
        /** The cell a container's output sits in. */
        RESULT

    }

    /**
     * One cell's square, in Minecraft pixels.
     *
     * @param x the left edge
     * @param y the top edge
     * @param size the side, which is 18 for every cell but a crafting result
     * @param role what the cell belongs to
     */
    public record Cell(int x, int y, int size, @NotNull Role role) {

        /**
         * This cell as a {@link Window.Box} at the given output scale.
         *
         * @param scale the output pixels each Minecraft pixel occupies on a side
         * @return the cell box
         */
        public @NotNull Window.Box box(int scale) {
            return new Window.Box(this.x, this.y, this.size, this.size, scale);
        }

    }

    /**
     * The cells a caller's slot indices address, in layout order - the container's own, and the one a
     * result sits in where the screen has one. The player's section is drawn and never addressed.
     *
     * @return the addressable cells
     */
    public @NotNull ConcurrentList<Cell> slotCells() {
        return this.cells.stream()
            .filter(cell -> cell.role() == Role.CONTAINER || cell.role() == Role.RESULT)
            .collect(Concurrent.toList());
    }

    /**
     * The extent this panel needs as a {@link Window.Box} at the given output scale.
     *
     * @param scale the output pixels each Minecraft pixel occupies on a side
     * @return the panel box
     */
    public @NotNull Window.Box box(int scale) {
        return new Window.Box(0, 0, this.width, this.height, scale);
    }

}
