package lib.minecraft.renderer.tooling.gate;

import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.policy.StyleRoster;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every clock the style roster drives, held to the place in the client jar vanilla states it.
 *
 * <p>A clock's numbers are behavioural facts of vanilla's own tick - a tail's eased speed, a lid's step,
 * a heartbeat's interval - which no walk of a pose can read, so the roster declares them as literals.
 * Each literal is read back here where vanilla writes it, so a version bump that moves one fails as
 * that clock rather than as a strip that runs at the old rate.
 *
 * <p>The roster's one countdown is held the same way: the count the iron golem's offer starts at, where
 * vanilla sets it on the offer's event and lowers it a tick at a time.
 *
 * <p>Two of the roster's values are chosen rather than read, and each is asserted as chosen: the squid
 * strokes at the rate the roster's resting entity id draws, and the shulker loops vanilla's shortest
 * peek with no wait between peeks.
 *
 * <p>Every clock field is also one name in one keyspace. The fold keeps a driven name symbolic wherever
 * a pose reads it, so a field two render states declare would be driven in both; each is held to
 * exactly one render state declaring it on which the fold does not derive it from another figure.
 *
 * <p>Reads the cached client jar, and abandons the class where no jar is cached.
 */
@DisplayName("clock roster: every clock at its jar coordinate")
class ClockRosterJarTest {

    private static final @NotNull String GUARDIAN = "net/minecraft/world/entity/monster/Guardian";
    private static final @NotNull String ENDER_DRAGON = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
    private static final @NotNull String AXOLOTL = "net/minecraft/world/entity/animal/axolotl/Axolotl";
    private static final @NotNull String BINARY_ANIMATOR = "net/minecraft/util/BinaryAnimator";
    private static final @NotNull String SQUID = "net/minecraft/world/entity/animal/squid/Squid";
    private static final @NotNull String LEGACY_RANDOM = "net/minecraft/world/level/levelgen/LegacyRandomSource";
    private static final @NotNull String BIT_RANDOM = "net/minecraft/world/level/levelgen/BitRandomSource";
    private static final @NotNull String SHULKER = "net/minecraft/world/entity/monster/Shulker";
    private static final @NotNull String SHULKER_PEEK = SHULKER + "$ShulkerPeekGoal";
    private static final @NotNull String WARDEN = "net/minecraft/world/entity/monster/warden/Warden";
    private static final @NotNull String IRON_GOLEM = "net/minecraft/world/entity/animal/golem/IronGolem";

    /** Where every render state the renderers fill is declared. */
    private static final @NotNull String RENDERER_ROOT = "net/minecraft/client/renderer/";

    /** The suffix every render state's class entry ends with. */
    private static final @NotNull String RENDER_STATE_ENTRY = "RenderState.class";

    /** The reader of the figures a renderer derives from a driven one, reached by name because it is not exported. */
    private static final @NotNull String INPUT_DEFAULTS = "lib.minecraft.renderer.tooling.animation.InputDefaultResolver";

    private static ClassNodeCache cache;

