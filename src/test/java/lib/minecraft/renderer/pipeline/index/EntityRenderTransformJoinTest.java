package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join between a subject and what its renderer composes above every mesh it submits.
 *
 * <p>The join is on the renderer's simple name, so the model table's fully-qualified spelling has to
 * be narrowed to it - a subject joined on the internal name finds nothing and stands still, which
 * looks exactly like a renderer that composes nothing. What the join lands is a composed POSE: the
 * renderer's steps are seated at the front of the container of every pose the subject's meshes take,
 * once at load, so nothing at render composes them again.
 *
 * <p><b>The two halves of that container are in different frames, and the join is where they meet.</b>
 * A renderer turns the subject about the ground it stands on, a model's own container turns it about
 * the mesh origin, and vanilla's translate between the two is a step like any other - so the sequence
 * carries it, and its position in the sequence is what the ordering test pins.
 *
 * <p><b>The refusal is the load-bearing half.</b> A {@code setupRotations} reaches this renderer by
 * two routes - the per-age {@code y_shift} applied to the mesh, and the transform seated on the
 * pose - and only one of them may answer for a subject. Both together move it twice, which is a
 * subject sitting at the wrong height with nothing to say why. The corpus has neither conflict
 * today, every renderer the shift claims being one the transform walk declines, so the guard is
 * what keeps that a fact rather than a coincidence.
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

    /**
     * What the step closing a renderer's sequence translates by, spelled here rather than read off
     * the builder - a test that took the constant from the code under test would pin nothing, and the
     * number is vanilla's own {@code translate(0, -1.501, 0)} in the model pixels a pivot is in.
     */
    private static final float GROUND_FRAME_Y = -1.501f * 16f;

    @Test
    @DisplayName("the row is found by the renderer's simple name, not by the class name the table writes")
    void theJoinNarrowsTheInternalName() {
        Entity subject = assemble(RENDERER, Map.of());
        List<Map<PoseChannel, PoseExpr>> container = subject.pose().container();
        assertEquals(2, container.size(), "the subject's pose carries its renderer's step and the frame");
        assertEquals(-3f, container.getFirst().get(PoseChannel.Y).constantValue().orElseThrow(),
            "and carries the step the table declared");
    }

    @Test
    @DisplayName("the renderer's step is seated outermost, above the frame, above the model's own container")
    void theRendererStepIsOutermost() {
        // Vanilla composes setupRotations onto the pose stack before the model poses anything, so
        // what the renderer carries has to sit ABOVE what the model's own container holds. The two
        // are the same shape and compose in one direction only: swapping them turns the subject about
        // the wrong origin, which renders and looks deliberate.
        //
        // Between them is the frame the two are expressed in, which is the same statement read one
        // level down: a renderer turns the subject about the ground it stands on and a model's own
        // container turns it about the mesh origin, and the step carrying one frame to the other is
        // vanilla's own translate. Seated anywhere but here it turns the wrong half of the sequence.
        EntityPose modelPose = new EntityPose(
            Concurrent.newUnmodifiableList(Map.of(PoseChannel.Y, new PoseExpr.Const(-7d, PoseOperator.Width.FLOAT))),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty());
        Entity subject = assemble(RENDERER, Map.of("TestModel", modelPose));
        List<Map<PoseChannel, PoseExpr>> container = subject.pose().container();
        assertEquals(3, container.size(), "the pose gains a step and a frame, and keeps its own");
        assertEquals(-3f, container.get(0).get(PoseChannel.Y).constantValue().orElseThrow(),
            "the renderer's step is the outer one");
        assertEquals(GROUND_FRAME_Y, container.get(1).get(PoseChannel.Y).constantValue().orElseThrow(),
            "the frame crossing is between them");
        assertEquals(-7f, container.get(2).get(PoseChannel.Y).constantValue().orElseThrow(),
            "the model's own is the inner one");
    }

    @Test
    @DisplayName("a renderer the table names no transform for leaves the subject composing nothing")
    void anUnnamedRendererComposesNothing() {
        assertTrue(assemble("net/minecraft/client/renderer/entity/OtherRenderer", Map.of())
            .pose().container().isEmpty(), "an unnamed renderer answers no steps");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Entity assemble(
        @Nullable String renderer, @NotNull Map<String, EntityPose> poses) {

        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            renderer,        // renderer
            null,            // render
            null,            // rest
            null,            // bones
            null,            // overlays
            null,            // block_overlays
            null,            // armor
            null,            // equipment
            ageAxis(),  // axes
            null));          // members
        return EntityIndexBuilder.assemble(
            Map.of(COORD, mesh()), new RawEntityModelsFile(models), poses, TRANSFORMS).get(ENTITY);
    }

    private static @NotNull RawAxes ageAxis() {
        Map<String, RawOption> options = new LinkedHashMap<>();
        options.put("adult", new RawOption(COORD, "test", null, null, null, null, null));
        return new RawAxes(null, new RawAxis(null, options), null, null, null);
    }

    private static @NotNull EntityModelData mesh() {
        EntityModelData model = new EntityModelData();
        model.getBones().put("body", new EntityModelData.Bone());
        return model;
    }

}
