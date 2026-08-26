package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code setupRotations} translate, written into the mesh it moves.
 *
 * <p>Pins the arithmetic - the sign flip and the crossing into model units - and the rule that only
 * root bones move, on a hand-built tree rather than a parsed mesh. Also pins the refusal that came
 * here with the translate: a transform and a shift are two spellings of one thing, and a subject
 * reaching both would be moved twice.
 */
@DisplayName("the setupRotations translate")
class EntityMeshShiftTest {

    private static final String COORD = "Mesh#layer";
    private static final String RENDERER = "net/minecraft/client/renderer/entity/TestRenderer";

    private static Diagnostics diagnostics;

    @BeforeAll
    static void open() {
        diagnostics = Diagnostics.root("bones", Diagnostics.Output.NONE, null);
    }

    /** A root pair and a child hanging off one of them, so the root-only rule is observable. */
    private static @NotNull JsonTree mesh() {
        JsonTree bones = JsonTree.object();
        bones.put("body", JsonTree.object().putFloats("pivot", 0f, 8f, 0f));
        bones.put("head", JsonTree.object().putFloats("pivot", 1f, 15f, 2f));
        bones.put("hat", JsonTree.object().putFloats("pivot", 0f, 3f, 0f).put("parent", "head"));
        return JsonTree.object().put("bones", bones);
    }

    /** One subject naming one mesh at one shift, optionally through a renderer. */
    private static @NotNull JsonTree models(float shift, @NotNull String renderer) {
        JsonTree adult = JsonTree.object().put("geometry", COORD);
        if (shift != 0f) adult.put("y_shift", shift);
        JsonTree subject = JsonTree.object()
            .put("renderer", renderer)
            .put("axes", JsonTree.object()
                .put("age", JsonTree.object().put("options", JsonTree.object().put("adult", adult))));
        return JsonTree.object().put("minecraft:test", subject);
    }

    @Test
    @DisplayName("crosses the blocks into model units with the sign flipped, moving roots alone")
    void theTranslateLandsOnTheRootsOnly() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());

        EntityMeshShift.apply(diagnostics, models(-0.7f, RENDERER), geometries, Set.of());

        JsonTree bones = geometries.get(COORD).getObject("bones");
        // Vanilla translates where +Y is up and the mesh is authored Y-down, so -0.7 blocks lifts
        // the mesh by 11.2 model units.
        assertEquals(19.2f, bones.getObject("body").getArray("pivot").getFloat(1, 0f), "the body root moves");
        assertEquals(26.2f, bones.getObject("head").getArray("pivot").getFloat(1, 0f), "so does the head root");
        assertEquals(3f, bones.getObject("hat").getArray("pivot").getFloat(1, 0f),
            "the child holds, its pivot being relative to its parent");
        assertEquals(0f, bones.getObject("body").getArray("pivot").getFloat(0, -1f), "and nothing moves on x");
    }

    @Test
    @DisplayName("takes the member off the model table once the mesh carries it")
    void theModelTableStopsSayingIt() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());
        JsonTree models = models(-0.7f, RENDERER);

        EntityMeshShift.apply(diagnostics, models, geometries, Set.of());

        assertFalse(models.getObject("minecraft:test")
                .require("axes", "age", "options", "adult").has("y_shift"),
            "the mesh answers for it now");
    }

    @Test
    @DisplayName("a subject carrying both a transform and a shift is refused rather than moved twice")
    void bothSpellingsOfOneSetupRotationsAreRefused() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());

        ToolingException raised = assertThrows(ToolingException.class, () ->
            EntityMeshShift.apply(diagnostics, models(-0.7f, RENDERER), geometries, Set.of("TestRenderer")));
        assertTrue(raised.getMessage().contains("would move it twice"),
            "the refusal names the doubling: " + raised.getMessage());
    }

    @Test
    @DisplayName("a shift on its own stands, the transform being what it must not meet")
    void aShiftWithoutATransformStands() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());

        EntityMeshShift.apply(diagnostics, models(-0.7f, RENDERER), geometries, Set.of("OtherRenderer"));

        assertEquals(19.2f, geometries.get(COORD).getObject("bones")
            .getObject("body").getArray("pivot").getFloat(1, 0f), "it moved");
    }

    @Test
    @DisplayName("one mesh two subjects shift differently is refused, a mesh standing in one place")
    void aMeshCannotStandInTwoPlaces() {
        Map<String, JsonTree> geometries = new LinkedHashMap<>();
        geometries.put(COORD, mesh());
        JsonTree models = models(-0.7f, RENDERER);
        models.put("minecraft:other", models(-0.35f, RENDERER).getObject("minecraft:test"));

        ToolingException raised = assertThrows(ToolingException.class, () ->
            EntityMeshShift.apply(diagnostics, models, geometries, Set.of()));
        assertTrue(raised.getMessage().contains("stands in one place"),
            "the refusal names the contradiction: " + raised.getMessage());
    }

}
