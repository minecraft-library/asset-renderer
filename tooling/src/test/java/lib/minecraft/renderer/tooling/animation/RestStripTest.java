package lib.minecraft.renderer.tooling.animation;

import dev.simplified.gson.JsonTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the model table stops stating once the fold has read it: each row's resting answer and
 * the age its baby renders at, with every member a reader joins on left in place.
 */
@DisplayName("what the model table stops stating once the fold has read it")
class RestStripTest {

    @Test
    @DisplayName("the rest and the baby's age_scale come off, and the baby's mesh stays")
    void theRestAndTheBabysAgeComeOff() {
        JsonTree baby = JsonTree.object()
            .put("geometry", "BabyHorseModel#createBabyMesh")
            .put(PoseFlow.AGE_SCALE, 0.5f);
        JsonTree row = JsonTree.object()
            .put("rest", JsonTree.object().put("isSaddled", "false"))
            .put("axes", JsonTree.object().put("age", JsonTree.object().put("options", JsonTree.object()
                .put("adult", JsonTree.object().put("geometry", "HorseModel#createBodyLayer"))
                .put("baby", baby))));
        JsonTree models = JsonTree.object().put("minecraft:horse", row);

        RestStrip.apply(models);

        JsonTree stripped = models.find("minecraft:horse").orElseThrow();
        JsonTree strippedBaby = stripped.findPath("axes", "age", "options", "baby").orElseThrow();
        assertFalse(stripped.find("rest").isPresent(), "the resting answer is folded and comes off");
        assertFalse(strippedBaby.find(PoseFlow.AGE_SCALE).isPresent(), "and so does the age the baby rendered at");
        assertEquals("BabyHorseModel#createBabyMesh", strippedBaby.findString("geometry").orElseThrow(),
            "the baby's mesh is what a reader joins on and stays");
        assertTrue(stripped.findPath("axes", "age", "options", "adult").isPresent(), "the adult option stays");
    }

    @Test
    @DisplayName("an equipment row's item_field comes off, and its wearer_pose stays")
    void theItemFieldComesOff() {
        JsonTree row = JsonTree.object();
        row.childArray("equipment").add(JsonTree.object()
            .put("slot", "body")
            .put(PoseFlow.ITEM_FIELD, "bodyItem")
            .put("wearer_pose", "HappyGhastModel@bodyItem.isEmpty=false"));
        JsonTree models = JsonTree.object().put("minecraft:happy_ghast", row);

        RestStrip.apply(models);

        JsonTree layer = models.find("minecraft:happy_ghast").orElseThrow().find("equipment").orElseThrow()
            .elements().toList().getFirst();
        assertFalse(layer.find(PoseFlow.ITEM_FIELD).isPresent(), "the field the fold read is generation-only");
        assertEquals("HappyGhastModel@bodyItem.isEmpty=false", layer.findString("wearer_pose").orElseThrow(),
            "the wearer pose is what a reader joins on and stays");
    }

}
