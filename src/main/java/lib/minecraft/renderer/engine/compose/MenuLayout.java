package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

/**
 * A laid-out menu panel - how big it is and where every cell in it sits, in Minecraft pixels.
 * <p>
 * Nothing here knows how a cell is painted or what goes in one. It is the arithmetic between a
 * {@link MenuScreen} and a {@link Window}, so the same layout serves a panel drawn from rules and a
 * panel sliced from art.
 *
 * @param width the panel width
 * @param height the panel height
 * @param cells every cell, in the order they are laid out
 */
public record MenuLayout(int width, int height, @NotNull ConcurrentList<Cell> cells) {

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
        /** A cell outside the regular grid, such as a crafting result. */
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