    @BeforeAll
    static void open() {
        // Gated rather than acquired: this suite is the fast one, so a jar nothing has cached yet
        // abandons the class instead of opening a socket. ClientJarGuardTest is what says so loudly.
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assumeTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar
            + "' - run './gradlew generateTables' or any parity capture to cache one");
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    // ------------------------------------------------------------------------------------
    // the guardian
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("tailAnimation ramps at the speeds Guardian.aiStep eases the tail to, still and moving")
    void theTailRampsAtItsEasedSpeeds() {
        MethodNode aiStep = declared(GUARDIAN, "aiStep", "()V");
        String speed = "clientSideTailAnimationSpeed";
        List<Ease> eases = eases(aiStep, speed);
        assertEquals(2, eases.size(), "tailAnimation: Guardian.aiStep eases the tail speed toward two targets: " + eases);
        Ease moving = eases.getFirst();
        Ease still = eases.getLast();
        FieldInsnNode kick = AsmWalker.over(aiStep).ofType(FieldInsnNode.class)
            .first(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals(speed)
                && Float.valueOf(4.0f).equals(AsmWalker.floatLiteral(AsmWalker.previousReal(put))));
        assertNotNull(kick, "tailAnimation: Guardian.aiStep kicks a slow moving tail to 4");
        assertTrue(aiStep.instructions.indexOf(kick) < aiStep.instructions.indexOf(moving.store()),
            "tailAnimation: the moving ease is the one past the kick");
        assertEquals(0.1f, moving.rate(), "tailAnimation: Guardian.aiStep's moving ease rate");
        assertEquals(0.2f, still.rate(), "tailAnimation: Guardian.aiStep's still ease rate");
        assertTrue(AsmWalker.over(aiStep).ofType(FieldInsnNode.class).any(put -> put.getOpcode() == Opcodes.PUTFIELD
                && put.name.equals("clientSideTailAnimation")
                && AsmWalker.previousReal(put) instanceof AbstractInsnNode add && add.getOpcode() == Opcodes.FADD
                && AsmWalker.previousReal(add) instanceof FieldInsnNode read && read.name.equals(speed)),
            "tailAnimation: Guardian.aiStep adds the speed to the tail every tick");

        StyleRoster.Clock tail = clock("tailAnimation");
        assertEquals(StyleRoster.Motion.ramp(still.target()), tail.idle(),
            "tailAnimation: the idle half ramps at Guardian.aiStep's still target");
        assertEquals(Optional.of(StyleRoster.Motion.ramp(moving.target())), tail.stride(),
            "tailAnimation: the stride half ramps at Guardian.aiStep's moving target");
    }

    @Test
    @DisplayName("spikesAnimation holds the levels Guardian.aiStep eases the spikes to, still and moving")
    void theSpikesHoldAtTheirEasedLevels() {
        List<Ease> eases = eases(declared(GUARDIAN, "aiStep", "()V"), "clientSideSpikesAnimation");
        assertEquals(2, eases.size(), "spikesAnimation: Guardian.aiStep eases the spikes toward two levels: " + eases);
        Ease moving = eases.getFirst();
        Ease still = eases.getLast();
        assertEquals(0.25f, moving.rate(), "spikesAnimation: Guardian.aiStep's moving ease rate");
        assertEquals(0.06f, still.rate(), "spikesAnimation: Guardian.aiStep's still ease rate");

        StyleRoster.Clock spikes = clock("spikesAnimation");
        assertEquals(StyleRoster.Motion.hold(still.target()), spikes.idle(),
            "spikesAnimation: the idle half holds Guardian.aiStep's still level");
        assertEquals(Optional.of(StyleRoster.Motion.hold(moving.target())), spikes.stride(),
            "spikesAnimation: the stride half holds Guardian.aiStep's moving level");
    }

    // ------------------------------------------------------------------------------------
    // the ender dragon
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("flapTime ramps at the step EnderDragon.aiStep adds while its phase sits")
    void theWingsBeatAtTheSittingStep() {
        MethodNode aiStep = declared(ENDER_DRAGON, "aiStep", "()V");
        List<FieldInsnNode> literalSteps = AsmWalker.over(aiStep).ofType(FieldInsnNode.class)
            .where(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("flapTime")
                && AsmWalker.previousReal(put) instanceof AbstractInsnNode add && add.getOpcode() == Opcodes.FADD
                && AsmWalker.floatLiteral(AsmWalker.previousReal(add)) != null)
            .toList();
        assertEquals(1, literalSteps.size(), "flapTime: EnderDragon.aiStep adds one literal to flapTime");
        AbstractInsnNode add = AsmWalker.previousReal(literalSteps.getFirst());
        Float step = AsmWalker.floatLiteral(AsmWalker.previousReal(add));
        MethodInsnNode gate = AsmWalker.before(add).ofType(MethodInsnNode.class).first(call -> true);
        assertNotNull(gate, "flapTime: a call decides the literal step");
        assertEquals("isSitting", gate.name, "flapTime: EnderDragon.aiStep adds its literal right after an isSitting call");

        StyleRoster.Clock flap = clock("flapTime");
        assertEquals(StyleRoster.Motion.ramp(step), flap.idle(), "flapTime: the idle half ramps at the sitting step");
        assertEquals(Optional.empty(), flap.stride(), "flapTime: a hovering dragon beats alike at both gaits");
    }

