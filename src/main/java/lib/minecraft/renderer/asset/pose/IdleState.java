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
 * {@link #STILL} is the false arm of a boolean, and the clip groups rest on a stopped animation
 * state. Two have none. {@link Group#BAT}'s own {@code setupAnimationStates} stops one of its two
 * states to start the other on every tick, so a bat is on the wing or hanging and never neither;
 * {@link Group#BREEZE_WHIRL}'s single state is started unconditionally, so a breeze whirls or has
 * not been ticked. A member for either absence would be the coinage the paragraph above rules out.
 *
 * <p><b>A group is a selector rather than a subject, and where two subjects spell one field the
 * group holds both.</b> {@link Group#IDLE_CLIP} is a camel's idle and a copper golem's;
 * {@link Group#ACTION_CLIP} is a warden's, a creaking's and a sniffer's, because the first pair
 * share {@code attackAnimationState} and the second share {@code diggingAnimationState}. A field
 * name is the whole of what the frame is asked, so splitting those per subject would make
 * {@link #ofField} a first match rather than an answer.
 *
 * <p><b>One state vanilla drives is deliberately absent, for a mechanism reason rather than an
 * oversight.</b> A baby axolotl's {@code walkAnimationState} gates a walk-driven play site the
 * generator's fold settles and drops, so driving it would put that site back. It is named where the
 * group that would have held it is declared.
 *
 * <p><b>A member whose state also DRAWS its bone is half of a pair.</b> {@link #CROAKING} is the
 * corpus's one: the frog's sac is drawn only while the croak runs, so the mesh rests it undrawn
 * carrying a toggle and a render that wants the croak selects the appearance as well as the state.
 * Nothing here couples the two, because an appearance is resolved before any of these are.
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
     * A rabbit tilting its head, which is what an idle render selects.
     *
     * <p>{@code Rabbit.setupAnimationStates} starts it whenever its own timer has run out and the
     * subject is neither leashed nor {@code isNoAi()}, both of which a fresh rabbit answers - so the
     * first tick starts it and the draw sets only the {@code [180, 220)} ticks until the next.
     */
    TILTING(Group.RABBIT, "idleHeadTiltAnimationState"),

    /**
     * A rabbit in mid-hop, which is the whole of how the animal moves and what a walking render
     * selects.
     *
     * <p>The second arm of {@code Rabbit.setupAnimationStates}' own three-way: with no idle due it
     * starts this whenever {@code jumpTicks > 0} and stops the head tilt, and a rabbit that is going
     * anywhere is always inside that counter. It is the one clip in the corpus a STRIDE ought to
     * reach and cannot - the animal carries no walk-gated clip at all, so a rabbit asked to walk
     * without this holds still while its legs are told to swing.
     */
    HOPPING(Group.RABBIT, "hopAnimationState"),

    /**
     * A rabbit doing neither, which is what its own timer leaves it doing between two head tilts.
     *
     * <p>The arm {@code setupAnimationStates} falls to when no idle is due and {@code jumpTicks} has
     * run out, held for the 180 to 220 ticks its random puts between two plays.
     */
    NOT_TILTING(Group.RABBIT, ""),

    /**
     * An armadillo curling into its shell, which its own {@code ArmadilloState.ROLLING} arm plays.
     *
     * <p>{@code Armadillo.setupAnimationStates} is a switch over that enum and nothing else - a
     * synched state rather than anything about movement - so this is a place in a selector on the
     * same terms an axolotl's is. The animal's walk is a separate walk-gated clip that plays already
     * and is not one of these arms.
     */
    ROLLING_UP(Group.ARMADILLO_SHELL, "rollUpAnimationState"),

    /** An armadillo uncurling, which its {@code ArmadilloState.UNROLLING} arm plays. */
    ROLLING_OUT(Group.ARMADILLO_SHELL, "rollOutAnimationState"),

    /**
     * An armadillo peering out of a closed shell, which its {@code ArmadilloState.SCARED} arm plays.
     *
     * <p>Not the shulker's {@code peekAmount} under another name - that is a scalar a figure sweeps,
     * where this is one arm of an enum, and the two share only the English word.
     */
    PEEKING(Group.ARMADILLO_SHELL, "peekAnimationState"),

    /**
     * An armadillo walking about unbothered, which is {@code ArmadilloState.IDLE} and what every
     * render selects unless it asks otherwise.
     *
     * <p>That arm stops all three of the states above, so it is the group's resting member and the
     * one a never-ticked armadillo already holds.
     */
    UNBALLED(Group.ARMADILLO_SHELL, ""),

    /** A camel lowering itself to the ground, which {@code isVisuallySittingDown} plays. */
    SITTING_DOWN(Group.CAMEL_STANCE, "sitAnimationState"),

    /** A camel settled on the ground, which is the pose it holds once it is down. */
    SITTING(Group.CAMEL_STANCE, "sitPoseAnimationState"),

    /** A camel getting back to its feet, which its own pose transition plays. */
    STANDING_UP(Group.CAMEL_STANCE, "sitUpAnimationState"),

    /**
     * A camel at a dash, which {@code dashAnimationState.animateWhen(isDashing(), tickCount)} plays.
     *
     * <p>A synched flag a rider sets rather than a gait: the animal's ordinary walk is a walk-gated
     * clip that plays under a stride already, so this is a sprint a caller asks for and not what
     * {@code EntityOptions.PoseMode.WALK} means.
     */
    DASHING(Group.CAMEL_STANCE, "dashAnimationState"),

    /**
     * A camel standing on all four feet, which is what a camel nobody is riding holds.
     *
     * <p>The group's resting member: not visually sitting stops the three sit states, and not
     * dashing leaves the fourth stopped too.
     */
    STANDING(Group.CAMEL_STANCE, ""),

    /**
     * A breeze whirling, which is the base animation every ticked one runs.
     *
     * <p><b>Not one arm of the pose selector below it.</b> {@code Breeze.tick} calls
     * {@code idle.startIfStopped(tickCount)} unconditionally, before and regardless of the pose
     * switch, so the whirl runs under every other clip the subject plays rather than excluding one.
     * That makes it a group of its own rather than an arm of the pose one-hot.
     *
     * <p><b>Its group carries no second member, and that is the same refusal {@link Group#BAT}
     * makes.</b> The start is unconditional, so a breeze with the whirl stopped is a frame vanilla
     * draws only before the subject's first tick - and a member for it would be a coinage rather
     * than one of vanilla's own arms.
     */
    WHIRLING(Group.BREEZE_WHIRL, "idle"),

    /**
     * A breeze skimming along the ground, which is how the subject travels and what a walking render
     * selects.
     *
     * <p>{@code Pose.SLIDING} in the one-hot {@code Breeze.onSyncedDataUpdated} drives, and the
     * second of the two clips in the corpus a stride ought to reach: a breeze carries no walk-gated
     * clip, so a walking one without this is a subject sliding nowhere.
     */
    SLIDING(Group.BREEZE_POSE, "slide"),

    /**
     * A breeze settling out of a slide, which its own tick starts as the pose leaves
     * {@code SLIDING}.
     *
     * <p>Held in the same group as the slide rather than beside it because the two are consecutive
     * rather than concurrent - the tick starts this one and stops that one in the same statement.
     */
    SLIDING_BACK(Group.BREEZE_POSE, "slideBack"),

    /** A breeze drawing breath, which {@code Pose.INHALING} plays. */
    INHALING(Group.BREEZE_POSE, "inhale"),

    /** A breeze firing, which {@code Pose.SHOOTING} plays. */
    SHOOTING(Group.BREEZE_POSE, "shoot"),

    /** A breeze in a long jump, which {@code Pose.LONG_JUMPING} plays. */
    LONG_JUMPING(Group.BREEZE_POSE, "longJump"),

    /**
     * A breeze standing, which is {@code Pose.STANDING} and what an idle render selects.
     *
     * <p>The group's resting member, and a real arm rather than an absence: that pose starts none of
     * the five states above and {@link #WHIRLING} carries what a standing breeze still does.
     */
    GROUNDED(Group.BREEZE_POSE, ""),

    /** A copper golem taking an item out of a container it is holding nothing from. */
    GETTING_ITEM(Group.CHEST_INTERACTION, "interactionGetItem"),

    /** A copper golem reaching into a container and finding nothing to take. */
    GETTING_NOTHING(Group.CHEST_INTERACTION, "interactionGetNoItem"),

    /** A copper golem putting the item it carries into a container. */
    DROPPING_ITEM(Group.CHEST_INTERACTION, "interactionDropItem"),

    /** A copper golem reaching into a container with nothing to put in it. */
    DROPPING_NOTHING(Group.CHEST_INTERACTION, "interactionDropNoItem"),

    /**
     * A copper golem at no container at all, which is what one nothing has sent to a chest holds.
     *
     * <p>The group's resting member. Held apart from {@link #IDLING}, which the golem spells
     * {@code idleAnimationState} and shares with a camel - a golem plays its idle clip on a timer of
     * its own whether or not it is at a container, so the two are concurrent rather than exclusive.
     */
    NOT_INTERACTING(Group.CHEST_INTERACTION, ""),

    /** A frog mid-leap, which its own jump state plays. */
    JUMPING(Group.FROG_ACTION, "jumpAnimationState"),

    /** A frog catching something on its tongue. */
    TONGUING(Group.FROG_ACTION, "tongueAnimationState"),

    /** A frog holding station in water, which is its idle wherever it is swimming. */
    SWIM_IDLING(Group.FROG_ACTION, "swimIdleAnimationState"),

    /**
     * A frog croaking, which inflates the sac under its chin and is the one clip in the corpus that
     * DRAWS a bone as well as moving it.
     *
     * <p>{@code FrogModel.setupAnim} writes {@code croakingBody.visible} from this state, so the
     * clip and the bone are inseparable - the clip writes that bone and nothing else, and the bone
     * exists only while the clip runs. <b>Which is why selecting this alone is half an answer</b>:
     * the mesh rests the sac undrawn carrying a {@code croak} toggle, so a render that wants the
     * croak asks for that appearance as well. Two selections rather than one, on the same terms an
     * armour stand's arms take.
     */
    CROAKING(Group.FROG_ACTION, "croakAnimationState"),

    /** A frog sitting on land doing none of those, which is what a fresh one holds. */
    FROG_RESTING(Group.FROG_ACTION, ""),

    /** A baby axolotl swimming, which its own swim state plays. */
    BABY_SWIMMING(Group.AXOLOTL_CLIP, "swimAnimation"),

    /** A baby axolotl resting on the floor of a body of water. */
    BABY_IDLING_UNDERWATER_ON_GROUND(Group.AXOLOTL_CLIP, "idleUnderWaterOnGroundAnimationState"),

    /** A baby axolotl hovering in open water. */
    BABY_IDLING_UNDERWATER(Group.AXOLOTL_CLIP, "idleUnderWaterAnimationState"),

    /** A baby axolotl out of water and on the ground. */
    BABY_IDLING_ON_GROUND(Group.AXOLOTL_CLIP, "idleOnGroundAnimationState"),

    /** A baby axolotl rolled onto its back. */
    BABY_PLAYING_DEAD(Group.AXOLOTL_CLIP, "playDeadAnimationState"),

    /**
     * A baby axolotl in none of those, which is what a never-ticked one holds.
     *
     * <p>The group's resting member. <b>A separate selector from {@link Group#AXOLOTL}</b>, which is
     * the same animal answering through blend factors: one render state carries both encodings
     * because the adult mesh weights four factors where the baby's plays keyframe clips, and a
     * member drives one field. <b>The underwater walk is deliberately not an arm here</b>: its model
     * reads {@code walkAnimationState.isStarted()} to gate a WALK-driven site, so driving it puts
     * back a play site the fold settled and drops.
     */
    BABY_AXOLOTL_STILL(Group.AXOLOTL_CLIP, ""),

    /**
     * A subject swinging at something, which a warden and a creaking both spell
     * {@code attackAnimationState}.
     *
     * <p>One member for two subjects for the reason {@link #IDLING} is one for two: a field name is
     * the whole of what the frame is asked, so at this resolution they are one selector. It is also
     * why the group holds three animals rather than one - the warden shares this field with the
     * creaking and {@link #DIGGING} with the sniffer, and a member belongs to exactly one group.
     */
    ATTACKING(Group.ACTION_CLIP, "attackAnimationState"),

    /**
     * A subject digging, which a warden and a sniffer both spell {@code diggingAnimationState}.
     *
     * <p>The warden burrows down and the sniffer digs a seed up, and the question the frame is asked
     * is the same one; see {@link #ATTACKING}.
     */
    DIGGING(Group.ACTION_CLIP, "diggingAnimationState"),

    /** A warden roaring, which its own pose change starts and an attack stops. */
    ROARING(Group.ACTION_CLIP, "roarAnimationState"),

    /** A warden casting about for a smell, which {@code Pose.SNIFFING} starts. */
    WARDEN_SNIFFING(Group.ACTION_CLIP, "sniffAnimationState"),

    /** A warden rising out of the ground, which {@code Pose.EMERGING} starts. */
    EMERGING(Group.ACTION_CLIP, "emergeAnimationState"),

    /** A warden firing its sonic boom, which its own entity event starts. */
    SONIC_BOOMING(Group.ACTION_CLIP, "sonicBoomAnimationState"),

    /** A creaking that cannot be hurt, which its own invulnerability state plays. */
    INVULNERABLE(Group.ACTION_CLIP, "invulnerabilityAnimationState"),

    /** A creaking coming apart, which its own death state plays. */
    DYING(Group.ACTION_CLIP, "deathAnimationState"),

    /** A sniffer casting about, which {@code Sniffer.State.SNIFFING} plays. */
    SNIFFER_SNIFFING(Group.ACTION_CLIP, "sniffingAnimationState"),

    /** A sniffer lifting its head out of a dig, which {@code Sniffer.State.RISING} plays. */
    RISING(Group.ACTION_CLIP, "risingAnimationState"),

    /** A sniffer pleased with itself, which {@code Sniffer.State.FEELING_HAPPY} plays. */
    FEELING_HAPPY(Group.ACTION_CLIP, "feelingHappyAnimationState"),

    /** A sniffer taking a scent off the ground, which {@code Sniffer.State.SCENTING} plays. */
    SCENTING(Group.ACTION_CLIP, "scentingAnimationState"),

    /**
     * A subject doing none of these, which is what a warden, a creaking and a sniffer all rest at.
     *
     * <p>The group's resting member and its default. Every one of the twelve above is started by a
     * pose change, an entity event or an enum arm a never-ticked subject is not in, so the stopped
     * arm is both what vanilla holds them at and what an offline render draws.
     */
    NOT_ACTING(Group.ACTION_CLIP, "");

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

        /** Which of a head tilt, a hop or neither a rabbit is doing, over its own three-way. */
        RABBIT,

        /** Where an armadillo is in its shell, over {@code ArmadilloState}'s own four members. */
        ARMADILLO_SHELL,

        /** Whether a camel is sitting, rising, dashing or standing, over its own tick's arms. */
        CAMEL_STANCE,

        /**
         * Whether a breeze's whirl is running, which its own tick starts unconditionally.
         *
         * <p>One member, because that start is not a choice: {@code Breeze.tick} runs it before the
         * pose switch and regardless of it, so there is no second arm to select and offering one
         * would be a coinage.
         */
        BREEZE_WHIRL,

        /** Which pose clip a breeze is playing, over the arms of {@code Pose} its tick switches on. */
        BREEZE_POSE,

        /** Which container interaction a copper golem is playing, over its own four. */
        CHEST_INTERACTION,

        /** Which action clip a frog is playing, over its own four. */
        FROG_ACTION,

        /** Which keyframe clip a baby axolotl is playing, its adult answering through factors. */
        AXOLOTL_CLIP,

        /**
         * Which one-shot action clip is running, over the twelve a warden, a creaking and a sniffer
         * spell in one keyspace.
         *
         * <p><b>Three subjects rather than one, and that is forced rather than chosen.</b> A warden
         * and a creaking both spell an attack {@code attackAnimationState}, and a warden and a
         * sniffer both spell a dig {@code diggingAnimationState} - a field name is the whole of what
         * the frame is asked, so each of those pairs is one member, and a member belongs to exactly
         * one group. Splitting them per subject would make {@link IdleState#ofField} a first match
         * rather than an answer, which is the one thing this roster may not be.
         */
        ACTION_CLIP;

        /**
         * The member a render selects where a caller names none, which is the one vanilla's own tick
         * settles on at that gait.
         *
         * <p><b>Two groups answer differently under a stride, and both are subjects whose locomotion
         * IS a state-gated clip.</b> A rabbit travels by hopping and a breeze by sliding, and
         * neither carries a walk-gated clip at all - so a walking render that left them at their
         * resting arm would swing the legs of a subject vanilla draws in another animation entirely.
         * Every other group answers the same member either way, because what a stride reaches for
         * those subjects is a walk-gated clip that already plays: a camel's dash is a sprint a rider
         * asks for and an armadillo's roll is fear, and selecting either under a stride would
         * animate something vanilla does not do when the animal merely walks.
         *
         * @param walking whether the render drives a stride
         * @return the group's default member at that gait
         */
        public @NotNull IdleState selected(boolean walking) {
            return switch (this) {
                case AXOLOTL -> IN_WATER;
                case DOLPHIN -> MOVING;
                case BAT -> FLYING;
                case IDLE_CLIP -> IDLING;
                case RABBIT -> walking ? HOPPING : TILTING;
                case ARMADILLO_SHELL -> UNBALLED;
                case CAMEL_STANCE -> STANDING;
                case BREEZE_WHIRL -> WHIRLING;
                case BREEZE_POSE -> walking ? SLIDING : GROUNDED;
                case CHEST_INTERACTION -> NOT_INTERACTING;
                case FROG_ACTION -> FROG_RESTING;
                case AXOLOTL_CLIP -> BABY_AXOLOTL_STILL;
                case ACTION_CLIP -> NOT_ACTING;
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
