package lib.minecraft.renderer.content.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxes;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxis;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawModel;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOption;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlay;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawTextureScroll;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pass's render type translates its texture by, joined onto the pass and read at a tick.
 *
 * <p>The offset is wrapped where vanilla wraps it - into the factory's own argument - rather than
 * left to the fetch. The two disagree once the authored coordinate and the offset are on different
 * whole turns, which is where a sheet's own seam falls, so wrapping late samples a texel vanilla
 * never reaches.
 *
 * <p>The refusal holds the rows to the direction vanilla runs: the offset is built from age, which
 * only runs forward, so a negative rate is a transcription error rather than a pass. No shipped row
 * is negative and this is what keeps that a fact rather than an assumption.
 */
@DisplayName("a pass samples where its render type says")
class EntityTextureScrollJoinTest {

    private static final @NotNull String ENTITY = "minecraft:test";
    private static final @NotNull String COORD = "TestModel#createBodyLayer";

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("the breeze's wind pass carries the rate its own layer builds the render type with")
    void theWindPassCarriesItsRate() {
        Entity breeze = entities.get("minecraft:breeze");
        assertNotNull(breeze, "the corpus carries a breeze");

        List<Entity.OverlayLayer> scrolling = breeze.overlays().stream()
            .filter(pass -> pass.textureScroll().isPresent())
            .toList();
        assertEquals(1, scrolling.size(), "one of its passes scrolls");
        assertEquals(0.02f, scrolling.getFirst().textureScroll().orElseThrow().x(),
            "along u, at the rate BreezeWindLayer multiplies the age by");
        assertEquals(0f, scrolling.getFirst().textureScroll().orElseThrow().y(),
            "and along v by nothing");
    }

    @Test
    @DisplayName("an offset wraps where vanilla wraps it, before it reaches a coordinate")
    void theOffsetWrapsBeforeItIsAdded() {
        Entity.OverlayLayer pass = scrolls(0.02f, 0f);

        assertTrue(pass.textureOffsetAt(0).isPresent(), "a scrolling pass answers at every tick");
        assertEquals(0f, pass.textureOffsetAt(0).orElseThrow().x(), "a subject that has not aged samples where it is");
        assertEquals(3 * 0.02f, pass.textureOffsetAt(3).orElseThrow().x(), "and three ticks on, three ticks along");
        // Fifty ticks is one whole turn of the sheet at this rate, so the pass is back where it
        // started - which is the wrap, and the reason it is taken here rather than at the fetch.
        assertEquals(50 * 0.02f % 1f, pass.textureOffsetAt(50).orElseThrow().x(),
            "a whole turn later it samples a whole turn along, which is where it began");
        assertTrue(pass.textureOffsetAt(60).orElseThrow().x() < 1f, "and never past one");
    }

    @Test
    @DisplayName("a pass that scrolls nothing answers no offset rather than a zero one")
    void aStillPassAnswersNothing() {
        assertTrue(new Entity.OverlayLayer(new EntityMesh(), Optional.empty(),
            PassDeclaration.DEFAULT, 0, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), EntityPose.NONE).textureOffsetAt(7).isEmpty(),
            "a pass with no rate has no offset to answer, at any tick");
    }

    @Test
    @DisplayName("a scroll running backwards is refused rather than sampled off the sheet")
    void aBackwardsScrollIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> assemble(-0.02f, 0f));
        assertTrue(raised.getMessage().contains("a scroll runs forward"),
            "the refusal names the direction: " + raised.getMessage());
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity.OverlayLayer scrolls(float u, float v) {
        Entity built = assemble(u, v);
        assertEquals(1, built.overlays().size(), "the fixture draws its one pass");
        return built.overlays().getFirst();
    }

    private static @NotNull Entity assemble(float u, float v) {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            null,                                   // renderer
            null,                                   // render
            List.of(new RawOverlay(
                null,                               // geometry
                null,                               // no_hat_geometry
                "test",  // texture
                null,                               // tint
                null,                               // tint_by
                null,                               // texture_by
                null,                               // pipeline
                new RawTextureScroll(u, v),         // texture_scroll
                false,                              // skip_bounds
                null,                               // when
                null)),                             // baby
            null,                                   // block_overlays
            null,                                   // armor
            null,                                   // wings
            null,                                   // equipment
            ageAxis(),                              // axes
            null,                                   // members
            null));                                 // styles
        return EntityIndexBuilder.assemble(
            Map.of(COORD, mesh()), new EntityModelsTable(models, null), Map.of()).get(ENTITY);
    }

    private static @NotNull RawAxes ageAxis() {
        Map<String, RawOption> options = new LinkedHashMap<>();
        options.put("adult", new RawOption(COORD, "test", null, null, null, null, null, null));
        return new RawAxes(null, new RawAxis(null, options), null, null, null);
    }

    private static @NotNull EntityMesh mesh() {
        EntityMesh model = new EntityMesh();
        model.getBones().put("body", new EntityMesh.Bone());
        return model;
    }

}
