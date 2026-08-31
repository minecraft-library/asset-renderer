package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an overlay pass does to the mesh it draws, written into a mesh of its own.
 *
 * <p>Exercised on hand-built trees rather than a parsed client mesh, so the subset rule, the deformation
 * and the subtree clear are pinned independently of any entity's bone layout. These are the contracts the
 * load-time surgery used to hold; they moved here with the work.
 */
@DisplayName("entity mesh overlays")
class EntityMeshOverlaysTest {

    private static Diagnostics diagnostics;

    @BeforeAll
    static void open() {
        diagnostics = Diagnostics.root("overlays", Diagnostics.Output.NONE, null);
    }

    private static final String COORD = "Mesh#layer";

    /**
     * The villager bone shape: {@code head} carries {@code hat} (which carries {@code hat_rim}) and
     * {@code nose}, alongside an untouched {@code body} / {@code right_leg} pair. Every bone owns one
     * cube carrying a deformation, so an added one is observable against what was already there.
     */
    private static @NotNull JsonTree mesh() {
        JsonTree bones = JsonTree.object();
        bones.put("body", bone(null));
        bones.put("head", bone(null));
        bones.put("hat", bone("head"));
        bones.put("hat_rim", bone("hat"));
        bones.put("nose", bone("head"));
        bones.put("right_leg", bone("body"));
        return JsonTree.object()
            .put("source", JsonTree.object().put("class", "a/b/Mesh").put("method", "layer"))
            .put("bones", bones);
    }

    private static @NotNull JsonTree bone(@Nullable String parent) {
        JsonTree cube = JsonTree.object().putFloats("origin", 0f, 0f, 0f).put("grow", 0.25f);
        JsonTree bone = JsonTree.object().putFloats("pivot", 0f, 0f, 0f);
        bone.childArray("cubes").add(cube);
        if (parent != null) bone.put("parent", parent);
        return bone;
    }

    /** One subject drawing {@code COORD} as its body, with one overlay pass over it. */
    private static @NotNull JsonTree models(@NotNull JsonTree pass) {
        JsonTree adult = JsonTree.object().put("geometry", COORD);
        JsonTree subject = JsonTree.object()
            .put("axes", JsonTree.object()
                .put("age", JsonTree.object().put("options", JsonTree.object().put("adult", adult))));
        subject.childArray("overlays").add(pass);
        return JsonTree.object().put("minecraft:test", subject);
    }

    /** The bones one entry names, so a cube list can be asked after. */
    private static @NotNull JsonTree bonesOf(@NotNull Map<String, JsonTree> geometries, @NotNull String key) {
        return geometries.get(key).getObject("bones");
    }

    /** Whether one bone of one entry draws anything. */
    private static boolean draws(@NotNull Map<String, JsonTree> geometries, @NotNull String key, @NotNull String bone) {
        return !bonesOf(geometries, key).getObject(bone).findArray("cubes").orElse(JsonTree.array()).isEmpty();
    }

    /** The deformation one bone's single cube carries. */
    private static float growOf(@NotNull Map<String, JsonTree> geometries, @NotNull String key, @NotNull String bone) {
        return bonesOf(geometries, key).getObject(bone)
            .findArray("cubes")
            .flatMap(cubes -> cubes.findAt(0))
            .map(cube -> cube.getFloat("grow", 0f))
            .orElse(0f);
    }

    /** The one overlay row of the fixture subject. */
    private static @NotNull JsonTree rowOf(@NotNull JsonTree models) {
        return models.getObject("minecraft:test").getArray("overlays").findAt(0).orElseThrow();
    }

