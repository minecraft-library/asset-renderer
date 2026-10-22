package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * What a subject's ambling and fastest walks are read from where the generic reads cannot decide
 * them. Never fetches ({@code PolicyPurityTest}).
 *
 * <p>The stroll rows answer the brain mobs. A brain builds its idle activity in a sibling class
 * through a provider nothing in the entity names, and which branch of it is the wander is a reading
 * of vanilla's behaviour rather than a fact of the bytecode - so each row names the member holding
 * the brain's idle stroll and the speed is still read there. The villager's stroll travels as a
 * parameter of the package that strolls, so its row declares the number.
 *
 * <p>The value rows hold what no reader can see: the medium a subject whose control does not defer
 * wanders in, a subject that wanders by another's pace, the mean of a speed rolled at spawn, and the
 * settings the fastest walk takes that live in a parameter, a modifier a behaviour switches on, a base
 * the tick rewrites, a medium, a velocity or an age gate rather than in a goal's constructor. Each map
 * is looked up by id and never iterated, so its order reaches no byte and no log line.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
enum EntityPacePolicies implements NavigationPolicy {

    /** The allay's idle flight, the one stroll its idle activity makes. */
    ALLAY_IDLE_STROLL(
        List.of("minecraft:allay"),
        new Navigation.At("net/minecraft/world/entity/animal/allay/AllayAi", "initIdleActivity", null),
        "the IDLE RunOne flies by RandomStroll.fly; its other movers walk to an item or a player the allay is"
            + " handed, which no idle allay has"),

    /** The armadillo's idle stroll. */
    ARMADILLO_IDLE_STROLL(
        List.of("minecraft:armadillo"),
        new Navigation.At("net/minecraft/world/entity/animal/armadillo/ArmadilloAi", "initIdleActivity", null),
        "the IDLE RunOne strolls by RandomStroll.stroll; the tempt and the baby's follow need a player or an"
            + " adult to walk to"),

    /** The camel's idle stroll, which the camel husk's unridden wander is too - it declares no brain of its own. */
    CAMEL_IDLE_STROLL(
        List.of("minecraft:camel", "minecraft:camel_husk"),
        new Navigation.At("net/minecraft/world/entity/animal/camel/CamelAi", "initIdleActivity", null),
        "the IDLE RunOne strolls and walks to a look target at the same speed, both gated on not refusing to"
            + " move; RandomSitting and DoNothing stand it still. CamelHusk extends Camel and declares no AI, so"
            + " its unridden wander is the camel's; a ridden husk walks by its rider and is not read"),

    /** The copper golem's idle stroll. */
    COPPER_GOLEM_IDLE_STROLL(
        List.of("minecraft:copper_golem"),
        new Navigation.At("net/minecraft/world/entity/animal/golem/CopperGolemAi", "initIdleActivity", null),
        "the IDLE activity strolls by RandomStroll.stroll beside carrying items between containers, which needs"
            + " a container to carry from"),

    /** The creaking's idle stroll. */
    CREAKING_IDLE_STROLL(
        List.of("minecraft:creaking"),
        new Navigation.At("net/minecraft/world/entity/monster/creaking/CreakingAi", "initIdleActivity", null),
        "the IDLE RunOne strolls by RandomStroll.stroll; the creaking moves at all only while no player looks at it"),

    /** The frog's idle walk on land; its swim is another activity's. */
    FROG_IDLE_STROLL(
        List.of("minecraft:frog"),
        new Navigation.At("net/minecraft/world/entity/animal/frog/FrogAi", "initIdleActivity", null),
        "the land IDLE activity strolls by RandomStroll.stroll; the swim activity's RandomStroll.swim sits in"
            + " initSwimActivity, the medium a frog walking on land is not in"),

    /** The goat's idle stroll. */
    GOAT_IDLE_STROLL(
        List.of("minecraft:goat"),
        new Navigation.At("net/minecraft/world/entity/animal/goat/GoatAi", "initIdleActivity", null),
        "the IDLE RunOne strolls by RandomStroll.stroll; the tempt, the baby's follow and the ram each need a"
            + " target to walk to"),

    /** The hoglin's idle stroll, built in a helper its idle and avoid activities share. */
    HOGLIN_IDLE_STROLL(
        List.of("minecraft:hoglin"),
        new Navigation.At("net/minecraft/world/entity/monster/hoglin/HoglinAi", "createIdleMovementBehaviors", null),
        "createIdleMovementBehaviors serves the IDLE and AVOID activities alike and strolls by"
            + " RandomStroll.stroll; initIdleActivity only names it"),

