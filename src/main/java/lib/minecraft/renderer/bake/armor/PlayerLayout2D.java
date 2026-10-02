package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.jetbrains.annotations.NotNull;

/**
 * The 2D front-facing canvas layout of a player body scope - where on a square canvas each part the
 * scope draws is blitted.
 * <p>
 * The 2D armour pass reads its rows: each worn slot's sheet and trim land in the rectangle of every
 * part that slot covers.
 */
@UtilityClass
@Parity(as = PlayerRenderer.class)
@Parity(claim = "option-surface")
public final class PlayerLayout2D {

    /**
     * Lays one scope out on a square canvas - each part it draws, and the canvas rectangle that part
     * is blitted into.
     *
     * <p>The scale fills the canvas height with the scope's own pixel height and the body is
     * centred horizontally. The front view then lays the lattice out directly: a part's canvas X is
     * how far its left edge sits from the scope's own left edge, and its canvas Y is how far its
     * <em>top</em> edge sits below the scope's top - the one inversion, because the lattice counts
     * Y upward and a canvas counts it down. The rectangle's extent is the part's own.
     *
     * <p>It reads nothing but the scope's four union integers and each part's own pixel box, which
     * is the same table {@link PlayerLattice#boxes()} is derived from.
     *
     * @param scope the body scope to lay out
     * @param canvasSize the square canvas edge in pixels
     * @return the parts in draw order, each with its canvas rectangle
     */
    public static @NotNull ConcurrentList<BodyPart2D> of(@NotNull PlayerLattice scope, int canvasSize) {
        int scale = canvasSize / scope.bodyHeight();
        int offsetX = (canvasSize - scope.bodyWidth() * scale) / 2;

        return scope.parts().stream()
            .map(part -> new BodyPart2D(part,
                offsetX + (part.minPixelX() - scope.minPixelX()) * scale,
                (scope.maxPixelY() - part.maxPixelY()) * scale,
                part.pixelWidth() * scale,
                part.pixelHeight() * scale))
            .collect(Concurrent.toWideUnmodifiableList());
    }

    /**
     * One body part and the canvas rectangle it is drawn in, in output pixels with Y counted
     * downward.
     *
     * <p>The five travel together at every site that draws the 2D composite - the skin pass, the
     * overlay pass, and each armour slot's sheet and trim - so they travel as one value rather than
     * as a part plus four loose integers.
     *
     * @param part the body part to crop and blit
     * @param x left edge of the destination rectangle
     * @param y top edge of the destination rectangle
     * @param w destination width in pixels
     * @param h destination height in pixels
     */
    public record BodyPart2D(@NotNull HumanoidPart part, int x, int y, int w, int h) {}

}
