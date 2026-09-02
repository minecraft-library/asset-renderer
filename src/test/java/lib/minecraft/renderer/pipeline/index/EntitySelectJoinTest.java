package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The select-join validation the assembler runs over every play site: a {@code select} site's
 * field is either driven by one of the entity's own style rows, or driven by no family's rows at
 * all - and a field another family's rows drive while this entity's do not refuses the load.
 *
 * <p>The tolerated arm is the corpus's own shape: the baby axolotl's locomotion clip sits behind
 * {@code walkAnimationState}, a field no row of any family drives, so the site rests and resting is
 * the shipped answer. What the refusal catches is cross-wiring - a site gated on a selection the
 * entity's own catalog cannot reach, which otherwise plays nothing at render and says so nowhere.
 */
@DisplayName("a select site joins a style row or refuses at load")
class EntitySelectJoinTest {

    /** Plain, because every reader here is declared on the type it reads. */
    private static final @NotNull Gson GSON = new Gson();

    private static final @NotNull String COORD = "SelectModel#createBodyLayer";
    private static final @NotNull String BABY_COORD = "BabySelectModel#createBodyLayer";

    /** A file whose first family's rows drive the field the second family's site is gated on. */
    private static final @NotNull String CROSS_WIRED = """
        { "period_ticks": 24,
          "models": {
            "minecraft:styled": {
              "axes": { "age": { "options": {
                  "adult": { "geometry": "SelectModel#createBodyLayer", "texture": "styled",
                             "pose": "OtherModel" } } } },
              "styles": [
                { "id": "croak", "sources": [ "select" ],
                  "drives": [ { "field": "croakAnimationState", "wave": "hold" } ] } ] },
            "minecraft:wired": {
              "axes": { "age": { "options": {
                  "adult": { "geometry": "SelectModel#createBodyLayer", "texture": "wired" } } } } }
          } }""";

    @Test
    @DisplayName("a site whose field the entity's own rows drive loads clean")
    void anOwnDrivenFieldLoadsClean() {
        Entity built = assemble("""
            { "period_ticks": 24,
              "models": { "minecraft:styled": {
                "axes": { "age": { "options": {
                    "adult": { "geometry": "SelectModel#createBodyLayer", "texture": "styled" } } } },
                "styles": [
                  { "id": "croak", "sources": [ "select" ],
                    "drives": [ { "field": "croakAnimationState", "wave": "hold" } ] } ] } } }""",
            Map.of("SelectModel", selecting("croakAnimationState")))
            .get("minecraft:styled");
        assertNotNull(built, "a joined selection is expected to assemble");
    }

    @Test
    @DisplayName("a site gated on another family's field refuses the load, naming both")
    void aCrossWiredFieldRefuses() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> assemble(CROSS_WIRED, Map.of("SelectModel", selecting("croakAnimationState"))));
        assertTrue(raised.getMessage().contains("minecraft:wired"),
            "the refusal names the entity: " + raised.getMessage());
        assertTrue(raised.getMessage().contains("croakAnimationState"),
            "and the field: " + raised.getMessage());
    }

    @Test
    @DisplayName("a site gated on a field no family drives is tolerated as the resting answer")
    void anUnansweredFieldIsTolerated() {
        Entity built = assemble(CROSS_WIRED, Map.of("SelectModel", selecting("walkAnimationState")))
            .get("minecraft:wired");
        assertNotNull(built,
            "a field no row of any family drives is the roster's no-answer token and loads clean");
    }

    @Test
    @DisplayName("a baby form's site is validated against the same catalog")
    void aBabyPoseSiteIsValidated() {
        String aged = """
            { "period_ticks": 24,
              "models": {
                "minecraft:styled": {
                  "axes": { "age": { "options": {
                      "adult": { "geometry": "SelectModel#createBodyLayer", "texture": "styled",
                                 "pose": "OtherModel" } } } },
                  "styles": [
                    { "id": "croak", "sources": [ "select" ],
                      "drives": [ { "field": "croakAnimationState", "wave": "hold" } ] } ] },
                "minecraft:wired": {
                  "axes": { "age": { "options": {
                      "adult": { "geometry": "SelectModel#createBodyLayer", "texture": "wired",
                                 "pose": "OtherModel" },
                      "baby": { "geometry": "BabySelectModel#createBodyLayer" } } } } }
              } }""";
        PipelineException raised = assertThrows(PipelineException.class,
            () -> assemble(aged, Map.of("BabySelectModel", selecting("croakAnimationState"))));
        assertTrue(raised.getMessage().contains("minecraft:wired"),
            "the refusal reaches the baby fork: " + raised.getMessage());
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull ConcurrentMap<String, Entity> assemble(
        @NotNull String json, @NotNull Map<String, EntityPose> poses) {

        RawEntityModelsFile raw = GSON.fromJson(json, RawEntityModelsFile.class);
        return EntityIndexBuilder.assemble(
            Map.of(COORD, mesh(), BABY_COORD, mesh()), raw, poses);
    }

    /** A pose whose one play site is a selection gated on the given render-state field. */
    private static @NotNull EntityPose selecting(@NotNull String field) {
        return new EntityPose(
            Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(),
            Concurrent.newUnmodifiableList(new EntityPose.Clip(
                "test/clip", MotionSource.SELECT, Optional.of(field),
                Concurrent.newUnmodifiableList(),
                new PoseClip(1f, false, Concurrent.newUnmodifiableList()))),
            Optional.empty());
    }

    private static @NotNull EntityModelData mesh() {
        EntityModelData model = new EntityModelData();
        model.getBones().put("body", new EntityModelData.Bone());
        return model;
    }

}
