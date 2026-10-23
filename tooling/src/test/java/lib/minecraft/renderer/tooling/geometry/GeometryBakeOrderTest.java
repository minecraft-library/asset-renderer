package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The order the geometry walk replays each factory's parts into, held against the order vanilla's own
 * factories bake them in.
 *
 * <p>Each expected order is a level vanilla 26.1 builds, read off the {@code PartDefinition} its
 * factory returns on the JDK 25 it runs on. They are the levels where one fresh hash map per parent
 * filled in declaration order is not vanilla's order, because vanilla filled the level with a copy:
 * the root a whole-mesh scaling rebuilds - the giant's, the villager's, the witch's, the happy ghast
 * harness's, which scales inside its factory - a part a factory replaces, as the nautilus armour's
 * {@code root} and the witch's {@code hat}, and the levels a retain surgery empties, as the warden's
 * passes do, and the armour sets vanilla registers through a scaling, which take a shell of their own.
 * The unscaled armour mesh is the counterpart, a root vanilla builds by {@code put} alone.
 *
 * <p>The shipped table is held to the same replay, so a table written in any other order fails here as
 * well as in the flow's own byte diff.
 *
 * <p>The parse runs against the cached client jar and assumes away where nothing has cached one, as
 * every walk of this suite does.
 */
@DisplayName("the geometry walk replays each factory's parts into vanilla's draw order")
class GeometryBakeOrderTest {

    private static final @NotNull String HUMANOID = "net/minecraft/client/model/HumanoidModel";
    private static final @NotNull String WARDEN = "net/minecraft/client/model/monster/warden/WardenModel";

    /** The whole-mesh scales vanilla registers armour sets through: the giant's, the husk's and the wither skeleton's. */
    private static final float @NotNull [] SCALED_SHELLS = {6f, 1.0625f, 1.2f};

    /** The shipped table, relative to the renderer root every Test task runs at. */
    private static final @NotNull Path SHIPPED_GEOMETRY =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    private static ClassNodeCache cache;

