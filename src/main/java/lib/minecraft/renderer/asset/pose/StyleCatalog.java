package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

/**
 * One entity's whole answer to "what can its output uniquely look like", ordered as shipped.
 *
 * <p>The catalog is the axis and a style id is how a caller spells a point on it. The rows carried
 * here are the entity's own; the {@code bind} row is synthesized rather than carried, every entity
 * having it, and the universal ids {@link PoseStyle#IDLE idle}, {@link PoseStyle#STRIDE stride} and
 * {@link PoseStyle#ANIMATED animated} answer on every catalog whether or not a row spells them -
 * an entity that ships none is answered with the universal rows.
 *
 * @param periodTicks the ticks one whole excursion spans - what a sweeping or cycling driver wraps
 *     at, and the span one strip divides
 * @param styles the shipped rows in shipped order; never carries the synthesized {@code bind} row
 */
public record StyleCatalog(
    int periodTicks,
    @NotNull ConcurrentList<PoseStyle> styles
) {

    /**
     * The frames one shipped strip samples across {@link #periodTicks}, so a strip shows one whole
     * excursion, its last frame does not repeat its first, and an animated render loops.
     */
    public static final int STRIP_FRAMES = 8;

    /**
     * The catalog of an entity the shipped file never mentions - no rows, the synthesized
     * {@code bind} row alone, at the shipped period of twenty-four ticks.
     */
    public static final @NotNull StyleCatalog BIND_ONLY =
        new StyleCatalog(24, Concurrent.newUnmodifiableList());

}
