package lib.minecraft.renderer.call.result;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * What a layout render hands back: the canvas, and where each child was drawn on it. Its stand-ins are
 * its children's.
 * <p>
 * There is one child per appended child, in append order. A child is drawn unscaled, so its rect is the
 * corner the layout placed it at and the size its first frame measured, and a child that places other
 * renders keeps its own narrower result.
 *
 * @param image the composed canvas, static for one frame and animated for several
 * @param children one child per appended child, in append order
 */
@Parity(subject = Subject.LAYOUT)
public record LayoutResult(@NotNull ImageData image, @NotNull ConcurrentList<Child> children) implements RenderResult {

    /**
     * Constructs a new {@code LayoutResult}, keeping the children unmodifiable in the order given.
     */
    public LayoutResult {
        children = children.stream().collect(Concurrent.toUnmodifiableList());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<Substitution> substitutions() {
        return PlainResult.distinctSorted(this.children.stream()
            .flatMap(child -> child.result().substitutions().stream()));
    }

    /**
     * One child drawn on the layout.
     *
     * @param index the child's position in append order
     * @param x the left edge it was drawn at, in output pixels
     * @param y the top edge it was drawn at, in output pixels
     * @param width the width its first frame measured, in output pixels
     * @param height the height its first frame measured, in output pixels
     * @param result the child's own result
     */
    public record Child(int index, int x, int y, int width, int height, @NotNull RenderResult result) {}

}
