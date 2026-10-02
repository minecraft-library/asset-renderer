package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a subject rests without, written onto the mesh it rests in.
 *
 * <p>Exercised on hand-built trees rather than a parsed client mesh, so the subtree closure and the
 * drop-versus-mark rule are pinned independently of any entity's bone layout. These are the contracts
 * the load-time strip used to hold; they moved here with the work.
 */
@DisplayName("entity mesh marking")
class EntityMeshMarkingTest {

    private static Diagnostics diagnostics;

    @BeforeAll
    static void open() {
        diagnostics = Diagnostics.root("bones", Diagnostics.Output.NONE, null);
    }

    /**
     * The zombie-nautilus shape, declared so a grandchild precedes its parent and that parent precedes
     * ITS parent - the ordering a single closure pass gets wrong.
     */
    private static @NotNull JsonTree shellMesh() {
        JsonTree bones = JsonTree.object();
        bones.put("coral_tip", bone("corals"));
        bones.put("corals", bone("shell"));
        bones.put("shell", bone(null));
        bones.put("body", bone(null));
        return JsonTree.object().put("bones", bones);
    }

    private static @NotNull JsonTree bone(String parent) {
        JsonTree bone = JsonTree.object().putFloats("pivot", 0f, 0f, 0f);
        if (parent != null) bone.put("parent", parent);
        return bone;
    }

    /** One subject naming one mesh, resting without {@code undrawn} and toggling {@code toggles}. */
    private static @NotNull JsonTree models(
        @NotNull String coordinate, @NotNull List<String> undrawn,
        @NotNull Map<String, List<String>> toggles) {

        JsonTree bones = rest(JsonTree.object(), undrawn, toggles);
        JsonTree adult = JsonTree.object().put("geometry", coordinate);
        JsonTree subject = JsonTree.object()
            .put("bones", bones)
            .put("axes", JsonTree.object()
                .put("age", JsonTree.object().put("options", JsonTree.object().put("adult", adult))));
        return JsonTree.object().put("minecraft:test", subject);
    }

