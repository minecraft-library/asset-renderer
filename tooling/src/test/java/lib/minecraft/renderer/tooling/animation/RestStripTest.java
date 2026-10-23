package lib.minecraft.renderer.tooling.animation;

import dev.simplified.gson.JsonTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the model table stops stating once the fold has read it: each row's resting answer and
 * the age its baby or its aged-down size renders at, with every member a reader joins on left in
 * place.
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
    @DisplayName("an aged-down size option's age_scale comes off, and the option reads as it did before the flow stamped it")
    void theSizeOptionsAgeComesOff() {
        JsonTree small = JsonTree.object()
            .put("geometry", "ArmorStandModel#createBodyLayer@baby=HumanoidModel.BABY_TRANSFORMER")
            .put("pose", "ArmorStandModel")
            .put(PoseFlow.AGE_SCALE, 0.5f);
        JsonTree row = JsonTree.object().put("axes", JsonTree.object()
            .put("age", JsonTree.object().put("options", JsonTree.object()
                .put("adult", JsonTree.object().put("geometry", "ArmorStandModel#createBodyLayer"))))
            .put("size", JsonTree.object().put("default", "large").put("options", JsonTree.object().put("small", small))));
        JsonTree models = JsonTree.object().put("minecraft:armor_stand", row);

        RestStrip.apply(models);

        JsonTree stripped = models.find("minecraft:armor_stand").orElseThrow()
            .findPath("axes", "size", "options", "small").orElseThrow();
        assertEquals(List.of("geometry", "pose"), stripped.keys().toList(),
            "the generation-only age is gone and nothing a reader joins on moved");
    }

    @Test
    @DisplayName("an equipment row's item_field comes off, and the pose its slot's equipment option names stays")
    void theItemFieldComesOff() {
        JsonTree row = JsonTree.object();
        row.childArray("equipment").add(JsonTree.object()
            .put("slot", "body")
            .put(PoseFlow.ITEM_FIELD, "bodyItem"));
        row.child("axes").child(PoseFlow.EQUIPMENT_AXIS).child("options").child("body")
            .put("pose", "HappyGhastModel@bodyItem.isEmpty=false");
        JsonTree models = JsonTree.object().put("minecraft:happy_ghast", row);

        RestStrip.apply(models);

        JsonTree stripped = models.find("minecraft:happy_ghast").orElseThrow();
        JsonTree layer = stripped.find("equipment").orElseThrow().elements().toList().getFirst();
        assertEquals(List.of("slot"), layer.keys().toList(), "the field the fold read is generation-only");
        assertEquals("HappyGhastModel@bodyItem.isEmpty=false",
            stripped.findPath("axes", PoseFlow.EQUIPMENT_AXIS, "options", "body").orElseThrow()
                .findString("pose").orElseThrow(),
            "the wearer pose is what a reader joins on and stays");
    }

    @Test
    @DisplayName("a block row's carried_field comes off, and the pose the carried axis names stays")
    void theCarriedFieldComesOff() {
        JsonTree row = JsonTree.object();
        row.childArray("block_overlays").add(JsonTree.object()
            .put("source", "CarriedBlockLayer")
            .put("selectable", true)
            .put(PoseFlow.CARRIED_FIELD, "carriedBlock"));
        row.child("axes").child(PoseFlow.CARRIED_AXIS).child("options").child("block")
            .put("pose", "EndermanModel@carriedBlock.isEmpty=false");
        JsonTree models = JsonTree.object().put("minecraft:enderman", row);

        RestStrip.apply(models);

        JsonTree stripped = models.find("minecraft:enderman").orElseThrow();
        JsonTree block = stripped.find("block_overlays").orElseThrow().elements().toList().getFirst();
        assertFalse(block.find(PoseFlow.CARRIED_FIELD).isPresent(), "the field the fold read is generation-only");
        assertEquals(List.of("source", "selectable"), block.keys().toList(), "and nothing else on the row moved");
        assertEquals(List.of("pose"),
            stripped.findPath("axes", PoseFlow.CARRIED_AXIS, "options", "block").orElseThrow().keys().toList(),
            "the wearer pose is what a reader joins on and stays");
    }

}
