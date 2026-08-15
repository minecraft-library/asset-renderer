package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A rectangular picture written as role codes, and how many Minecraft pixels each of them draws as.
 * <p>
 * This is for a picture whose pixels are not a rule - a frame's corner block, the hammer above an
 * anvil's title - all of them measured off shipped art and irreducible. Everything else a window
 * paints is arithmetic and stays a loop: a cell's bevel closes at any size, an arrow's head narrows
 * by a row, a panel's bars run to whatever length is left. The rule is the proof that those
 * reproduce, where a transcription would only assert that someone typed one correctly.
 * <p>
 * What varies between two pictures is the table their codes resolve through, so that is the
 * argument: {@link Ink} is the seam, and a picture drawn in a panel's palette and a picture drawn in
 * colours no palette holds go through the same stamp.
 *
 * @param rows the picture, one character per authored pixel, every row the same length
 * @param pixel Minecraft pixels a side each authored pixel is drawn as
 */
record Stencil(@NotNull List<String> rows, int pixel) {

    /**
     * The code leaving the destination untouched. It is resolved before any table is consulted, so
     * no table declares an arm for it and a zero from one still means the same thing.
     */
    static final char CLEAR = '.';

    Stencil {
        if (rows.isEmpty()) throw new IllegalArgumentException("A stencil draws at least one row");
        if (pixel < 1) throw new IllegalArgumentException("Stencil pixel scale '%d' is under one".formatted(pixel));

        int columns = rows.getFirst().length();
        for (String row : rows) {
            if (row.length() != columns)
                throw new IllegalArgumentException(
                    "Stencil row '%s' is '%d' wide where its first row is '%d'".formatted(row, row.length(), columns));
        }

        rows = List.copyOf(rows);
    }

    /**
     * A picture drawn at one Minecraft pixel per authored pixel.
     *
     * @param rows the picture, one character per authored pixel
     * @return the stencil
     */
    static @NotNull Stencil of(@NotNull String @NotNull ... rows) {
        return of(1, rows);
    }

    /**
     * A picture drawn at the given scale.
     *
     * @param pixel Minecraft pixels a side each authored pixel is drawn as
     * @param rows the picture, one character per authored pixel
     * @return the stencil
     */
    static @NotNull Stencil of(int pixel, @NotNull String @NotNull ... rows) {
        return new Stencil(List.of(rows), pixel);
    }

    /**
     * How many authored pixels wide this picture is, which every row shares.
     *
     * @return the column count
     */
    int columns() {
        return this.rows.getFirst().length();
    }

    /**
     * The extent this picture draws as, which is its own size at its own scale. A caller reading it
     * cannot disagree with the art, there being no second number to keep in step.
     *
     * @return the extent in Minecraft pixels
     */
    @NotNull Window.Extent extent() {
        return new Window.Extent(this.columns() * this.pixel, this.rows.size() * this.pixel);
    }

    /**
     * Stamps this picture at an offset within the box, each authored pixel a square of
     * {@link #pixel} Minecraft ones.
     *
     * @param dest the buffer to paint into
     * @param box the rect the offset is measured from, carrying the output scale
     * @param originX the left edge within the box, in Minecraft pixels
     * @param originY the top edge within the box, in Minecraft pixels
     * @param ink the table this picture's codes resolve through
     */
    void stamp(
        @NotNull PixelBuffer dest, @NotNull Window.Box box, int originX, int originY, @NotNull Ink ink
    ) {
        for (int row = 0; row < this.rows.size(); row++) {
            String cells = this.rows.get(row);

            for (int col = 0; col < cells.length(); col++) {
                char code = cells.charAt(col);
                if (code == CLEAR) continue;
                int argb = ink.of(code);

                for (int dy = 0; dy < this.pixel; dy++) {
                    for (int dx = 0; dx < this.pixel; dx++)
                        box.put(dest, originX + col * this.pixel + dx, originY + row * this.pixel + dy, argb);
                }
            }
        }
    }

    /**
     * A picture's role codes resolved to the ink each is drawn in.
     * <p>
     * One table reads a panel's palette and another answers with colours no palette holds, and the
     * two are the same shape because a palette is the data a table reads rather than the ink itself.
     */
    @FunctionalInterface
    interface Ink {

        /**
         * Resolves one role code to its ink.
         *
         * @param code the role code
         * @return the ARGB colour
         * @throws IllegalArgumentException if the code is not one this picture is drawn in
         */
        int of(char code);

    }

}
