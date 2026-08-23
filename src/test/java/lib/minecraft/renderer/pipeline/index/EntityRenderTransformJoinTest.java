package lib.minecraft.renderer.pipeline.index;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join between a subject and what its renderer composes above every mesh it submits.
 *
 * <p>The join is on the renderer's simple name, so the model table's fully-qualified spelling has to
 * be narrowed to it - a subject joined on the internal name finds nothing and stands still, which
 * looks exactly like a renderer that composes nothing.
 *
 * <p><b>The refusal is the load-bearing half.</b> A {@code setupRotations} reaches this renderer by
 * two routes - the per-age {@code y_shift} applied to the mesh at load, and the transform composed
 * above it at render - and only one of them may answer for a subject. Both together move it twice,
 * which is a subject sitting at the wrong height with nothing to say why. The corpus has neither
 * conflict today, every renderer the shift claims being one the transform walk declines, so the
 * guard is what keeps that a fact rather than a coincidence.
 */
@DisplayName("a subject joins the transform its renderer composes")
class EntityRenderTransformJoinTest {

    private static final @NotNull String ENTITY = "minecraft:test";
    private static final @NotNull String COORD = "TestModel#createBodyLayer";
    private static final @NotNull String RENDERER = "net/minecraft/client/renderer/entity/TestRenderer";

    /** One step, standing for whatever a renderer composes - the join is what is under test. */
    private static final @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> TRANSFORMS = Map.of(
        "TestRenderer",
        List.of(Map.of(PoseChannel.Y, new PoseExpr.Const(-3d, PoseOperator.Width.FLOAT))));

    @Test
    @DisplayName("the row is found by the renderer's simple name, not by the class name the table writes")
    void theJoinNarrowsTheInternalName() {
        Entity subject = assemble(RENDERER, 0f);
        assertEquals(1, subject.renderTransform().size(), "the subject carries its renderer's step");
        assertEquals(-3f,
            subject.renderTransform().getFirst().get(PoseChannel.Y).constantValue().orElseThrow(),
            "and carries the step the table declared");
    }

    @Test
    @DisplayName("a renderer the table names no transform for leaves the subject composing nothing")
    void anUnnamedRendererComposesNothing() {
        assertTrue(assemble("net/minecraft/client/renderer/entity/OtherRenderer", 0f)
            .renderTransform().isEmpty(), "an unnamed renderer answers no steps");
    }

    @Test
    @DisplayName("a subject carrying both a transform and an age shift is refused rather than moved twice")
    void bothSpellingsOfOneSetupRotationsAreRefused() {
        PipelineException raised = assertThrows(PipelineException.class, () -> assemble(RENDERER, -0.7f));
        assertTrue(raised.getMessage().contains("would move it twice"),
            "the refusal names the doubling: " + raised.getMessage());
    }

    @Test
    @DisplayName("an age shift on its own is left alone, the shift being the older of the two spellings")
    void aShiftWithoutATransformStands() {
        assertTrue(assemble("net/minecraft/client/renderer/entity/OtherRenderer", -0.7f)
            .renderTransform().isEmpty(), "a shifted subject with no transform builds");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity assemble(@Nullable String renderer, float adultYShift) {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            renderer,        // renderer
            null,            // render
            null,            // rest
            null,            // bones
            null,            // overlays
            null,            // block_overlays
            null,            // layers
            ageAxis(adultYShift),  // axes
            null));          // group_of
        return EntityIndexBuilder.assemble(
            Map.of(COORD, mesh()), new RawEntityModelsFile(models), Map.of(), TRANSFORMS).get(ENTITY);
    }

    private static @NotNull RawAxes ageAxis(float adultYShift) {
        Map<String, RawAgeOption> options = new LinkedHashMap<>();
        options.put("adult", new RawAgeOption(COORD, "minecraft:textures/entity/test.png", adultYShift));
        return new RawAxes(null, new RawAgeAxis(options), null, null, null);
    }

    private static @NotNull EntityModelData mesh() {
        EntityModelData model = new EntityModelData();
        model.getBones().put("body", new EntityModelData.Bone());
        return model;
    }

}
