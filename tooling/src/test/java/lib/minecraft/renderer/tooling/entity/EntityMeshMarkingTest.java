package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
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

        JsonTree bones = JsonTree.object();
        if (!undrawn.isEmpty()) bones.putStrings("undrawn", undrawn.toArray(String[]::new));
        if (!toggles.isEmpty()) {
            JsonTree declared = bones.child("toggles");
            toggles.forEach((name, named) ->
                declared.put(name, JsonTree.object().putStrings("bones", named.toArray(String[]::new))));
        }
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

    /** The mesh one subject's adult age option names. */
    private static String geometryOf(@NotNull JsonTree models, @NotNull String id) {
        return models.getObject(id).getObject("axes").getObject("age")
            .getObject("options").getObject("adult").getString("geometry", null);
    }

}