    // ------------------------------------------------------------------------------------
    // the axolotl
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("movingFactor holds the two ends of the animator Axolotl.<init> builds it on")
    void theMovingFactorHoldsItsAnimatorsEnds() {
        FieldInsnNode stored = AsmWalker.over(declared(AXOLOTL, "<init>")).ofType(FieldInsnNode.class)
            .first(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("movingAnimator"));
        assertNotNull(stored, "movingFactor: Axolotl.<init> stores its moving animator");
        AbstractInsnNode built = AsmWalker.previousReal(stored);
        assertTrue(built instanceof MethodInsnNode init && init.owner.equals(BINARY_ANIMATOR) && init.name.equals("<init>"),
            "movingFactor: the animator is a BinaryAnimator built in place");
        AbstractInsnNode easing = AsmWalker.previousReal(built);
        assertTrue(easing instanceof FieldInsnNode read && read.name.equals("IN_OUT_SINE"),
            "movingFactor: Axolotl.<init> eases its moving animator in and out on a sine");
        Integer length = AsmWalker.intLiteral(AsmWalker.previousReal(easing));
        assertEquals(10, length, "movingFactor: Axolotl.<init>'s animator length");

        // BinaryAnimator.tick holds its count between zero and the length, and getFactor eases the
        // count over the length, so the factor's two ends are the easing at 0 and at 1.
        MethodNode tick = declared(BINARY_ANIMATOR, "tick", "(Z)V");
        assertTrue(AsmWalker.over(tick).any(node -> node.getOpcode() == Opcodes.IF_ICMPGE),
            "movingFactor: BinaryAnimator.tick stops counting up at its length");
        assertTrue(AsmWalker.over(tick).any(node -> node.getOpcode() == Opcodes.IFLE),
            "movingFactor: BinaryAnimator.tick stops counting down at zero");
        MethodNode factor = declared(BINARY_ANIMATOR, "getFactor", "(F)F");
        assertTrue(AsmWalker.over(factor).any(node -> node.getOpcode() == Opcodes.FDIV
                && AsmWalker.previousReal(node) instanceof AbstractInsnNode widen && widen.getOpcode() == Opcodes.I2F
                && AsmWalker.previousReal(widen) instanceof FieldInsnNode read && read.name.equals("animationLength")),
            "movingFactor: BinaryAnimator.getFactor divides its count by its length");

        float still = (float) PoseOperator.EASE_IN_OUT_SINE.apply(0.0 / length);
        float swimming = (float) PoseOperator.EASE_IN_OUT_SINE.apply((double) length / length);
        StyleRoster.Clock moving = clock("movingFactor");
        // The easing's low end is a negative zero, which holds the field at the same nothing the
        // roster's zero does, so the ends are compared as numbers.
        assertEquals("hold", moving.idle().wave(), "movingFactor: the idle half holds");
        assertEquals(still, moving.idle().extent(), 0f, "movingFactor: the idle half holds the animator's low end");
        assertEquals(StyleRoster.Motion.hold(moving.idle().extent()), moving.idle(), "movingFactor: the idle half is a plain hold");
        assertEquals(Optional.of(StyleRoster.Motion.hold(swimming)), moving.stride(),
            "movingFactor: the stride half holds the animator's high end");
    }

