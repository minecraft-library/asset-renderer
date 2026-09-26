package lib.minecraft.renderer.tooling.policy;

import dev.simplified.util.StringUtil;
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
 * render-state figures vanilla's tick sweeps and over what range, which one-hot states form an
 * exclusion group, which member of each group a resting subject stands in at either gait, and the
 * rule a state field's style id derives by.
 *
 * <p>The same declaration holds the two sets the fold reads - {@link #DRIVEN}, what the tick moves
 * and the fold therefore keeps symbolic, and {@link #DRIVEN_FIGURES}, the half of it a flag is
 * settled against - and {@link #DECLARED_RESTS}, what a figure rests at where no constructor
 * settles it.
 *
 * <p>These are behavioural facts of vanilla's own {@code tick} that no bytecode walk can read - a
 * bat rests flying, a tentacle sweeps a quarter turn - so they are declared here, once, and the
 * emitter joins them to the fields each entity's poses actually read. The float literals are the
 * excursion arithmetic's own and are held character-identical with the harness's deliberate copy,
 * which a mirror test pins against the shipped file.
 *
 * <p><b>Everything here is ordered data.</b> Group and member order is emission order for the
 * per-entity select rows, so the lists are {@link List#of} in declaration order and nothing is
 * keyed through a hash-ordered map.
 */
public final class StyleRoster {

    /**
     * A swept render-state scalar the tick drives - a figure in the roster's sense.
     *
     * @param field the render-state field name a pose reads it under
     * @param rest what the figure holds at the ends of its travel
     * @param extent what the figure reaches at the middle of a sweep, or wraps at over a cycle
     * @param wave the wave token the drive is emitted with ({@code sweep} or {@code cycle})
     */
    public record Figure(@NotNull String field, float rest, float extent, @NotNull String wave) {}

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
     * @param age the age token whose forms alone read every driven member - {@code baby} or
     *     {@code adult} - empty where either age's forms read the group
     */
    public record Group(
        @NotNull String name,
        @NotNull List<Member> members,
        @NotNull String idleSelected,
        @NotNull String strideSelected,
        @NotNull String age
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

    /** The four swept figures, in roster order. */
    public static final @NotNull List<Figure> FIGURES = List.of(
        new Figure("tentacleAngle", 0f, 0.7853982f, "sweep"),
        new Figure("flapTime", 0f, 1f, "cycle"),
        new Figure("peekAmount", 0f, 1f, "sweep"),
        new Figure("movingFactor", 0f, 1f, "sweep"));

    /** The thirteen exclusion groups, in roster order, each with its members in roster order. */
    public static final @NotNull List<Group> GROUPS = List.of(
        new Group("AXOLOTL", List.of(
            new Member("PLAYING_DEAD", "playingDeadFactor"),
            new Member("IN_WATER", "inWaterFactor"),
            new Member("ON_GROUND", "onGroundFactor"),
            new Member("IN_AIR", "")),
            "IN_WATER", "IN_WATER", "adult"),
        new Group("DOLPHIN", List.of(
            new Member("MOVING", "isMoving"),
            new Member("STILL", "")),
            "MOVING", "MOVING", ""),
        new Group("BAT", List.of(
            new Member("FLYING", "flyAnimationState"),
            new Member("RESTING", "restAnimationState")),
            "FLYING", "FLYING", ""),
        new Group("IDLE_CLIP", List.of(
            new Member("IDLING", "idleAnimationState"),
            new Member("NOT_IDLING", "")),
            "IDLING", "IDLING", ""),
        new Group("RABBIT", List.of(
            new Member("TILTING", "idleHeadTiltAnimationState"),
            new Member("HOPPING", "hopAnimationState"),
            new Member("NOT_TILTING", "")),
            "TILTING", "HOPPING", ""),
        new Group("ARMADILLO_SHELL", List.of(
            new Member("ROLLING_UP", "rollUpAnimationState"),
            new Member("ROLLING_OUT", "rollOutAnimationState"),
            new Member("PEEKING", "peekAnimationState"),
            new Member("UNBALLED", "")),
            "UNBALLED", "UNBALLED", ""),
        new Group("CAMEL_STANCE", List.of(
            new Member("SITTING_DOWN", "sitAnimationState"),
            new Member("SITTING", "sitPoseAnimationState"),
            new Member("STANDING_UP", "sitUpAnimationState"),
            new Member("DASHING", "dashAnimationState"),
            new Member("STANDING", "")),
            "STANDING", "STANDING", ""),
        new Group("BREEZE_WHIRL", List.of(
            new Member("WHIRLING", "idle")),
            "WHIRLING", "WHIRLING", ""),
        new Group("BREEZE_POSE", List.of(
            new Member("SLIDING", "slide"),
            new Member("SLIDING_BACK", "slideBack"),
            new Member("INHALING", "inhale"),
            new Member("SHOOTING", "shoot"),
            new Member("LONG_JUMPING", "longJump"),
            new Member("GROUNDED", "")),
            "GROUNDED", "SLIDING", ""),
        new Group("CHEST_INTERACTION", List.of(
            new Member("GETTING_ITEM", "interactionGetItem"),
            new Member("GETTING_NOTHING", "interactionGetNoItem"),
            new Member("DROPPING_ITEM", "interactionDropItem"),
            new Member("DROPPING_NOTHING", "interactionDropNoItem"),
            new Member("NOT_INTERACTING", "")),
            "NOT_INTERACTING", "NOT_INTERACTING", ""),
        new Group("FROG_ACTION", List.of(
            new Member("JUMPING", "jumpAnimationState"),
            new Member("TONGUING", "tongueAnimationState"),
            new Member("SWIM_IDLING", "swimIdleAnimationState"),
            new Member("CROAKING", "croakAnimationState"),
            new Member("FROG_RESTING", "")),
            "FROG_RESTING", "FROG_RESTING", ""),
        new Group("AXOLOTL_CLIP", List.of(
            new Member("BABY_SWIMMING", "swimAnimation"),
            new Member("BABY_IDLING_UNDERWATER_ON_GROUND", "idleUnderWaterOnGroundAnimationState"),
            new Member("BABY_IDLING_UNDERWATER", "idleUnderWaterAnimationState"),
            new Member("BABY_IDLING_ON_GROUND", "idleOnGroundAnimationState"),
            new Member("BABY_PLAYING_DEAD", "playDeadAnimationState"),
            new Member("BABY_AXOLOTL_STILL", "")),
            "BABY_AXOLOTL_STILL", "BABY_AXOLOTL_STILL", "baby"),
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
            "NOT_ACTING", "NOT_ACTING", ""));

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
     * subject leaves at zero - a tentacle's angle, a wing phase, a lid, an axolotl's four mixing
     * factors, whether a dolphin is under way. Folding one to its resting zero holds still something
     * vanilla animates, so they stay symbolic too and a caller drives each over the range vanilla's
     * arithmetic bounds it to. <b>None of them needs the entity ticked</b>: what a draw decides on
     * these paths is a rate or an interval, and every draw but the squid's sits behind a gate an
     * offline subject never passes.
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
     * universal rows drive, plus every swept render-state scalar. Everything else in {@code DRIVEN}
     * is a one-hot state, and the shipped catalog those rows land in is held to the harness
     * contract by {@code StyleCatalogMirrorTest} - so a figure filed on the wrong side ships as a
     * held selection rather than a wave, and the mirror reports it.
     */
    public static final @NotNull Set<String> DRIVEN_FIGURES = Set.of(
        "ageInTicks", "walkAnimationPos", "walkAnimationSpeed",
        "tentacleAngle", "flapTime", "peekAmount", "movingFactor");

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
     *       one strip and every lower one is a fraction of the same curve - the same argument the
     *       stride amplitude rests on. It is a caller's coinage, legitimate on the same terms as an
     *       idle excursion: the harness answers the identical number, and
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

    /**
     * The figure one field sweeps, or empty for a field no figure drives.
     *
     * @param field the render-state field
     * @return the figure
     */
    public static @NotNull Optional<Figure> figureOf(@NotNull String field) {
        return FIGURES.stream()
            .filter(figure -> figure.field().equals(field))
            .findFirst();
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