    /** The nautilus's idle swim. */
    NAUTILUS_IDLE_STROLL(
        List.of("minecraft:nautilus"),
        new Navigation.At("net/minecraft/world/entity/animal/nautilus/NautilusAi", "initIdleActivity", null),
        "the IDLE RunOne swims by RandomStroll.swim; the nautilus cannot move out of water"),

    /** The zombie nautilus's idle swim. */
    ZOMBIE_NAUTILUS_IDLE_STROLL(
        List.of("minecraft:zombie_nautilus"),
        new Navigation.At("net/minecraft/world/entity/animal/nautilus/ZombieNautilusAi", "initIdleActivity", null),
        "the IDLE RunOne swims by RandomStroll.swim, as the nautilus's does"),

    /** The piglin's idle stroll, built in its idle movement helper. */
    PIGLIN_IDLE_STROLL(
        List.of("minecraft:piglin"),
        new Navigation.At("net/minecraft/world/entity/monster/piglin/PiglinAi", "createIdleMovementBehaviors", null),
        "createIdleMovementBehaviors strolls by RandomStroll.stroll; the celebrate activity's stroll sits in"
            + " initCelebrateActivity, which needs a won fight"),

    /** The piglin brute's idle stroll, built in its idle movement helper. */
    PIGLIN_BRUTE_IDLE_STROLL(
        List.of("minecraft:piglin_brute"),
        new Navigation.At("net/minecraft/world/entity/monster/piglin/PiglinBruteAi", "createIdleMovementBehaviors", null),
        "createIdleMovementBehaviors strolls by RandomStroll.stroll"),

    /** The sniffer's idle stroll. */
    SNIFFER_IDLE_STROLL(
        List.of("minecraft:sniffer"),
        new Navigation.At("net/minecraft/world/entity/animal/sniffer/SnifferAi", "initIdleActivity", null),
        "the IDLE RunOne strolls by RandomStroll.stroll; sniffing and digging stand it at a spot first"),

    /** The warden's idle stroll. */
    WARDEN_IDLE_STROLL(
        List.of("minecraft:warden"),
        new Navigation.At("net/minecraft/world/entity/monster/warden/WardenAi", "initIdleActivity", null),
        "the IDLE RunOne strolls by RandomStroll.stroll; investigating and fighting need a disturbance"),

    /** The zoglin's idle stroll, built in the entity itself. */
    ZOGLIN_IDLE_STROLL(
        List.of("minecraft:zoglin"),
        new Navigation.At("net/minecraft/world/entity/monster/Zoglin", "initIdleActivity", null),
        "the zoglin builds its own brain; the IDLE RunOne strolls by RandomStroll.stroll"),

    /**
     * The villager's idle stroll. Its idle package strolls at the package's own parameter, and the
     * float travels in from the brain provider, which no single-member read follows.
     */
    VILLAGER_IDLE_STROLL(
        List.of("minecraft:villager"),
        0.5f,
        "Villager.lambda$static$0 hands VillagerGoalPackages.getIdlePackage 0.5f, which strolls by"
            + " VillageBoundRandomStroll.create at its own parameter; the baby's PLAY package is handed the same"
            + " 0.5f"),

    /**
     * The medium a subject wanders in where its installed control does not defer to the base one - a
     * control that leaves the medium to the subject's situation.
     */
    PACE_MEDIA(
        List.of(),
        Map.of(
            "minecraft:allay", PaceChain.FLYING,
            "minecraft:nautilus", PaceChain.SMOOTH_SWIM,
            "minecraft:zombie_nautilus", PaceChain.SMOOTH_SWIM,
            "minecraft:frog", PaceChain.SMOOTH_ON_LAND,
            "minecraft:parrot", PaceChain.GROUND,
            "minecraft:turtle", PaceChain.TURTLE_SWIM_FAR),
        "the allay flies its idle and travels by travelFlying at its speed; the nautilus family cannot move out"
            + " of water, its outside-water share being 0; the frog walks on land, playing the walk clip its stride"
            + " already plays; the parrot walks the ground on its movement speed, which FlyingMoveControl uses while"
            + " it is on the ground; the turtle swims more than sixteen blocks from home, where its control halves"
            + " its speed to a floor"),

    /** A subject that wanders by another's pace, its own inputs read off that subject. */
    BORROWED_PACES(
        List.of(),
        Map.of("minecraft:giant", "minecraft:zombie"),
        "the giant registers no goal and runs no brain, so vanilla never wanders it; its mesh is a scaled zombie"
            + " and its stride walks at the zombie's pace, read off the zombie's own supplier, stroll and feed"),