    @BeforeAll
    static void open() {
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assumeTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar
            + "' - run './gradlew generateTables' or any parity capture to cache one");
        cache = ClassNodeCache.open(jar);
    }

    @AfterAll
    static void close() {
        cache.close();
    }

    @Test
    @DisplayName("a whole-mesh scaling copies the root it rebuilds")
    void aScalingCopiesTheRoot() {
        Parsed giant = parse(GeometryRequest.body(HUMANOID, "createMesh", "minecraft:giant", 64, 64, null, 6f));
        assertEquals(List.of("head", "right_arm", "left_leg", "body", "left_arm", "right_leg"), giant.level(null), "giant root");

        Parsed villager = parse(GeometryRequest.body("net/minecraft/client/model/npc/VillagerModel", "createBodyModel",
            "minecraft:villager", 64, 64, null, 0.9375f));
        assertEquals(List.of("head", "arms", "left_leg", "body", "right_leg"), villager.level(null), "villager root");

        Parsed harness = parse(GeometryRequest.equipment("net/minecraft/client/model/animal/ghast/HappyGhastHarnessModel",
            "createHarnessLayer", "(Z)Lnet/minecraft/client/model/geom/builders/LayerDefinition;", "minecraft:happy_ghast",
            null, null, null, GeometryRequest.NO_GROW, 1f));
        assertEquals(List.of("harness", "goggles"), harness.level(null), "harness root, scaled inside its factory");
    }

    @Test
    @DisplayName("an unscaled root is put in a sixteen-slot table")
    void anUnscaledRootIsPut() {
        Parsed armor = parse(GeometryRequest.armor(HUMANOID, "createBaseArmorMesh", "minecraft:zombie", 64, 32, null, 1f));
        assertEquals(List.of("head", "right_arm", "left_leg", "left_arm", "right_leg", "body"), armor.level(null), "armour root");
        assertEquals(List.of("head", "hat", "right_arm", "left_leg", "left_arm", "right_leg", "body"), armor.order(),
            "a part draws before the parts below it, depth first");
    }

    @Test
    @DisplayName("a scaled armour set's shell copies its root, as vanilla's scaled slot meshes draw it")
    void aScaledShellCopiesItsRoot() {
        // Vanilla prunes each slot mesh of the base armour mesh in place and then scales it, so every
        // slot of the giant's, the husk's and the wither skeleton's sets draws its parts in the order
        // of one copy of the unscaled root.
        for (float scale : SCALED_SHELLS) {
            Parsed shell = parse(GeometryRequest.armor(HUMANOID, "createBaseArmorMesh", "minecraft:giant", 64, 32, null, scale));
            assertEquals(List.of("head", "right_arm", "left_leg", "body", "left_arm", "right_leg"), shell.level(null),
                "armour root scaled by " + scale);
        }
    }

    @Test
    @DisplayName("a replaced part copies the children it held")
    void aReplacedPartCopiesItsChildren() {
        Parsed witch = parse(GeometryRequest.body("net/minecraft/client/model/monster/witch/WitchModel", "createBodyLayer",
            "minecraft:witch", null, null, null, 0.9375f));
        assertEquals(List.of("head", "arms", "left_leg", "body", "right_leg"), witch.level(null), "witch root");
        assertEquals(List.of("hat_rim", "hat2"), witch.level("hat"), "the witch's hat, replaced on the villager's mesh");

        Parsed nautilus = parse(GeometryRequest.body("net/minecraft/client/model/animal/nautilus/NautilusArmorModel",
            "createBodyLayer", "minecraft:nautilus", null, null, null, 1f));
        assertEquals(List.of("body", "shell"), nautilus.level("root"), "the nautilus armour's root, replaced on the body mesh");
    }

    @Test
    @DisplayName("a retain surgery copies each level it empties a part of")
    void aRetainSurgeryCopiesTheLevelsItEmpties() {
        Parsed spots = parse(GeometryRequest.overlay(WARDEN, "createPulsatingSpotsLayer", "minecraft:warden", null, null,
            GeometryRequest.NO_GROW));
        assertEquals(List.of("left_leg", "right_leg", "body"), spots.level("bone"), "the body layer the spots derive from");
        spots.tree().retainExactParts(Set.of("body", "head", "left_arm", "left_leg", "right_arm", "right_leg"));
        assertEquals(List.of("left_leg", "body", "right_leg"), spots.level("bone"), "the spots' bone level");

        Parsed glow = parse(GeometryRequest.overlay(WARDEN, "createBioluminescentLayer", "minecraft:warden", null, null,
            GeometryRequest.NO_GROW));
        glow.tree().retainExactParts(Set.of("head", "left_arm", "right_arm", "left_leg", "right_leg"));
        assertEquals(List.of("left_leg", "body", "right_leg"), glow.level("bone"), "the bioluminescent bone level");
        assertEquals(List.of("head", "right_arm", "right_ribcage", "left_arm", "left_ribcage"), glow.level("body"),
            "the bioluminescent body level");
    }

    @Test
    @DisplayName("a part a factory's own surgery drops still holds its place")
    void aDroppedPartHoldsItsPlace() {
        // The breeze retains its head and rods under a body the parser drops, so the head and rods
        // hang from a parent the bones do not carry, and the tree still orders them under it.
        Parsed breeze = parse(GeometryRequest.body("net/minecraft/client/model/monster/breeze/BreezeModel", "createBodyLayer",
            "minecraft:breeze", null, null, null, 1f));
        List<String> carried = List.copyOf(breeze.bones().keys().sorted().toList());
        List<String> placed = breeze.order().stream().filter(breeze.bones()::has).sorted().toList();
        assertEquals(carried, placed, "every bone the parse carries has its place in the tree");
        assertEquals(List.of("head", "rods"), breeze.level("body"), "the body the parser drops still orders its children");
    }

    @Test
    @DisplayName("the shipped table holds each mesh's bones in the order its part tree replays")
    void theShippedTableIsInTheReplayedOrder() {
        JsonTree shipped = JsonTree.parse(read(SHIPPED_GEOMETRY)).getObject("geometries");
        assertShippedInOrder(shipped, "HumanoidModel#createMesh@scaled=6.0",
            parse(GeometryRequest.body(HUMANOID, "createMesh", "minecraft:giant", 64, 64, null, 6f)));
        assertShippedInOrder(shipped, "HumanoidModel#createBaseArmorMesh",
            parse(GeometryRequest.armor(HUMANOID, "createBaseArmorMesh", "minecraft:zombie", 64, 32, null, 1f)));
        assertShippedInOrder(shipped, "WitchModel#createBodyLayer@scaled=0.9375",
            parse(GeometryRequest.body("net/minecraft/client/model/monster/witch/WitchModel", "createBodyLayer",
                "minecraft:witch", null, null, null, 0.9375f)));
        assertShippedInOrder(shipped, "NautilusArmorModel#createBodyLayer",
            parse(GeometryRequest.body("net/minecraft/client/model/animal/nautilus/NautilusArmorModel",
                "createBodyLayer", "minecraft:nautilus", null, null, null, 1f)));
        assertShippedInOrder(shipped, "BreezeModel#createBodyLayer",
            parse(GeometryRequest.body("net/minecraft/client/model/monster/breeze/BreezeModel", "createBodyLayer",
                "minecraft:breeze", null, null, null, 1f)));

        for (float scale : SCALED_SHELLS) {
            assertShippedInOrder(shipped, "HumanoidModel#createBaseArmorMesh@scaled=" + scale,
                parse(GeometryRequest.armor(HUMANOID, "createBaseArmorMesh", "minecraft:giant", 64, 32, null, scale)));
        }

        // A pass whose subset is derived after the parse ships in the order the derivation replays.
        Parsed glow = parse(GeometryRequest.overlay(WARDEN, "createBioluminescentLayer", "minecraft:warden", null, null,
            GeometryRequest.NO_GROW));
        glow.tree().retainExactParts(Set.of("head", "left_arm", "right_arm", "left_leg", "right_leg"));
        assertShippedInOrder(shipped, "WardenModel#createBioluminescentLayer", glow);
    }

    /** Asserts one shipped entry holds its bones in the replayed order, over the bones it carries. */
    private static void assertShippedInOrder(@NotNull JsonTree shipped, @NotNull String key, @NotNull Parsed parsed) {
        JsonTree bones = shipped.find(key).flatMap(entry -> entry.find("bones")).orElseThrow();
        List<String> written = List.copyOf(bones.keys().toList());
        List<String> replayed = parsed.order().stream().filter(bones::has).toList();
        assertEquals(replayed, written, key + " bone order");
    }

    /** Reads one file the renderer root holds, failing loudly where it is absent. */
    private static byte @NotNull [] read(@NotNull Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new UncheckedIOException("no shipped table at " + path.toAbsolutePath(), error);
        }
    }

    /** Parses one request, failing loudly where the walk refuses its part tree. */
    private static @NotNull Parsed parse(@NotNull GeometryRequest request) {
        JsonTree parsed = GeometryParser.parse(cache, request, Diagnostics.root("geometryBakeOrderTest", Diagnostics.Output.NONE, null));
        assertNotNull(parsed, request.factoryMethod() + " parse returned null");
        PartTree tree = PartTree.of(parsed);
        assertEquals(Optional.empty(), tree.refusal(), request.factoryMethod() + " part tree");
        return new Parsed(parsed.find("bones").orElseThrow(), tree);
    }

    /**
     * One parse: its bones and its part tree.
     *
     * @param bones the parsed bones
     * @param tree the part tree the walk replayed
     */
    private record Parsed(@NotNull JsonTree bones, @NotNull PartTree tree) {

        /** Every part, in the tree's draw order. */
        @NotNull List<String> order() {
            return this.tree.order();
        }

        /** The children of one part in draw order, read off the parsed parent links, the root's for {@code null}. */
        @NotNull List<String> level(@Nullable String parent) {
            return this.tree.order()
                .stream()
                .filter(name -> this.bones.find(name)
                    .map(bone -> Objects.equals(parent, bone.findString("parent").orElse(null)))
                    .orElse(false))
                .toList();
        }

    }

}
