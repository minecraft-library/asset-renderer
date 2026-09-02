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

    /**
     * The network id every harness-built subject renders at, which MUST match the asset side's
     * declared {@code entityId} rest.
     *
     * <p>Vanilla spreads an idle animation across a crowd by seeding it from the entity's own id, and
     * that id comes off a counter over everything the client has ever built - so it reads as
     * deterministic per subject and is not, and two runs of one schedule drew two different noses.
     * Pinning it is what makes the set reproduce; WHICH value is a choice, and it is this one.
     *
     * <p><b>Nine because the excursion is the point.</b> A witch's nose turns by
     * {@code sin(ageInTicks * 0.01 * (entityId % 10))}, so the id picks a frequency and nothing else.
     * Zero is the one frequency in ten at which the bob is a constant, which is a subject held still
     * rather than one animated; nine is the highest, shows the most of the cycle inside one strip,
     * and every lower frequency is a fraction of the same curve - the same argument
     * {@link AnimationClock#WALK_AMPLITUDE} rests on.
     */
    public static final int PINNED_ENTITY_ID = 9;

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

        /** Which of a head tilt, a hop or neither a rabbit is doing, over its own three-way. */
        RABBIT,

        /** Where an armadillo is in its shell, over {@code ArmadilloState}'s own four members. */
        ARMADILLO_SHELL,

        /** Whether a camel is sitting, rising, dashing or standing, over its own tick's arms. */
        CAMEL_STANCE,

        /** Whether a breeze's whirl is running, which its own tick starts unconditionally. */
        BREEZE_WHIRL,

        /** Which pose clip a breeze is playing, over the arms of {@code Pose} its tick switches on. */
        BREEZE_POSE,

        /** Which container interaction a copper golem is playing, over its own four. */
        CHEST_INTERACTION,

        /** Which action clip a frog is playing, over its own four. */
        FROG_ACTION,

        /** Which keyframe clip a baby axolotl is playing, its adult answering through factors. */
        AXOLOTL_CLIP,

        /** Which one-shot action clip a warden, a creaking or a sniffer is playing. */
        ACTION_CLIP;

        /**
         * The member a render selects where a caller names none.
         *
         * <p>MUST match the asset side's {@code IdleState.Group.selected}. Two groups answer
         * differently under a stride, both being subjects whose locomotion is a state-gated clip
         * rather than a walk-gated one - a rabbit hops and a breeze slides, and neither carries a
         * walk-gated clip at all.
         */
        public State selected(boolean walking) {
            return switch (this) {
                case AXOLOTL -> State.IN_WATER;
                case DOLPHIN -> State.MOVING;
                case BAT -> State.FLYING;
                case IDLE_CLIP -> State.IDLING;
                case RABBIT -> walking ? State.HOPPING : State.TILTING;
                case ARMADILLO_SHELL -> State.UNBALLED;
                case CAMEL_STANCE -> State.STANDING;
                case BREEZE_WHIRL -> State.WHIRLING;
                case BREEZE_POSE -> walking ? State.SLIDING : State.GROUNDED;
                case CHEST_INTERACTION -> State.NOT_INTERACTING;
                case FROG_ACTION -> State.FROG_RESTING;
                case AXOLOTL_CLIP -> State.BABY_AXOLOTL_STILL;
                case ACTION_CLIP -> State.NOT_ACTING;
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

        TILTING(Group.RABBIT, "idleHeadTiltAnimationState"),

        HOPPING(Group.RABBIT, "hopAnimationState"),

        NOT_TILTING(Group.RABBIT, ""),

        ROLLING_UP(Group.ARMADILLO_SHELL, "rollUpAnimationState"),

        ROLLING_OUT(Group.ARMADILLO_SHELL, "rollOutAnimationState"),

        PEEKING(Group.ARMADILLO_SHELL, "peekAnimationState"),

        UNBALLED(Group.ARMADILLO_SHELL, ""),

        SITTING_DOWN(Group.CAMEL_STANCE, "sitAnimationState"),

        SITTING(Group.CAMEL_STANCE, "sitPoseAnimationState"),

        STANDING_UP(Group.CAMEL_STANCE, "sitUpAnimationState"),

        DASHING(Group.CAMEL_STANCE, "dashAnimationState"),

        STANDING(Group.CAMEL_STANCE, ""),

        WHIRLING(Group.BREEZE_WHIRL, "idle"),

        SLIDING(Group.BREEZE_POSE, "slide"),

        SLIDING_BACK(Group.BREEZE_POSE, "slideBack"),

        INHALING(Group.BREEZE_POSE, "inhale"),

        SHOOTING(Group.BREEZE_POSE, "shoot"),

        LONG_JUMPING(Group.BREEZE_POSE, "longJump"),

        GROUNDED(Group.BREEZE_POSE, ""),

        GETTING_ITEM(Group.CHEST_INTERACTION, "interactionGetItem"),

        GETTING_NOTHING(Group.CHEST_INTERACTION, "interactionGetNoItem"),

        DROPPING_ITEM(Group.CHEST_INTERACTION, "interactionDropItem"),

        DROPPING_NOTHING(Group.CHEST_INTERACTION, "interactionDropNoItem"),

        NOT_INTERACTING(Group.CHEST_INTERACTION, ""),

        JUMPING(Group.FROG_ACTION, "jumpAnimationState"),

        TONGUING(Group.FROG_ACTION, "tongueAnimationState"),

        SWIM_IDLING(Group.FROG_ACTION, "swimIdleAnimationState"),

        CROAKING(Group.FROG_ACTION, "croakAnimationState"),

        FROG_RESTING(Group.FROG_ACTION, ""),

        BABY_SWIMMING(Group.AXOLOTL_CLIP, "swimAnimation"),

        BABY_IDLING_UNDERWATER_ON_GROUND(Group.AXOLOTL_CLIP, "idleUnderWaterOnGroundAnimationState"),

        BABY_IDLING_UNDERWATER(Group.AXOLOTL_CLIP, "idleUnderWaterAnimationState"),

        BABY_IDLING_ON_GROUND(Group.AXOLOTL_CLIP, "idleOnGroundAnimationState"),

        BABY_PLAYING_DEAD(Group.AXOLOTL_CLIP, "playDeadAnimationState"),

        BABY_AXOLOTL_STILL(Group.AXOLOTL_CLIP, ""),

        ATTACKING(Group.ACTION_CLIP, "attackAnimationState"),

        DIGGING(Group.ACTION_CLIP, "diggingAnimationState"),

        ROARING(Group.ACTION_CLIP, "roarAnimationState"),

        WARDEN_SNIFFING(Group.ACTION_CLIP, "sniffAnimationState"),

        EMERGING(Group.ACTION_CLIP, "emergeAnimationState"),

        SONIC_BOOMING(Group.ACTION_CLIP, "sonicBoomAnimationState"),

        INVULNERABLE(Group.ACTION_CLIP, "invulnerabilityAnimationState"),

        DYING(Group.ACTION_CLIP, "deathAnimationState"),

        SNIFFER_SNIFFING(Group.ACTION_CLIP, "sniffingAnimationState"),

        RISING(Group.ACTION_CLIP, "risingAnimationState"),

        FEELING_HAPPY(Group.ACTION_CLIP, "feelingHappyAnimationState"),

        SCENTING(Group.ACTION_CLIP, "scentingAnimationState"),

        NOT_ACTING(Group.ACTION_CLIP, "");

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
     * The member one selector holds at the gait this render is at.
     *
     * <p>The one way a mixin should reach a default, because the gait is not optional: two groups
     * answer a different member under a stride, and a call site that resolved the group without it
     * would leave a rabbit tilting its head while its legs swing.
     *
     * @param group the selector being asked
     * @return the member selected at the armed gait
     */
    public static State selected(Group group) {
        return group.selected(PoseState.walking());
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
        if (!PoseState.posed()) return false;
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
        if (!PoseState.posed()) return rest;
        float phase = Math.floorMod(AnimationClock.tick, PERIOD_TICKS) / (float) PERIOD_TICKS;
        float travel = shape == Shape.CYCLE ? phase : 1f - Math.abs(2f * phase - 1f);
        return rest + (extent - rest) * travel;
    }
}