    // ------------------------------------------------------------------------------------
    // the squids
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("tentacleAngle strokes at the rate Squid.<init> draws for the resting entity id, as far as Squid.aiStep reaches")
    void theTentaclesStrokeAtTheRestingIdsDraw() {
        MethodNode init = declared(SQUID, "<init>");
        MethodInsnNode seed = AsmWalker.over(init).ofType(MethodInsnNode.class).first(call -> call.name.equals("setSeed"));
        assertNotNull(seed, "tentacleAngle: Squid.<init> seeds its random");
        assertTrue(AsmWalker.previousReal(seed) instanceof AbstractInsnNode widen && widen.getOpcode() == Opcodes.I2L
                && AsmWalker.previousReal(widen) instanceof MethodInsnNode id && id.name.equals("getId"),
            "tentacleAngle: Squid.<init> seeds its random with its own entity id");
        MethodInsnNode draw = AsmWalker.after(seed).ofType(MethodInsnNode.class).first(call -> call.name.equals("nextFloat"));
        assertNotNull(draw, "tentacleAngle: Squid.<init> draws a float after seeding");
        Float numerator = AsmWalker.after(seed).real().mapNotNull(AsmWalker::floatLiteral).first();
        Float addend = AsmWalker.after(draw).real().mapNotNull(AsmWalker::floatLiteral).first();
        FieldInsnNode speed = AsmWalker.after(draw).ofType(FieldInsnNode.class)
            .first(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("tentacleSpeed"));
        assertNotNull(speed, "tentacleAngle: Squid.<init> stores the drawn speed");
        Float scale = AsmWalker.floatLiteral(AsmWalker.previousReal(AsmWalker.previousReal(speed)));
        assertNotNull(numerator, "tentacleAngle: Squid.<init>'s speed numerator");
        assertNotNull(addend, "tentacleAngle: Squid.<init>'s speed addend");
        assertNotNull(scale, "tentacleAngle: Squid.<init>'s speed scale");

        ClassNode legacy = cache.require(LEGACY_RANDOM, "the clock roster jar test");
        long multiplier = (Long) constant(legacy, "MULTIPLIER");
        long increment = (Long) constant(legacy, "INCREMENT");
        long mask = (Long) constant(legacy, "MODULUS_MASK");
        MethodNode nextFloat = declared(BIT_RANDOM, "nextFloat", "()F");
        Integer bits = AsmWalker.over(nextFloat).mapNotNull(AsmWalker::intLiteral).first();
        Float unit = AsmWalker.over(nextFloat).mapNotNull(AsmWalker::floatLiteral).first();
        assertNotNull(bits, "tentacleAngle: BitRandomSource.nextFloat's bit count");
        assertNotNull(unit, "tentacleAngle: BitRandomSource.nextFloat's unit");

        // The roster rests every subject at one entity id, so that id is the seed vanilla draws the
        // stroke's speed from - the squid's rate is that id's draw, chosen with the id.
        long id = StyleRoster.DECLARED_RESTS.get("entityId").longValue();
        long state = (id ^ multiplier) & mask;
        state = (state * multiplier + increment) & mask;
        float drawn = (int) (state >> (48 - bits)) * unit;
        float rate = numerator / (drawn + addend) * scale;

        FieldInsnNode stroke = AsmWalker.over(declared(SQUID, "aiStep", "()V")).ofType(FieldInsnNode.class)
            .first(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("tentacleAngle")
                && AsmWalker.previousReal(put).getOpcode() == Opcodes.FMUL);
        assertNotNull(stroke, "tentacleAngle: Squid.aiStep strokes the tentacles");
        AbstractInsnNode outer = AsmWalker.previousReal(stroke);
        Float quarter = AsmWalker.floatLiteral(AsmWalker.previousReal(outer));
        AbstractInsnNode inner = AsmWalker.previousReal(AsmWalker.previousReal(outer));
        Float turn = AsmWalker.floatLiteral(AsmWalker.previousReal(inner));
        assertTrue(AsmWalker.previousReal(AsmWalker.previousReal(inner)) instanceof MethodInsnNode sine && sine.name.equals("sin"),
            "tentacleAngle: Squid.aiStep scales the stroke's sine");
        assertNotNull(turn, "tentacleAngle: Squid.aiStep's inner scale");
        assertNotNull(quarter, "tentacleAngle: Squid.aiStep's outer scale");

        StyleRoster.Clock tentacles = clock("tentacleAngle");
        assertEquals(StyleRoster.Motion.stroke(turn * quarter, rate), tentacles.idle(),
            "tentacleAngle: the idle half strokes as far as Squid.aiStep reaches at entity id " + id + "'s rate");
        assertEquals(0x3decbdab, Float.floatToIntBits(tentacles.idle().rate()),
            "tentacleAngle: the roster's stroke rate bits");
        assertEquals(Optional.empty(), tentacles.stride(), "tentacleAngle: the stroke runs alike at both gaits");
    }

