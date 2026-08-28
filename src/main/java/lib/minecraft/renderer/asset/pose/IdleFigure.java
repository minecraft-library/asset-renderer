package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * A scalar vanilla's own tick fills, and the excursion an offline render moves it across instead.
 *
 * <p>An offline subject is built and never added to a world, so nothing ticks it and every figure a
 * {@code tick} or an {@code aiStep} would have filled holds zero. Both sides then agree at zero and
 * both are still, which is correct as a diff and a fiction as a render: a squid's tentacles do not
 * wave and an axolotl does not hover. Ticking the subject is not the answer either - those paths
 * draw from vanilla's random, so a ticked reference would not reproduce, and pinning the draw would
 * oblige this side to reproduce mob AI to answer the same number.
 *
 * <p><b>What the draws actually decide is a rate, an interval or a target, never a shape.</b> A
 * squid's is the whole of what one reaches: {@code tentacleSpeed} is redrawn in {@code (0.1, 0.2]}
 * and decides how fast a fixed cycle advances, while the cycle itself and the angle it produces are
 * the same set whatever it draws. Every other draw this roster meets sits behind
 * {@code isEffectiveAi() && !isClientSide}, which an offline subject never passes. So moving a
 * figure across its own range is not an approximation of the random - it is a superset of every
 * outcome the random has.
 *
 * <p><b>The excursion runs from what the figure RESTS at, not from its numeric minimum</b>, so at
 * tick zero every one answers exactly what it answers with no excursion at all. That is what keeps
 * frame 0 of a strip, every authored render and every frozen reference where they were.
 *
 * <p><b>{@link IdleState} is the other half of the problem and deliberately not this type.</b> Some
 * factors are a smoothed one-hot over a vanilla enum rather than a scalar, and they share nothing
 * with these but the need to be answered by field name: a scalar is a function of the tick and
 * carries no notion of a selection, where a state is a function of a selection and carries no notion
 * of the tick. Held under one interface each kind ignored the other's parameter, which is what said
 * they were two things.
 *
 * <p>This is a caller's coinage rather than a vanilla fact - vanilla has no idle range, it has an AI
 * that visits one. What makes it legitimate is that the harness answers the same numbers, so a render
 * that overrides nothing is comparable against the reference set.
 *
 * <p>It sits beside the pose vocabulary rather than beside the options that tune it because what it
 * names is a figure a shipped pose READS: the token here and the token in a table's
 * {@code {"input": ...}} node are one spelling, and a caller's override is a setting on top of that
 * rather than the thing itself.
 *
 * <p><b>Parity.</b> Read by the entity pose runtime alone, so an entity is the whole of what it
 * moves - and only one rendered at a tick, the authored pose answering no figure at all. A caller
 * that overrides an excursion has left the reference set behind, which is a property of that render
 * rather than of this roster.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum IdleFigure {

    /**
     * A squid's tentacle angle, which its own tentacle phase produces.
     *
     * <p>{@code Squid.aiStep} steps {@code tentacleMovement} by a redrawn {@code tentacleSpeed} and,
     * while that phase is under {@code PI}, writes
     * {@code tentacleAngle = sin((m / PI) ^ 2 * PI) * PI * 0.25}. So the angle rises to a quarter
     * turn and returns, and the redrawn rate decides only how quickly. The phase itself never
     * reaches a render state, so the angle is the only member of the pair this roster carries.
     */
    TENTACLE_ANGLE("tentacleAngle", 0f, 0.7853982f, Shape.SWEEP),

    /**
     * An ender dragon's wing phase.
     *
     * <p>The one figure on this roster no random reaches at all: a dragon rests in the hovering
     * phase, where {@code isSitting()} is unconditionally true, and {@code aiStep} then steps
     * {@code flapTime} by a literal {@code 0.1} a tick. Every reader multiplies it by {@code 2 * PI},
     * so it is a phase of period one and a whole beat is ten ticks. Its neck and tail read
     * differences across a flight history whose samples are identical offline, so the beat is the
     * whole of what an offline dragon animates.
     */
    FLAP_TIME("flapTime", 0f, 1f, Shape.CYCLE),

    /**
     * How far a shulker's lid stands open, which its model turns the head and raises the lid by.
     *
     * <p>Vanilla ramps it by {@code 0.05} a tick toward a target its peek goal picks, and that goal
     * is server-side, so an offline shulker never opens at all. Bounded at one by the same field
     * that bounds a lid.
     */
    PEEK_AMOUNT("peekAmount", 0f, 1f, Shape.SWEEP),

    /**
     * How much of an axolotl's swimming its animation mixes in, against its hovering.
     *
     * <p>The axolotl's one genuinely continuous factor, and the only one of its four that is: it
     * ticks on a boolean of its own - moving, or turning in either axis - rather than on the state
     * selector the other three encode. Its model weights swimming at
     * {@code min(moving, whereFactor)} and hovering at {@code min(1 - moving, whereFactor)}, so with
     * a place selected this is what blends one into the other and back.
     */
    MOVING_FACTOR("movingFactor", 0f, 1f, Shape.SWEEP);

    /**
     * The ticks one whole excursion spans.
     *
     * <p>MUST match the harness's own, and it is the animated sweep's whole strip - eight frames
     * three ticks apart - so a strip shows one excursion, the last frame does not repeat the first,
     * and an animated render loops.
     */
    public static final int PERIOD_TICKS = 24;

    /** How a figure travels between the value it rests at and the one it reaches. */
    public enum Shape {

        /** Out and back, so a strip begins and ends at rest. */
        SWEEP,

        /** A phase, wrapping at its period. */
        CYCLE
    }

    /** What one figure rests at and reaches, where a caller means other than the roster. */
    public record Excursion(float rest, float extent) {}

    /** The render-state field vanilla fills, which is the name a shipped pose reads it under. */
    @KeyField
    private final @NotNull String field;

    /** What an untouched subject holds, and so what tick zero answers. */
    private final float rest;

    /** The far end of the excursion, read off the arithmetic named in this constant's own docs. */
    private final float extent;

    /** How it travels between the two. */
    private final @NotNull Shape shape;

    /**
     * What this figure holds at one tick, at the excursion this roster declares.
     *
     * @param tick the tick being posed
     * @return its value at that tick
     */
    public float at(int tick) {
        return at(tick, this.rest, this.extent);
    }

    /**
     * What this figure holds at one tick.
     *
     * @param tick the tick being posed
     * @param rest what it rests at, which a caller may have overridden
     * @param extent what it reaches, which a caller may have overridden
     * @return its value at that tick
     */
    public float at(int tick, float rest, float extent) {
        float phase = Math.floorMod(tick, PERIOD_TICKS) / (float) PERIOD_TICKS;
        float travel = this.shape == Shape.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
        return rest + (extent - rest) * travel;
    }

}
