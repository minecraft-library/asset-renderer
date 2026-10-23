package lib.minecraft.renderer.tooling.animation;

import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The curated idle roster the style emitter merges with what the walked poses read - which
 * render-state clocks vanilla's tick advances in plain IDLE or WALK and the wave each gait drives
 * them by, which counts a selection starts and the tick lowers, which one-hot states form an
 * exclusion group, which member of each group a resting
 * subject stands in at either gait, and the rule a state field's style id derives by.
 *
 * <p>The same declaration holds the two sets the fold reads - {@link #DRIVEN}, what the tick moves
 * and the fold therefore keeps symbolic, and {@link #DRIVEN_FIGURES}, the half of it a flag is
 * settled against - and {@link #DECLARED_RESTS}, what a figure rests at where no constructor
 * settles it.
 *
 * <p>These are behavioural facts of vanilla's own {@code tick} that no bytecode walk can read - a
 * bat rests flying, a tentacle strokes a quarter turn - so they are declared here, once, and the
 * emitter joins them to the fields each entity's poses and pass opacities actually read. Each
 * clock's numbers are vanilla's own, read where its javadoc names them, and a jar test holds every
 * one of them there.
 *
 * <p><b>Everything here is ordered data.</b> Clock, group and member order is emission order for the
 * per-entity rows, so the lists are {@link List#of} in declaration order and nothing is keyed
 * through a hash-ordered map.
 */
public final class StyleRoster {

    /**
     * One render-state field vanilla's tick advances in plain IDLE or WALK, and the wave each gait
     * drives it by.
     *
     * @param field the render-state field a pose or a pass opacity reads
     * @param idle the half the idle row carries, which every row composing over idle inherits
     * @param stride the half the stride row spells where it differs from the idle half, empty where
     *     the stride inherits it
     */
    public record Clock(@NotNull String field, @NotNull Motion idle, @NotNull Optional<Motion> stride) {}

    /**
     * One half of a clock - the wave a gait drives its field by, travelling from a rest of zero.
     *
     * @param wave the wave the drive travels, with whatever that wave reads beside its extent
     * @param extent the held level, the slope of a ramp, or the far end of a stroke or a trapezoid
     */
    public record Motion(@NotNull StyleDriver.Wave wave, float extent) {

        /**
         * Creates a level held at every tick.
         *
         * @param extent the level
         * @return the held half
         */
        public static @NotNull Motion hold(float extent) {
            return new Motion(StyleDriver.Wave.Plain.HOLD, extent);
        }

        /**
         * Creates a field advanced by a fixed step every tick.
         *
         * @param extent the step a tick
         * @return the ramped half
         */
        public static @NotNull Motion ramp(float extent) {
            return new Motion(StyleDriver.Wave.Plain.RAMP, extent);
        }

        /**
         * Creates vanilla's squid stroke - a flare from rest to the extent and back, then a hang at
         * rest for as long again.
         *
         * @param extent the far end of the flare
         * @param rate the radians of stroke phase a tick
         * @return the stroked half
         * @throws IllegalArgumentException if the rate is not a finite rate above zero
         */
        public static @NotNull Motion stroke(float extent, float rate) {
            return new Motion(new StyleDriver.Wave.Stroke(rate), extent);
        }

        /**
         * Creates a climb from rest to the extent, a hold there, a return and a hold at rest, each in
         * whole ticks.
         *
         * @param extent the level the climb reaches
         * @param rise the ticks the climb takes
         * @param dwell the ticks the extent is held
         * @param fall the ticks the return takes
         * @param gap the ticks the rest is held before the next climb
         * @return the trapezoid half
         * @throws IllegalArgumentException if a span is negative or the four span no tick
         */
        public static @NotNull Motion trapezoid(float extent, int rise, int dwell, int fall, int gap) {
            return new Motion(new StyleDriver.Wave.Trapezoid(rise, dwell, fall, gap), extent);
        }

    }

    /**
     * One render-state field vanilla's tick counts down from the moment an event sets it - a count a
     * selection starts, read as that start less the elapsed age.
     *
     * @param field the render-state field the body reads
     * @param start the count the event sets
     */
    public record Countdown(@NotNull String field, int start) {}

    /**
     * One selectable member of an exclusion group.
     *
     * @param name the member's roster name
     * @param field the render-state field the selection drives, or empty for a resting member
     */
    public record Member(@NotNull String name, @NotNull String field) {

        /**
         * Whether selecting this member drives a field at all.
         *
         * @return {@code true} for a field-driving member
         */
        public boolean drives() {
            return !this.field.isEmpty();
        }

    }

    /**
     * One exclusion group - at most one member is selected at a time, and an unselected member's
     * field answers zero.
     *
     * @param name the group's roster name, whose snake case is the emitted group token
     * @param members the members in roster order
     * @param idleSelected the name of the member a resting subject stands in
     * @param strideSelected the name of the member a walking subject stands in
     * @param age the age whose forms alone read every driven member, empty where either age's forms
     *     read the group
     */
    public record Group(
        @NotNull String name,
        @NotNull List<Member> members,
        @NotNull String idleSelected,
        @NotNull String strideSelected,
        @NotNull Optional<Age> age
    ) {

        /**
         * The token this group is spelled with in an emitted drive.
         *
         * @return the group's name in snake case
         */
        public @NotNull String token() {
            return StringUtil.toSnakeCase(this.name);
        }

        /**
         * Whether the resting selection differs by gait.
         *
         * @return {@code true} when the stride selection is another member
         */
        public boolean forked() {
            return !this.idleSelected.equals(this.strideSelected);
        }

        /**
         * Whether a member is the resting selection at either gait, which is what ships no row of
         * its own - its output is the idle or stride row.
         *
         * @param member the member to ask about
         * @return {@code true} for a default selection
         */
        public boolean isDefault(@NotNull Member member) {
            return member.name().equals(this.idleSelected) || member.name().equals(this.strideSelected);
        }

        /**
         * The member one gait selects.
         *
         * @param walking whether the subject strides
         * @return the selected member
         */
        public @NotNull Member selected(boolean walking) {
            String name = walking ? this.strideSelected : this.idleSelected;
            return this.members.stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow();
        }

        /**
         * Whether any driven member's field is among the given read set.
         *
         * @param reads the fields an entity's poses read
         * @return {@code true} when the group reaches the entity at all
         */
        public boolean readBy(@NotNull Set<String> reads) {
            return this.members.stream()
                .anyMatch(member -> member.drives() && reads.contains(member.field()));
        }

    }

    /**
     * The clocks vanilla's tick advances in plain IDLE or WALK, in roster order, each read where its
     * vanilla value is written:
     *
     * <ul>
     *   <li><b>{@code tailAnimation}</b> - {@code Guardian.aiStep} eases the tail speed to 0.125 a
     *       tick still in water and to 0.5 a tick moving, and adds it to the tail every tick.</li>
     *   <li><b>{@code spikesAnimation}</b> - the same method eases the spikes to 1 still and to 0
     *       moving.</li>
     *   <li><b>{@code flapTime}</b> - {@code EnderDragon.aiStep} adds 0.1 a tick while the phase
     *       sits, and a spawned dragon's phase is {@code HOVERING}, which sits.</li>
     *   <li><b>{@code movingFactor}</b> - the axolotl's moving animator settles at 0 still and 1
     *       swimming, the two ends of the {@code BinaryAnimator} {@code Axolotl}'s constructor
     *       builds.</li>
     *   <li><b>{@code tentacleAngle}</b> - {@code Squid.aiStep}'s stroke, a quarter turn at most, at
     *       the rate {@code Squid}'s constructor draws for entity id 9 -
     *       {@code 1 / (nextFloat() + 1) * 0.2f} after {@code setSeed(9)}, 9 being the id
     *       {@link #DECLARED_RESTS} rests every subject at. <b>The rate is chosen with the id</b>:
     *       each entity draws its own.</li>
     *   <li><b>{@code peekAmount}</b> - {@code ShulkerPeekGoal} raises the peek to 30 and lowers it
     *       20, 40 or 60 ticks after the raise, and {@code Shulker.updatePeekAmount} moves the lid
     *       0.05 a tick toward {@code 30 * 0.01f}, the extent: the shortest peek rises for 6 ticks,
     *       holds for 14 and falls for 6. <b>The shortest peek and the gap of 0 are chosen</b>:
     *       vanilla draws one of the three holds and waits a random interval between peeks, and this
     *       row loops the shortest with no wait.</li>
     *   <li><b>{@code heartAnimation}</b> - {@code Warden.tick} sets the heart to 10 every 40 ticks
     *       at no anger and counts it down a tick at a time, read as tenths. No pose reads it; the
     *       heart layer's opacity does.</li>
     * </ul>
     */
    public static final @NotNull List<Clock> CLOCKS = List.of(
        new Clock("tailAnimation", Motion.ramp(0.125f), Optional.of(Motion.ramp(0.5f))),
        new Clock("spikesAnimation", Motion.hold(1f), Optional.of(Motion.hold(0f))),
        new Clock("flapTime", Motion.ramp(0.1f), Optional.empty()),
        new Clock("movingFactor", Motion.hold(0f), Optional.of(Motion.hold(1f))),
        new Clock("tentacleAngle", Motion.stroke(0.7853982f, 0.11559614f), Optional.empty()),
        new Clock("peekAmount", Motion.trapezoid(0.29999998f, 6, 14, 6, 0), Optional.empty()),
        new Clock("heartAnimation", Motion.trapezoid(1f, 0, 0, 10, 30), Optional.empty()));

    /**
     * The counts vanilla's tick lowers from the moment a selection starts them, each read where its
     * vanilla value is written:
     *
     * <ul>
     *   <li><b>{@code offerFlowerTick}</b> - {@code IronGolem.handleEntityEvent} sets it to 400 on event
     *       11, the offered flower, and {@code IronGolem.aiStep} lowers it one a tick while it is above
     *       zero. The golem's body raises its right arm while it is.</li>
     * </ul>
     *
     * <p>A countdown joins neither {@link #DRIVEN} nor {@link #DRIVEN_FIGURES}: a body folded with its
     * selection answered reads the field as its start less the elapsed age, so the age clock every posed
     * row already drives carries it.
     */
    public static final @NotNull List<Countdown> COUNTDOWNS = List.of(
        new Countdown("offerFlowerTick", 400));

    /** The thirteen exclusion groups, in roster order, each with its members in roster order. */
    public static final @NotNull List<Group> GROUPS = List.of(
        new Group("AXOLOTL", List.of(
            new Member("PLAYING_DEAD", "playingDeadFactor"),
            new Member("IN_WATER", "inWaterFactor"),
            new Member("ON_GROUND", "onGroundFactor"),
            new Member("IN_AIR", "")),
            "IN_WATER", "IN_WATER", Optional.of(Age.ADULT)),
        new Group("DOLPHIN", List.of(
            new Member("MOVING", "isMoving"),
            new Member("STILL", "")),
            "STILL", "MOVING", Optional.empty()),
        new Group("BAT", List.of(
            new Member("FLYING", "flyAnimationState"),
            new Member("RESTING", "restAnimationState")),
            "FLYING", "FLYING", Optional.empty()),
        new Group("IDLE_CLIP", List.of(
            new Member("IDLING", "idleAnimationState"),
            new Member("NOT_IDLING", "")),
            "IDLING", "IDLING", Optional.empty()),
        new Group("RABBIT", List.of(
            new Member("TILTING", "idleHeadTiltAnimationState"),
            new Member("HOPPING", "hopAnimationState"),
            new Member("NOT_TILTING", "")),
            "TILTING", "HOPPING", Optional.empty()),
        new Group("ARMADILLO_SHELL", List.of(
            new Member("ROLLING_UP", "rollUpAnimationState"),
            new Member("ROLLING_OUT", "rollOutAnimationState"),
            new Member("PEEKING", "peekAnimationState"),
            new Member("UNBALLED", "")),
            "UNBALLED", "UNBALLED", Optional.empty()),
        new Group("CAMEL_STANCE", List.of(
            new Member("SITTING_DOWN", "sitAnimationState"),
            new Member("SITTING", "sitPoseAnimationState"),
            new Member("STANDING_UP", "sitUpAnimationState"),
            new Member("DASHING", "dashAnimationState"),
            new Member("STANDING", "")),
            "STANDING", "STANDING", Optional.empty()),
        new Group("BREEZE_WHIRL", List.of(
            new Member("WHIRLING", "idle")),
            "WHIRLING", "WHIRLING", Optional.empty()),
        new Group("BREEZE_POSE", List.of(
            new Member("SLIDING", "slide"),
            new Member("SLIDING_BACK", "slideBack"),
            new Member("INHALING", "inhale"),
            new Member("SHOOTING", "shoot"),
            new Member("LONG_JUMPING", "longJump"),
            new Member("GROUNDED", "")),
            "GROUNDED", "SLIDING", Optional.empty()),
        new Group("CHEST_INTERACTION", List.of(
            new Member("GETTING_ITEM", "interactionGetItem"),
            new Member("GETTING_NOTHING", "interactionGetNoItem"),
            new Member("DROPPING_ITEM", "interactionDropItem"),
            new Member("DROPPING_NOTHING", "interactionDropNoItem"),
            new Member("NOT_INTERACTING", "")),
            "NOT_INTERACTING", "NOT_INTERACTING", Optional.empty()),
        new Group("FROG_ACTION", List.of(
            new Member("JUMPING", "jumpAnimationState"),
            new Member("TONGUING", "tongueAnimationState"),
            new Member("SWIM_IDLING", "swimIdleAnimationState"),
            new Member("CROAKING", "croakAnimationState"),
            new Member("FROG_RESTING", "")),
            "FROG_RESTING", "FROG_RESTING", Optional.empty()),
        new Group("AXOLOTL_CLIP", List.of(
            new Member("BABY_SWIMMING", "swimAnimation"),
            new Member("BABY_IDLING_UNDERWATER_ON_GROUND", "idleUnderWaterOnGroundAnimationState"),
            new Member("BABY_IDLING_UNDERWATER", "idleUnderWaterAnimationState"),
            new Member("BABY_IDLING_ON_GROUND", "idleOnGroundAnimationState"),
            new Member("BABY_PLAYING_DEAD", "playDeadAnimationState"),
            new Member("BABY_AXOLOTL_STILL", "")),
            "BABY_IDLING_UNDERWATER", "BABY_SWIMMING", Optional.of(Age.BABY)),
        new Group("ACTION_CLIP", List.of(
            new Member("ATTACKING", "attackAnimationState"),
            new Member("DIGGING", "diggingAnimationState"),
            new Member("ROARING", "roarAnimationState"),
            new Member("WARDEN_SNIFFING", "sniffAnimationState"),
            new Member("EMERGING", "emergeAnimationState"),
            new Member("SONIC_BOOMING", "sonicBoomAnimationState"),
            new Member("INVULNERABLE", "invulnerabilityAnimationState"),
            new Member("DYING", "deathAnimationState"),
            new Member("SNIFFER_SNIFFING", "sniffingAnimationState"),
            new Member("RISING", "risingAnimationState"),
            new Member("FEELING_HAPPY", "feelingHappyAnimationState"),
            new Member("SCENTING", "scentingAnimationState"),
            new Member("NOT_ACTING", "")),
            "NOT_ACTING", "NOT_ACTING", Optional.empty()));

    /**
     * The one select-gated field no roster member drives - a baby axolotl's walk clip reads it, the
     * fold keeps the site, and no style row ever answers it non-zero. It ships no row and is named
     * in the emit diagnostics rather than silently absent.
     */
    public static final @NotNull String NO_ROW_FIELD = "walkAnimationState";

    /**
     * The render-state figures the tick drives, which stay symbolic through the fold.
     *
     * <p>Elapsed age is what makes one frame differ from its neighbour at all; the stride pair is
     * what a gait adds, and vanilla steps the phase BY the amplitude once a tick rather than deriving
     * it from the clock, so the two are one schedule and a caller naming one without the other has
     * described no gait. Everything else an offline subject answers at rest.
     *
     * <p>The rest of this set is what vanilla's own {@code tick} would have filled and a never-ticked
     * subject leaves at zero - a tentacle's angle, a wing phase, a lid, a guardian's tail and spikes,
     * a warden's heart, an axolotl's four mixing factors, whether a dolphin is under way. Folding one
     * to its resting zero holds still something vanilla animates, so they stay symbolic too: each of
     * {@link #CLOCKS} is driven at the rate vanilla's tick advances it, and each selection at the
     * member a gait stands in. <b>None of them needs the entity ticked</b>: what a draw decides on
     * these paths is a rate or an interval, and every draw but the squid's sits behind a gate an
     * offline subject never passes.
     *
     * <p><b>A field only a pass's opacity reads belongs here as one a pose reads does.</b> The
     * warden's heart is read by its layer's alpha alone, and the alpha is folded against this set
     * like a pose, so leaving the heart off would fold its pass to the never-ticked dark.
     *
     * <p><b>A boolean belongs on this list exactly as a float does.</b> A render-state field the
     * walk keeps symbolic arrives as a number wherever the body reads it, and a body that branches
     * on one leaves a select comparing that number against zero - so a dolphin's {@code isMoving}
     * needs no widening of the fold and no second kind of channel. What folds to a literal at
     * generation is a FLAG, which is a bone's visibility and a different thing entirely.
     *
     * <p><b>The animation states are here because a body may ask whether one is running, not because
     * a clip's gate reads them.</b> A play site names the state it sits behind outright, so the gate
     * needs nothing of this set. What does is the three models that branch on {@code isStarted} -
     * a rabbit assigns its head from the look angles only while the head tilt is NOT playing, so a
     * table that folded the question would turn a head the clip is already turning. Only the states
     * a caller can select are named: one nobody can start folds to a subject nothing has ticked,
     * which is what it is.
     *
     * <p><b>One state a caller CAN select is still left off, and one of those three models is
     * why.</b> {@code BabyAxolotlModel} reads {@code walkAnimationState.isStarted()} to gate a
     * WALK-driven play site, so driving that state puts back a site the fold settles and drops. It
     * is named on the asset side's own roster where the group that would have held it is declared,
     * so the two sides state one omission rather than disagreeing silently.
     *
     * <p>{@code FrogModel}'s croak reads the same way and IS here, because what its state gates is a
     * FLAG and {@link #DRIVEN_FIGURES} is the line that lets one through: the fold settles a flag
     * against the figures alone, so a bone gated on a selection resolves to the arm a resting
     * subject stands in and the mesh's own toggle carries the choice.
     *
     * <p>A slime's squash is the one figure of this shape deliberately left off. Both its renderer
     * and a magma cube's read it in the per-renderer {@code scale} this side models nowhere, so
     * driving it would move a reference in two places and a render in one.
     */
    public static final @NotNull Set<String> DRIVEN = Set.of(
        "ageInTicks", "walkAnimationPos", "walkAnimationSpeed",
        "tentacleAngle", "flapTime", "peekAmount",
        "tailAnimation", "spikesAnimation", "heartAnimation",
        "inWaterFactor", "movingFactor", "onGroundFactor", "playingDeadFactor", "isMoving",
        "flyAnimationState", "restAnimationState", "idleAnimationState",
        "idleHeadTiltAnimationState", "hopAnimationState", "croakAnimationState",
        "rollUpAnimationState", "rollOutAnimationState", "peekAnimationState",
        "sitAnimationState", "sitPoseAnimationState", "sitUpAnimationState", "dashAnimationState",
        "idle", "slide", "slideBack", "inhale", "shoot", "longJump",
        "interactionGetItem", "interactionGetNoItem", "interactionDropItem",
        "interactionDropNoItem",
        "jumpAnimationState", "tongueAnimationState", "swimIdleAnimationState",
        "swimAnimation", "idleUnderWaterOnGroundAnimationState", "idleUnderWaterAnimationState",
        "idleOnGroundAnimationState", "playDeadAnimationState",
        "attackAnimationState", "diggingAnimationState", "roarAnimationState",
        "sniffAnimationState", "emergeAnimationState", "sonicBoomAnimationState",
        "invulnerabilityAnimationState", "deathAnimationState", "sniffingAnimationState",
        "risingAnimationState", "feelingHappyAnimationState", "scentingAnimationState");

    /**
     * The half of {@link #DRIVEN} that is a FIGURE rather than a one-hot state, which is the free set
     * a FLAG is folded against.
     *
     * <p><b>The distinction is what a bone's visibility could be carried BY.</b> A flag gated on a
     * state is a bone a selection draws - the mesh keeps it, resting at the arm a never-ticked subject
     * stands in and naming the toggle that flips it - so the fold settles the state and the toggle
     * carries the choice. A flag gated on a FIGURE is a bone that blinks with the clock, and no
     * toggle can say that, so it stays symbolic here and {@code PoseFlow.restingUndrawn} refuses it.
     * Keeping both halves symbolic refused a frog whose croaking body is exactly the first case.
     *
     * <p>The membership is the split the emitted style rows are derived from: the three fields the
     * universal rows drive, plus every field of {@link #CLOCKS}. Everything else in {@code DRIVEN}
     * is a one-hot state, and {@code StyleFlowEmitTest} holds the set to the roster, so a clock
     * added to one and not the other fails there rather than folding a flag it reads at rest.
     */
    public static final @NotNull Set<String> DRIVEN_FIGURES = Set.of(
        "ageInTicks", "walkAnimationPos", "walkAnimationSpeed",
        "tailAnimation", "spikesAnimation", "flapTime", "movingFactor", "tentacleAngle", "peekAmount",
        "heartAnimation");

    /**
     * What a render-state figure rests at where no constructor settles it and a zero would be wrong.
     *
     * <p><b>Every figure this table does not name rests at zero, and for a boolean that is usually
     * right</b> - a fresh subject is not swimming, not searching, not attacking. It is wrong exactly
     * where vanilla's own {@code defineSynchedData} declares the accessor's backing value as
     * something else, and then the zero is not a resting value but a value nobody read.
     *
     * <p>{@code InputDefaultResolver} cannot reach these. It reads what a render state's own
     * CONSTRUCTOR settles, and a field like this one is not constructed at all - it is assigned in
     * {@code extractRenderState} from an accessor whose body is
     * {@code entityData.get(<static accessor>)}, so the value lives in a builder call in a different
     * class and travels through a token the walk has no term for.
     *
     * <p>Declared with its provenance rather than fitted, one entry per line:
     *
     * <ul>
     *   <li><b>{@code canMove}</b> - {@code Creaking.defineSynchedData} calls
     *       {@code builder.define(CAN_MOVE, true)}, and {@code Creaking.canMove()} returns that get
     *       unconditionally. Its model plays the walk clip only under it, so a resting zero drops
     *       the clip vanilla is playing. A read fact, reached through a token no walk here has a
     *       term for.</li>
     *   <li><b>{@code entityId}</b> - a CHOSEN value rather than a read one, and the only entry
     *       here that is. {@code WitchModel} bobs its nose at {@code 0.01 * (entityId % 10)}, and an
     *       id is a counter over every entity the client has built - so it is deterministic per
     *       subject only by accident, and the harness has always pinned it. Pinned at zero it is the
     *       one frequency in ten at which the bob is a constant, which drew a nose that never moves
     *       on both sides and agreed about it. <b>Nine because the excursion is the point</b>: the
     *       frequency is a multiplier, so the highest of the ten shows the most of the cycle inside
     *       one strip and every lower one is a fraction of the same curve. The squid's stroke rate in
     *       {@link #CLOCKS} is the draw this same id seeds, so the one choice sets both. It is a
     *       caller's coinage, legitimate on the same terms as an idle excursion: the harness answers
     *       the identical number, and
     *       {@code StyleCatalogMirrorTest} holds the two together.</li>
     * </ul>
     *
     * <p><b>Ordered, and a {@code Map.of} here was a table that flapped per JVM launch.</b> Every
     * entry seeds the fold's defaults through one {@code putIfAbsent} apiece into an
     * insertion-ordered map, and {@code Map.of} salts its iteration per launch from two entries up -
     * so the order is kept determinate rather than left to whatever a launch hashes it to, and
     * nothing the fold or its diagnostics derive can inherit a per-launch ordering, which is the
     * shape that once cost a parity capture a mover nothing had changed.
     */
    public static final @NotNull Map<String, Float> DECLARED_RESTS = declaredRests();

    /** The appearance bone toggles a selection entails, by the field the selection drives. */
    private static final @NotNull Map<String, List<String>> TOGGLES =
        Map.of("croakAnimationState", List.of("croak"));

    /**
     * The style-id spellings that override the derivation rule, by field. The adult playing-dead
     * factor spells the id its baby clip twin derives, so the axolotl's age-split pair shares one
     * name; the two fields deriving the reserved id {@code idle} stay uncovered, both being
     * default selections that ship no row.
     */
    private static final @NotNull Map<String, String> ID_OVERRIDES =
        Map.of("playingDeadFactor", "play_dead");

    /** Every field a driven group member names, in roster order, cached once. */
    private static final @NotNull Set<String> DRIVEN_FIELDS = drivenFields();

    private StyleRoster() {}

    /**
     * The style id one driven field derives - the override table first, then the {@code
     * AnimationState} suffix stripped, else a trailing {@code Animation}, else a trailing {@code
     * Factor}, and the remainder in snake case.
     *
     * @param field the render-state field the selection drives
     * @return the derived style id
     */
    public static @NotNull String styleId(@NotNull String field) {
        String override = ID_OVERRIDES.get(field);
        if (override != null) return override;
        String stem = field.endsWith("AnimationState")
            ? field.substring(0, field.length() - "AnimationState".length())
            : field.endsWith("Animation")
                ? field.substring(0, field.length() - "Animation".length())
                : field.endsWith("Factor")
                    ? field.substring(0, field.length() - "Factor".length())
                    : field;
        return StringUtil.toSnakeCase(stem);
    }

    /**
     * The bone toggles selecting a field entails.
     *
     * @param field the render-state field the selection drives
     * @return the toggles, empty for every field but the croak
     */
    public static @NotNull List<String> togglesOf(@NotNull String field) {
        return TOGGLES.getOrDefault(field, List.of());
    }

    /**
     * Every field a driven group member names.
     *
     * @return the driven fields, in roster order
     */
    public static @NotNull Set<String> driven() {
        return DRIVEN_FIELDS;
    }

    /** The declared rests in the order this class documents them, which is the order they ship in. */
    private static @NotNull Map<String, Float> declaredRests() {
        Map<String, Float> rests = new LinkedHashMap<>();
        rests.put("canMove", 1f);
        rests.put("entityId", 9f);
        return Collections.unmodifiableMap(rests);
    }

    /** The driven-field set in roster order, built once at load. */
    private static @NotNull Set<String> drivenFields() {
        Set<String> out = new LinkedHashSet<>();
        for (Group group : GROUPS)
            for (Member member : group.members())
                if (member.drives()) out.add(member.field());
        Map<String, String> owners = new LinkedHashMap<>();
        for (Group group : GROUPS)
            for (Member member : group.members())
                if (member.drives() && owners.putIfAbsent(member.field(), group.name()) != null)
                    throw new IllegalStateException(
                        "Field '" + member.field() + "' is driven by two groups");
        return Collections.unmodifiableSet(out);
    }

}