    @Test
    @DisplayName("a bone nothing can draw is dropped, and takes its whole subtree with it")
    void theNeverDrawnGoAndCloseDownwards() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of());

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree bones = geometries.get("Mesh#layer").getObject("bones");
        assertFalse(bones.has("shell"), "the shell is gone");
        assertFalse(bones.has("corals"), "its child goes with it");
        assertFalse(bones.has("coral_tip"),
            "and its grandchild, which a single closure pass would orphan");
        assertTrue(bones.has("body"), "the body is untouched");
    }

    @Test
    @DisplayName("a bone a selection can draw stays, standing hidden and naming the selection")
    void theToggleableStayMarked() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("corals"), Map.of("coral", List.of("corals", "coral_tip")));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree bones = geometries.get("Mesh#layer").getObject("bones");
        assertTrue(bones.has("corals"), "a selection can ask for it, so it stays");
        assertEquals(false, bones.getObject("corals").getBoolean("visible", true), "and rests hidden");
        assertEquals("coral", bones.getObject("corals").getString("toggle", null), "naming what flips it");
        assertTrue(bones.has("coral_tip"), "its subtree stays with it");
        assertEquals(false, bones.getObject("coral_tip").getBoolean("visible", true),
            "resting hidden alongside it");
    }

    @Test
    @DisplayName("a bone a selection hides rests drawn, saying only what flips it")
    void aHidingToggleLeavesTheBoneDrawn() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of(), Map.of("coral", List.of("corals")));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree corals = geometries.get("Mesh#layer").getObject("bones").getObject("corals");
        assertTrue(corals.getBoolean("visible", true), "it rests drawn, so nothing says otherwise");
        assertFalse(corals.has("visible"), "and the member is omitted rather than written true");
        assertEquals("coral", corals.getString("toggle", null), "only what flips it is written");
    }

    @Test
    @DisplayName("the two members come off the model table, and an emptied node goes with them")
    void theModelTableStopsSayingIt() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of("coral", List.of("corals")));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree subject = models.getObject("minecraft:test");
        assertFalse(subject.has("bones"), "the node held only those two, so it goes");
    }

    @Test
    @DisplayName("a mesh two sites rest differently in splits, and the bare coordinate is left to no one")
    void aDivergentCoordinateSplits() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of());
        models.put("minecraft:other",
            models("Mesh#layer", List.of("body"), Map.of()).getObject("minecraft:test"));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        assertFalse(geometries.containsKey("Mesh#layer"), "the bare coordinate names no state");
        assertTrue(geometries.containsKey("Mesh#layer@rest=shell"), "one state per key");
        assertTrue(geometries.containsKey("Mesh#layer@rest=body"), "and the other says which it is");
    }

    @Test
    @DisplayName("a mesh every site rests the same in keeps its key")
    void anAgreedCoordinateIsMarkedWhereItStands() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of());

        EntityMeshMarking.apply(diagnostics, models, geometries);

        assertEquals(List.of("Mesh#layer"), List.copyOf(geometries.keySet()),
            "nothing to distinguish, so no discriminator");
    }

    @Test
    @DisplayName("a site resting whole keeps the bare mesh, and the site resting without splits off")
    void aSiteRestingWholeIsNotMarkedWithItsNeighbour() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of());
        models.put("minecraft:whole",
            models("Mesh#layer", List.of(), Map.of()).getObject("minecraft:test"));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        assertTrue(geometries.containsKey("Mesh#layer"), "the site resting whole keeps the bare key");
        assertTrue(geometries.get("Mesh#layer").getObject("bones").has("shell"),
            "and its mesh still draws the shell its neighbour rests without");
        assertTrue(geometries.containsKey("Mesh#layer@rest=shell"), "the resting site splits off");
        assertFalse(geometries.get("Mesh#layer@rest=shell").getObject("bones").has("shell"),
            "and that mesh is the one the shell is gone from");
    }

    @Test
    @DisplayName("the site resting whole keeps naming the bare coordinate")
    void theWholeSiteIsNotRepointed() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of("shell"), Map.of());
        models.put("minecraft:whole",
            models("Mesh#layer", List.of(), Map.of()).getObject("minecraft:test"));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        assertEquals("Mesh#layer", geometryOf(models, "minecraft:whole"),
            "nothing was done to its mesh, so nothing repoints it");
        assertEquals("Mesh#layer@rest=shell", geometryOf(models, "minecraft:test"),
            "the resting site names the mesh minted for it");
    }

    @Test
    @DisplayName("a size mesh keeps the bones its own option's toggles reach, and marks the plate one flips")
    void aSizeOptionMarksItsOwnMesh() {
        // The armour stand's shape: both meshes rest armless and without the hat, and the small one
        // is read off its size option alone, so the toggles it keeps are the ones that option names.
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Stand#layer", standMesh());
        geometries.put("Stand#layer@baby", standMesh());
        List<String> undrawn = List.of("hat", "left_arm", "right_arm");
        Map<String, List<String>> toggles = Map.of(
            "arms", List.of("left_arm", "right_arm"), "base_plate", List.of("base_plate"));
        JsonTree models = models("Stand#layer", undrawn, toggles);
        JsonTree small = rest(JsonTree.object().put("geometry", "Stand#layer@baby"), undrawn, toggles);
        models.getObject("minecraft:test").getObject("axes")
            .put("size", JsonTree.object().put("options", JsonTree.object().put("small", small)));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree bones = geometries.get("Stand#layer@baby").getObject("bones");
        for (String arm : List.of("right_arm", "left_arm")) {
            assertTrue(bones.has(arm), "the small " + arm + " stays, since a selection can ask for it");
            assertEquals(false, bones.getObject(arm).getBoolean("visible", true), "and rests hidden");
            assertEquals("arms", bones.getObject(arm).getString("toggle", null), "naming what flips it");
        }
        assertEquals("base_plate", bones.getObject("base_plate").getString("toggle", null),
            "the small plate names the selection that hides it");
        assertFalse(bones.has("hat"), "the hat nothing reaches still goes");
        assertEquals(List.of("geometry"), small.keys().toList(),
            "and the option is left naming its mesh alone, the mesh saying what it rests without");
    }

    @Test
    @DisplayName("a baby mesh keeps the bones its own option's toggles reach, hidden or drawn as it rests")
    void aBabyOptionMarksItsOwnMesh() {
        // The baby donkey's chests and the baby goat's horns in one mesh: the chests rest hidden and a
        // selection draws them, the horns rest drawn and a selection hides them. The baby is read off
        // its own age option, so the toggles it keeps are the ones that option names.
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        geometries.put("Mesh#baby", babyMesh());
        JsonTree models = models("Mesh#layer", List.of(), Map.of());
        JsonTree baby = rest(JsonTree.object().put("geometry", "Mesh#baby"), List.of("left_chest", "right_chest"),
            Map.of("chest", List.of("left_chest", "right_chest"), "horn", List.of("left_horn", "right_horn")));
        models.getObject("minecraft:test").getObject("axes").getObject("age").getObject("options")
            .put("baby", baby);

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree bones = geometries.get("Mesh#baby").getObject("bones");
        for (String chest : List.of("right_chest", "left_chest")) {
            assertTrue(bones.has(chest), "the baby " + chest + " stays, since a selection can ask for it");
            assertEquals(false, bones.getObject(chest).getBoolean("visible", true), "and rests hidden");
            assertEquals("chest", bones.getObject(chest).getString("toggle", null), "naming what flips it");
        }
        for (String horn : List.of("right_horn", "left_horn")) {
            assertEquals("horn", bones.getObject(horn).getString("toggle", null),
                "the baby " + horn + " names the selection that hides it");
            assertFalse(bones.getObject(horn).has("visible"), "and rests drawn, so nothing says otherwise");
        }
        assertEquals(List.of("geometry"), baby.keys().toList(),
            "and the option is left naming its mesh alone, the mesh saying what it rests without");
    }

    @Test
    @DisplayName("a coat's own toggles mark the mesh that coat draws, and nothing else")
    void aCoatOptionMarksItsOwnMesh() {
        // The warm zombie nautilus: its coat draws a mesh of its own, posed by a class that hides the
        // corals while the body armour slot is filled. The toggle is the coat's, so the coat's mesh
        // names it on the whole subtree and the family's mesh is left as it stands.
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        geometries.put("Coral#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of(), Map.of());
        JsonTree warm = rest(JsonTree.object().put("geometry", "Coral#layer"), List.of(),
            Map.of("body_armor_item", List.of("corals", "coral_tip")));
        JsonTree temperate = JsonTree.object().put("geometry", "Mesh#layer");
        models.getObject("minecraft:test").getObject("axes")
            .put("variant", JsonTree.object().put("options", JsonTree.object()
                .put("temperate", temperate).put("warm", warm)));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        JsonTree coral = geometries.get("Coral#layer").getObject("bones");
        for (String bone : List.of("corals", "coral_tip")) {
            assertEquals("body_armor_item", coral.getObject(bone).getString("toggle", null),
                "the coat's " + bone + " names the selection that hides it");
            assertFalse(coral.getObject(bone).has("visible"), "and rests drawn, so nothing says otherwise");
        }
        assertFalse(coral.getObject("shell").has("toggle"), "the shell above them names nothing");
        assertFalse(geometries.get("Mesh#layer").getObject("bones").getObject("corals").has("toggle"),
            "the family's mesh is not the coat's, so its corals name nothing");
        assertEquals(List.of("geometry"), warm.keys().toList(),
            "and the coat is left naming its mesh alone, the mesh saying what it rests without");
    }

    @Test
    @DisplayName("a toggle the coat and the family both name marks the coat's mesh with the coat's bones")
    void aCoatToggleOutranksTheFamilysOfTheSameName() {
        // A coat's toggles are read off the class that poses the coat and expanded over the coat's own
        // mesh; the family's of the same name were expanded over the family's mesh, which may stop
        // short of bones only the coat has.
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Mesh#layer", shellMesh());
        geometries.put("Coral#layer", shellMesh());
        JsonTree models = models("Mesh#layer", List.of(), Map.of("body_armor_item", List.of("corals")));
        JsonTree warm = rest(JsonTree.object().put("geometry", "Coral#layer"), List.of(),
            Map.of("body_armor_item", List.of("corals", "coral_tip")));
        models.getObject("minecraft:test").getObject("axes")
            .put("variant", JsonTree.object().put("options", JsonTree.object().put("warm", warm)));

        EntityMeshMarking.apply(diagnostics, models, geometries);

        assertEquals("body_armor_item",
            geometries.get("Coral#layer").getObject("bones").getObject("coral_tip").getString("toggle", null),
            "the coat's tip names the toggle its own class expanded onto it");
    }

    /** A baby's shape: two horns under the head and two chests under the body. */
    private static @NotNull JsonTree babyMesh() {
        JsonTree bones = JsonTree.object();
        bones.put("head", bone(null));
        bones.put("right_horn", bone("head"));
        bones.put("left_horn", bone("head"));
        bones.put("body", bone(null));
        bones.put("right_chest", bone("body"));
        bones.put("left_chest", bone("body"));
        return JsonTree.object().put("bones", bones);
    }

    /** An armour stand's shape: a hat under the head, and two arms and a plate at the root. */
    private static @NotNull JsonTree standMesh() {
        JsonTree bones = JsonTree.object();
        bones.put("head", bone(null));
        bones.put("hat", bone("head"));
        bones.put("body", bone(null));
        bones.put("right_arm", bone(null));
        bones.put("left_arm", bone(null));
        bones.put("base_plate", bone(null));
        return JsonTree.object().put("bones", bones);
    }

    /** Writes a rest state onto a node the way the resolvers and the pose flow do. */
    private static @NotNull JsonTree rest(
        @NotNull JsonTree node, @NotNull List<String> undrawn, @NotNull Map<String, List<String>> toggles) {

        if (!undrawn.isEmpty()) node.putStrings("undrawn", undrawn.toArray(String[]::new));
        if (!toggles.isEmpty()) {
            JsonTree declared = node.child("toggles");
            toggles.forEach((name, named) ->
                declared.put(name, JsonTree.object().putStrings("bones", named.toArray(String[]::new))));
        }
        return node;
    }

    /** The mesh one subject's adult age option names. */
    private static String geometryOf(@NotNull JsonTree models, @NotNull String id) {
        return models.getObject(id).getObject("axes").getObject("age")
            .getObject("options").getObject("adult").getString("geometry", null);
    }

}