    // ------------------------------------------------------------------------------------
    // the shulker
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("peekAmount climbs, holds and falls as vanilla's shortest peek, with no wait between peeks")
    void theLidPeeksAsTheShortestPeek() {
        MethodNode start = declared(SHULKER_PEEK, "start", "()V");
        MethodInsnNode raise = AsmWalker.over(start).ofType(MethodInsnNode.class).first(call -> call.name.equals("setRawPeekAmount"));
        assertNotNull(raise, "peekAmount: ShulkerPeekGoal.start raises the lid");
        Integer raw = AsmWalker.intLiteral(AsmWalker.previousReal(raise));
        assertNotNull(raw, "peekAmount: the raw peek ShulkerPeekGoal.start raises to");
        AbstractInsnNode multiply = AsmWalker.over(start).first(node -> node.getOpcode() == Opcodes.IMUL);
        assertNotNull(multiply, "peekAmount: ShulkerPeekGoal.start scales its hold by a draw");
        Integer hold = AsmWalker.over(start).mapNotNull(AsmWalker::intLiteral).first();
        assertTrue(AsmWalker.before(multiply).real().limit(1).any(node -> node.getOpcode() == Opcodes.IADD),
            "peekAmount: the hold's multiplier is one plus a draw, so the shortest hold is the base alone");
        assertNotNull(hold, "peekAmount: the hold ShulkerPeekGoal.start counts from the raise");

        MethodNode update = declared(SHULKER, "updatePeekAmount", "()Z");
        AbstractInsnNode scaled = AsmWalker.over(update).first(node -> AsmWalker.floatLiteral(node) != null
            && AsmWalker.nextReal(node) instanceof AbstractInsnNode next && next.getOpcode() == Opcodes.FMUL);
        AbstractInsnNode stepped = AsmWalker.over(update).first(node -> AsmWalker.floatLiteral(node) != null
            && AsmWalker.nextReal(node) instanceof AbstractInsnNode next
            && (next.getOpcode() == Opcodes.FADD || next.getOpcode() == Opcodes.FSUB));
        assertNotNull(scaled, "peekAmount: Shulker.updatePeekAmount's raw-to-lid unit");
        assertNotNull(stepped, "peekAmount: Shulker.updatePeekAmount moves the lid by a step");
        float unit = AsmWalker.floatLiteral(scaled);
        float step = AsmWalker.floatLiteral(stepped);

        float extent = (float) raw * unit;
        int rise = (int) Math.ceil(extent / step);
        StyleRoster.Motion peek = clock("peekAmount").idle();
        assertEquals(extent, peek.extent(), "peekAmount: the lid rises to ShulkerPeekGoal.start's raw peek times its unit");
        assertEquals(rise, peek.rise(), "peekAmount: the lid climbs a step a tick");
        assertEquals(rise, peek.fall(), "peekAmount: the lid falls a step a tick");
        assertEquals(hold - rise, peek.dwell(), "peekAmount: the shortest hold counts from the raise, so the lid sits for it less the climb");
        assertEquals(0, peek.gap(), "peekAmount: the gap of 0 is chosen - vanilla waits a random interval between peeks, "
            + "and the roster loops the shortest peek with no wait");
        assertEquals(StyleRoster.Motion.trapezoid(extent, rise, hold - rise, rise, 0), peek,
            "peekAmount: the idle half is the shortest peek");
        assertEquals(Optional.empty(), clock("peekAmount").stride(), "peekAmount: the lid peeks alike at both gaits");
    }

    // ------------------------------------------------------------------------------------
    // the warden's heart
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("heartAnimation flashes for the beat Warden.tick sets and falls a tick at a time, once a delay")
    void theHeartBeatsOnceADelay() {
        Integer delay = AsmWalker.over(declared(WARDEN, "getHeartBeatDelay", "()I")).mapNotNull(AsmWalker::intLiteral).first();
        assertNotNull(delay, "heartAnimation: Warden.getHeartBeatDelay's calm delay");
        MethodNode tick = declared(WARDEN, "tick", "()V");
        List<FieldInsnNode> stores = AsmWalker.over(tick).ofType(FieldInsnNode.class)
            .where(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("heartAnimation"))
            .toList();
        Integer beat = stores.stream().map(put -> AsmWalker.intLiteral(AsmWalker.previousReal(put)))
            .filter(Objects::nonNull).findFirst().orElse(null);
        assertNotNull(beat, "heartAnimation: the beat Warden.tick sets");
        assertTrue(stores.stream().anyMatch(put -> AsmWalker.previousReal(put) instanceof AbstractInsnNode subtract
                && subtract.getOpcode() == Opcodes.ISUB && Integer.valueOf(1).equals(AsmWalker.intLiteral(AsmWalker.previousReal(subtract)))),
            "heartAnimation: Warden.tick counts the beat down a tick at a time");
        Float divisor = AsmWalker.over(declared(WARDEN, "getHeartAnimation", "(F)F")).mapNotNull(AsmWalker::floatLiteral).first();
        assertNotNull(divisor, "heartAnimation: Warden.getHeartAnimation's divisor");

        StyleRoster.Clock heart = clock("heartAnimation");
        assertEquals(StyleRoster.Motion.trapezoid(beat / divisor, 0, 0, beat, delay - beat), heart.idle(),
            "heartAnimation: the idle half flashes at the beat over the divisor and falls back over the beat, once a delay");
        assertEquals(Optional.empty(), heart.stride(), "heartAnimation: the heart beats alike at both gaits");
    }

