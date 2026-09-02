package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pose table's grammar, read at its refusals as well as its reads.
 *
 * <p>The reader's arms are what the emitter writes and nothing more, so what its grammar dropped
 * refuses rather than reading as something else: a {@code renderers} table, a flag channel, a drive
 * token no play site takes, and a selection naming no field. Each refusal is pinned from a written
 * table rather than from the shipped one, the shipped table carrying none of them.
 */
@DisplayName("the pose table's grammar")
class EntityPoseFormatTest {

    /** Plain, because the reader is declared on the type it reads rather than configured onto one. */
    private static final @NotNull Gson GSON = new Gson();

    /** The clip tables the play sites resolve against - what varies per test is the sites alone. */
    private static final @NotNull String CLIPS = """
        "clips": {
          "TestAnimation#JUMP": { "length": 1.0, "channels": [ { "bone": "body", "target": "position",
              "keyframes": [ { "time": 0.0, "value": [0.0, 1.0, 0.0], "curve": "linear" } ] } ] },
          "TestAnimation#WALK": { "length": 0.5, "looping": true, "channels": [ { "bone": "body", "target": "rotation",
              "keyframes": [ { "time": 0.0, "value": [10.0, 0.0, 0.0], "curve": "linear" } ] } ] },
          "TestAnimation#REST": { "length": 0.25, "channels": [ { "bone": "head", "target": "scale",
              "keyframes": [ { "time": 0.0, "value": [1.0, 1.0, 1.0], "curve": "catmullrom" } ] } ] }
        }""";

    /** One model in the shipped grammar: drive/field play sites, container complete, no renderers. */
    private static final @NotNull String TABLE = """
        { "format": 3,
          %s,
          "poses": { "TestModel": {
            "shared": [ { "const": 0.25 } ],
            "container": [ { "y": { "ref": 0 } } ],
            "bones": { "head": { "x_rot": { "ref": 0 }, "y_rot": { "input": "ageInTicks" } } },
            "clips": [
              { "clip": "TestAnimation#JUMP", "drive": "select", "field": "jumpAnimationState",
                "args": [ { "const": 1.0 } ] },
              { "clip": "TestAnimation#WALK", "drive": "stride",
                "args": [ { "const": 1.0 }, { "const": 2.5 },
                          { "input": "walkAnimationPos" }, { "input": "walkAnimationSpeed" } ] },
              { "clip": "TestAnimation#REST", "drive": "none" }
            ] } } }""".formatted(CLIPS);

    @Test
    @DisplayName("a table loads its container, bones and play sites, each site carrying its drive")
    void theGrammarLoadsWhole() {
        EntityPose pose = GSON.fromJson(TABLE, RawEntityPosesFile.class).poses().get("TestModel");

        assertTrue(pose.isReadable(), "a table carrying no refusal is readable");
        assertEquals(1, pose.container().size(), "the container arrives complete, one step as written");
        assertEquals(2, pose.bones().get("head").size(), "and the bones carry the channels they write");

        List<EntityPose.Clip> clips = pose.clips();
        assertEquals(MotionSource.SELECT, clips.get(0).drive(), "a select site carries its drive");
        assertEquals(Optional.of("jumpAnimationState"), clips.get(0).field(), "and names its field");
        assertEquals(MotionSource.STRIDE, clips.get(1).drive(), "a stride site carries its drive");
        assertEquals(Optional.empty(), clips.get(1).field(), "and names no field");
        assertEquals(MotionSource.NONE, clips.get(2).drive(), "a still site carries its drive");
        assertEquals(Optional.empty(), clips.get(2).field(), "and names no field");
    }

    @Test
    @DisplayName("a table carrying a renderers member is refused")
    void aRenderersMemberIsRefused() {
        PipelineException raised = assertThrows(PipelineException.class, () -> GSON.fromJson("""
            { "format": 3, "renderers": {}, "poses": {} }""", RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("renderers"),
            "the refusal names the member: " + raised.getMessage());
    }

    @Test
    @DisplayName("a flag channel is the unknown channel it is")
    void aFlagChannelIsRefused() {
        PipelineException raised = assertThrows(PipelineException.class, () -> GSON.fromJson("""
            { "format": 3, "poses": { "TestModel": {
              "bones": { "head": { "visible": { "const": 1.0 } } } } } }""", RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("not a channel"),
            "which bones a subject rests without is the model table's fact: " + raised.getMessage());
    }

    @Test
    @DisplayName("a drive token that is no drive at all is refused")
    void anUnknownDriveTokenIsRefused() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> GSON.fromJson(site("\"drive\": \"bogus\""), RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("'bogus'"),
            "the refusal names the token: " + raised.getMessage());
    }

    @Test
    @DisplayName("a drive kind no play site takes is refused, even though it is a drive")
    void aNonSiteDriveIsRefused() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> GSON.fromJson(site("\"drive\": \"figure\""), RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("no play site takes"),
            "the refusal says why: " + raised.getMessage());
    }

    @Test
    @DisplayName("a select site naming no field is refused")
    void aSelectSiteWithoutAFieldIsRefused() {
        PipelineException raised = assertThrows(PipelineException.class,
            () -> GSON.fromJson(site("\"drive\": \"select\""), RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("names nowhere"),
            "the refusal says what is missing: " + raised.getMessage());
    }

    /** A table whose one play site carries the given members beside its coordinate. */
    private static @NotNull String site(@NotNull String members) {
        return """
            { "format": 3,
              "clips": { "TestAnimation#JUMP": { "length": 1.0 } },
              "poses": { "TestModel": {
                "clips": [ { "clip": "TestAnimation#JUMP", %s } ] } } }""".formatted(members);
    }

}
