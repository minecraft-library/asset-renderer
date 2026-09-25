package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.bake.pose.StyleSelection;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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

    /**
     * Narrows this catalog to the view one resolved subject holds: a row whose age refuses the
     * subject's drops out, and within each kept row a gated source entry survives iff the given
     * predicate admits its gate - an unconditional entry always does. Answers this catalog itself
     * where nothing narrows, and {@link PoseStyle#moves()} and {@link StyleSelection#animated} on the
     * narrowed catalog answer for the subject as its appearance left it.
     *
     * @param baby whether the subject renders the baby mesh
     * @param gateAdmitted whether the appearance kept the pass a gate token names
     * @return the narrowed catalog, or this one where nothing narrows
     */
    public @NotNull StyleCatalog inForce(boolean baby, @NotNull Predicate<String> gateAdmitted) {
        List<PoseStyle> kept = new ArrayList<>(this.styles().size());
        boolean narrowed = false;
        for (PoseStyle style : this.styles()) {
            if (style.age().map(age -> age != (baby ? Age.BABY : Age.ADULT)).orElse(false)) {
                narrowed = true;
                continue;
            }
            ConcurrentList<PoseStyle.StyleSource> admitted = admitted(style.sources(), gateAdmitted);
            narrowed |= admitted != style.sources();
            kept.add(admitted == style.sources() ? style
                : new PoseStyle(style.id(), admitted, style.drivers(), style.toggles(), style.age(),
                    style.periodTicks()));
        }
        return narrowed
            ? new StyleCatalog(this.periodTicks(), Concurrent.newUnmodifiableList(kept))
            : this;
    }

    /**
     * The source entries the predicate admits, or the given list itself where it refuses none - a
     * gated entry survives iff its gate is admitted, an unconditional one always.
     */
    private static @NotNull ConcurrentList<PoseStyle.StyleSource> admitted(
        @NotNull ConcurrentList<PoseStyle.StyleSource> sources,
        @NotNull Predicate<String> gateAdmitted) {

        boolean refused = sources.stream()
            .anyMatch(source -> source.gate().filter(gate -> !gateAdmitted.test(gate)).isPresent());
        if (!refused) return sources;
        return sources.stream()
            .filter(source -> source.gate().map(gateAdmitted::test).orElse(true))
            .collect(Concurrent.toUnmodifiableList());
    }

}
