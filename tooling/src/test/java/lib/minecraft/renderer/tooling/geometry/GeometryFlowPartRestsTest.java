package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Where a mesh's parts rest, as a pose's read of a part's own position answers it - the number a
 * state silhouette's literal is compared with before a row two ages reach leaves the channel out.
 *
 * <p>Read through the renderer's own {@link EntityMesh}, so the factor test is the mesh's: a mesh
 * flattened at a factor answers nothing, because a read there crosses the factor and the feet-anchor
 * translate rather than answering the pivot. Only a part hanging from the root is answered, and an
 * aged-down mesh, whose parts share no one factor, answers its top-level pivots as stored.
 */
@DisplayName("where a mesh's parts rest")
class GeometryFlowPartRestsTest {

    /** One bone node at a pivot, under an optional parent and at an optional rest scale. */
    private static @NotNull JsonTree bone(float x, float y, float z, float scale, @Nullable String parent) {
        JsonTree bone = JsonTree.object().putFloats("pivot", x, y, z);
        if (parent != null) bone.put("parent", parent);
        if (scale != 1f) bone.put("scale", scale);
        return bone;
    }

    /** A parsed entry holding the given bones over a 64 by 64 texture. */
    private static @NotNull JsonTree entry(@NotNull JsonTree bones) {
        return JsonTree.object().putInts("texture_size", 64, 64).put("bones", bones);
    }

    @Test
    @DisplayName("a mesh flattened at nothing answers each top-level part's pivot, and no part below one")
    void aPlainMeshAnswersItsTopLevelPivots() {
        JsonTree bones = JsonTree.object()
            .put("left_arm", bone(5f, 2f, 0f, 1f, null))
            .put("left_hand", bone(1f, 10f, 0f, 1f, "left_arm"));

        Map<String, Vector3f> rests = GeometryFlow.partRests(entry(bones)).orElseThrow();

        assertEquals(new Vector3f(5f, 2f, 0f), rests.get("left_arm"), "a top-level part rests at its pivot");
        assertFalse(rests.containsKey("left_hand"), "a part below one carries its parent's factor and is left out");
    }

    @Test
    @DisplayName("a part with no stored pivot rests at the origin")
    void anAbsentPivotIsTheOrigin() {
        JsonTree bones = JsonTree.object().put("body", JsonTree.object());

        assertEquals(Vector3f.ZERO, GeometryFlow.partRests(entry(bones)).orElseThrow().get("body"),
            "the parser drops a pivot at the origin, and the mesh reads it back there");
    }

    @Test
    @DisplayName("a mesh whose every part shares one factor answers nothing, its read crossing that factor")
    void aFlattenedMeshAnswersNothing() {
        JsonTree bones = JsonTree.object()
            .put("body_front", bone(0f, 20f, 0f, 0.5f, null))
            .put("body_back", bone(0f, 20f, 8f, 0.5f, null));

        assertEquals(Optional.empty(), GeometryFlow.partRests(entry(bones)));
    }

    @Test
    @DisplayName("an aged-down mesh, its parts resting at scales of their own, answers its stored pivots")
    void anAgedDownMeshAnswersItsStoredPivots() {
        JsonTree bones = JsonTree.object()
            .put("head", bone(0f, 12.75f, 0f, 0.75f, null))
            .put("left_arm", bone(2.5f, 13f, 0f, 0.5f, null));

        assertEquals(new Vector3f(2.5f, 13f, 0f), GeometryFlow.partRests(entry(bones)).orElseThrow().get("left_arm"),
            "vanilla's baby transform writes the proportions into the part's own pose, which the mesh stores");
    }

    @Test
    @DisplayName("the shipped stands rest their arms at 5 and 2.5, each the attack's offset at that stand's age")
    void theShippedStandsRestTheirArmsAtTheirAgesOffsets() throws Exception {
        JsonTree geometries = JsonTree.parse(Files.readAllBytes(
            Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json"))).getObject("geometries");

        Map<String, Vector3f> large = GeometryFlow.partRests(geometries.getObject("ArmorStandModel#createBodyLayer"))
            .orElseThrow();
        Map<String, Vector3f> small = GeometryFlow.partRests(
            geometries.getObject("ArmorStandModel#createBodyLayer@baby=HumanoidModel.BABY_TRANSFORMER")).orElseThrow();

        assertEquals(5f, large.get("left_arm").x(), "5 * ageScale at one");
        assertEquals(-5f, large.get("right_arm").x());
        assertEquals(2.5f, small.get("left_arm").x(), "5 * ageScale at one half");
        assertEquals(-2.5f, small.get("right_arm").x());
    }

}
