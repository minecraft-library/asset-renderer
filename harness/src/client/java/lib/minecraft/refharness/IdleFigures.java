package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.sweep.EntityAnimationSweep;
import net.minecraft.world.entity.AnimationState;

/**
 * What each figure vanilla's own tick would have filled holds at the tick a render is posed at.
 *
 * <p>An entity the harness builds is never added to a world, so nothing ticks it and every figure a
 * {@code tick} or an {@code aiStep} fills rests at zero. Ticking it is not the answer: those paths
 * draw from {@code RandomSource}, so a ticked reference would not reproduce, and pinning the draw
 * would oblige the asset side to reproduce mob AI to answer the same number. What the draws decide
 * is a rate, an interval or a target and never a shape, so moving a figure across its own range is a
 * superset of every outcome a draw has rather than an approximation of one.
 *
 * <p><b>The asset side answers these from a roster of its own and the two are asserted equal</b>,
 * the same way {@code START_TICK}, {@code FRAME_COUNT} and {@code TICKS_PER_FRAME} are - a value that
 * moved on one side only would render happily and report as a renderer defect.
 *
 * <p>The excursion runs from what a figure RESTS at rather than from its numeric minimum, so tick
 * zero answers exactly what an unmoved figure answers. That, and the {@code ANIMATED} gate below,
 * are what keep every frozen reference where it was.
 */
@UtilityClass
public final class IdleFigures {

    /**
     * The ticks one whole excursion spans. MUST match the asset side's {@code IdleFigure}.
     *
     * <p>One strip, so a strip shows one excursion, the last frame does not repeat the first, and an
     * animated render loops.
     */
    public static final int PERIOD_TICKS =
        EntityAnimationSweep.FRAME_COUNT * EntityAnimationSweep.TICKS_PER_FRAME;

    /** How a scalar figure travels between the value it rests at and the one it reaches. */
    public enum Shape {

        /** Out and back, so a strip begins and ends at rest. */
        SWEEP,

        /** A phase, wrapping at its period. */
        CYCLE
    }

    /**
     * The scalar roster, whose constants are the asset side's {@code IdleFigure.Continuous}
     * character for character.
     *
     * <p>Written as one declaration per figure rather than as literals at each call site so the two
     * copies can be compared as text - the arithmetic is shared by both sides answering the same
     * numbers, and nothing but a test makes that true.
     */
    public enum Continuous {

        TENTACLE_ANGLE("tentacleAngle", 0f, 0.7853982f, Shape.SWEEP),

        FLAP_TIME("flapTime", 0f, 1f, Shape.CYCLE),

        PEEK_AMOUNT("peekAmount", 0f, 1f, Shape.SWEEP),

        MOVING_FACTOR("movingFactor", 0f, 1f, Shape.SWEEP);

        private final String field;

        private final float rest;

        private final float extent;

        private final Shape shape;

        Continuous(String field, float rest, float extent, Shape shape) {
            this.field = field;
            this.rest = rest;
            this.extent = extent;
            this.shape = shape;
        }

        /** The render-state field vanilla fills. */
        public String field() {
            return this.field;
        }
    }

    /** One selector, over which a caller chooses exactly one member. */
    public enum Group {

        /** An axolotl's place, over {@code AxolotlAnimationState}'s own four members. */
        AXOLOTL,

        /** Whether a dolphin is under way, over the two arms of {@code DolphinRenderState.isMoving}. */
        DOLPHIN,

        /** Whether a bat is on the wing or hanging, which its own tick stops one to start. */
        BAT,

        /** Whether the idle clip a camel and a copper golem both spell one way is playing. */
        IDLE_CLIP,

        /** Whether a rabbit's head tilt is playing, which is the idle it spells apart from those. */
        HEAD_TILT;

        /** The member an idle render selects where a caller names none. */
        public State selected() {
            return switch (this) {
                case AXOLOTL -> State.IN_WATER;
                case DOLPHIN -> State.MOVING;
                case BAT -> State.FLYING;
                case IDLE_CLIP -> State.IDLING;
                case HEAD_TILT -> State.TILTING;
            };
        }
    }

    /**
     * The one-hot roster, whose constants are the asset side's {@code IdleState} character for
     * character.
     *
     * <p>A selection rather than an excursion: vanilla decides one member and drives a field per
     * member toward "am I the one", so the selected member's field answers one and every other
     * answers zero. A member naming no field - {@code IN_AIR}, {@code STILL} - rests its whole group.
     */
    public enum State {