    private static @NotNull Map<String, JsonTree> geometries() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());
        return geometries;
    }

    // ------------------------------------------------------------------------------------
    // the retained subset
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a bone keeps its cubes only when it is named and no ancestor of it is")
    void retainKeepsCubesOnlyOnANamedBoneWithNoNamedAncestor() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD)
            .putStrings("retain_bones", "head", "hat");

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        String key = COORD + "@retain=hat,head";
        assertTrue(draws(geometries, key, "head"), "head is named and has no named ancestor, so it draws");
        assertFalse(draws(geometries, key, "hat"), "hat is named but hangs off head, which is also named");
        for (String name : new String[]{"hat_rim", "nose", "body", "right_leg"})
            assertFalse(draws(geometries, key, name), "the unnamed " + name + " is emptied");
    }

    @Test
    @DisplayName("a retained subset empties cubes and never drops a bone, so the chain survives")
    void retainKeepsEveryBoneAsAPoseOnlyNode() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).putStrings("retain_bones", "head");

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        JsonTree before = mesh().getObject("bones");
        JsonTree after = bonesOf(geometries, COORD + "@retain=head");
        before.members().forEach((name, bone) -> {
            assertTrue(after.has(name), name + " survives as a pose-only node");
            assertEquals(bone.findString("parent").orElse(null),
                after.getObject(name).findString("parent").orElse(null), name + " keeps its parent");
            assertEquals(bone.getArray("pivot").toJson(),
                after.getObject(name).getArray("pivot").toJson(), name + " keeps its pivot");
        });
    }

    // ------------------------------------------------------------------------------------
    // the deformation
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an inflate ADDS to the deformation a cube already carries")
    void inflateAddsToTheGrowACubeCarries() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("grow", 0.5f);

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        String key = COORD + "@inflate=0.5";
        for (String name : new String[]{"body", "head", "hat", "hat_rim", "nose", "right_leg"})
            assertEquals(0.75f, growOf(geometries, key, name), name + " grows from its own 0.25");
        assertEquals(0.25f, growOf(geometries, COORD, "head"), "the mesh it derived from is untouched");
    }

    @Test
    @DisplayName("a subset and a deformation compose in that order, under one key naming both")
    void retainAndInflateComposeInOrder() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD)
            .putStrings("retain_bones", "head").put("grow", 0.5f);

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        String key = COORD + "@retain=head@inflate=0.5";
        assertTrue(geometries.containsKey(key), "the key spells both, in the canonical order");
        assertEquals(0.75f, growOf(geometries, key, "head"), "the surviving bone is inflated");
        assertFalse(draws(geometries, key, "body"), "and the unnamed bone drew nothing to inflate");
    }

    // ------------------------------------------------------------------------------------
    // the cleared subtree
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a cleared subtree empties the root bone and every descendant, sparing the rest")
    void clearedEmptiesTheSubtree() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("no_hat_root", "head");

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        String key = COORD + "@cleared=head";
        for (String name : new String[]{"head", "hat", "hat_rim", "nose"})
            assertFalse(draws(geometries, key, name), "the " + name + " subtree bone is emptied");
        for (String name : new String[]{"body", "right_leg"})
            assertTrue(draws(geometries, key, name), "the " + name + " bone outside the subtree draws");
    }

    @Test
    @DisplayName("the cleared mesh is named BESIDE the drawn one, which the pass keeps naming")
    void clearedShipsBesideThePrimary() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("no_hat_root", "head");
        JsonTree models = models(pass);

        EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest());

        JsonTree row = rowOf(models);
        assertEquals(COORD, row.getString("geometry", null),
            "the pass still draws the body's own mesh, which is what keeps its bounds skip");
        assertEquals(COORD + "@cleared=head", row.getString("no_hat_geometry", null),
            "and names the suppressed form with a member of its own");
        assertFalse(row.has("no_hat_root"), "the member asking for it is gone");
    }

    @Test
    @DisplayName("the primary mesh still draws the subtree the suppressed form clears")
    void theClearLeavesTheSourceIntact() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("no_hat_root", "head");

        EntityMeshOverlays.apply(diagnostics, models(pass), geometries, new GeometryManifest());

        assertTrue(draws(geometries, COORD, "head"), "the primary mesh still draws its head");
        assertTrue(draws(geometries, COORD, "hat"), "the primary mesh still draws its hat");
    }

    @Test
    @DisplayName("a root the mesh does not carry names no mesh at all, rather than a partial one")
    void anUnknownClearedRootNamesNothing() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("no_hat_root", "snout");
        JsonTree models = models(pass);

        EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest());

        assertEquals(List.of(COORD), List.copyOf(geometries.keySet()), "nothing was derived");
        assertFalse(rowOf(models).has("no_hat_geometry"),
            "so the pass names no suppressed form and simply has none");
    }

    // ------------------------------------------------------------------------------------
    // where the mesh is derived
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a mesh only the pass draws is derived where it stands, minting no key")
    void aMeshNoOneElseDrawsIsDerivedInPlace() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put("Spots#layer", mesh());
        JsonTree pass = JsonTree.object().put("geometry", "Spots#layer").putStrings("retain_bones", "head");
        JsonTree models = models(pass);

        EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest());

        assertEquals(List.of("Spots#layer"), List.copyOf(geometries.keySet()),
            "nothing to distinguish, so no discriminator and no orphan left behind");
        assertFalse(draws(geometries, "Spots#layer", "body"), "and the mesh itself is the subset");
        assertEquals("Spots#layer", rowOf(models).getString("geometry", null),
            "so the pass keeps naming it");
    }

    @Test
    @DisplayName("a mesh the body also draws is derived under a key, and the body keeps the bare one")
    void aSharedMeshMintsRatherThanDerivingInPlace() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("geometry", COORD).put("grow", 0.5f);
        JsonTree models = models(pass);

        EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest());

        assertTrue(geometries.containsKey(COORD), "the body draws the mesh as it is, so the bare key stays");
        assertEquals(0.25f, growOf(geometries, COORD, "head"), "and is not deformed on the pass's behalf");
        assertEquals(0.75f, growOf(geometries, COORD + "@inflate=0.5", "head"), "the pass draws the derived one");
    }

    @Test
    @DisplayName("an armor row's own grow is not a pass's inflate, and is left where it is")
    void anArmorRowKeepsItsLayerDeformations() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree armor = JsonTree.object().put("geometry", COORD);
        armor.child("grow").put("inner", 0.5f).put("outer", 1.0f);
        JsonTree models = models(JsonTree.object().put("geometry", COORD).put("no_hat_root", "head"));
        models.getObject("minecraft:test").put("armor", armor);

        EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest());

        JsonTree kept = models.getObject("minecraft:test").getObject("armor").getObject("grow");
        assertEquals(0.5f, kept.getFloat("inner", 0f), "the leggings deformation survives");
        assertEquals(1.0f, kept.getFloat("outer", 0f), "and so does the helmet one");
    }

    @Test
    @DisplayName("a pass deriving from a body its coats name differently is refused")
    void aPassOverCoatsThatNameTheirOwnBodyIsRefused() {
        Map<String, JsonTree> geometries = geometries();
        JsonTree pass = JsonTree.object().put("grow", 0.5f);      // no geometry of its own
        JsonTree models = models(pass);
        JsonTree coats = JsonTree.object();
        coats.put("red", JsonTree.object().put("geometry", "Mesh#red"));
        models.getObject("minecraft:test").getObject("axes")
            .put("variant", JsonTree.object().put("options", coats));

        ToolingException refused = assertThrows(ToolingException.class, () ->
            EntityMeshOverlays.apply(diagnostics, models, geometries, new GeometryManifest()));
        assertTrue(refused.getMessage().contains("different mesh"),
            "the refusal says what it could not name: " + refused.getMessage());
    }

}
