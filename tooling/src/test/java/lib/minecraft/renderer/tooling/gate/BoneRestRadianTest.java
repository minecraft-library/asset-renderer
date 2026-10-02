package lib.minecraft.renderer.tooling.gate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rest radians a bone loses to the geometry table's degrees, held to the set the client jar
 * measures today.
 *
 * <p>Vanilla's {@code ModelPart} holds a bone's rest rotation as the float radian its factory hands
 * {@code PartPose}. The table stores the float degree the parser converts that radian to, and the
 * renderer converts the degree back through {@link EulerRotation}, so a radian no float degree
 * converts back onto rests one ULP off vanilla's. Every non-zero rest angle the table carries is
 * converted the renderer's way and classified against the float literals its own factory spells -
 * the method the geometry's {@code source} names and every model-package method, lambda or static
 * initialiser that reaches, transitively:
 * <ul>
 *   <li><b>exact</b> - the converted radian is one of those literals, compared by magnitude because a
 *   negative angle may negate a positive literal</li>
 *   <li><b>lossy</b> - it is not, and the float on either side of it is</li>
 *   <li><b>computed</b> - neither, because vanilla evaluates the radian at run time rather than
 *   spelling it, as the squid's tentacle loop and the guardian's spike tables do</li>
 * </ul>
 *
 * <p>Exact is a heuristic rather than a proof. The check never sees the radian vanilla evaluates, only
 * whether the converted one equals some literal the factory spells, so a computed angle that lands on
 * an unrelated literal reads as exact; only the parser's own conversion site, where the evaluated
 * radian is still in hand, could decide every angle. Lossy and computed are pinned whole, each by
 * (class, bone, degree) rather than by geometry key, so a derived key minted or dropped moves nothing
 * here while a version bump that adds, drops or moves a loss fails. Two factories of one class
 * answering one rest differently keep the less decided answer, so a factory that still spells the
 * literal cannot hide a loss in its sibling.
 *
 * <p>Only a bone's rotation is read. A cube's carries a renderer's constant facing turn, which no
 * factory spells.
 *
 * <p>Reads the cached client jar, and assumes away where nothing has cached one.
 */
@DisplayName("bone rest radians the geometry table's degrees lose, against the literals each factory spells")
class BoneRestRadianTest {

    /** The shipped table, relative to the renderer root every Test task runs at. */
    private static final @NotNull Path SHIPPED_GEOMETRY =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /** The package a factory's reach is followed through, as an internal-name prefix. */
    private static final @NotNull String MODEL_PACKAGE = "net/minecraft/client/model/";

    /** A static initialiser's descriptor. */
    private static final @NotNull String CLINIT_DESC = "()V";

    /** The rule a moved pin re-opens, named so a red after a version bump reads as a re-measurement. */
    private static final @NotNull String RULE = "the RENDERER-RULES.md bullet 'Do not carry a bone's rotation in radians'";

    /** The order the pins and the measurement are compared in. */
    private static final @NotNull Comparator<Rest> ORDER = Comparator.comparing(Rest::owner)
        .thenComparing(Rest::bone)
        .thenComparingDouble(Rest::degrees);

    /** The rest angles whose radian the stored degree loses by one ULP. */
    private static final @NotNull List<Rest> LOSSY = List.of(
        rest("animal/armadillo/AdultArmadilloModel", "left_ear_cube", 4.1138372f),
        rest("animal/armadillo/AdultArmadilloModel", "right_ear_cube", -4.1138372f),
        rest("monster/wither/WitherBossModel", "tail", 47.7f));

    /**
     * The rest angles whose radian no literal their factory spells decides - the squid's tentacles,
     * turned by a loop in double, and the guardian's spikes, each pi times an entry of a static table.
     */
    private static final @NotNull List<Rest> COMPUTED = List.of(
        rest("animal/squid/BabySquidModel", "tentacle0", 90f),
        rest("animal/squid/BabySquidModel", "tentacle1", 45f),
        rest("animal/squid/BabySquidModel", "tentacle3", -45f),
        rest("animal/squid/BabySquidModel", "tentacle4", -90f),
        rest("animal/squid/BabySquidModel", "tentacle5", -135f),
        rest("animal/squid/BabySquidModel", "tentacle6", -180f),
        rest("animal/squid/BabySquidModel", "tentacle7", -225f),
        rest("animal/squid/SquidModel", "tentacle0", 90f),
        rest("animal/squid/SquidModel", "tentacle1", 45f),
        rest("animal/squid/SquidModel", "tentacle3", -45f),
        rest("animal/squid/SquidModel", "tentacle4", -90f),
        rest("animal/squid/SquidModel", "tentacle5", -135f),
        rest("animal/squid/SquidModel", "tentacle6", -180f),
        rest("animal/squid/SquidModel", "tentacle7", -225f),
        rest("monster/guardian/GuardianModel", "spike0", 315.00003f),
        rest("monster/guardian/GuardianModel", "spike1", 45f),
        rest("monster/guardian/GuardianModel", "spike2", 45f),
        rest("monster/guardian/GuardianModel", "spike3", 315.00003f),
        rest("monster/guardian/GuardianModel", "spike4", 45f),
        rest("monster/guardian/GuardianModel", "spike4", 90f),
        rest("monster/guardian/GuardianModel", "spike5", 90f),
        rest("monster/guardian/GuardianModel", "spike5", 315.00003f),
        rest("monster/guardian/GuardianModel", "spike6", 90f),
        rest("monster/guardian/GuardianModel", "spike6", 225.00002f),
        rest("monster/guardian/GuardianModel", "spike7", 90f),
        rest("monster/guardian/GuardianModel", "spike7", 135f),
        rest("monster/guardian/GuardianModel", "spike8", 225.00002f),
        rest("monster/guardian/GuardianModel", "spike9", 135f),
        rest("monster/guardian/GuardianModel", "spike10", 135f),
        rest("monster/guardian/GuardianModel", "spike11", 225.00002f));