        PLAYING_DEAD(Group.AXOLOTL, "playingDeadFactor"),

        IN_WATER(Group.AXOLOTL, "inWaterFactor"),

        ON_GROUND(Group.AXOLOTL, "onGroundFactor"),

        IN_AIR(Group.AXOLOTL, ""),

        MOVING(Group.DOLPHIN, "isMoving"),

        STILL(Group.DOLPHIN, ""),

        FLYING(Group.BAT, "flyAnimationState"),

        RESTING(Group.BAT, "restAnimationState"),

        IDLING(Group.IDLE_CLIP, "idleAnimationState"),

        NOT_IDLING(Group.IDLE_CLIP, ""),

        TILTING(Group.HEAD_TILT, "idleHeadTiltAnimationState"),

        NOT_TILTING(Group.HEAD_TILT, "");

        private final Group group;

        private final String field;

        State(Group group, String field) {
            this.group = group;
            this.field = field;
        }

        /** The selector this member belongs to. */
        public Group group() {
            return this.group;
        }

        /** The render-state field this member drives, or empty where the member drives none. */
        public String field() {
            return this.field;
        }
    }

    /**
     * What one scalar figure holds at the armed tick.
     *
     * @param figure the figure being driven
     * @return its value at that tick, and its resting value on a run that does not animate
     */
    public static float at(Continuous figure) {
        return at(figure.rest, figure.extent, figure.shape);
    }

    /**
     * What one field of a one-hot holds, given the member an idle render selects.
     *
     * <p>Unlike a scalar this needs no clock: an idle subject is already in the state it is in, and
     * a ramp between two of them is the crossfade this deliberately does not render.
     *
     * @param selected the member selected
     * @param factor the field being answered
     * @return one where that field belongs to the selected member, zero otherwise
     */
    public static float select(State selected, State factor) {
        return selects(selected, factor) ? 1f : 0f;
    }

    /**
     * Whether one arm of a boolean selector is the selected one.
     *
     * <p>The same answer {@link #select} gives, for the fields vanilla declares as a boolean rather
     * than as a factor - a dolphin's {@code isMoving} is one arm of a two-member selection and not a
     * weight, so it is answered rather than ramped.
     *
     * @param selected the member selected
     * @param factor the field being answered
     * @return whether that field belongs to the selected member
     */
    public static boolean selects(State selected, State factor) {
        if (!HarnessConfig.ANIMATED) return false;
        return selected == factor;
    }

    /**
     * Runs one animation state where its own member is the selected one, and stops it otherwise.
     *
     * <p>Started at the tick the strip itself starts at, because {@code getTimeInMillis} is the
     * elapsed age less that tick and the asset side subtracts the same nothing - so a clip's own
     * instant is the armed tick times fifty on both sides. Vanilla's exclusion is reproduced rather
     * than assumed: {@code setupAnimationStates} stops one state to start another, so a member that
     * is not the selected one is stopped here too and a subject declaring six clips plays one.
     *
     * <p>Inert on a frozen run, where {@link #selects} answers false for every member and nothing
     * has started a state anyway - and where {@code SkipSetupAnimMixin} means no play site runs at
     * all.
     *
     * @param selected the member selected
     * @param factor the member this state belongs to
     * @param state the animation state to run or stop
     */
    public static void play(State selected, State factor, AnimationState state) {
        if (selects(selected, factor)) state.start(EntityAnimationSweep.START_TICK);
        else state.stop();
    }

    /**
     * A figure's value at the armed tick, and its resting value on a run that does not animate.
     *
     * <p>The {@code ANIMATED} gate is what makes this inert on the seven frozen sub-trees: they hold
     * the resting value already, so a run that is not the animated one writes back what it read.
     *
     * @param rest what the figure rests at
     * @param extent the far end of its excursion
     * @param shape how it travels between them
     * @return its value at the armed tick
     */
    public static float at(float rest, float extent, Shape shape) {
        if (!HarnessConfig.ANIMATED) return rest;
        float phase = Math.floorMod(AnimationClock.tick, PERIOD_TICKS) / (float) PERIOD_TICKS;
        float travel = shape == Shape.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
        return rest + (extent - rest) * travel;
    }
}
