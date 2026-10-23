package lib.minecraft.renderer.tooling.gate;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.AgePace;
import lib.minecraft.renderer.tooling.animation.EntityPace;
import lib.minecraft.renderer.tooling.animation.WalkPace;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.entity.EntityPaceResolver;
import lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.EntitySubject;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every walking subject's ambling and fastest pace, read off the cached client jar.
 *
 * <p>Two layers. The pins hold each (entity, age) side whose stride reads the walk clock to the exact
 * float bits the readers answer, wander and ceiling alike, so a change to a reader, to the arithmetic
 * or to the jar moves a named number in review before it moves a byte. The list is edited by hand in
 * the change that moves it and has no regenerate switch.
 *
 * <p>The guards hold each declared policy row to the coordinate it was won at - a brain's stroll
 * member to the speed it strolls at, a spawn roll to its generator at the mean draw, an attack boost
 * to the modifier its subject adds - and each constant the pace arithmetic states to the place
 * vanilla states it. A row that drifts with the jar fails as that row rather than as a moved number.
 *
 * <p>Reads the cached client jar and the shipped model table, and abandons the class where no jar is
 * cached.
 */
@DisplayName("entity paces: the ambling and fastest walk of every walking subject")
class EntityPaceJarTest {

    /** The shipped model table, whose age axis says which sides a subject offers. */
    private static final @NotNull Path MODELS = Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** The flow-local policy the readers consult, reached by name because it is not exported. */
    private static final @NotNull String POLICIES = "lib.minecraft.renderer.tooling.entity.EntityPacePolicies";

    /** The fastest-speed reader, reached by name for its census run without the age exclusions. */
    private static final @NotNull String CEILING_READER = "lib.minecraft.renderer.tooling.entity.EntityCeilingResolver";

    /** One side of the census line, with the rows deciding it. */
    private static final @NotNull Pattern DECIDED_SIDE = Pattern.compile("(minecraft:\\w+ \\w+) \\(([^)]*)\\)");

    private static final @NotNull String RANDOM_STROLL = "net/minecraft/world/entity/ai/behavior/RandomStroll";
    private static final @NotNull String VILLAGE_BOUND_STROLL = "net/minecraft/world/entity/ai/behavior/VillageBoundRandomStroll";
    private static final @NotNull String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    private static final @NotNull String ATTRIBUTE_INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    private static final @NotNull String ATTRIBUTE_MODIFIER = "net/minecraft/world/entity/ai/attributes/AttributeModifier";
    private static final @NotNull String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final @NotNull String VILLAGER = "net/minecraft/world/entity/npc/villager/Villager";
    private static final @NotNull String VILLAGER_PACKAGES = "net/minecraft/world/entity/ai/behavior/VillagerGoalPackages";
    private static final @NotNull String TURTLE = "net/minecraft/world/entity/animal/turtle/Turtle";
    private static final @NotNull String TURTLE_MOVE_CONTROL = TURTLE + "$TurtleMoveControl";

    private static ToolingRun run;
    private static List<EntitySubject> subjects;
    private static JsonTree models;
    private static Map<String, EntityPace> paces;

