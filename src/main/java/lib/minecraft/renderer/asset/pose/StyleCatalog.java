package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.EntityOptions;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * One entity's whole answer to "what can its output uniquely look like", ordered as shipped.
 *
 * <p>The catalog is the axis and a style id is how a caller spells a point on it. The rows carried
 * here are the entity's own; the {@code bind} row is synthesized rather than carried, every entity
 * having it, and the universal ids {@link PoseStyle#IDLE idle}, {@link PoseStyle#STRIDE stride} and
 * {@link PoseStyle#ANIMATED animated} resolve on every catalog whether or not a row spells them -
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

    /** The synthesized still row - nothing sourced, nothing driven, nothing toggled, either age. */
    private static final @NotNull PoseStyle BIND_ROW = new PoseStyle(PoseStyle.BIND,
        Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(),
        Concurrent.newUnmodifiableList(), Optional.empty());

    /**
     * The standing row an entity that ships none is answered with - elapsed age ramped at slope
     * one, nothing else driven.
     */
    private static final @NotNull PoseStyle UNIVERSAL_IDLE = new PoseStyle(PoseStyle.IDLE,
        Concurrent.newUnmodifiableList(),
        Concurrent.newUnmodifiableMap(Map.of("ageInTicks",
            new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()))),
        Concurrent.newUnmodifiableList(), Optional.empty());

    /**
     * The walking row an entity that ships none is answered with - the standing drivers plus the
     * pair a stride is carried on, the amplitude held at one and the phase ramped by it.
     */
    private static final @NotNull PoseStyle UNIVERSAL_STRIDE = strideOver(UNIVERSAL_IDLE);

    /**
     * The synthesized still row - always answered and never in {@link #styles}, every entity
     * having it.
     *
     * @return the {@code bind} row
     */
    public @NotNull PoseStyle bind() {
        return BIND_ROW;
    }

    /**
     * The shipped row of one id, or empty where the catalog carries none - the synthesized rows are
     * answered by {@link #resolve} rather than found here.
     *
     * @param id the style id to look up
     * @return the shipped row, or empty
     */
    public @NotNull Optional<PoseStyle> byId(@NotNull String id) {
        return this.styles.stream()
            .filter(style -> style.id().equals(id))
            .findFirst();
    }

    /**
     * The row {@link PoseStyle#ANIMATED animated} resolves to - the first shipped row anything
     * moves, in shipped order, or {@link #bind()} where nothing does.
     *
     * @return the first moving row, or the {@code bind} row
     */
    public @NotNull PoseStyle animated() {
        return this.styles.stream()
            .filter(PoseStyle::moves)
            .findFirst()
            .orElseGet(this::bind);
    }

    /**
     * The row one style id selects for one request.
     *
     * <p>The four universal ids always resolve: {@code bind} to the synthesized still row,
     * {@code idle} and {@code stride} to the shipped row of that id where one is carried and to the
     * universal row otherwise, {@code animated} to {@link #animated()}. Any other id resolves iff
     * the catalog carries it and the row {@link PoseStyle#appliesTo applies to} the request's
     * appearance.
     *
     * @param id the style id being selected
     * @param options the render request the selection came with
     * @return the resolved row
     * @throws RendererException if the id names no row of this catalog that applies
     */
    public @NotNull PoseStyle resolve(@NotNull String id, @NotNull EntityOptions options) {
        return switch (id) {
            case PoseStyle.BIND -> bind();
            case PoseStyle.IDLE -> byId(id).orElse(UNIVERSAL_IDLE);
            case PoseStyle.STRIDE -> byId(id).orElse(UNIVERSAL_STRIDE);
            case PoseStyle.ANIMATED -> animated();
            default -> byId(id)
                .filter(style -> style.appliesTo(options))
                .orElseThrow(() -> new RendererException(
                    "Entity '%s' has no style '%s' - it supports %s",
                    options.getEntityId().orElse(""), id, ids()));
        };
    }

    /**
     * The ids naming a distinct output - {@code bind} first, then every shipped row with a
     * non-empty source inventory, in shipped order. A shipped row nothing moves renders identically
     * to {@code bind} and is not listed; {@link #resolve} accepts the universal ids whether they
     * are listed or not.
     *
     * @return the listed ids, {@code bind} first
     */
    public @NotNull ConcurrentList<String> ids() {
        List<String> out = new ArrayList<>(1 + this.styles.size());
        out.add(PoseStyle.BIND);
        for (PoseStyle style : this.styles)
            if (style.moves()) out.add(style.id());
        return Concurrent.newUnmodifiableList(out);
    }

    /**
     * The ticks between two frames of one shipped strip - {@link #periodTicks} divided across
     * {@link #STRIP_FRAMES}.
     *
     * @return the per-frame tick step
     */
    public int stripTicksPerFrame() {
        return this.periodTicks / STRIP_FRAMES;
    }

    /**
     * This catalog as one resolved subject holds it: a row whose age refuses the subject's drops
     * out, and within each kept row a gated source entry survives iff the given predicate admits
     * its gate - an unconditional entry always does. Answers this catalog itself where nothing
     * narrows, and {@link PoseStyle#moves()} and {@link #animated()} on the narrowed catalog answer
     * for the subject as its appearance left it.
     *
     * @param baby whether the subject renders the baby mesh
     * @param gateAdmitted whether the appearance kept the pass a gate token names
     * @return the narrowed catalog, or this one where nothing narrows
     */
    public @NotNull StyleCatalog inForce(boolean baby, @NotNull Predicate<String> gateAdmitted) {
        List<PoseStyle> kept = new ArrayList<>(this.styles.size());
        boolean narrowed = false;
        for (PoseStyle style : this.styles) {
            if (style.age().map(age -> age != (baby ? Age.BABY : Age.ADULT)).orElse(false)) {
                narrowed = true;
                continue;
            }
            ConcurrentList<PoseStyle.StyleSource> admitted = admitted(style.sources(), gateAdmitted);
            narrowed |= admitted != style.sources();
            kept.add(admitted == style.sources() ? style
                : new PoseStyle(style.id(), admitted, style.drivers(), style.toggles(), style.age()));
        }
        return narrowed
            ? new StyleCatalog(this.periodTicks, Concurrent.newUnmodifiableList(kept))
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

    /** The universal walking row, composed as the given standing row's drivers plus the walk pair. */
    private static @NotNull PoseStyle strideOver(@NotNull PoseStyle idle) {
        LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>(idle.drivers());
        drivers.put("walkAnimationSpeed",
            new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
        drivers.put("walkAnimationPos",
            new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        return new PoseStyle(PoseStyle.STRIDE, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(drivers), Concurrent.newUnmodifiableList(),
            Optional.empty());
    }

}
