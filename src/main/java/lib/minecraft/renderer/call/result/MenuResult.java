package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.vanilla.gui.Mark;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.stream.Stream;

/**
 * What a menu render hands back: the menu, the stand-ins its own chrome drew, and where each cell's
 * content and each mark's icon was drawn. Its stand-ins are its chrome's, its slots' and its icons'.
 * <p>
 * A slot is a caller's content, placed under {@link MenuSlot#SLOT}, or the fill drawn in a cell no
 * caller populated, placed under {@link MenuSlot#CONTENT}; every fill cell holds the one fill render's
 * result. Slots are kept in slot-index order and icons in the screen's mark order. Each rect is the
 * content's corner and its own size, since a menu draws its content unscaled.
 *
 * @param image the composed menu, static for one frame and animated for several
 * @param chrome the stand-ins the window art drew, distinct and in their natural order
 * @param slots one slot per drawn cell, in slot-index order
 * @param icons one icon per drawn mark icon, in the screen's mark order
 */
public record MenuResult(
    @NotNull ImageData image,
    @NotNull ConcurrentList<Substitution> chrome,
    @NotNull ConcurrentList<Slot> slots,
    @NotNull ConcurrentList<Icon> icons
) implements RenderResult {

    /**
     * Constructs a new {@code MenuResult}, keeping the chrome's stand-ins distinct and sorted, the slots
     * in slot-index order and the icons in the order given, each unmodifiable.
     */
    public MenuResult {
        chrome = PlainResult.distinctSorted(chrome.stream());
        slots = slots.stream()
            .sorted(Comparator.comparingInt(Slot::index))
            .collect(Concurrent.toUnmodifiableList());
        icons = icons.stream().collect(Concurrent.toUnmodifiableList());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<Substitution> substitutions() {
        return PlainResult.distinctSorted(Stream.of(
                this.chrome.stream(),
                this.slots.stream().flatMap(slot -> slot.result().substitutions().stream()),
                this.icons.stream().flatMap(icon -> icon.result().substitutions().stream()))
            .flatMap(stream -> stream));
    }

    /**
     * One cell's content drawn on the menu.
     *
     * @param index the slot index of the cell it was drawn in
     * @param layer the layer it was placed under - {@link MenuSlot#SLOT} for a caller's content,
     *     {@link MenuSlot#CONTENT} for the fill
     * @param x the left edge it was drawn at, in output pixels
     * @param y the top edge it was drawn at, in output pixels
     * @param width its width, in output pixels
     * @param height its height, in output pixels
     * @param result the content's own result
     */
    public record Slot(int index, @NotNull MenuSlot layer, int x, int y, int width, int height, @NotNull RenderResult result) {}

    /**
     * One mark's icon drawn on the menu.
     *
     * @param index the mark's position in the screen's mark list
     * @param mark the mark whose face the icon sits on
     * @param x the left edge it was drawn at, in output pixels
     * @param y the top edge it was drawn at, in output pixels
     * @param width its width, in output pixels
     * @param height its height, in output pixels
     * @param result the icon's own result
     */
    public record Icon(int index, @NotNull Mark mark, int x, int y, int width, int height, @NotNull RenderResult result) {}

}
