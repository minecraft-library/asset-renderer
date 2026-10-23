package lib.minecraft.renderer.engine.frame;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalDouble;

/**
 * One sampled contribution to one channel of a posed row - what one part of the row puts on the channel
 * at each whole tick of a strip that starts at tick 0, and what kind of part it is.
 *
 * <p>Tracks sharing a {@link #channel} key are summed, because what the eye sees is the sum: a pose's
 * write and a clip's displacement on one bone member compose by addition. The tracks of one key agree
 * on {@link #kind} and {@link #wrap}, which {@link StripFit#fit} holds them to.
 *
 * <p>The record keeps {@link #values} as handed over and never writes it, so its equality is by array
 * identity; nothing keys on a track.
 *
 * @param channel the key the fit sums contributions under, opaque to it
 * @param kind what the channel carries, which fixes its unit and how far it may jump unseen
 * @param role where the contribution's period comes from, and how its restart is judged
 * @param values the contribution at each tick from 0, {@link StripFit#WINDOW_TICKS} of them, in the kind's
 *     unit
 * @param wrap the span the channel comes round at - a full turn for a rotation, the sheet's extent for a
 *     scroll - and empty where it wraps nowhere
 */
@Parity(subject = Subject.ENTITY)
public record StripTrack(
    @NotNull String channel,
    @NotNull Kind kind,
    @NotNull Role role,
    double @NotNull [] values,
    @NotNull OptionalDouble wrap
) {

    /**
     * Constructs a new {@code StripTrack}, refusing one the fit cannot read.
     *
     * @throws IllegalArgumentException if the track holds other than {@link StripFit#WINDOW_TICKS}
     *     samples, or wraps at a span that is not positive and finite
     */
    public StripTrack {
        if (values.length != StripFit.WINDOW_TICKS)
            throw new IllegalArgumentException(String.format("Track '%s' holds '%s' samples, not the window's '%s'", channel, values.length, StripFit.WINDOW_TICKS));
        if (wrap.isPresent() && !(wrap.getAsDouble() > 0d && Double.isFinite(wrap.getAsDouble())))
            throw new IllegalArgumentException(String.format("Track '%s' wraps at '%s', which is not a positive span", channel, wrap.getAsDouble()));
    }

    /**
     * What a channel carries - the unit its samples are in, and how far it may move in that unit and
     * still read as not moving at all.
     *
     * <p>One tolerance is the one bar every test of the fit is measured against: a channel that never
     * leaves its start by more is still, a restart that jumps no more closes, and motion smaller than
     * one cannot buy a finer step.
     */
    @RequiredArgsConstructor
    public enum Kind {

        /** A rotation about one Euler axis, in degrees. */
        ROTATION(1d),

        /** A pivot offset in the model's own pixels, before any scale the mesh is drawn at. */
        POSITION(0.25),

        /** A scale, as the factor a pose writes or the delta a clip adds to it. */
        SCALE(1d / 64),

        /** A texture scroll, in texels of the sheet it scrolls over. */
        TEXEL(0.25),

        /**
         * A pass's opacity, as a fraction of full. It counts toward whether a strip moves, which step it
         * takes and how long it runs, and never toward where it restarts, so a glow that does not share
         * the body's loop jumps at the restart rather than holding the body off it.
         */
        OPACITY(1d / 64);

        /** How far a channel of this kind may move, in its own unit, and still read as not moving. */
        @Getter(value = AccessLevel.PACKAGE, style = NamingStyle.FLUENT)
        private final double tolerance;

    }

    /**
     * Where a contribution's period comes from, which decides the floor it sets under a strip's length
     * and how its restart is judged.
     */
    public sealed interface Role permits Role.Expression, Role.Looping, Role.OneShot {

        /**
         * A pose expression's write, whose period the fit reads off the samples themselves.
         */
        record Expression() implements Role {}

        /**
         * A part that repeats on a known period - a looping clip at its clock, or a scroll. A period
         * that is not positive and finite is no period at all: the part sets no floor, and its jump at
         * the restart is still judged.
         *
         * @param periodTicks the ticks one repeat spans
         */
        record Looping(double periodTicks) implements Role {}

        /**
         * A clip that plays once and then holds its last frame. It sets a floor of its whole length; one
         * that does not end where it started stops the strip at the floor every visible part sets, with no
         * search past it, and one still running at a length is judged at the restart as the cut it is.
         *
         * @param lengthTicks the ticks one run lasts
         */
        record OneShot(double lengthTicks) implements Role {}

    }

}
