package lib.minecraft.renderer.content.index;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way a bone toggle points, derived rather than declared beside it.
 *
 * <p>A toggle names the state a subject is NOT resting in - a hornless goat, a turtle carrying an
 * egg - so which bones it shows and which it hides is decided by what the subject rests as. That is
 * the mesh's own answer: a bone stands at a rest visibility and names the selection that flips it,
 * and reading the direction off the bone that renders is what keeps it from drifting.
 *
 * <p>It HAD drifted, on whether a bee rests with its sting, which is why there is one answer left.
 * The model table names no direction because it no longer names a toggle at all; what a subject
 * rests without is written where it renders.
 *
 * <p>A bone nothing can ever draw is absent from the mesh rather than standing hidden in it, so
 * "rests without" reads two ways here on purpose: an evoker's arms are gone, and an armour stand's
 * are present and not drawn, because a selection can ask for the second and nothing can ask for the
 * first.
 */
@DisplayName("a bone toggle's resting side")
class BoneToggleRestTest {

    /** Where the shipped declaration this is compared against lives. */
    private static final @NotNull String MODELS = "/lib/minecraft/renderer/entity_models.json";

    private static ConcurrentMap<String, Entity> entities;
    private static JsonObject models;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
        models = read().getAsJsonObject("models");
    }

    @Test
    @DisplayName("is the only place which way is written down, the model table naming no toggle at all")
    void theShippedTableDeclaresNoSide() {
        Map<String, String> declared = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : models.entrySet())
            for (String member : restMembers(entry.getValue().getAsJsonObject()))
                if ("toggles".equals(member) || "undrawn".equals(member))
                    declared.put(entry.getKey(), member);

        assertEquals(Map.of(), declared,
            "what a subject rests without is the mesh's answer, so no row restates it");

        int toggled = 0;
        for (Entity entity : entities.values())
            for (EntityMesh.Bone bone : entity.model().getBones().values())
                if (bone.getToggle() != null) toggled++;
        assertFalse(toggled == 0, "the shipped meshes are expected to name selections, not skip past");
    }

    @Test
    @DisplayName("leaves a bone a selection can ask for standing hidden, and drops one nothing can")
    void theMeshCarriesWhatASelectionCanStillReach() {
        // An armour stand rests armless, and its arms toggle can ask for them - so they stand in the
        // mesh, not drawn. The hat nothing ever draws is gone: no selection names it.
        assertEquals(List.of("left_arm", "right_arm"), restingHidden("minecraft:armor_stand"),
            "a stand rests armless, and a selection can put the arms back");
        assertFalse(hasBone("minecraft:armor_stand", "hat"),
            "the hat nothing ever draws is absent rather than hidden");

        // An evoker rests with its arms crossed and declares no toggle, so nothing can ask for the
        // pair it hangs and the mesh does not carry them.
        for (String bone : List.of("hat", "left_arm", "right_arm"))
            assertFalse(hasBone("minecraft:evoker", bone),
                "an evoker's " + bone + " is drawn by nothing and asked for by nothing");
        assertEquals(List.of(), restingHidden("minecraft:evoker"),
            "so it rests hiding nothing - what it rests without simply is not there");

        // A frog's croak sac is the third shape: drawn by nothing at rest, and asked for by a
        // selection - so it is KEPT and hidden rather than dropped. Its gate is an animation state
        // rather than a boolean field, which is the one place the two shapes are told apart, so it
        // is here to catch a generator that stopped reading that gate and dropped the bone again.
        assertTrue(hasBone("minecraft:frog", "croaking_body"),
            "a frog keeps the sac a croak selection draws");
        assertEquals(List.of("croaking_body"), restingHidden("minecraft:frog"),
            "and rests it hidden, which is the frog no croak has been started on");

        assertEquals(List.of(), restingHidden("minecraft:goat"),
            "a goat rests with everything its toggles flip");
    }

    @Test
    @DisplayName("gives a small stand the arms and the plate its selections reach, where its own mesh puts them")
    void theSmallStandCarriesWhatItsSelectionsReach() {
        // Vanilla's small stand is the full-size model aged down, and its setupAnim gates the arms on
        // showArms and the plate on showBasePlate as the full-size one does. So the small mesh keeps
        // both arms standing hidden, at the pivot and scale the aged-down transform gives them, and
        // its plate names the selection that hides it.
        EntityMesh small = sized("minecraft:armor_stand", Size.SMALL);
        Map<String, Vector3f> pivots = Map.of(
            "right_arm", new Vector3f(-2.5f, 13f, 0f), "left_arm", new Vector3f(2.5f, 13f, 0f));
        pivots.forEach((name, pivot) -> {
            EntityMesh.Bone arm = small.getBones().get(name);
            assertNotNull(arm, "a small stand carries its " + name);
            assertFalse(arm.isVisible(), "and rests without it, as the full-size stand does");
            assertEquals("arms", arm.getToggle(), "naming the selection that draws it");
            assertEquals(pivot, arm.getPivot(), name + " stands where the aged-down transform puts it");
            assertEquals(0.5f, arm.getScale(), name + " rests at the aged-down scale");
        });
        EntityMesh.Bone plate = small.getBones().get("base_plate");
        assertNotNull(plate, "a small stand carries its plate");
        assertTrue(plate.isVisible(), "and rests on it");
        assertEquals("base_plate", plate.getToggle(), "naming the selection that hides it");
    }

    @Test
    @DisplayName("points a goat at its horns and a donkey away from its chest")
    void theTwoDirectionsBothArrive() {
        // One of each, because a toggle that answered one way for everything would pass a test that
        // only looked at the other: a goat rests WITH the bones its toggle hides, and a donkey rests
        // WITHOUT the ones its toggle shows.
        assertEquals(true, restsDrawn("minecraft:goat", "horn"), "a goat rests with its horns");
        assertEquals(false, restsDrawn("minecraft:donkey", "chest"), "a donkey rests without its chest");
        assertEquals(true, restsDrawn("minecraft:armor_stand", "base_plate"), "a stand rests on its plate");
        assertEquals(false, restsDrawn("minecraft:armor_stand", "arms"), "and rests without its arms");
    }

    @Test
    @DisplayName("selecting one moves the bones it names, in whichever direction it rests")
    void aSelectedToggleMovesItsBones() {
        // A toggle that answered its own resting side would resolve to the mesh it started from and
        // read as working. So what is asserted is the CHANGE, per direction, rather than the state
        // after.
        assertToggleMoves("minecraft:goat", "horn", "left_horn");
        assertToggleMoves("minecraft:donkey", "chest", "left_chest");
        assertToggleMoves("minecraft:armor_stand", "arms", "left_arm");
    }

    @Test
    @DisplayName("selecting one on a small stand moves the bones of the mesh its size draws")
    void aSelectedToggleMovesTheSmallStandsBones() {
        // The small size swaps in a mesh of its own, so a selection has to land on that mesh - one
        // flipped on the row's mesh before the swap leaves with it. The small stand rests armless and
        // on its plate as the full-size one does, so its arms selection draws both arms and its plate
        // selection hides the plate.
        Optional<Size> small = Optional.of(Size.SMALL);
        assertToggleMoves("minecraft:armor_stand", small, "arms", "left_arm");
        assertToggleMoves("minecraft:armor_stand", small, "arms", "right_arm");
        assertToggleMoves("minecraft:armor_stand", small, "base_plate", "base_plate");
    }

    @Test
    @DisplayName("selecting one on a stand named at its declared size flips what naming no size does")
    void theDeclaredSizeFlipsWhatNoSizeDoes() {
        // The declared size is the row as built, so naming it swaps the row's own unflipped mesh back
        // in - a selection flipped before that swap is lost to a caller who spells the default out.
        Optional<Size> large = Optional.of(Size.LARGE);
        assertToggleMoves("minecraft:armor_stand", large, "arms", "left_arm");
        assertToggleMoves("minecraft:armor_stand", large, "arms", "right_arm");
        assertToggleMoves("minecraft:armor_stand", large, "base_plate", "base_plate");
        for (String toggle : List.of("arms", "base_plate"))
            assertEquals(drawn("minecraft:armor_stand", Optional.empty(), toggle),
                drawn("minecraft:armor_stand", large, toggle),
                "'" + toggle + "' at the declared size is expected to draw what it draws with no size named");
    }

    @Test
    @DisplayName("selecting nothing a stand's meshes name leaves each size drawing its own mesh as it stands")
    void aSelectionReachingNoBoneKeepsTheMeshItsSizeDraws() {
        // A mesh no selection reaches comes back as itself, so a size still draws its form's own
        // mesh where the flip moves nothing, and a selection that moves no bone rebuilds none. The
        // stand names no bone after a donkey's chest.
        List<Optional<Size>> sizes = List.of(Optional.empty(), Optional.of(Size.SMALL), Optional.of(Size.LARGE));
        for (Optional<Size> size : sizes) {
            EntityMesh own = size.map(named -> sized("minecraft:armor_stand", named))
                .orElseGet(() -> mesh("minecraft:armor_stand"));
            for (Set<String> toggles : List.of(Set.<String>of(), Set.of("chest")))
                assertSame(own, resolved("minecraft:armor_stand", size, toggles),
                    "minecraft:armor_stand" + size.map(named -> " at " + named).orElse("") + " selecting "
                        + toggles + " is expected to draw its size's own mesh instance");
        }
    }

    @Test
    @DisplayName("selecting one on a large tropical fish moves the bones of the mesh its shape draws")
    void aSelectedToggleMovesTheLargeShapesBones() {
        // No shipped shape-axis mesh names a toggle, so the large body's bottom fin - a bone the small
        // body does not carry - is marked here to rest drawn under a selection that hides it. The
        // shape swap puts in a mesh of its own as a size does, so a selection has to land on that mesh.
        Entity fish = entities.get("minecraft:tropical_fish");
        assertNotNull(fish, "minecraft:tropical_fish is expected to load");
        Entity.Axes axes = fish.axes();
        Entity large = axes.shape().select(Entity.SHAPE_LARGE).orElseThrow(
            () -> new AssertionError("minecraft:tropical_fish is expected to carry a large form"));
        EntityMesh mesh = marked(large.model(), "bottom_fin", "fin");
        Map<String, Entity> shapes = new LinkedHashMap<>(axes.shape().options());
        shapes.put(Entity.SHAPE_LARGE, large.mutate().model(mesh).build());
        Entity finned = fish.mutate()
            .axes(new Entity.Axes(axes.baby(),
                new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(shapes), axes.shape().declared()),
                axes.state(), axes.size(), axes.variant()))
            .build();

        AppearanceOptions flopper = AppearanceOptions.builder().pattern(TropicalFishPattern.FLOPPER).build();
        assertSame(mesh, flopper.resolve(finned).model(),
            "a large fish selecting nothing is expected to draw its shape's own mesh instance");
        EntityMesh.Bone rest = mesh.getBones().get("bottom_fin");
        EntityMesh.Bone selected = flopper.mutate().toggles(Set.of("fin")).build().resolve(finned)
            .model().getBones().get("bottom_fin");
        assertFalse(selected.isVisible(), "a large fish's 'fin' is expected to move bottom_fin, which rests drawn");
        assertEquals(rest.withVisible(false), selected,
            "a large fish's 'fin' is expected to draw bottom_fin where the mesh its shape draws places it");
    }

    @Test
    @DisplayName("gives a bee the sting its own model draws, which the hidden list used to take away")
    void theBeeRestsWithTheStingItsModelDraws() {
        // The subject the two answers disagreed about. BeeRenderState builds hasStinger at one, so
        // AdultBeeModel draws the sting on a bee that has not stung - which is every bee this
        // renderer builds - and the hidden list stripped the bone anyway.
        //
        // It costs no pixels either way: the sting is a single zero-width plane cube, and the
        // vanilla reference is byte-identical whether the harness pins the bone drawn or hidden.
        // What it costs is the ability to say which of two answers was right, which is the whole
        // reason there is now one.
        assertTrue(hasBone("minecraft:bee", "stinger"), "the resting mesh carries the sting");
        assertEquals(List.of(), restingHidden("minecraft:bee"),
            "and rests drawing it, the model drawing it on a bee that has not stung");
    }

    @Test
    @DisplayName("gives a baby the toggles its own model gates, resting drawn where the adult's do")
    void aBabyCarriesTheTogglesItsModelGates() {
        // A baby's mesh is gated by the same setupAnim as an adult's, so a baby goat's horns and a baby
        // bee's sting rest drawn under the selections that hide them.
        assertAll(
            () -> assertBabyBone("minecraft:goat", "left_horn", true, "horn"),
            () -> assertBabyBone("minecraft:goat", "right_horn", true, "horn"),
            () -> assertBabyBone("minecraft:bee", "stinger", true, "stinger"));
    }

    @Test
    @DisplayName("gives a baby donkey the two cubeless chests its layer builds, resting hidden")
    void aBabyDonkeyKeepsTheChestsItsLayerBuilds() {
        // Vanilla's baby donkey layer builds both chests with no box, so a selection flips two bones
        // that draw nothing either way - the mesh matching the layer rather than a rule leaving them out.
        List<Executable> checks = new ArrayList<>();
        for (String id : List.of("minecraft:donkey", "minecraft:mule"))
            for (String chest : List.of("left_chest", "right_chest"))
                checks.add(() -> {
                    EntityMesh.Bone bone = assertBabyBone(id, chest, false, "chest");
                    String where = "a baby " + id + "'s " + chest;
                    assertEquals("body", bone.getParent(), where + " is expected to hang from the body");
                    assertEquals(new Vector3f(-1f, 10f, 0f), bone.getPivot(),
                        where + " is expected at its authored pivot");
                    assertTrue(bone.getCubes().isEmpty(), where + " is expected to carry no cube");
                });
        assertAll(checks);
    }

    @Test
    @DisplayName("gives a baby llama no chest, its renderer never letting a baby wear one")
    void aBabyLlamaCarriesNoChest() {
        // The llama's model gates its chests as the donkey's does and vanilla's baby layer builds both,
        // but LlamaRenderer stores false into hasChest for every baby - so no selection can reach them,
        // and the mesh carries neither rather than a chest vanilla never draws on a baby.
        for (String id : List.of("minecraft:llama", "minecraft:trader_llama")) {
            EntityMesh baby = babyMesh(id);
            for (String chest : List.of("left_chest", "right_chest"))
                assertFalse(baby.getBones().containsKey(chest), "a baby " + id + " is expected to carry no " + chest);
            baby.getBones().forEach((name, bone) -> assertFalse("chest".equals(bone.getToggle()),
                "a baby " + id + "'s " + name + " is expected to name no chest selection"));
        }
    }

    @Test
    @DisplayName("selecting one on a baby moves the bones of the mesh its age draws, in whichever direction they rest")
    void aSelectedToggleMovesTheBabysBones() {
        // A baby swaps in a mesh of its own, so a selection has to land on that mesh - one stopped at
        // the swap leaves the baby drawing what the adult hides. A baby goat rests with its horns and
        // a baby bee with its sting, and each selection hides them; a baby donkey or mule rests
        // without its two cubeless chests, and its selection turns them drawn with nothing to draw.
        List<Executable> checks = new ArrayList<>();
        for (String horn : List.of("left_horn", "right_horn"))
            checks.add(() -> assertToggleMoves("minecraft:goat", Age.BABY, "horn", horn));
        checks.add(() -> assertToggleMoves("minecraft:bee", Age.BABY, "stinger", "stinger"));
        for (String id : List.of("minecraft:donkey", "minecraft:mule"))
            for (String chest : List.of("left_chest", "right_chest"))
                checks.add(() -> assertToggleMoves(id, Age.BABY, "chest", chest));
        assertAll(checks);
    }

    @Test
    @DisplayName("selecting nothing a baby's mesh names leaves the baby drawing its form's own mesh as it stands")
    void aSelectionReachingNoBabyBoneKeepsTheMeshItsAgeDraws() {
        // A mesh no selection reaches comes back as itself, so every baby selecting nothing still draws
        // its form's own mesh instance - in every coat, the variant fold resolving a named coat to that
        // coat's own row before the age swaps its baby in - and so does a baby goat selecting a chest it
        // has no bone for. A baby llama selecting one is the vanilla case: its renderer never lets a baby
        // wear a chest, so its mesh names no bone the selection reaches and nothing is drawn for it.
        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, Entity> entry : entities.entrySet()) {
            Entity row = entry.getValue();
            Map<Optional<String>, Entity> coats = new LinkedHashMap<>();
            coats.put(Optional.empty(), row);
            row.axes().variant().options().forEach((coat, form) -> coats.put(Optional.of(coat), form));
            coats.forEach((coat, form) -> form.axes().baby().ifPresent(baby -> checks.add(() -> assertSame(
                baby.model(), AppearanceOptions.builder().age(Age.BABY).variant(coat).build().resolve(row).model(),
                "a baby " + entry.getKey() + coat.map(named -> " in its " + named + " coat").orElse("")
                    + " selecting nothing is expected to draw its form's own mesh instance"))));
        }
        assertFalse(checks.isEmpty(), "the shipped rows are expected to carry babies, not skip past");
        for (String id : List.of("minecraft:goat", "minecraft:llama", "minecraft:trader_llama"))
            checks.add(() -> {
                EntityMesh chested = resolved(id, Age.BABY, Optional.empty(), Set.of("chest"));
                assertSame(babyMesh(id), chested,
                    "a baby " + id + " selecting 'chest' is expected to draw its form's own mesh instance");
                chested.getBones().forEach((name, bone) -> assertFalse(bone.isVisible() && name.endsWith("_chest"),
                    "a baby " + id + " selecting 'chest' is expected to draw no " + name));
            });
        assertAll(checks);
    }

    @Test
    @DisplayName("shearing a baby moves a bone the mesh its age draws marks sheared")
    void shearingMovesTheBabysMarkedBone() {
        // No shipped baby mesh names the sheared selection - only the bogged's does, and a bogged has
        // no baby - so a baby sheep's body is marked here to rest drawn under it. Shearing is a
        // selection as a named toggle is, so it has to land on the baby's mesh as well.
        Entity sheep = entities.get("minecraft:sheep");
        assertNotNull(sheep, "minecraft:sheep is expected to load");
        Entity.Axes axes = sheep.axes();
        Entity baby = axes.baby().orElseThrow(
            () -> new AssertionError("minecraft:sheep is expected to carry a baby form"));
        EntityMesh mesh = marked(baby.model(), "body", "sheared");
        Entity marked = sheep.mutate()
            .axes(new Entity.Axes(Optional.of(baby.mutate().model(mesh).build()),
                axes.shape(), axes.state(), axes.size(), axes.variant()))
            .build();

        AppearanceOptions young = AppearanceOptions.builder().age(Age.BABY).build();
        assertSame(mesh, young.resolve(marked).model(),
            "a baby sheep left unsheared is expected to draw its form's own mesh instance");
        EntityMesh.Bone rest = mesh.getBones().get("body");
        EntityMesh.Bone selected = young.mutate().sheared(true).build().resolve(marked)
            .model().getBones().get("body");
        assertFalse(selected.isVisible(), "a sheared baby sheep is expected to move body, which rests drawn");
        assertEquals(rest.withVisible(false), selected,
            "a sheared baby sheep is expected to draw body where the mesh its age draws places it");
    }

    @Test
    @DisplayName("selecting one on a baby moves the bones of the equipment it wears, as it does its own")
    void aSelectedToggleMovesTheBabysEquipment() {
        // No shipped baby form wears equipment - the index builds each with none - so a baby donkey is
        // handed the adult's saddle here. A layer's toggles take the selection the wearer's do, so a
        // ridden baby draws the reins its saddle rests without, on the saddle the baby wears.
        Entity donkey = entities.get("minecraft:donkey");
        assertNotNull(donkey, "minecraft:donkey is expected to load");
        Entity.Axes axes = donkey.axes();
        Entity baby = axes.baby().orElseThrow(
            () -> new AssertionError("minecraft:donkey is expected to carry a baby form"));
        ConcurrentList<Entity.EquipmentOverlay> worn = donkey.layers().equipment();
        Entity saddled = donkey.mutate()
            .axes(new Entity.Axes(
                Optional.of(baby.mutate().layers(new Entity.Layers(worn, baby.layers().humanoidArmor(), baby.layers().wings())).build()),
                axes.shape(), axes.state(), axes.size(), axes.variant()))
            .build();

        AppearanceOptions young = AppearanceOptions.builder().age(Age.BABY).build();
        assertSame(worn, young.resolve(saddled).layers().equipment(),
            "a baby donkey selecting nothing is expected to wear its form's own equipment list");
        EntityMesh rest = saddleOf(worn);
        EntityMesh selected = saddleOf(young.mutate().toggles(Set.of("ridden")).build().resolve(saddled)
            .layers().equipment());
        List<Executable> checks = new ArrayList<>();
        for (String line : List.of("left_saddle_line", "right_saddle_line"))
            checks.add(() -> {
                EntityMesh.Bone bone = rest.getBones().get(line);
                assertNotNull(bone, "a donkey's saddle is expected to carry " + line);
                boolean atRest = bone.isVisible();
                assertEquals(bone.withVisible(!atRest), selected.getBones().get(line),
                    "a ridden baby donkey is expected to move its saddle's " + line + ", which rests "
                        + (atRest ? "drawn" : "hidden") + ", and nothing else about it");
            });
        assertAll(checks);
    }

    @Test
    @DisplayName("filling a warm zombie nautilus's body armour slot hides its corals, as vanilla's coral model does")
    void aFilledBodySlotHidesTheWarmCorals() {
        // ZombieNautilusCoralModel.setupAnim writes corals.visible = state.bodyArmorItem.isEmpty(), and
        // the body-armour layer draws from that same stack. So the corals - the whole subtree, vanilla's
        // visible skipping every part below - rest drawn and hide while the slot is filled, whatever
        // the material: vanilla's model asks only whether the stack is empty.
        Entity nautilus = entities.get("minecraft:zombie_nautilus");
        assertNotNull(nautilus, "minecraft:zombie_nautilus is expected to load");
        EntityMesh coat = nautilus.axes().variant().select("warm").orElseThrow(
            () -> new AssertionError("minecraft:zombie_nautilus is expected to carry a warm coat")).model();
        List<String> corals = new ArrayList<>();
        coat.getBones().forEach((name, bone) -> {
            if ("body_armor_item".equals(bone.getToggle())) corals.add(name);
        });
        assertEquals(12, corals.size(), "the warm coat's corals and every part below them name the slot's toggle");
        assertTrue(corals.contains("corals"), "the coral group itself among them: " + corals);

        AppearanceOptions warm = AppearanceOptions.builder().variant(Optional.of("warm")).build();
        assertSame(coat, warm.resolve(nautilus).model(), "an unarmoured warm nautilus draws its coat as it rests");
        for (String material : List.of("copper", "", "no_such_armor")) {
            EntityMesh armoured = warm.mutate().equipment(Map.of("body", material)).build().resolve(nautilus).model();
            for (String coral : corals) {
                EntityMesh.Bone rest = coat.getBones().get(coral);
                assertTrue(rest.isVisible(), coral + " rests drawn on an unarmoured warm nautilus");
                assertEquals(rest.withVisible(false), armoured.getBones().get(coral),
                    coral + " is hidden, and only hidden, on a warm nautilus whose body slot holds '" + material + "'");
            }
        }
        assertSame(coat, warm.mutate().equipment(Map.of("saddle", "")).build().resolve(nautilus).model(),
            "a saddle fills a stack the coral model does not ask about");
        EntityMesh temperate = nautilus.model();
        assertSame(temperate, AppearanceOptions.builder().equipment(Map.of("body", "copper")).build()
            .resolve(nautilus).model(), "a temperate nautilus has no corals for its armour to hide");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The bones one subject's resting mesh carries but does not draw, sorted - WHICH bones rest
     * hidden is this test's subject, and the mesh's own order is pinned where it is load-bearing,
     * by {@code PosePlayerTest}.
     */
    private static @NotNull List<String> restingHidden(@NotNull String id) {
        List<String> hidden = new ArrayList<>();
        mesh(id).getBones().forEach((name, bone) -> {
            if (!bone.isVisible()) hidden.add(name);
        });
        return hidden.stream().sorted().toList();
    }

    /** Whether one subject's resting mesh carries a bone at all, drawn or not. */
    private static boolean hasBone(@NotNull String id, @NotNull String bone) {
        return mesh(id).getBones().containsKey(bone);
    }

    /** Whether the bones one named selection moves are drawn where the subject rests. */
    private static boolean restsDrawn(@NotNull String id, @NotNull String toggle) {
        for (EntityMesh.Bone bone : mesh(id).getBones().values())
            if (toggle.equals(bone.getToggle())) return bone.isVisible();
        throw new AssertionError(id + " is expected to name a '" + toggle + "' selection");
    }

    /** That selecting a toggle puts one of its bones on the other side of the mesh than it rests. */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull String toggle, @NotNull String bone) {

        assertToggleMoves(id, Age.ADULT, Optional.empty(), toggle, bone);
    }

    /** That selecting a toggle at one age draws one of its bones the other way from how that age rests it. */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull Age age, @NotNull String toggle, @NotNull String bone) {

        assertToggleMoves(id, age, Optional.empty(), toggle, bone);
    }

    /** That selecting a toggle at one size draws one of its bones the other way from how that size rests it. */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull Optional<Size> size, @NotNull String toggle, @NotNull String bone) {

        assertToggleMoves(id, Age.ADULT, size, toggle, bone);
    }

    /**
     * That selecting a toggle at one age and size draws one of its bones the other way from how the
     * mesh they draw rests it - the baby form's own mesh for a baby, the size's for an adult at a named
     * size and the row's own otherwise - and leaves it where that mesh places it, so the flip lands on
     * the mesh the age and size swap in rather than on another's.
     */
    private static void assertToggleMoves(
        @NotNull String id, @NotNull Age age, @NotNull Optional<Size> size, @NotNull String toggle,
        @NotNull String bone) {

        EntityMesh own = age == Age.BABY ? babyMesh(id) : size.map(named -> sized(id, named)).orElseGet(() -> mesh(id));
        EntityMesh.Bone rest = own.getBones().get(bone);
        assertNotNull(rest, id + " is expected to carry " + bone + " in the mesh its age and size draw");
        boolean atRest = rest.isVisible();
        EntityMesh.Bone selected = resolved(id, age, size, Set.of(toggle)).getBones().get(bone);
        String where = id + (age == Age.BABY ? " as a baby" : "") + size.map(named -> " at " + named).orElse("")
            + " '" + toggle + "'";
        assertNotNull(selected, where + " is expected to draw a mesh carrying " + bone);
        assertEquals(!atRest, selected.isVisible(),
            where + " is expected to move " + bone + ", which rests " + (atRest ? "drawn" : "hidden"));
        assertEquals(rest.withVisible(!atRest), selected,
            where + " is expected to draw " + bone + " where the mesh its age and size draw places it");
    }

    /** The mesh one subject draws with some toggles selected, at one size or at none. */
    private static @NotNull EntityMesh resolved(
        @NotNull String id, @NotNull Optional<Size> size, @NotNull Set<String> toggles) {

        return resolved(id, Age.ADULT, size, toggles);
    }

    /** The mesh one subject draws with some toggles selected, at one age and at one size or at none. */
    private static @NotNull EntityMesh resolved(
        @NotNull String id, @NotNull Age age, @NotNull Optional<Size> size, @NotNull Set<String> toggles) {

        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return AppearanceOptions.builder().age(age).size(size).toggles(toggles).build().resolve(entity).model();
    }

    /** Whether each bone is drawn, by name, in the mesh one subject draws with one toggle selected. */
    private static @NotNull Map<String, Boolean> drawn(
        @NotNull String id, @NotNull Optional<Size> size, @NotNull String toggle) {

        Map<String, Boolean> visible = new TreeMap<>();
        resolved(id, size, Set.of(toggle)).getBones().forEach((name, bone) -> visible.put(name, bone.isVisible()));
        return visible;
    }

    private static @NotNull EntityMesh mesh(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.model();
    }

    /**
     * One mesh with one of its bones resting drawn under a named selection, everything else about
     * it as the mesh has it.
     *
     * @param mesh the mesh to mark
     * @param name the bone that names the selection
     * @param toggle the selection it names
     * @return a mesh whose {@code name} bone the {@code toggle} selection flips
     */
    private static @NotNull EntityMesh marked(@NotNull EntityMesh mesh, @NotNull String name, @NotNull String toggle) {
        EntityMesh.Bone bone = mesh.getBones().get(name);
        assertNotNull(bone, "the mesh is expected to carry " + name);
        LinkedHashMap<String, EntityMesh.Bone> bones = new LinkedHashMap<>(mesh.getBones());
        bones.put(name, new EntityMesh.Bone(bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
            bone.getScale(), bone.getCubes(), bone.getParent(), bone.getPoseScale(), true, toggle));
        return new EntityMesh(mesh.getTextureSize(), Concurrent.adoptLinkedMap(bones), mesh.isCull());
    }

    /** The mesh one subject's size form draws at one size. */
    private static @NotNull EntityMesh sized(@NotNull String id, @NotNull Size size) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.axes().size().select(size).orElseThrow(
            () -> new AssertionError(id + " is expected to carry a " + size + " form")).model();
    }

    /** The saddle's mesh in one equipment list. */
    private static @NotNull EntityMesh saddleOf(@NotNull List<Entity.EquipmentOverlay> equipment) {
        return equipment.stream()
            .filter(overlay -> overlay.slot().equals("saddle"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the equipment is expected to carry a saddle"))
            .model();
    }

    /** The mesh one subject's baby form draws. */
    private static @NotNull EntityMesh babyMesh(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.axes().baby().orElseThrow(
            () -> new AssertionError(id + " is expected to carry a baby form")).model();
    }

    /**
     * That one subject's baby form carries a bone resting drawn or hidden under a named selection.
     *
     * @param id the subject
     * @param name the bone
     * @param drawn whether the bone rests drawn
     * @param toggle the selection the bone is expected to name
     * @return the bone
     */
    private static @NotNull EntityMesh.Bone assertBabyBone(
        @NotNull String id, @NotNull String name, boolean drawn, @NotNull String toggle) {

        EntityMesh.Bone bone = babyMesh(id).getBones().get(name);
        String where = "a baby " + id + "'s " + name;
        assertNotNull(bone, where + " is expected to be carried");
        assertEquals(drawn, bone.isVisible(), where + " is expected to rest " + (drawn ? "drawn" : "hidden"));
        assertEquals(toggle, bone.getToggle(), where + " is expected to name the '" + toggle + "' selection");
        return bone;
    }

    /**
     * The member names of every node the generator states one row's rest on before moving it onto
     * the mesh - the row's own {@code bones} node, each option of each of its axes, and each of its
     * equipment rows' {@code bones} node, a baby or a size option carrying a rest of its own.
     */
    private static @NotNull List<String> restMembers(@NotNull JsonObject row) {
        List<String> members = new ArrayList<>(membersOf(row, "bones"));
        JsonObject axes = row.getAsJsonObject("axes");
        if (axes != null)
            for (String axis : axes.keySet()) {
                JsonObject options = axes.getAsJsonObject(axis).getAsJsonObject("options");
                if (options != null)
                    for (String option : options.keySet())
                        members.addAll(membersOf(options, option));
            }
        JsonArray equipment = row.getAsJsonArray("equipment");
        if (equipment != null)
            for (JsonElement layer : equipment)
                members.addAll(membersOf(layer.getAsJsonObject(), "bones"));
        return members;
    }

    /** The member names one node's child object carries, empty where it has no such child. */
    private static @NotNull List<String> membersOf(@NotNull JsonObject owner, @NotNull String child) {
        JsonObject node = owner.getAsJsonObject(child);
        return node == null ? List.of() : List.copyOf(node.keySet());
    }

    private static @NotNull JsonObject read() {
        try (InputStream source = BoneToggleRestTest.class.getResourceAsStream(MODELS)) {
            assertNotNull(source, "the shipped entity models are expected on the classpath");
            return new Gson().fromJson(
                new InputStreamReader(source, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + MODELS, error);
        }
    }

}
