package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * One catalog row: a uniquely identifiable output of one entity.
 *
 * <p>A row is the whole of one way a subject's render can look - which mechanisms move it, which
 * render-state fields its poses read and how each travels, which appearance bone toggles the
 * selection entails, and which age it applies to. The four universal ids are typed here so a caller
 * spelling one holds a constant rather than a literal; every other id is the entity's own, carried
 * in its {@link StyleCatalog}.
 *
 * @param id the name a caller selects this output by - {@code bind}, {@code idle}, {@code stride},
 *     or an entity's own selection like {@code croak}
 * @param sources the inventory of mechanisms this row moves through, each with the gate that admits
 *     it; empty for a row that holds still
 * @param drivers the drive behind each driven render-state field, keyed by the field name a shipped
 *     pose reads it under; a field absent here rests at zero
 * @param toggles the appearance bone toggles this style entails - a croak selection draws the sac
 *     bone the croak state inflates
 * @param age the age this row applies to; empty applies to both
 * @param periodTicks the ticks this row's own excursion spans; empty rides the catalog period
 */
public record PoseStyle(
    @NotNull String id,
    @NotNull ConcurrentList<StyleSource> sources,
    @NotNull ConcurrentMap<String, StyleDriver> drivers,
    @NotNull ConcurrentList<String> toggles,
    @NotNull Optional<Age> age,
    @NotNull Optional<Integer> periodTicks
) {

    /** The universal still row every entity has - the authored pose, one frame. */
    public static final @NotNull String BIND = "bind";

    /** The universal standing row - elapsed age is the only figure that stops resting. */
    public static final @NotNull String IDLE = "idle";

    /** The universal walking row - the standing drivers plus the pair a stride is carried on. */
    public static final @NotNull String STRIDE = "stride";

    /** The request for whatever moves this entity, resolved by {@link StyleCatalog#animated()}. */
    public static final @NotNull String ANIMATED = "animated";

    /**
     * One inventory entry: a mechanism, plus the appearance flag that admits it.
     *
     * @param source the kind of drive behind the movement
     * @param gate the flag admitting this entry - the one the gated pass's {@code when} key names, or
     *     {@link Flag#CARRIED} for a movement only the body a selected carried block swaps in makes;
     *     empty for an unconditional entry
     */
    public record StyleSource(
        @NotNull StyleClock source,
        @NotNull Optional<Flag> gate
    ) {}

    /**
     * Whether anything moves this row's output - any source present. On a catalog
     * {@link StyleCatalog#inForce narrowed to a resolved subject} the inventory is the in-force
     * view, so this answers for the subject as its appearance left it.
     *
     * @return whether a source is present
     */
    public boolean moves() {
        return !this.sources.isEmpty();
    }

    /**
     * What each render-state field holds at one tick under this style - the driven fields their
     * driver's answer, everything else its resting zero. A row declaring its own
     * {@link #periodTicks() period} frames its excursions against that window; every other row
     * frames against the given one.
     *
     * @param tick the tick being posed
     * @param periodTicks the ticks one whole excursion spans where this row declares no period
     * @return the frame function a pose evaluation reads fields through
     */
    public @NotNull ToDoubleFunction<String> frameAt(int tick, int periodTicks) {
        int window = this.periodTicks.orElse(periodTicks);
        return field -> {
            StyleDriver driver = this.drivers.get(field);
            return driver == null ? 0d : driver.at(tick, window);
        };
    }

    /**
     * This row scoped to another age of a catalog and walking at the stride that catalog draws there
     * - every field the stride of this row's own age drives taken out, then every driver of the other
     * age's stride put in its own order, every other driver and every other component kept. A row
     * spanning both ages walks at the adult's stride.
     *
     * @param catalog the catalog both strides are drawn from
     * @param age the age the row is scoped to and walks at
     * @return the row at that age
     */
    public @NotNull PoseStyle restrided(@NotNull StyleCatalog catalog, @NotNull Age age) {
        LinkedHashMap<String, StyleDriver> restrided = new LinkedHashMap<>(this.drivers);
        catalog.strideAt(this.age.orElse(Age.ADULT)).drivers().keySet().forEach(restrided::remove);
        restrided.putAll(catalog.strideAt(age).drivers());
        return new PoseStyle(this.id, this.sources, Concurrent.adoptLinkedMap(restrided).toUnmodifiable(),
            this.toggles, Optional.of(age), this.periodTicks);
    }

}