    private static ClassNodeCache cache;
    private static Map<Kind, List<Rest>> measured;

    @BeforeAll
    static void open() throws IOException {
        // Gated rather than acquired: this suite is the fast one, so a jar nothing has cached yet
        // abandons the class instead of opening a socket. ToolingJarGuardTest is what says so loudly.
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assumeTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar
            + "' - run './gradlew generateTables' or any parity capture to cache one");
        cache = ClassNodeCache.open(jar);
        // Read off disk rather than off the classpath: this build ships no resources and never
        // processes the renderer's, and every Test task runs at the renderer root.
        try (Reader reader = Files.newBufferedReader(SHIPPED_GEOMETRY, StandardCharsets.UTF_8)) {
            measured = classify(JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("geometries"));
        }
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("the rest radians the stored degrees lose are the wither's tail and the adult armadillo's ear cubes")
    void theLossyRestsAreThePinnedSet() {
        assertEquals(LOSSY.stream().sorted(ORDER).toList(), measured.get(Kind.LOSSY), () -> "The bone rest"
            + " radians the shipped degrees lose by one ULP are not the set " + RULE + " records. After a"
            + " version bump this asks for that measurement to be taken again - force these radians,"
            + " re-render, compare - and for the bullet and this pin to move together; it is not a defect"
            + " on its own");
    }

    @Test
    @DisplayName("the rest radians no factory literal decides are the squid's tentacles and the guardian's spikes")
    void theComputedRestsAreThePinnedSet() {
        assertEquals(COMPUTED.stream().sorted(ORDER).toList(), measured.get(Kind.COMPUTED), () -> "The bone"
            + " rest radians no literal of their own factory decides are not the pinned set. Read a new"
            + " one's factory and evaluate its radian: a stored degree that does not convert back onto it"
            + " is a loss " + RULE + " has to record");
    }

    // ------------------------------------------------------------------------------------