    @BeforeAll
    static void resolve() throws IOException {
        // Gated rather than acquired: this suite is the fast one, so a jar nothing has cached yet
        // abandons the class instead of opening a socket. ClientJarGuardTest is what says so loudly.
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assumeTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar
            + "' - run './gradlew generateTables' or any parity capture to cache one");
        run = new ToolingRun(ClientOptions.defaults(), ClassNodeCache.open(jar),
            Diagnostics.root("entityModels", Diagnostics.Output.NONE, null));
        subjects = EntityRegistryDiscovery.discover(run).stream()
            .filter(EntitySubject.class::isInstance)
            .map(EntitySubject.class::cast)
            .toList();
        models = JsonTree.parse(Files.readAllBytes(MODELS)).find("models").orElseThrow();
        paces = EntityPaceResolver.resolve(run, subjects, models, run.diagnostics().child("styles").child("pace"));
    }

    @AfterAll
    static void close() {
        if (run != null) run.close();
    }

    // ------------------------------------------------------------------------------------
    // the pins
    // ------------------------------------------------------------------------------------

    /**
     * One paced side and the four floats it answers.
     *
     * @param entity the subject's id
     * @param age the age the side applies to
     * @param speed the wander's walkAnimationSpeed
     * @param rate the wander's walkAnimationPos a tick
     * @param fastestSpeed the ceiling's walkAnimationSpeed
     * @param fastestRate the ceiling's walkAnimationPos a tick
     */
    private record Side(
        @NotNull String entity, @NotNull Age age, float speed, float rate, float fastestSpeed, float fastestRate) {

        @NotNull String key() {
            return this.entity + " " + this.age.name().toLowerCase(Locale.ROOT);
        }
    }

    private static @NotNull Side side(
        @NotNull String entity, @NotNull Age age, float speed, float rate, float fastestSpeed, float fastestRate) {
        return new Side(entity, age, speed, rate, fastestSpeed, fastestRate);
    }

    /** Every side whose stride reads the walk clock, by the floats its wander and ceiling answer. */
    private static final @NotNull List<Side> PINS = List.of(
        side("minecraft:allay", Age.ADULT, 0.4444446f, 0.4444446f, 1.0f, 1.0f),
        side("minecraft:armadillo", Age.ADULT, 0.17268725f, 0.17268725f, 0.690749f, 0.690749f),
        side("minecraft:armadillo", Age.BABY, 0.17268725f, 0.51806176f, 0.690749f, 2.072247f),
        side("minecraft:bogged", Age.ADULT, 0.55066085f, 0.55066085f, 0.7929517f, 0.7929517f),
        side("minecraft:camel", Age.ADULT, 0.42819393f, 0.42819393f, 1.0f, 1.0f),
        side("minecraft:camel", Age.BABY, 0.42819393f, 1.2845818f, 1.0f, 3.0f),
        side("minecraft:camel_husk", Age.ADULT, 0.42819393f, 0.42819393f, 1.0f, 1.0f),
        side("minecraft:cat", Age.ADULT, 0.5074891f, 0.5074891f, 1.0f, 1.0f),
        side("minecraft:cat", Age.BABY, 0.5074891f, 1.5224673f, 1.0f, 3.0f),
        side("minecraft:cave_spider", Age.ADULT, 0.5074891f, 0.5074891f, 1.0f, 1.0f),
        side("minecraft:chicken", Age.ADULT, 0.55066085f, 0.55066085f, 1.0f, 1.0f),
        side("minecraft:chicken", Age.BABY, 0.55066085f, 1.6519825f, 1.0f, 3.0f),
        side("minecraft:copper_golem", Age.ADULT, 0.35242295f, 0.35242295f, 0.7929517f, 0.7929517f),
        side("minecraft:cow", Age.ADULT, 0.35242295f, 0.35242295f, 1.0f, 1.0f),
        side("minecraft:cow", Age.BABY, 0.35242295f, 1.0572689f, 1.0f, 3.0f),
        side("minecraft:creaking", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 3.0f),
        side("minecraft:creeper", Age.ADULT, 0.35242295f, 0.35242295f, 0.7929517f, 0.7929517f),
        side("minecraft:donkey", Age.ADULT, 0.13221367f, 0.13221367f, 0.42159972f, 0.42159972f),
        side("minecraft:donkey", Age.BABY, 0.13221367f, 0.39664102f, 0.42159972f, 1.2647991f),
        side("minecraft:drowned", Age.ADULT, 0.46607938f, 0.46607938f, 0.46607938f, 0.46607938f),
        side("minecraft:drowned", Age.BABY, 1.0f, 3.0f, 1.0f, 3.0f),
        side("minecraft:enderman", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 1.0f),
        side("minecraft:evoker", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 1.0f),
        side("minecraft:fox", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 1.0f),
        side("minecraft:fox", Age.BABY, 0.7929517f, 2.3788552f, 1.0f, 3.0f),
        side("minecraft:frog", Age.ADULT, 0.5506609f, 0.5506609f, 1.0f, 1.0f),
        side("minecraft:giant", Age.ADULT, 0.46607938f, 0.46607938f, 0.46607938f, 0.46607938f),
        side("minecraft:goat", Age.ADULT, 0.35242295f, 0.35242295f, 1.0f, 1.0f),
        side("minecraft:goat", Age.BABY, 0.35242295f, 1.0572689f, 1.0f, 3.0f),
        side("minecraft:hoglin", Age.ADULT, 0.12687227f, 0.12687227f, 1.0f, 1.0f),
        side("minecraft:hoglin", Age.BABY, 0.12687227f, 0.3806168f, 1.0f, 3.0f),
        side("minecraft:horse", Age.ADULT, 0.2185573f, 0.2185573f, 0.69693017f, 0.69693017f),
        side("minecraft:horse", Age.BABY, 0.2185573f, 0.6556719f, 0.69693017f, 2.0907905f),
        side("minecraft:husk", Age.ADULT, 0.46607938f, 0.46607938f, 0.46607938f, 0.46607938f),
        side("minecraft:husk", Age.BABY, 1.0f, 3.0f, 1.0f, 3.0f),
        side("minecraft:illusioner", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 1.0f),
        side("minecraft:iron_golem", Age.ADULT, 0.19823793f, 0.19823793f, 0.55066085f, 0.55066085f),
        side("minecraft:llama", Age.ADULT, 0.13221367f, 0.13221367f, 1.0f, 1.0f),
        side("minecraft:llama", Age.BABY, 0.13221367f, 0.39664102f, 1.0f, 3.0f),
        side("minecraft:mooshroom", Age.ADULT, 0.35242295f, 0.35242295f, 1.0f, 1.0f),
        side("minecraft:mooshroom", Age.BABY, 0.35242295f, 1.0572689f, 1.0f, 3.0f),
        side("minecraft:mule", Age.ADULT, 0.13221367f, 0.13221367f, 0.42159972f, 0.42159972f),
        side("minecraft:mule", Age.BABY, 0.13221367f, 0.39664102f, 0.42159972f, 1.2647991f),
        side("minecraft:nautilus", Age.ADULT, 0.44f, 0.44f, 1.0f, 1.0f),
        side("minecraft:nautilus", Age.BABY, 0.44f, 1.3199999f, 0.704f, 2.112f),
        side("minecraft:ocelot", Age.ADULT, 0.5074891f, 0.5074891f, 1.0f, 1.0f),
        side("minecraft:ocelot", Age.BABY, 0.5074891f, 1.5224673f, 1.0f, 3.0f),
        side("minecraft:panda", Age.ADULT, 0.19823793f, 0.19823793f, 0.7929517f, 0.7929517f),
        side("minecraft:panda", Age.BABY, 0.19823793f, 0.5947138f, 0.7929517f, 2.3788552f),
        side("minecraft:parched", Age.ADULT, 0.55066085f, 0.55066085f, 0.7929517f, 0.7929517f),
        side("minecraft:parrot", Age.ADULT, 0.35242295f, 0.35242295f, 0.55066085f, 0.55066085f),
        side("minecraft:pig", Age.ADULT, 0.55066085f, 0.55066085f, 0.8604076f, 0.8604076f),
        side("minecraft:pig", Age.BABY, 0.55066085f, 1.6519825f, 0.8604076f, 2.5812228f),
        side("minecraft:piglin", Age.ADULT, 0.38854635f, 0.38854635f, 1.0f, 1.0f),
        side("minecraft:piglin", Age.BABY, 0.5595067f, 1.6785202f, 1.0f, 3.0f),
        side("minecraft:piglin_brute", Age.ADULT, 0.38854635f, 0.38854635f, 1.0f, 1.0f),
        side("minecraft:pillager", Age.ADULT, 0.3885463f, 0.3885463f, 1.0f, 1.0f),
        side("minecraft:polar_bear", Age.ADULT, 0.55066085f, 0.55066085f, 1.0f, 1.0f),
        side("minecraft:polar_bear", Age.BABY, 0.55066085f, 1.6519825f, 1.0f, 3.0f),
        side("minecraft:ravager", Age.ADULT, 0.12687226f, 0.12687226f, 1.0f, 1.0f),
        side("minecraft:sheep", Age.ADULT, 0.46607938f, 0.46607938f, 0.72824895f, 0.72824895f),
        side("minecraft:sheep", Age.BABY, 0.46607938f, 1.3982382f, 0.72824895f, 2.1847467f),
        side("minecraft:skeleton", Age.ADULT, 0.55066085f, 0.55066085f, 0.7929517f, 0.7929517f),
        side("minecraft:skeleton_horse", Age.ADULT, 0.17268725f, 0.17268725f, 0.5074891f, 0.5074891f),
        side("minecraft:skeleton_horse", Age.BABY, 0.17268725f, 0.51806176f, 0.35242295f, 1.0572689f),
        side("minecraft:sniffer", Age.ADULT, 0.08810574f, 0.08810574f, 0.35242295f, 0.35242295f),
        side("minecraft:sniffer", Age.BABY, 0.08810574f, 0.2643172f, 0.35242295f, 1.0572689f),
        side("minecraft:spider", Age.ADULT, 0.5074891f, 0.5074891f, 1.0f, 1.0f),
        side("minecraft:stray", Age.ADULT, 0.55066085f, 0.55066085f, 0.7929517f, 0.7929517f),
        side("minecraft:strider", Age.ADULT, 0.26982382f, 0.26982382f, 0.73459536f, 0.73459536f),
        side("minecraft:strider", Age.BABY, 0.26982382f, 0.8094715f, 0.73459536f, 2.2037861f),
        side("minecraft:trader_llama", Age.ADULT, 0.13221367f, 0.13221367f, 1.0f, 1.0f),
        side("minecraft:trader_llama", Age.BABY, 0.13221367f, 0.39664102f, 1.0f, 3.0f),
        side("minecraft:turtle", Age.ADULT, 0.405f, 0.405f, 1.0f, 1.0f),
        side("minecraft:turtle", Age.BABY, 0.33499998f, 1.0049999f, 0.36f, 1.08f),
        side("minecraft:villager", Age.ADULT, 0.55066085f, 0.55066085f, 1.0f, 1.0f),
        side("minecraft:villager", Age.BABY, 0.55066085f, 1.6519825f, 1.0f, 3.0f),
        side("minecraft:vindicator", Age.ADULT, 0.3885463f, 0.3885463f, 1.0f, 1.0f),
        side("minecraft:wandering_trader", Age.ADULT, 0.5288547f, 0.5288547f, 1.0f, 1.0f),
        side("minecraft:wandering_trader", Age.BABY, 0.5288547f, 1.5865642f, 1.0f, 3.0f),
        side("minecraft:warden", Age.ADULT, 0.19823793f, 0.19823793f, 1.0f, 1.0f),
        side("minecraft:witch", Age.ADULT, 0.55066085f, 0.55066085f, 0.6071035f, 0.6071035f),
        side("minecraft:wither_skeleton", Age.ADULT, 0.55066085f, 0.55066085f, 0.7929517f, 0.7929517f),
        side("minecraft:wolf", Age.ADULT, 0.7929517f, 0.7929517f, 1.0f, 1.0f),
        side("minecraft:wolf", Age.BABY, 0.7929517f, 2.3788552f, 1.0f, 3.0f),
        side("minecraft:zoglin", Age.ADULT, 0.12687227f, 0.12687227f, 0.7929517f, 0.7929517f),
        side("minecraft:zoglin", Age.BABY, 0.12687227f, 0.3806168f, 0.7929517f, 2.3788552f),
        side("minecraft:zombie", Age.ADULT, 0.46607938f, 0.46607938f, 0.46607938f, 0.46607938f),
        side("minecraft:zombie", Age.BABY, 1.0f, 3.0f, 1.0f, 3.0f),
        side("minecraft:zombie_horse", Age.ADULT, 0.26777974f, 0.26777974f, 0.8538894f, 0.8538894f),
        side("minecraft:zombie_horse", Age.BABY, 0.26777974f, 0.80333924f, 0.8538894f, 2.5616682f),
        side("minecraft:zombie_nautilus", Age.ADULT, 0.484f, 0.484f, 1.0f, 1.0f),
        side("minecraft:zombie_villager", Age.ADULT, 0.46607938f, 0.46607938f, 0.46607938f, 0.46607938f),
        side("minecraft:zombie_villager", Age.BABY, 1.0f, 3.0f, 1.0f, 3.0f),
        side("minecraft:zombified_piglin", Age.ADULT, 0.46607938f, 0.46607938f, 0.690749f, 0.690749f),
        side("minecraft:zombified_piglin", Age.BABY, 1.0f, 3.0f, 1.0f, 3.0f)
    );

    @Test
    @DisplayName("the pinned sides are exactly the sides whose stride reads the walk clock")
    void everyReadingSideIsPinned() {
        Set<String> reading = new TreeSet<>();
        models.members().forEach((entity, row) -> {
            if (!readsTheWalkClock(row)) return;
            reading.add(entity + " adult");
            if (row.findPath("axes", "age", "options", "baby").isPresent()) reading.add(entity + " baby");
        });
        assertEquals(reading, new TreeSet<>(PINS.stream().map(Side::key).toList()),
            "the sides a stride row reads the walk clock on, against the sides this test pins");
    }

    @Test
    @DisplayName("every reading side walks and reaches exactly the floats it is pinned at")
    void everyPinnedSideWalksAtItsPinnedPace() {
        List<String> moved = new ArrayList<>();
        for (Side pinned : PINS) {
            Optional<AgePace> side = Optional.ofNullable(paces.get(pinned.entity())).flatMap(pace -> walksAt(pace, pinned.age()));
            if (side.isEmpty()) {
                moved.add(pinned.key() + ": no pace");
                continue;
            }
            WalkPace wander = side.get().wander();
            WalkPace ceiling = side.get().ceiling();
            if (wander.speed() != pinned.speed() || wander.rate() != pinned.rate()
                || ceiling.speed() != pinned.fastestSpeed() || ceiling.rate() != pinned.fastestRate()) {
                moved.add("%s: wander %s / %s, ceiling %s / %s (pinned %s / %s, %s / %s)".formatted(pinned.key(),
                    wander.speed(), wander.rate(), ceiling.speed(), ceiling.rate(),
                    pinned.speed(), pinned.rate(), pinned.fastestSpeed(), pinned.fastestRate()));
            }
        }
        assertEquals(List.of(), moved, "sides whose pace moved from the floats this test pins");
    }

    @Test
    @DisplayName("a baby walks its clock at the float product of its feed's scale and its own swing")
    void aBabyRateIsTheFloatProductOfItsSwing() {
        for (Side pinned : PINS) {
            if (pinned.age() == Age.BABY) {
                assertEquals(3f * pinned.speed(), pinned.rate(), 0f, pinned.key() + " wander");
                assertEquals(3f * pinned.fastestSpeed(), pinned.fastestRate(), 0f, pinned.key() + " ceiling");
            }
        }
    }

    @Test
    @DisplayName("no ceiling is under its own wander at either float")
    void theCeilingIsNeverUnderTheWander() {
        paces.forEach((entity, pace) -> {
            for (Age age : Age.values()) {
                walksAt(pace, age).ifPresent(side -> {
                    assertTrue(side.ceiling().speed() >= side.wander().speed(), entity + " " + age + " speed");
                    assertTrue(side.ceiling().rate() >= side.wander().rate(), entity + " " + age + " rate");
                });
            }
        });
    }

    @Test
    @DisplayName("the pace pass logs no error, no stroll it could not agree on and no unreadable baby modifier")
    void theCensusLinesNameOnlyTheExpected() {
        List<Diagnostics.Entry> entries = run.diagnostics().entries();
        assertEquals(0, run.diagnostics().count(Diagnostics.Severity.ERROR), "errors logged by the pace pass");
        List<String> paceLines = entries.stream().map(Diagnostics.Entry::message).toList();
        assertFalse(paceLines.stream().anyMatch(line -> line.contains(" strolls by ")),
            "a subject whose strolls disagree: " + paceLines);
        assertFalse(paceLines.stream().anyMatch(line -> line.contains("baby movement modifier of a shape")),
            "a baby modifier the reader could not read: " + paceLines);
        String unpaced = paceLines.stream().filter(line -> line.contains(" take no pace: ")).findFirst().orElseThrow();
        for (Side pinned : PINS)
            assertFalse(unpaced.contains(pinned.entity() + " ("), pinned.entity() + " is reported unpaced: " + unpaced);
    }

    @Test
    @DisplayName("a policy row decides exactly the eleven ceilings the jar cannot")
    void theDecidedCeilingsAreTheElevenTheJarCannotRead() {
        assertEquals(new TreeMap<>(Map.ofEntries(
            Map.entry("minecraft:enderman adult", List.of("ATTACK_SPEED_MODIFIERS")),
            Map.entry("minecraft:giant adult", List.of("BORROWED_PACES")),
            Map.entry("minecraft:nautilus adult", List.of("CHARGE_VELOCITY")),
            Map.entry("minecraft:ravager adult", List.of("RAVAGER_TARGET_SPEED")),
            Map.entry("minecraft:skeleton_horse baby", List.of("CEILING_GOAL_EXCLUSIONS")),
            Map.entry("minecraft:turtle adult", List.of("CEILING_MEDIA")),
            Map.entry("minecraft:turtle baby", List.of("CEILING_GOAL_EXCLUSIONS")),
            Map.entry("minecraft:villager adult", List.of("VILLAGER_FASTEST")),
            Map.entry("minecraft:villager baby", List.of("VILLAGER_FASTEST")),
            Map.entry("minecraft:zombie_nautilus adult", List.of("CHARGE_VELOCITY")),
            Map.entry("minecraft:zombified_piglin adult", List.of("ATTACK_SPEED_MODIFIERS")))), decidedSides());
    }

    // ------------------------------------------------------------------------------------
    // the wander rows held to the jar
    // ------------------------------------------------------------------------------------

    /** Each brain's stroll row, to the float its member strolls at. */
    private static final @NotNull Map<String, Float> STROLL_ROWS = Map.ofEntries(
        Map.entry("ALLAY_IDLE_STROLL", 1.0f), Map.entry("ARMADILLO_IDLE_STROLL", 1.0f),
        Map.entry("CAMEL_IDLE_STROLL", 2.0f), Map.entry("COPPER_GOLEM_IDLE_STROLL", 1.0f),
        Map.entry("CREAKING_IDLE_STROLL", 0.3f), Map.entry("FROG_IDLE_STROLL", 1.0f),
        Map.entry("GOAT_IDLE_STROLL", 1.0f), Map.entry("HOGLIN_IDLE_STROLL", 0.4f),
        Map.entry("NAUTILUS_IDLE_STROLL", 1.0f), Map.entry("ZOMBIE_NAUTILUS_IDLE_STROLL", 1.0f),
        Map.entry("PIGLIN_IDLE_STROLL", 0.6f), Map.entry("PIGLIN_BRUTE_IDLE_STROLL", 0.6f),
        Map.entry("SNIFFER_IDLE_STROLL", 1.0f), Map.entry("WARDEN_IDLE_STROLL", 0.5f),
        Map.entry("ZOGLIN_IDLE_STROLL", 0.4f));

    @Test
    @DisplayName("each brain's stroll row names a member holding one stroll at the speed the row is held to")
    void eachStrollRowReadsTheSpeedItsTableStates() {
        Map<String, Float> read = new TreeMap<>();
        rows().forEach((name, row) -> {
            if (!(row.navigate(frame()) instanceof Navigation.At at)) return;
            MethodNode member = declared(at.owner(), at.member());
            List<MethodInsnNode> strolls = calls(member, call -> call.getOpcode() == Opcodes.INVOKESTATIC
                && (call.owner.equals(RANDOM_STROLL) || call.owner.equals(VILLAGE_BOUND_STROLL)));
            assertEquals(1, strolls.size(), name + " strolls at one call");
            read.put(name, (Float) pushes(strolls.getFirst()).getFirst());
        });
        assertEquals(new TreeMap<>(STROLL_ROWS), read, "the float each stroll row's member strolls at");
    }

    @Test
    @DisplayName("the villager strolls at the float its brain provider hands its idle package")
    void theVillagerStrollsAtTheFloatItsProviderHands() {
        Object declared = valueOf("VILLAGER_IDLE_STROLL");
        MethodNode provider = declared(VILLAGER, "lambda$static$0");
        MethodInsnNode idle = calls(provider, call -> call.owner.equals(VILLAGER_PACKAGES) && call.name.equals("getIdlePackage"))
            .getFirst();
        assertEquals(declared, pushes(idle).getFirst(), "the float the provider hands the idle package");
        MethodInsnNode stroll = calls(declared(VILLAGER_PACKAGES, "getIdlePackage"),
            call -> call.owner.equals(VILLAGE_BOUND_STROLL)).getFirst();
        assertTrue(AsmWalker.previousReal(stroll) instanceof VarInsnNode load
            && load.getOpcode() == Opcodes.FLOAD && load.var == 0, "the idle package strolls at its own parameter");
    }

    @Test
    @DisplayName("each spawn roll mean is its generator at the mean draw, and every rolled subject has one")
    void eachSpawnRollMeanIsItsGeneratorAtTheMeanDraw() {
        @SuppressWarnings("unchecked")
        Map<String, Double> means = (Map<String, Double>) valueOf("SPAWN_ROLL_MEANS");
        Map<String, Double> rolled = new TreeMap<>();
        for (EntitySubject subject : subjects) {
            ClassNode declaring = ClassKit.walkSuperChainUntil(run.cache(), subject.entityClass(),
                node -> ClassKit.findMethod(node, "randomizeAttributes", "(Lnet/minecraft/util/RandomSource;)V") != null);
            if (declaring == null) continue;
            MethodNode roll = ClassKit.findMethod(declaring, "randomizeAttributes", "(Lnet/minecraft/util/RandomSource;)V");
            FieldInsnNode speed = AsmWalker.over(roll).ofType(FieldInsnNode.class)
                .first(read -> read.owner.equals(ATTRIBUTES) && read.name.equals("MOVEMENT_SPEED"));
            if (speed == null) continue;
            MethodInsnNode generator = AsmWalker.after(speed).ofType(MethodInsnNode.class)
                .first(call -> call.getOpcode() == Opcodes.INVOKESTATIC && call.desc.endsWith(")D"));
            assertNotNull(generator, subject.entityId() + " rolls its speed through a generator");
            MethodNode body = ClassKit.findMethodInHierarchy(run.cache(), generator.owner, generator.name, generator.desc);
            rolled.put(subject.entityId(), meanDraw(body));
        }
        assertEquals(rolled.keySet(), new TreeSet<>(means.keySet()), "the subjects whose spawn rolls their speed");
        rolled.forEach((entity, mean) -> assertEquals(mean, means.get(entity), 0d, entity + " mean speed"));
    }

    @Test
    @DisplayName("each declared medium is the chain its subject walks, and every other reading subject walks the ground")
    void eachPaceMediumIsTheChainItsSubjectWalks() {
        @SuppressWarnings("unchecked")
        Map<String, ?> media = (Map<String, ?>) valueOf("PACE_MEDIA");
        for (Side pinned : PINS) {
            if (pinned.age() != Age.ADULT) continue;
            String basis = paces.get(pinned.entity()).adult().orElseThrow().wander().basis();
            Object medium = media.get(pinned.entity());
            String expected = medium == null ? "ground:" : medium.toString().toLowerCase(Locale.ROOT) + " (PACE_MEDIA):";
            assertTrue(basis.startsWith(expected), pinned.entity() + " walks " + basis);
        }
    }

    @Test
    @DisplayName("the giant has no stroll of its own and borrows the zombie's, which strolls")
    void theGiantBorrowsAWanderItHasNone() {
        assertEquals(Map.of("minecraft:giant", "minecraft:zombie"), valueOf("BORROWED_PACES"));
        MethodNode goals = nearest("net/minecraft/world/entity/monster/Giant", "registerGoals", "()V");
        assertFalse(AsmWalker.over(goals).any(node -> node.getOpcode() == Opcodes.NEW), "the giant registers no goal");
        assertTrue(paces.get("minecraft:zombie").adult().orElseThrow().wander().basis().contains("(goal "),
            "the zombie strolls by a goal of its own");
        assertTrue(paces.get("minecraft:giant").adult().orElseThrow().wander().basis().contains("borrowed from minecraft:zombie"),
            "the giant walks the zombie's pace");
    }

    @Test
    @DisplayName("the turtle travels through water at the speed it strolls at")
    void theTurtleTravelsAtItsStrollSpeed() {
        MethodNode goals = declared(TURTLE, "registerGoals");
        Object stroll = firstDouble(constructed(goals, TURTLE + "$TurtleRandomStrollGoal"));
        Object travel = firstDouble(constructed(goals, TURTLE + "$TurtleTravelGoal"));
        assertNotNull(stroll, "the turtle's stroll speed");
        assertEquals(stroll, travel, "the turtle's water travel speed");
    }

    @Test
    @DisplayName("each constant the pace arithmetic states is where vanilla states it")
    void eachArmConstantIsAtItsJarCoordinate() {
        // the ground: a default block's friction, the air drag it is multiplied by, and the
        // acceleration that friction cubed divides out
        assertTrue(literals(declared("net/minecraft/world/level/block/state/BlockBehaviour$Properties", "<init>")).contains(0.6f),
            "a block's default friction");
        assertTrue(literals(declared(LIVING_ENTITY, "travelInAir")).contains(0.91f), "the air drag on the ground");
        assertTrue(literals(declared(LIVING_ENTITY, "getFrictionInfluencedSpeed")).contains(0.21600002f),
            "the acceleration friction cubed divides out");
        // flight and the water a smooth swimmer and a turtle move through
        assertTrue(literals(declared(LIVING_ENTITY, "travelFlying", "(Lnet/minecraft/world/phys/Vec3;FFF)V"))
            .contains((double) 0.91f), "the air drag in flight");
        assertTrue(literals(declared("net/minecraft/world/entity/animal/nautilus/AbstractNautilus", "travelInWater"))
            .contains(0.9d), "the water drag a smooth swimmer moves through");
        List<Object> travel = literals(declared(TURTLE, "travelInWater"));
        assertTrue(travel.contains(0.1f) && travel.contains(0.9d), "the turtle's water thrust and drag");
        // the turtle's own speed control: the far halving floored, the baby thirding floored, the ease
        List<Object> update = literals(declared(TURTLE_MOVE_CONTROL, "updateSpeed"));
        assertTrue(update.contains(2.0f) && update.contains(0.08f) && update.contains(3.0f) && update.contains(0.06f),
            "the turtle's speed floors: " + update);
        assertTrue(literals(declared(TURTLE_MOVE_CONTROL, "tick")).contains(0.125f), "the turtle's speed ease");
    }

    // ------------------------------------------------------------------------------------
    // the ceiling rows held to the jar
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the villager's fastest is its provider's float times its panic package's factor")
    void theVillagerFastestIsTheProviderFloatTimesThePanicFactor() {
        MethodNode provider = declared(VILLAGER, "lambda$static$0");
        MethodInsnNode panic = calls(provider, call -> call.owner.equals(VILLAGER_PACKAGES) && call.name.equals("getPanicPackage"))
            .getFirst();
        float handed = (Float) pushes(panic).getFirst();
        MethodNode packageBody = declared(VILLAGER_PACKAGES, "getPanicPackage");
        AbstractInsnNode multiply = AsmWalker.over(packageBody).first(node -> node.getOpcode() == Opcodes.FMUL);
        assertNotNull(multiply, "the panic package scales the speed it is handed");
        Float factor = AsmWalker.floatLiteral(AsmWalker.previousReal(multiply));
        assertNotNull(factor, "the panic package's factor");
        assertEquals(Map.of("minecraft:villager", handed * factor), valueOf("VILLAGER_FASTEST"));
    }

    @Test
    @DisplayName("an attack boost is the amount its subject adds while attacking, and two subjects add one")
    void eachAttackModifierIsTheAmountItsSubjectAddsWhileAttacking() {
        Map<String, Double> boosts = new TreeMap<>();
        for (EntitySubject subject : subjects) {
            ClassKit.walkSuperChainUntil(run.cache(), subject.entityClass(), node -> {
                if (node.name.equals(LIVING_ENTITY)) return true;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("setBaby")) {
                        speedModifiers(method).forEach(amount -> {
                            if (amount > 0) boosts.merge(subject.entityId(), amount, Math::max);
                        });
                    }
                }
                return false;
            });
        }
        assertEquals(new TreeMap<>(Map.of("minecraft:enderman", 0.15000000596046448, "minecraft:zombified_piglin", 0.05)),
            boosts, "the subjects whose own behaviours add a positive movement modifier");
        assertEquals(boosts, new TreeMap<>((Map<?, ?>) valueOf("ATTACK_SPEED_MODIFIERS")),
            "each declared boost is the modifier its subject adds");
    }

    @Test
    @DisplayName("the ravager's target speed is the base its tick eases toward while it has a target")
    void theRavagerTargetSpeedIsItsAiStepTarget() {
        MethodNode tick = declared("net/minecraft/world/entity/monster/Ravager", "aiStep");
        AbstractInsnNode targeted = AsmWalker.over(tick).first(node -> node.getOpcode() == Opcodes.IFNULL);
        assertNotNull(targeted, "the ravager tests for a target");
        assertEquals(Map.of("minecraft:ravager", AsmWalker.doubleLiteral(AsmWalker.nextReal(targeted))),
            valueOf("RAVAGER_TARGET_SPEED"));
    }

    @Test
    @DisplayName("each charge velocity is the speed its fight activity charges at")
    void eachChargeVelocityIsItsChargeAttackSpeed() {
        Map<String, Float> charges = new TreeMap<>();
        charges.put("minecraft:nautilus", chargeSpeed("net/minecraft/world/entity/animal/nautilus/NautilusAi"));
        charges.put("minecraft:zombie_nautilus", chargeSpeed("net/minecraft/world/entity/animal/nautilus/ZombieNautilusAi"));
        assertEquals(charges, new TreeMap<>((Map<?, ?>) valueOf("CHARGE_VELOCITY")));
    }

    @Test
    @DisplayName("the turtle's ceiling medium is the one its speed control does not halve, within sixteen blocks of home")
    void theTurtleCeilingMediumIsUpdateSpeedsHomeGate() {
        MethodNode update = declared(TURTLE_MOVE_CONTROL, "updateSpeed");
        MethodInsnNode home = calls(update, call -> call.name.equals("closerToCenterThan")).getFirst();
        assertEquals(16.0, AsmWalker.doubleLiteral(AsmWalker.previousReal(home)), "the home radius");
        assertTrue(AsmWalker.nextReal(home) instanceof JumpInsnNode skip && skip.getOpcode() == Opcodes.IFNE
            && AsmWalker.from(skip).until(skip.label).any(node -> node.getOpcode() == Opcodes.FDIV),
            "near home the speed skips the halving");
        assertEquals("TURTLE_SWIM_NEAR", String.valueOf(((Map<?, ?>) valueOf("CEILING_MEDIA")).get("minecraft:turtle")));
    }

    @Test
    @DisplayName("the goal exclusions move the census on the baby skeleton horse and both turtles, and the ceiling on two")
    void theGoalExclusionsMoveExactlyTwoCeilings() throws ReflectiveOperationException {
        Method census = Class.forName(CEILING_READER).getDeclaredMethod("fastest",
            ToolingRun.class, EntitySubject.class, Age.class, BiPredicate.class, Diagnostics.class);
        census.setAccessible(true);
        BiPredicate<String, Age> none = (goal, age) -> false;
        Set<String> moved = new TreeSet<>();
        for (Side pinned : PINS) {
            EntitySubject subject = subjects.stream().filter(one -> one.entityId().equals(pinned.entity())).findFirst()
                .orElseThrow();
            Object with = census.invoke(null, run, subject, pinned.age(), excluding(), run.diagnostics());
            Object without = census.invoke(null, run, subject, pinned.age(), none, run.diagnostics());
            if (!modifierOf(with).equals(modifierOf(without))) moved.add(pinned.key());
        }
        // The adult turtle's go-to-water is faster than its panic too, but near home both saturate its
        // swim, so its ceiling stays where it is and no row is said to decide it.
        assertEquals(Set.of("minecraft:skeleton_horse baby", "minecraft:turtle adult", "minecraft:turtle baby"), moved,
            "the sides whose census the goal exclusions move");
        Set<String> decided = new TreeSet<>();
        decidedSides().forEach((side, rows) -> {
            if (rows.contains("CEILING_GOAL_EXCLUSIONS")) decided.add(side);
        });
        assertEquals(Set.of("minecraft:skeleton_horse baby", "minecraft:turtle baby"), decided,
            "the sides whose ceiling the goal exclusions move");
    }

    // ------------------------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------------------------

    /** Whether a model row's stride reads the walk clock - its sources hold {@code stride}. */
    private static boolean readsTheWalkClock(@NotNull JsonTree row) {
        boolean[] reads = {false};
        row.find("styles").ifPresent(styles -> styles.elements().forEach(style -> {
            if (!style.findString("id").filter("stride"::equals).isPresent()) return;
            style.find("sources").ifPresent(sources -> sources.elements().forEach(source -> {
                if (source.asString().filter("stride"::equals).isPresent()) reads[0] = true;
            }));
        }));
        return reads[0];
    }

    /** Each side the pace pass's census line says a policy row decides the ceiling of, to those rows. */
    private static @NotNull Map<String, List<String>> decidedSides() {
        String line = run.diagnostics().entries().stream()
            .map(Diagnostics.Entry::message)
            .filter(message -> message.contains(" take a ceiling a policy row decides: "))
            .findFirst()
            .orElseThrow();
        Map<String, List<String>> decided = new TreeMap<>();
        Matcher side = DECIDED_SIDE.matcher(line);
        while (side.find()) decided.put(side.group(1), List.of(side.group(2).split(", ")));
        return decided;
    }

    /** The pace policy's rows, by name, as the SPI consults them. */
    private static @NotNull Map<String, NavigationPolicy> rows() {
        Map<String, NavigationPolicy> rows = new LinkedHashMap<>();
        try {
            for (Object constant : Class.forName(POLICIES).getEnumConstants())
                rows.put(((Enum<?>) constant).name(), (NavigationPolicy) constant);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("The pace policy is not on the test classpath", ex);
        }
        return rows;
    }

    /** One subject's walks at one age, as each age's side reads them. */
    private static @NotNull Optional<AgePace> walksAt(@NotNull EntityPace pace, @NotNull Age age) {
        return switch (age) {
            case ADULT -> pace.adult();
            case BABY -> pace.baby();
        };
    }

    /** The payload a declared row answers its consultation with. */
    private static @NotNull Object valueOf(@NotNull String row) {
        NavigationPolicy policy = rows().get(row);
        assertNotNull(policy, "the pace policy declares " + row);
        return assertInstanceOf(Navigation.Value.class, policy.navigate(frame())).value();
    }

    /** The exclusion the census applies, answered by the policy row it is declared in. */
    @SuppressWarnings("unchecked")
    private static @NotNull BiPredicate<String, Age> excluding() {
        Map<String, Set<Age>> excluded = (Map<String, Set<Age>>) valueOf("CEILING_GOAL_EXCLUSIONS");
        return (goal, age) -> excluded.getOrDefault(goal, Set.of()).contains(age);
    }

    /** The census answer's modifier, or empty for a side it finds nothing for. */
    private static @NotNull Optional<Double> modifierOf(@NotNull Object answer) throws ReflectiveOperationException {
        Optional<?> found = (Optional<?>) answer;
        if (found.isEmpty()) return Optional.empty();
        Method modifier = found.get().getClass().getDeclaredMethod("modifier");
        modifier.setAccessible(true);
        return Optional.of((Double) modifier.invoke(found.get()));
    }

    private static @NotNull AsmContext frame() {
        return new AsmContext(run, "<pace-test>", null, run.diagnostics());
    }

    private static @NotNull MethodNode declared(@NotNull String owner, @NotNull String name) {
        MethodNode method = ClassKit.findMethod(run.cache().require(owner, "the pace jar test"), name);
        assertNotNull(method, owner + " declares " + name);
        return method;
    }

    private static @NotNull MethodNode declared(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        MethodNode method = ClassKit.findMethod(run.cache().require(owner, "the pace jar test"), name, desc);
        assertNotNull(method, owner + " declares " + name + desc);
        return method;
    }

    private static @NotNull MethodNode nearest(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        MethodNode method = ClassKit.findMethodInHierarchy(run.cache(), owner, name, desc);
        assertNotNull(method, owner + " reaches " + name + desc);
        return method;
    }

    private static @NotNull List<MethodInsnNode> calls(@NotNull MethodNode method, @NotNull Predicate<MethodInsnNode> test) {
        return AsmWalker.over(method).ofType(MethodInsnNode.class).where(test).toList();
    }

    /** Every numeric literal a method pushes, as the boxed type it is pushed as. */
    private static @NotNull List<Object> literals(@NotNull MethodNode method) {
        return AsmWalker.over(method).mapNotNull(EntityPaceJarTest::literal).toList();
    }

    private static @Nullable Object literal(@NotNull AbstractInsnNode node) {
        Float single = AsmWalker.floatLiteral(node);
        if (single != null) return single;
        Double wide = AsmWalker.doubleLiteral(node);
        if (wide != null) return wide;
        return AsmWalker.intLiteral(node);
    }

    /**
     * The values a call is handed, one push per parameter read back from the call - each a literal, or
     * {@code null} where the push is not one.
     */
    private static @NotNull List<@Nullable Object> pushes(@NotNull MethodInsnNode call) {
        int arity = ClassKit.argTypes(call.desc).length;
        List<AbstractInsnNode> found = AsmWalker.before(call).real().limit(arity).toList();
        assertEquals(arity, found.size(), "a push per parameter of " + call.name);
        List<Object> values = new ArrayList<>();
        for (AbstractInsnNode push : found.reversed()) values.add(literal(push));
        return values;
    }

    /** The constructor call building the first instance of a class a method allocates. */
    private static @NotNull MethodInsnNode constructed(@NotNull MethodNode method, @NotNull String type) {
        MethodInsnNode call = AsmWalker.over(method).ofType(MethodInsnNode.class)
            .first(node -> node.getOpcode() == Opcodes.INVOKESPECIAL && node.owner.equals(type) && node.name.equals("<init>"));
        assertNotNull(call, "the method constructs " + type);
        return call;
    }

    /** The literal handed a constructor's first {@code double} parameter. */
    private static @Nullable Object firstDouble(@NotNull MethodInsnNode call) {
        var parameters = ClassKit.argTypes(call.desc);
        List<Object> values = pushes(call);
        for (int index = 0; index < parameters.length; index++)
            if (parameters[index].getSort() == org.objectweb.asm.Type.DOUBLE) return values.get(index);
        return null;
    }

    /** The speed a brain owner's fight activity hands its charge - the charge's first float. */
    private static @NotNull Float chargeSpeed(@NotNull String owner) {
        MethodInsnNode charge = constructed(declared(owner, "initFightActivity"), "net/minecraft/world/entity/ai/behavior/ChargeAttack");
        var parameters = ClassKit.argTypes(charge.desc);
        List<Object> values = pushes(charge);
        for (int index = 0; index < parameters.length; index++)
            if (parameters[index].getSort() == org.objectweb.asm.Type.FLOAT) return (Float) values.get(index);
        throw new IllegalStateException(owner + " charges with no float");
    }

    /**
     * A spawn-roll generator's value at the mean draw - its straight-line body evaluated with every
     * draw answering one half.
     */
    private static double meanDraw(@NotNull MethodNode generator) {
        Deque<Double> stack = new ArrayDeque<>();
        AsmWalker.over(generator).forEach(node -> {
            Double literal = AsmWalker.doubleLiteral(node);
            if (literal != null) stack.push(literal);
            else if (node instanceof MethodInsnNode call && call.name.equals("getAsDouble")) stack.push(0.5);
            else switch (node.getOpcode()) {
                case Opcodes.DADD -> {
                    double right = stack.pop();
                    stack.push(stack.pop() + right);
                }
                case Opcodes.DMUL -> {
                    double right = stack.pop();
                    stack.push(stack.pop() * right);
                }
                case Opcodes.DDIV -> {
                    double right = stack.pop();
                    stack.push(stack.pop() / right);
                }
                default -> { }
            }
        });
        assertEquals(1, stack.size(), generator.name + " leaves one value");
        return stack.pop();
    }

    /**
     * The amount of every movement-speed modifier a method adds - the modifier field read just before
     * the add, its amount read where the field's class builds it.
     */
    private static @NotNull List<Double> speedModifiers(@NotNull MethodNode method) {
        List<Double> amounts = new ArrayList<>();
        AsmWalker.over(method)
            .latch(node -> node instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                && read.owner.equals(ATTRIBUTES) ? read.name : null)
            .commitAt(MethodInsnNode.class, call -> call.owner.equals(ATTRIBUTE_INSTANCE)
                && (call.name.equals("addTransientModifier") || call.name.equals("addPermanentModifier")))
            .forEach(commit -> {
                if (!"MOVEMENT_SPEED".equals(commit.value())) return;
                if (!(AsmWalker.previousReal(commit.node()) instanceof FieldInsnNode modifier)
                    || !modifier.desc.equals("L" + ATTRIBUTE_MODIFIER + ";")) return;
                MethodNode clinit = ClassKit.findClinit(run.cache(), modifier.owner);
                if (clinit == null) return;
                AbstractInsnNode put = AsmWalker.over(clinit).first(node -> node instanceof FieldInsnNode store
                    && store.getOpcode() == Opcodes.PUTSTATIC && store.name.equals(modifier.name));
                if (put == null) return;
                Double amount = AsmWalker.before(put).real().mapNotNull(AsmWalker::doubleLiteral).first();
                if (amount != null) amounts.add(amount);
            });
        return amounts;
    }

}