    // ------------------------------------------------------------------------------------
    // the iron golem's offer
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("offerFlowerTick counts down from the start IronGolem.handleEntityEvent sets on event 11, one a tick in aiStep")
    void theOfferCountsDownFromItsEvent() {
        MethodNode event = declared(IRON_GOLEM, "handleEntityEvent", "(B)V");
        FieldInsnNode offer = AsmWalker.over(event).ofType(FieldInsnNode.class)
            .first(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("offerFlowerTick")
                && AsmWalker.intLiteral(AsmWalker.previousReal(put)) instanceof Integer start && start > 0);
        assertNotNull(offer, "offerFlowerTick: IronGolem.handleEntityEvent starts the offer at a literal count");
        Integer start = AsmWalker.intLiteral(AsmWalker.previousReal(offer));
        AbstractInsnNode branch = AsmWalker.before(offer).real().first(node -> node.getOpcode() == Opcodes.IF_ICMPNE);
        assertNotNull(branch, "offerFlowerTick: the start is stored behind a test of the event id");
        assertEquals(11, AsmWalker.intLiteral(AsmWalker.previousReal(branch)),
            "offerFlowerTick: IronGolem.handleEntityEvent starts the offer on event 11");

        assertTrue(AsmWalker.over(declared(IRON_GOLEM, "aiStep", "()V")).ofType(FieldInsnNode.class).any(put ->
                put.getOpcode() == Opcodes.PUTFIELD && put.name.equals("offerFlowerTick")
                    && AsmWalker.previousReal(put) instanceof AbstractInsnNode subtract && subtract.getOpcode() == Opcodes.ISUB
                    && Integer.valueOf(1).equals(AsmWalker.intLiteral(AsmWalker.previousReal(subtract)))),
            "offerFlowerTick: IronGolem.aiStep counts the offer down a tick at a time");

        assertEquals(List.of(new StyleRoster.Countdown("offerFlowerTick", start)), StyleRoster.COUNTDOWNS,
            "offerFlowerTick: the roster's one countdown starts at IronGolem.handleEntityEvent's count");
    }

    // ------------------------------------------------------------------------------------
    // one keyspace
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("each clock field is declared by exactly one render state the fold does not derive it on")
    void everyClockIsOneRenderStatesOwn() throws ReflectiveOperationException {
        Set<String> fields = new LinkedHashSet<>();
        for (StyleRoster.Clock clock : StyleRoster.CLOCKS) fields.add(clock.field());
        Map<?, ?> derived = derived(fields);

        List<String> shared = new ArrayList<>();
        for (String field : fields) {
            Set<String> owners = new TreeSet<>();
            for (String entry : cache.list(RENDERER_ROOT, RENDER_STATE_ENTRY)) {
                String owner = entry.substring(0, entry.length() - ".class".length());
                ClassNode state = cache.load(owner);
                if (state == null) continue;
                boolean declares = state.fields.stream()
                    .anyMatch(declared -> declared.name.equals(field) && (declared.access & Opcodes.ACC_STATIC) == 0);
                boolean derives = derived.get(owner) instanceof Map<?, ?> figures && figures.containsKey(field);
                if (declares && !derives) owners.add(ClassKit.simpleName(owner));
            }
            if (owners.size() != 1) shared.add("'" + field + "' is declared by " + owners);
        }
        assertEquals(List.of(), shared, "every clock field names one render state's own figure");
    }