    /**
     * Classifies every non-zero bone rest angle the table carries.
     *
     * @param geometries the table's {@code geometries} object
     * @return each kind's rests, sorted
     */
    private static @NotNull Map<Kind, List<Rest>> classify(@NotNull JsonObject geometries) {
        Map<String, Set<Float>> literalsByFactory = new HashMap<>();
        Map<Rest, Kind> kinds = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : geometries.entrySet()) {
            JsonObject geometry = entry.getValue().getAsJsonObject();
            JsonObject source = geometry.getAsJsonObject("source");
            String owner = source.get("class").getAsString();
            String factory = source.get("method").getAsString();
            Set<Float> literals = literalsByFactory.computeIfAbsent(owner + "#" + factory,
                key -> literalsReachedFrom(owner, factory));
            for (Map.Entry<String, JsonElement> bone : geometry.getAsJsonObject("bones").entrySet()) {
                JsonArray stored = bone.getValue().getAsJsonObject().getAsJsonArray("rotation");
                if (stored == null) continue;
                EulerRotation rotation = new EulerRotation(
                    stored.get(0).getAsFloat(), stored.get(1).getAsFloat(), stored.get(2).getAsFloat());
                float[] degrees = { rotation.pitch(), rotation.yaw(), rotation.roll() };
                float[] radians = { rotation.pitchRadians(), rotation.yawRadians(), rotation.rollRadians() };
                for (int axis = 0; axis < degrees.length; axis++) {
                    if (degrees[axis] == 0f) continue;
                    // two factories of one class answering one rest differently keep the less decided answer
                    kinds.merge(new Rest(owner, bone.getKey(), degrees[axis]), kindOf(radians[axis], literals),
                        BinaryOperator.maxBy(Comparator.naturalOrder()));
                }
            }
        }
        Map<Kind, List<Rest>> byKind = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            byKind.put(kind, kinds.entrySet().stream()
                .filter(classified -> classified.getValue() == kind)
                .map(Map.Entry::getKey)
                .sorted(ORDER)
                .toList());
        }
        return byKind;
    }

    /**
     * Classifies one converted rest radian against the literals its factory spells.
     *
     * @param radians the radian the renderer converts the stored degree to
     * @param literals the magnitudes of every float literal the factory reaches
     * @return the kind of rest it is
     */
    private static @NotNull Kind kindOf(float radians, @NotNull Set<Float> literals) {
        float magnitude = Math.abs(radians);
        if (literals.contains(magnitude)) return Kind.EXACT;
        if (literals.contains(Math.nextUp(magnitude)) || literals.contains(Math.nextDown(magnitude))) return Kind.LOSSY;
        return Kind.COMPUTED;
    }

    /**
     * Collects the magnitude of every float literal a factory spells, following each call, lambda and
     * static read into the model package so a bone built by a shared helper or a mesh transformer is
     * read against that code's literals.
     *
     * @param owner the internal name of the factory's class
     * @param factory the factory method's name, every overload of which is read
     * @return the literal magnitudes reached
     */
    private static @NotNull Set<Float> literalsReachedFrom(@NotNull String owner, @NotNull String factory) {
        Set<String> visited = new HashSet<>();
        Deque<MethodNode> pending = new ArrayDeque<>();
        for (MethodNode method : cache.require(owner, "a shipped geometry's source class").methods)
            if (method.name.equals(factory) && visited.add(owner + "." + method.name + method.desc))
                pending.add(method);
        assertFalse(pending.isEmpty(), () -> "the shipped table names factory " + owner + "#" + factory
            + ", which the cached client jar does not declare");

        Set<Float> literals = new HashSet<>();
        while (!pending.isEmpty()) {
            MethodNode method = pending.poll();
            for (Float literal : AsmWalker.over(method).mapNotNull(AsmWalker::floatLiteral).toList())
                literals.add(Math.abs(literal));
            for (MethodInsnNode call : AsmWalker.over(method).ofType(MethodInsnNode.class).toList())
                follow(call.owner, call.name, call.desc, visited, pending);
            // a static read runs its class's initialiser, which is where a mesh transformer held in a
            // static field is bound to the method that adds the bones
            for (FieldInsnNode read : AsmWalker.over(method).opcode(Opcodes.GETSTATIC).ofType(FieldInsnNode.class).toList())
                follow(read.owner, ClassKit.CLINIT, CLINIT_DESC, visited, pending);
            for (InvokeDynamicInsnNode indy : AsmWalker.over(method).ofType(InvokeDynamicInsnNode.class).toList()) {
                Handle lambda = AsmWalker.extractLambdaHandle(indy);
                if (lambda != null) follow(lambda.getOwner(), lambda.getName(), lambda.getDesc(), visited, pending);
            }
        }
        return literals;
    }

    /**
     * Queues the method a call resolves to, walking up the named class's superclasses for an inherited
     * one, when it is declared in the model package and not yet read.
     *
     * @param owner the internal name of the class the call names
     * @param name the method's name
     * @param desc the method's descriptor
     * @param visited the methods already queued, keyed by owner, name and descriptor
     * @param pending the methods still to read
     */
    private static void follow(
        @NotNull String owner, @NotNull String name, @NotNull String desc,
        @NotNull Set<String> visited, @NotNull Deque<MethodNode> pending) {
        String current = owner;
        while (current != null && current.startsWith(MODEL_PACKAGE)) {
            ClassNode declaring = cache.load(current);
            if (declaring == null) return;
            for (MethodNode method : declaring.methods) {
                if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
                if (visited.add(current + "." + name + desc)) pending.add(method);
                return;
            }
            current = declaring.superName;
        }
    }

    /**
     * Declares one pinned rest.
     *
     * @param owner the factory's class, relative to the model package
     * @param bone the bone's name
     * @param degrees the stored angle, in degrees
     * @return the rest
     */
    private static @NotNull Rest rest(@NotNull String owner, @NotNull String bone, float degrees) {
        return new Rest(MODEL_PACKAGE + owner, bone, degrees);
    }

    /**
     * What the literals a factory spells say about one converted rest radian, declared from the most
     * decided answer to the least.
     */
    private enum Kind {

        /** The radian is a literal the factory spells. */
        EXACT,

        /** The radian is one ULP off a literal the factory spells. */
        LOSSY,

        /** No literal the factory spells decides the radian. */
        COMPUTED

    }

    /**
     * One bone rest angle, keyed the way the pins are.
     *
     * @param owner the internal name of the class whose factory builds the bone
     * @param bone the bone's name
     * @param degrees the stored angle, in degrees
     */
    private record Rest(@NotNull String owner, @NotNull String bone, float degrees) {

        @Override
        public @NotNull String toString() {
            String spelled = this.owner.startsWith(MODEL_PACKAGE) ? this.owner.substring(MODEL_PACKAGE.length()) : this.owner;
            return spelled + " " + this.bone + " " + this.degrees;
        }

    }

}
