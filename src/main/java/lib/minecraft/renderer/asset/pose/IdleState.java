package lib.minecraft.renderer.asset.pose;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * A state a subject is IN, carried across the render-state fields of one selector as a one-hot.
 *
 * <p>Vanilla decides one member of a set and drives a field per member toward "am I the one", so
 * those fields are an encoding of a single choice rather than several sliders. An axolotl's is the
 * corpus's first: {@code tickAdultAnimations} picks one of {@code PLAYING_DEAD}, {@code IN_WATER},
 * {@code ON_GROUND} and {@code IN_AIR}, then ticks three animators on {@code state == <member>}.
 *
 * <p><b>Which is why moving one of them across a range is wrong.</b> Two factors at a half each is a
 * value vanilla only ever produces during the ten-tick crossfade between two states, so a strip that
 * swept them would render a transition rather than a fuller rest - and driving two to one at once is
 * a pose the one-hot can never reach. A caller SELECTS instead, and the selected member answers one
 * while every other answers zero.
 *
 * <p><b>A selector is a {@link Group} and a caller chooses one member of each.</b> The axolotl's
 * four are one vanilla enum; a dolphin's two are the arms of one boolean; a bat's two are the
 * animation states its own tick stops one of to start the other. They are the same shape - mutually
 * exclusive members over which exactly one holds - and the type carries them all rather than one
 * enum per subject, because what the runtime resolves is a render-state field name and a field
 * belongs to exactly one selector.
 *
 * <p><b>What the selected field is READ by is not this type's business, and that is what let the
 * clip-driven subjects in without a second mechanism.</b> An axolotl's factor is a weight a pose
 * expression multiplies by; a bat's animation state is the gate a play site sits behind. Both are a
 * render-state field the frame answers, so {@code ClipKit} asks it the same question
 * {@code PoseEvaluator} asks - and a model declaring six state-driven clips plays the one whose gate
 * the selection answers, which is vanilla's own exclusion rather than a rule anything here had to be
 * told.
 *
 * <p><b>Not a kind of {@link IdleFigure}, though the two are answered side by side.</b> A figure is
 * a function of the tick and carries no notion of a selection; a state is a function of a selection
 * and carries no notion of the tick. They share only that the runtime resolves both by render-state
 * field name, which is a dispatch rather than a behaviour - held under one interface each ignored
 * the other's parameter.
 *
 * <p>The vocabulary is vanilla's own rather than a coinage: a member is one of its enum's members or
 * one arm of its own boolean, so a version that adds one adds it here rather than reshaping
 * anything. <b>An animation state is a boolean for this purpose</b> - {@code isStarted()} is
 * {@code startTick != Integer.MIN_VALUE} and {@code stop()} puts it back - so the arm a clip is not
 * playing on is vanilla's own and not an off switch invented here. It is also the arm the subject
 * spends most of its life in: a camel redraws 80 to 120 ticks between two idles and plays one for
 * the length of the clip.
 *
 * <p><b>A group carries a resting member exactly where vanilla has a resting arm, so not every group
 * has one.</b> An axolotl's {@link #IN_AIR} is a member of vanilla's own enum, a dolphin's
 * {@link #STILL} is the false arm of a boolean, and the two clip groups rest on a stopped animation
 * state. {@link Group#BAT} has none: its own {@code setupAnimationStates} stops one of its two
 * states to start the other on every tick, so a bat is on the wing or hanging and never neither, and
 * a member for neither would be the coinage the paragraph above rules out.
 *
 * <p><b>Parity.</b> Read by the entity pose runtime alone, so an entity is the whole of what it
 * moves. A caller that selects other than the default has left the reference set behind, which is a
 * property of that render rather than of this roster.
 */
@EnumLookup
@RequiredArgsConstructor
@Getter(style = NamingStyle.FLUENT)
@Parity(subject = Subject.ENTITY)
public enum IdleState {

    /**
     * An axolotl rolled onto its back.
     *
     * <p>In the same one-hot as the other three despite its animation being layered on top of their
     * blend rather than being one of its weights - playing dead excludes being in water and being on
     * the ground, so it is a place in the selector and not a fifth slider.
     */
    PLAYING_DEAD(Group.AXOLOTL, "playingDeadFactor"),

    /**
     * An axolotl in water, which is what an idle one selects.
     *
     * <p>The group's default because the subject is aquatic: with this selected its model weights
     * hovering and swimming and rests the two ground blends, so the one continuous factor it carries
     * has something to blend BETWEEN.
     */
    IN_WATER(Group.AXOLOTL, "inWaterFactor"),

    /** An axolotl on a surface, which weights its crawl and its lie-still blends. */
    ON_GROUND(Group.AXOLOTL, "onGroundFactor"),

    /**
     * An axolotl in neither, which every factor of the group rests through.
     *
     * <p>Named with the empty token because a state that drives no field is still a member a caller
     * may choose, and choosing it is how an idle render asks for the subject to hold still.
     */
    IN_AIR(Group.AXOLOTL, ""),

    /**
     * A dolphin under way, which is what an idle one selects.
     *
     * <p>{@code DolphinRenderer.extractRenderState} fills the field as
     * {@code getDeltaMovement().horizontalDistanceSqr() > 1.0E-7}, and a subject nothing has moved
     * answers false - so the whole of what an offline dolphin holds still is a delta no tick filled.
     * No draw is anywhere near it. Its model reads the field as a gate rather than as a weight:
     * under it the body pitches by {@code -0.05 - 0.05 * cos(age * 0.3)} and the tail and its fin
     * turn by {@code -0.1} and {@code -0.2} of the same cosine, which is the swim every dolphin in
     * the game is drawn in.
     */
    MOVING(Group.DOLPHIN, "isMoving"),

    /** A dolphin holding station, which its model draws with the body its orientation alone places. */
    STILL(Group.DOLPHIN, ""),

    /**
     * A bat on the wing, which is what an idle one selects.
     *
     * <p>{@code Bat.setupAnimationStates} stops the resting state and starts this one whenever
     * {@code isResting()} is false, and that flag is one bit of a synched byte its own
     * {@code defineSynchedData} declares at zero - so a bat the client has built is flying, and the
     * whole reason an offline one hangs motionless is that nothing has ticked it into either.
     */
    FLYING(Group.BAT, "flyAnimationState"),

    /** A bat hanging from a ceiling, which its own resting clip draws. */
    RESTING(Group.BAT, "restAnimationState"),

    /**
     * A subject playing the idle clip it keeps on a timer of its own, which is what an idle render
     * selects.
     *
     * <p>Both the camel's and the copper golem's timers start at zero, so the first tick either has
     * ever taken starts the clip - and what their randoms decide is how long until the NEXT one, the
     * camel drawing an interval in {@code [80, 120)} ticks and the golem one in {@code [200, 240)}.
     * A draw that picks an interval picks no shape, which is the finding the whole roster rests on.
     */
    IDLING(Group.IDLE_CLIP, "idleAnimationState"),

    /**
     * A subject whose idle timer has not come round, which is every frame between two of them.
     *
     * <p>The stopped arm of the same {@code AnimationState} {@link #IDLING} is the started arm of,
     * and the one a camel holds for the 80 to 120 ticks its random puts between two plays.
     */
    NOT_IDLING(Group.IDLE_CLIP, ""),

    /**
     * A rabbit tilting its head, which is its whole idle and what an idle render selects.
     *
     * <p>{@code Rabbit.setupAnimationStates} starts it whenever its own timer has run out and the
     * subject is neither leashed nor {@code isNoAi()}, both of which a fresh rabbit answers - so the
     * first tick starts it and the draw sets only the {@code [180, 220)} ticks until the next.
     */
    TILTING(Group.HEAD_TILT, "idleHeadTiltAnimationState"),

    /**
     * A rabbit between two head tilts, which is what its own timer leaves it doing.
     *
     * <p>The stopped arm of the same {@code AnimationState} {@link #TILTING} is the started arm of,
     * held for the 180 to 220 ticks its random puts between two plays.
     */
    NOT_TILTING(Group.HEAD_TILT, "");

    /**
     * One selector, over which a caller chooses exactly one member.
     *
     * <p>A group is vanilla's own grouping rather than a convention: the axolotl's is the enum
     * {@code tickAdultAnimations} switches on, the dolphin's is the two arms of the one boolean its
     * model branches on, and a clip group's is the set of animation states one
     * {@code setupAnimationStates} stops to start another of. What makes them one type is that all
     * of them are answered the same way, by render-state field name.
     *
     * <p><b>A group is a selector rather than a subject, and two of them are named for the field
     * they select over rather than for whoever reads it.</b> A camel and a copper golem both spell
     * their idle clip's gate {@code idleAnimationState}, and a field name is the whole of what the
     * frame is asked - so at this resolution they are one selector, and calling the group
     * {@code CAMEL} would be claiming a distinction nothing downstream can make.
     */
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

        /**
         * The member an idle render selects where a caller names none, which is the one vanilla's own
         * tick settles on.
         *
         * @return the group's default member
         */
        public @NotNull IdleState selected() {
            return switch (this) {
                case AXOLOTL -> IN_WATER;
                case DOLPHIN -> MOVING;
                case BAT -> FLYING;
                case IDLE_CLIP -> IDLING;
                case HEAD_TILT -> TILTING;
            };
        }

    }

    /**
     * The members that drive a field, which is every member but a group's resting arm.
     *
     * <p>Held rather than filtered per call because {@link #ofField} is asked of every field a pose
     * reads, and {@code values()} hands back a fresh array each time. Safe in an enum's own static
     * initialiser, the constants being built before any other static field.
     */
    private static final @NotNull IdleState[] DRIVERS = Arrays.stream(values())
        .filter(member -> !member.field.isEmpty())
        .toArray(IdleState[]::new);

    /** The selector this member belongs to, over which exactly one member holds. */
    private final @NotNull Group group;

    /**
     * The render-state field this member drives, or empty where the member drives none.
     *
     * <p>Every member that names a field names one no other member does, which is what makes
     * {@link #ofField} an answer rather than a first match. The empty token is what the members
     * naming none carry, and there are four of them, so it is the one spelling that would break
     * that - which is why the lookup passes over them rather than scanning the whole roster.
     */
    private final @NotNull String field;

    /**
     * The member driving one render-state field, or {@code null} where none does.
     *
     * <p>Written out rather than generated off a key field, because that lookup is a linear scan
     * where the first match wins and a group's resting member carries the empty token - so a
     * generated one answered {@link #IN_AIR} for the empty string, which is a member no caller asked
     * for standing in for a field nothing spells. Passing the resting members over makes the empty
     * token unreachable rather than merely unasked for.
     *
     * @param field the render-state field being answered
     * @return the member that drives it, or {@code null} where no member does
     */
    public static @Nullable IdleState ofField(@NotNull String field) {
        for (IdleState member : DRIVERS)
            if (member.field.equals(field)) return member;
        return null;
    }

    /**
     * What one field holds when a given member of its group is the selected one.
     *
     * <p>A one-hot and nothing else: the selected member's own field answers one and every other
     * answers zero. There is no ramp, because a ramp is the crossfade between two states and an idle
     * subject is already in the one it is in - which is also why this takes no tick.
     *
     * @param selected the member the caller selected for this member's group
     * @return one where this field belongs to the selected member, zero otherwise
     */
    public float when(@NotNull IdleState selected) {
        return selected == this ? 1f : 0f;
    }

}