    @Test
    @DisplayName("the roster holds the seven clocks, in order")
    void theRosterHoldsTheSevenClocks() {
        assertEquals(List.of("tailAnimation", "spikesAnimation", "flapTime", "movingFactor", "tentacleAngle",
                "peekAmount", "heartAnimation"),
            StyleRoster.CLOCKS.stream().map(StyleRoster.Clock::field).toList(), "the clock roster, in roster order");
    }

    // ------------------------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------------------------

    /**
     * One eased step - {@code field += (target - field) * rate} - as {@code javac} writes it.
     *
     * @param target the level the field eases toward
     * @param rate the share of the distance it closes a tick
     * @param store the store closing the step
     */
    private record Ease(float target, float rate, @NotNull FieldInsnNode store) {}

    /**
     * Every eased step one method takes a field through, in instruction order -
     * {@code <target> aload_0 getfield f fsub <rate> fmul fadd putfield f}.
     */
    private static @NotNull List<Ease> eases(@NotNull MethodNode method, @NotNull String field) {
        List<Ease> out = new ArrayList<>();
        AsmWalker.over(method).ofType(FieldInsnNode.class)
            .where(put -> put.getOpcode() == Opcodes.PUTFIELD && put.name.equals(field))
            .forEach(put -> {
                AbstractInsnNode add = AsmWalker.previousReal(put);
                AbstractInsnNode scale = AsmWalker.previousReal(add);
                AbstractInsnNode rate = AsmWalker.previousReal(scale);
                AbstractInsnNode subtract = AsmWalker.previousReal(rate);
                AbstractInsnNode read = AsmWalker.previousReal(subtract);
                AbstractInsnNode self = AsmWalker.previousReal(read);
                AbstractInsnNode target = AsmWalker.previousReal(self);
                if (add == null || add.getOpcode() != Opcodes.FADD || scale == null || scale.getOpcode() != Opcodes.FMUL
                    || subtract == null || subtract.getOpcode() != Opcodes.FSUB
                    || !(read instanceof FieldInsnNode got) || !got.name.equals(field)
                    || !(self instanceof VarInsnNode load) || load.var != 0) return;
                Float level = AsmWalker.floatLiteral(target);
                Float share = AsmWalker.floatLiteral(rate);
                if (level != null && share != null) out.add(new Ease(level, share, (FieldInsnNode) put));
            });
        return out;
    }

    /** The figures each render state's renderer derives from a driven one, as the fold reads them. */
    private static @NotNull Map<?, ?> derived(@NotNull Set<String> fields) throws ReflectiveOperationException {
        Method derived = Class.forName(INPUT_DEFAULTS).getDeclaredMethod("derived",
            ClassNodeCache.class, Set.class, Set.class, Diagnostics.class);
        derived.setAccessible(true);
        return assertInstanceOf(Map.class, derived.invoke(null, cache, fields, StyleRoster.DRIVEN,
            Diagnostics.root("entityModels", Diagnostics.Output.NONE, null)));
    }

    /** The roster's clock on one field. */
    private static @NotNull StyleRoster.Clock clock(@NotNull String field) {
        return StyleRoster.CLOCKS.stream()
            .filter(clock -> clock.field().equals(field))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the roster drives no clock on '" + field + "'"));
    }

    /** A compile-time constant a class declares. */
    private static @NotNull Object constant(@NotNull ClassNode owner, @NotNull String name) {
        FieldNode field = owner.fields.stream().filter(declared -> declared.name.equals(name)).findFirst().orElse(null);
        assertNotNull(field, owner.name + " declares " + name);
        assertNotNull(field.value, owner.name + "." + name + " is a constant");
        return field.value;
    }

    private static @NotNull MethodNode declared(@NotNull String owner, @NotNull String name) {
        MethodNode method = ClassKit.findMethod(cache.require(owner, "the clock roster jar test"), name);
        assertNotNull(method, owner + " declares " + name);
        return method;
    }

    private static @NotNull MethodNode declared(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        MethodNode method = ClassKit.findMethod(cache.require(owner, "the clock roster jar test"), name, desc);
        assertNotNull(method, owner + " declares " + name + desc);
        return method;
    }

}
