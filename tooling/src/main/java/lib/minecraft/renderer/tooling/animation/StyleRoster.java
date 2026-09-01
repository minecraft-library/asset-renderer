package lib.minecraft.renderer.tooling.animation;

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
final class StyleRoster {

    /**
     * A swept render-state scalar the tick drives - a figure in the roster's sense.
     *
     * @param field the render-state field name a pose reads it under
     * @param rest what the figure holds at the ends of its travel
     * @param extent what the figure reaches at the middle of a sweep, or wraps at over a cycle
     * @param wave the wave token the drive is emitted with ({@code sweep} or {@code cycle})
     */
    record Figure(@NotNull String field, float rest, float extent, @NotNull String wave) {}

    /**
     * One selectable member of an exclusion group.
     *
     * @param name the member's roster name
     * @param field the render-state field the selection drives, or empty for a resting member
     */
    record Member(@NotNull String name, @NotNull String field) {

        /**
         * Whether selecting this member drives a field at all.
         *
         * @return {@code true} for a field-driving member
         */
        boolean drives() {
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
    record Group(
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
        @NotNull String token() {
            return StringUtil.toSnakeCase(this.name);
        }

        /**
         * Whether the resting selection differs by gait.
         *
         * @return {@code true} when the stride selection is another member
         */
        boolean forked() {
            return !this.idleSelected.equals(this.strideSelected);
        }

        /**
         * Whether a member is the resting selection at either gait, which is what ships no row of
         * its own - its output is the idle or stride row.
         *
         * @param member the member to ask about
         * @return {@code true} for a default selection
         */
        boolean isDefault(@NotNull Member member) {
            return member.name().equals(this.idleSelected) || member.name().equals(this.strideSelected);
        }

        /**
         * The member one gait selects.
         *
         * @param walking whether the subject strides
         * @return the selected member
         */
        @NotNull Member selected(boolean walking) {
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
        boolean readBy(@NotNull Set<String> reads) {
            return this.members.stream().anyMatch(member -> member.drives() && reads.contains(member.field()));
        }

    }

    /** The four swept figures, in roster order. */
    static final @NotNull List<Figure> FIGURES = List.of(
        new Figure("tentacleAngle", 0f, 0.7853982f, "sweep"),
        new Figure("flapTime", 0f, 1f, "cycle"),
        new Figure("peekAmount", 0f, 1f, "sweep"),
        new Figure("movingFactor", 0f, 1f, "sweep"));

    /** The thirteen exclusion groups, in roster order, each with its members in roster order. */
    static final @NotNull List<Group> GROUPS = List.of(
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
    static final @NotNull String NO_ROW_FIELD = "walkAnimationState";

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
    static @NotNull String styleId(@NotNull String field) {
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
    static @NotNull List<String> togglesOf(@NotNull String field) {
        return TOGGLES.getOrDefault(field, List.of());
    }

    /**
     * Every field a driven group member names.
     *
     * @return the driven fields, in roster order
     */
    static @NotNull Set<String> driven() {
        return DRIVEN_FIELDS;
    }

    /**
     * The figure one field sweeps, or empty for a field no figure drives.
     *
     * @param field the render-state field
     * @return the figure
     */
    static @NotNull Optional<Figure> figureOf(@NotNull String field) {
        return FIGURES.stream()
            .filter(figure -> figure.field().equals(field))
            .findFirst();
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
