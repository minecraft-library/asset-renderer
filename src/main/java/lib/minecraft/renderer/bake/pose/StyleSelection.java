package lib.minecraft.renderer.bake.pose;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.vanilla.UniversalStyles;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which row of a {@link StyleCatalog} a render plays - the selection a style id, a request and an
 * appearance together decide, taken at the point the pose is played rather than carried on the
 * table.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class StyleSelection {

    /**
     * The synthesized still row - always answered and never carried in a catalog's
     * {@link StyleCatalog#styles() rows}, every entity having it.
     *
     * @return the {@code bind} row
     */
    public static @NotNull PoseStyle bind() {
        return UniversalStyles.BIND_ROW;
    }

    /**
     * The first shipped row of one id, or empty where the catalog carries none - the synthesized
     * rows are answered by {@link #resolve} rather than found here.
     *
     * <p>An id an age-split pair shares answers the first-shipped of the two, which is the adult
     * row wherever one ships. A caller that has a request to hand wants
     * {@link #byId(StyleCatalog, String, EntityOptions)} instead, that being the overload the age
     * decides.
     *
     * @param catalog the catalog searched
     * @param id the style id to look up
     * @return the first shipped row of that id, or empty
     */
    public static @NotNull Optional<PoseStyle> byId(@NotNull StyleCatalog catalog, @NotNull String id) {
        return catalog.styles().stream()
            .filter(style -> style.id().equals(id))
            .findFirst();
    }

    /**
     * The shipped row of one id that applies to one request - among rows sharing one id the row
     * that applies to the request answers, the axolotl shipping {@code play_dead} once per age.
     *
     * @param catalog the catalog searched
     * @param id the style id to look up
     * @param options the render request the selection came with
     * @return the applying shipped row, or empty
     */
    public static @NotNull Optional<PoseStyle> byId(
        @NotNull StyleCatalog catalog, @NotNull String id, @NotNull EntityOptions options) {

        return catalog.styles().stream()
            .filter(style -> style.id().equals(id))
            .filter(style -> options.getAppearance().applies(style))
            .findFirst();
    }

    /**
     * Whether a catalog already answers for one id at one age - an empty age on either side
     * spans every age, so an ageless row is taken for every claim and two rows split by disjoint
     * ages are taken by neither.
     *
     * @param catalog the catalog searched
     * @param id the style id being claimed
     * @param age the age the claim is scoped to; empty claims every age
     * @return whether a carried row already answers for that id at that age
     */
    public static boolean carries(
        @NotNull StyleCatalog catalog, @NotNull String id, @NotNull Optional<Age> age) {

        return catalog.styles().stream()
            .filter(style -> style.id().equals(id))
            .anyMatch(style -> style.age().isEmpty() || age.isEmpty() || style.age().equals(age));
    }

    /**
     * The row {@link PoseStyle#ANIMATED animated} resolves to - the first shipped row anything
     * moves, in shipped order, or {@link #bind()} where nothing does.
     *
     * @param catalog the catalog searched
     * @return the first moving row, or the {@code bind} row
     */
    public static @NotNull PoseStyle animated(@NotNull StyleCatalog catalog) {
        return catalog.styles().stream()
            .filter(PoseStyle::moves)
            .findFirst()
            .orElseGet(StyleSelection::bind);
    }

    /**
     * The row one style id selects for one request.
     *
     * <p>The four universal ids always resolve: {@code bind} to the synthesized still row,
     * {@code idle} and {@code stride} to the shipped row of that id where one is carried and to the
     * universal row otherwise, {@code animated} to {@link #animated}. Any other id resolves iff
     * the catalog carries it and the row {@link AppearanceOptions#applies applies to} the request's
     * appearance; rows sharing one id and split by age resolve to the one that applies.
     *
     * @param catalog the catalog searched
     * @param id the style id being selected
     * @param options the render request the selection came with
     * @return the resolved row
     * @throws RendererException if the id names no row of this catalog that applies
     */
    public static @NotNull PoseStyle resolve(
        @NotNull StyleCatalog catalog, @NotNull String id, @NotNull EntityOptions options) {

        return switch (id) {
            case PoseStyle.BIND -> bind();
            case PoseStyle.IDLE -> byId(catalog, id, options).orElse(UniversalStyles.UNIVERSAL_IDLE);
            case PoseStyle.STRIDE -> byId(catalog, id, options).orElse(UniversalStyles.UNIVERSAL_STRIDE);
            case PoseStyle.ANIMATED -> animated(catalog);
            default -> byId(catalog, id, options)
                .orElseThrow(() -> new RendererException(
                    "Entity '%s' has no style '%s' - it supports %s",
                    options.getEntityId(), id, ids(catalog)));
        };
    }

    /**
     * The row a canvas-union member is measured under for one style id.
     *
     * <p>A member or variant coat arrives off the index as its adult form, so the row that answers
     * is that catalog's own for the id where one applies to that form - a baby-only row does not -
     * and the universal rows answer the universal ids exactly as {@link #resolve} answers them. An
     * id the catalog carries no applying row for is measured under the given row instead: the
     * union is a measurement of the family's silhouettes rather than a selection, so a member that
     * cannot answer the id is measured the way the requested subject is.
     *
     * <p>Three arms rather than {@link #resolve}'s four, and the missing one is correct: the only
     * caller hands this a resolved row's own id, and the animated id never names one - so a member
     * cannot arrive asking for it, and the default arm would answer it the same way regardless.
     *
     * @param catalog the member's own catalog
     * @param id the style id the render selected
     * @param requested the row the requested subject resolved, measured under where the catalog
     *     cannot answer the id
     * @return the row the member is measured under
     */
    public static @NotNull PoseStyle memberRow(
        @NotNull StyleCatalog catalog, @NotNull String id, @NotNull PoseStyle requested) {

        return switch (id) {
            case PoseStyle.BIND -> bind();
            case PoseStyle.IDLE -> adultRow(catalog, id).orElse(UniversalStyles.UNIVERSAL_IDLE);
            case PoseStyle.STRIDE -> adultRow(catalog, id).orElse(UniversalStyles.UNIVERSAL_STRIDE);
            default -> adultRow(catalog, id).orElse(requested);
        };
    }

    /** The first row of one id applying to the adult form - an ageless row applies to both. */
    private static @NotNull Optional<PoseStyle> adultRow(@NotNull StyleCatalog catalog, @NotNull String id) {
        return catalog.styles().stream()
            .filter(style -> style.id().equals(id))
            .filter(style -> style.age().map(age -> age == Age.ADULT).orElse(true))
            .findFirst();
    }

    /**
     * The ids naming a distinct output - {@code bind} first, then every shipped row's id in
     * shipped order, an age-split pair listed once. A shipped row nothing moves is still a
     * selectable output - a held stance renders a picture {@code bind} does not - and
     * {@link #resolve} accepts the universal ids whether they are listed or not.
     *
     * @param catalog the catalog listed
     * @return the listed ids, {@code bind} first
     */
    public static @NotNull ConcurrentList<String> ids(@NotNull StyleCatalog catalog) {
        Set<String> out = new LinkedHashSet<>(1 + catalog.styles().size());
        out.add(PoseStyle.BIND);
        for (PoseStyle style : catalog.styles())
            out.add(style.id());
        return Concurrent.newUnmodifiableList(new ArrayList<>(out));
    }

    /**
     * The ticks between two frames of one shipped strip - the catalog's
     * {@link StyleCatalog#periodTicks() period} divided across {@link StyleCatalog#STRIP_FRAMES}.
     *
     * @param catalog the catalog the strip samples
     * @return the per-frame tick step
     */
    public static int stripTicksPerFrame(@NotNull StyleCatalog catalog) {
        return catalog.periodTicks() / StyleCatalog.STRIP_FRAMES;
    }

    /**
     * The ticks between two frames of one strip under one resolved row - the row's own
     * {@link PoseStyle#periodTicks() period} where it declares one, the catalog's
     * {@link StyleCatalog#periodTicks() period} otherwise, divided across
     * {@link StyleCatalog#STRIP_FRAMES}.
     *
     * @param catalog the catalog the strip samples
     * @param style the resolved row the strip samples
     * @return the per-frame tick step
     */
    public static int stripTicksPerFrame(@NotNull StyleCatalog catalog, @NotNull PoseStyle style) {
        return style.periodTicks().orElse(catalog.periodTicks()) / StyleCatalog.STRIP_FRAMES;
    }

}