    /** The mean of a movement speed a subject rolls at spawn - its generator at a draw of one half. */
    SPAWN_ROLL_MEANS(
        List.of(),
        Map.of(
            "minecraft:horse", 0.22499999701976778,
            "minecraft:zombie_horse", 0.24905123429796497),
        "Horse.randomizeAttributes rolls AbstractHorse.generateSpeed, (0.44999998807907104 + 0.3 r1 + 0.3 r2"
            + " + 0.3 r3) x 0.25; ZombieHorse.randomizeAttributes rolls generateZombieHorseSpeed, (9.0 + r1 + r2"
            + " + r3) / 42.15999984741211. Each is affine in three uniform draws, so its mean is its value at a"
            + " draw of 0.5"),

    /**
     * The villager's fastest walk setting. Its panic package scales the speed it is handed, and the
     * speed travels in from the brain provider as the idle stroll's does.
     */
    VILLAGER_FASTEST(
        List.of(),
        Map.of("minecraft:villager", 0.75f),
        "Villager.lambda$static$0 hands VillagerGoalPackages.getPanicPackage 0.5f, which flees at that times"
            + " 1.5f; one provider builds every package and its panic trigger reads no age, so both ages panic"
            + " alike"),

    /**
     * The movement modifier a subject adds to itself while it attacks, added to the adult's movement
     * speed. A baby zombified piglin skips it, and the enderman offers no baby.
     */
    ATTACK_SPEED_MODIFIERS(
        List.of(),
        Map.of(
            "minecraft:enderman", 0.15000000596046448,
            "minecraft:zombified_piglin", 0.05),
        "EnderMan.setTarget adds SPEED_MODIFIER_ATTACKING to its movement speed while it has a target;"
            + " ZombifiedPiglin.customServerAiStep adds its own while angry and skips a baby; both ADD_VALUE. No read"
            + " of the supplier sees a modifier a behaviour adds"),

    /** The movement speed base a subject's tick eases toward while it has a target. */
    RAVAGER_TARGET_SPEED(
        List.of(),
        Map.of("minecraft:ravager", 0.35),
        "Ravager.aiStep sets its movement speed base each tick, eased by a tenth toward 0.35 with a target and"
            + " 0.3 without; no supplier or modifier holds the targeted base"),

    /**
     * The medium a subject's fastest walk is taken in where it differs from the one it wanders in. The
     * baby turtle lands on its floor in either, so the row answers its baby what the wander's medium
     * would.
     */
    CEILING_MEDIA(
        List.of(),
        Map.of("minecraft:turtle", PaceChain.TURTLE_SWIM_NEAR),
        "Turtle$TurtleMoveControl.updateSpeed halves the speed only when the turtle is not within sixteen blocks"
            + " of home; the turtle wanders far from home and runs its fastest near it, where the speed is not"
            + " halved"),

    /**
     * The velocity a subject's charge sets every tick, which is no walk target and outruns its walks.
     * Only an adult takes a target to charge.
     */
    CHARGE_VELOCITY(
        List.of(),
        Map.of(
            "minecraft:nautilus", 0.6f,
            "minecraft:zombie_nautilus", 0.5f),
        "NautilusAi.initFightActivity and ZombieNautilusAi.initFightActivity build a ChargeAttack, whose start"
            + " stores the direction to the target times its speed and whose tick sets that as the motion; a"
            + " baby nautilus never takes an attack target"),

    /**
     * The goals the fastest-walk census drops at an age, by the goal's internal name - a goal whose
     * age gate lives outside it, or whose move is in a medium the subject's fastest is not taken in.
     */
    CEILING_GOAL_EXCLUSIONS(
        List.of(),
        Map.of(
            "net/minecraft/world/entity/ai/goal/RunAroundLikeCrazyGoal", Set.of("baby"),
            "net/minecraft/world/entity/animal/turtle/Turtle$TurtleGoToWaterGoal", Set.of("adult", "baby")),
        "RunAroundLikeCrazyGoal needs a rider aboard, which a baby never carries; Turtle$TurtleGoToWaterGoal moves"
            + " the turtle on land, where its control floors the speed, outside the water its fastest is taken"
            + " in");

    /** The subjects a stroll row answers for; empty on a value row keyed by subject. */
    private final @NotNull List<String> strollers;
    private final @NotNull Object value;
    private final @NotNull String provenance;

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        if (this.value instanceof Navigation coordinate) return coordinate;
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * Whether this row answers a subject's idle stroll.
     *
     * @param entityId the subject's id
     * @return whether the row names it
     */
    boolean strollsFor(@NotNull String entityId) {
        return this.strollers.contains(entityId);
    }

