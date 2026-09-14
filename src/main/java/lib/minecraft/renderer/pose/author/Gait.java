package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

/**
 * The walking-cycle verb set - one shape stamped over every leg a mesh carries, stated once
 * rather than retyped per leg.
 *
 * <p>The cycle's length is the one value spoken in seconds; everything else a gait says is a
 * share of it, so changing the length moves the whole gait and nothing else. A wave written here
 * reads exactly as a wave written outside a gait and lowers the same way - what a gait adds is
 * that the legs it lands on are the mesh's answer rather than the author's count.
 *
 * <p>A row is addressed by rank, so a gait states the bee's three rests, the sniffer's six legs
 * and the spider's eight without naming a bone. A rank the mesh carries no row for reaches
 * nothing, and one shape stamped over every row on a mesh that also carries a shape of its own
 * for one of them waves that row's channel twice, which the compiler refuses - a gait states one
 * shape per row or one for all of them, never both.
 */
@Parity(subject = Subject.ENTITY)
public final class Gait {

    private final @NotNull List<Shape> shapes = new ArrayList<>();
    private final @NotNull Map<Rank, Double> phases = new EnumMap<>(Rank.class);
    private @NotNull OptionalDouble lengthSeconds = OptionalDouble.empty();
    private @NotNull OptionalDouble opposed = OptionalDouble.empty();
    private @NotNull Mirror mirror = Mirror.SIGNED;

    Gait() {}

    /**
     * States how long one whole cycle runs.
     *
     * <p>This is the period the gait's waves sweep over, so the strip frames it: a length the
     * strip does not tile refuses, as a period stated any other way does.
     *
     * @param seconds the seconds one cycle spans
     * @return this gait
     */
    public @NotNull Gait over(double seconds) {
        this.lengthSeconds = OptionalDouble.of(seconds);
        return this;
    }

    /**
     * Stamps one shape over every leg the mesh carries.
     *
     * @param shape the stance lambda, run once and stamped on every row
     * @return this gait
     */
    public @NotNull Gait step(@NotNull UnaryOperator<LimbStance> shape) {
        this.shapes.add(new Shape(Optional.empty(), shape));
        return this;
    }

    /**
     * Stamps one shape over a single row of legs.
     *
     * @param rank which row front to back
     * @param shape the stance lambda, run once and stamped on that row
     * @return this gait
     */
    public @NotNull Gait step(@NotNull Rank rank, @NotNull UnaryOperator<LimbStance> shape) {
        this.shapes.add(new Shape(Optional.of(rank), shape));
        return this;
    }

    /**
     * States where one row's copy of the shape starts in the cycle.
     *
     * <p>A phase is a real offset in time, which is the clip clock's to state and not the driver
     * clock's: a driver derives its phase from the tick alone and carries no offset to add. So a
     * phased row's shape is written as a {@link LimbStance#timeline timeline}, and a phase over a
     * {@link LimbStance#sway sway} refuses rather than quietly moving the shape onto the other
     * clock and dropping the field it was emitting.
     *
     * @param rank which row front to back
     * @param cycles the share of one cycle that row starts into
     * @return this gait
     */
    public @NotNull Gait phase(@NotNull Rank rank, double cycles) {
        this.phases.put(rank, cycles);
        return this;
    }

    /**
     * States how far behind the near one the far side of every pair starts.
     *
     * <p>Half a cycle is a pace on its own - the two sides of every row exactly opposite, which is
     * what a two-legged stride is and what a camel walks on four legs. The mirror sign rule cannot
     * say it, because it keeps pitch and pitch is where a leg's cycle lives, so under a signed
     * mirror the far side travels with the near one rather than against it.
     *
     * <p>An offset is real time and so the clip clock's to state, which binds this verb exactly as
     * it binds {@link #phase}: a shape written as a {@link LimbStance#sway sway} carries no offset
     * to start late by and refuses, naming {@link LimbStance#timeline timeline} as the remedy.
     *
     * <p>A row one bone paints whole has no far side, so the offset lands on nothing there and the
     * row takes one copy of the shape rather than two.
     *
     * @param cycles the share of one cycle the far side of each pair starts behind the near one
     * @return this gait
     */
    public @NotNull Gait oppose(double cycles) {
        this.opposed = OptionalDouble.of(cycles);
        return this;
    }

    /**
     * Reads the far side of every pair with every sign as written.
     *
     * <p>The far side otherwise derives under the mirror sign rule - pitch kept, yaw and roll
     * negated, the sideways component of positions and aim targets negated - which is wrong for
     * the meshes whose two sides turn the same way rather than opposite ways.
     *
     * @return this gait
     */
    public @NotNull Gait share() {
        this.mirror = Mirror.SHARED;
        return this;
    }

    /**
     * Writes what this gait captured onto the style being built.
     *
     * @param capture the style's own capture
     */
    void captured(@NotNull PoseScript.Capture capture) {
        this.lengthSeconds.ifPresent(capture::period);
        if (!this.phases.isEmpty()) capture.cycle(this.phases);
        this.opposed.ifPresent(capture::opposed);
        for (Shape shape : this.shapes)
            capture.selectedPair(
                new LimbSelector.Legs(shape.rank(), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(shape.rank(), Optional.of(Side.LEFT), Reach.ROOT,
                    LimbSelector.Stamp.FAR),
                PoseScript.AimAxis.DOWN, this.mirror, shape.verbs());
    }

    /**
     * One shape the gait stamps, and the row it is stamped over.
     *
     * @param rank which row front to back, or empty for every row
     * @param verbs the stance lambda, run once per side when the gait is written out
     */
    private record Shape(@NotNull Optional<Rank> rank,
                         @NotNull UnaryOperator<LimbStance> verbs) {}

}