    /**
     * The stroll speed a value row declares ({@link #VILLAGER_IDLE_STROLL}).
     *
     * @return the declared float
     * @throws ToolingException if the row declares a coordinate or a table instead
     */
    float strollSpeed() {
        if (this.value instanceof Float speed) return speed;
        throw new ToolingException("Pace policy row '%s' declares no stroll speed", this);
    }

    /**
     * The subjects a row names, sorted - its strollers, or the keys of its table.
     *
     * @return the named subjects or goals
     */
    @NotNull List<String> named() {
        if (this.value instanceof Map<?, ?> table)
            return table.keySet().stream().map(String::valueOf).sorted().toList();
        return this.strollers.stream().sorted().toList();
    }

    /**
     * The medium a subject whose control does not defer wanders in ({@link #PACE_MEDIA}).
     *
     * @param entityId the subject's id
     * @return the chain, or empty where the row names none
     */
    static @NotNull Optional<PaceChain> mediumOf(@NotNull String entityId) {
        return Optional.ofNullable(table(PACE_MEDIA, PaceChain.class).get(entityId));
    }

    /**
     * The subject whose pace a subject wanders by ({@link #BORROWED_PACES}).
     *
     * @param entityId the borrowing subject's id
     * @return the lending subject's id, or empty where the subject borrows none
     */
    static @NotNull Optional<String> borrowedFrom(@NotNull String entityId) {
        return Optional.ofNullable(table(BORROWED_PACES, String.class).get(entityId));
    }

    /**
     * The mean movement speed a subject rolls at spawn ({@link #SPAWN_ROLL_MEANS}).
     *
     * @param entityId the subject's id
     * @return the mean, or empty where the row names none
     */
    static @NotNull OptionalDouble rollMeanOf(@NotNull String entityId) {
        return optional(table(SPAWN_ROLL_MEANS, Double.class).get(entityId));
    }

    /**
     * A subject's fastest walk setting where it travels as a parameter ({@link #VILLAGER_FASTEST}).
     *
     * @param entityId the subject's id
     * @return the setting, widened, or empty where the row names none
     */
    static @NotNull OptionalDouble fastestSpeedOf(@NotNull String entityId) {
        return optional(table(VILLAGER_FASTEST, Float.class).get(entityId));
    }

    /**
     * The movement modifier an adult adds while it attacks ({@link #ATTACK_SPEED_MODIFIERS}).
     *
     * @param entityId the subject's id
     * @return the amount added to its movement speed, or empty where the row names none
     */
    static @NotNull OptionalDouble attackModifierOf(@NotNull String entityId) {
        return optional(table(ATTACK_SPEED_MODIFIERS, Double.class).get(entityId));
    }

    /**
     * The movement speed base a subject eases toward while targeting ({@link #RAVAGER_TARGET_SPEED}).
     *
     * @param entityId the subject's id
     * @return the base, or empty where the row names none
     */
    static @NotNull OptionalDouble targetSpeedOf(@NotNull String entityId) {
        return optional(table(RAVAGER_TARGET_SPEED, Double.class).get(entityId));
    }

    /**
     * The medium a subject's fastest walk is taken in where it is not its wander's ({@link #CEILING_MEDIA}).
     *
     * @param entityId the subject's id
     * @return the chain, or empty where the row names none
     */
    static @NotNull Optional<PaceChain> ceilingMediumOf(@NotNull String entityId) {
        return Optional.ofNullable(table(CEILING_MEDIA, PaceChain.class).get(entityId));
    }

    /**
     * The velocity an adult's charge sets ({@link #CHARGE_VELOCITY}).
     *
     * @param entityId the subject's id
     * @return the velocity, widened, or empty where the row names none
     */
    static @NotNull OptionalDouble chargeVelocityOf(@NotNull String entityId) {
        return optional(table(CHARGE_VELOCITY, Float.class).get(entityId));
    }

    /**
     * Whether the fastest-walk census drops a goal at an age ({@link #CEILING_GOAL_EXCLUSIONS}).
     *
     * @param goal the goal's internal name
     * @param age the age token the census is taken at
     * @return whether the goal is dropped there
     */
    @SuppressWarnings("unchecked")
    static boolean excludesGoal(@NotNull String goal, @NotNull String age) {
        Set<String> ages = (Set<String>) table(CEILING_GOAL_EXCLUSIONS, Set.class).get(goal);
        return ages != null && ages.contains(age);
    }

    /** A value row's table, its values of one type. */
    @SuppressWarnings("unchecked")
    private static <V> @NotNull Map<String, V> table(@NotNull EntityPacePolicies row, @NotNull Class<V> type) {
        return (Map<String, V>) row.value;
    }

    private static @NotNull OptionalDouble optional(@Nullable Number value) {
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value.doubleValue());
    }

}
