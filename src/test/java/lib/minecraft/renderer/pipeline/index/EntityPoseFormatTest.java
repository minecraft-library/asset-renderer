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
 * The pose table's two grammars, read against each other.
 *
 * <p>A format 2 and a format 3 spelling of one table have to load to EQUAL in-memory poses,
 * because the dual-read exists so the emitted bytes can move without the loaded shape moving - a
 * reader whose two arms disagree turns the byte cutover into a silent behaviour change. And the
 * format 3 arm has to refuse what its grammar dropped: a {@code renderers} table, a flag channel,
 * a drive token no play site takes, and a selection naming no field.
 */
@DisplayName("the pose table's two grammars")
class EntityPoseFormatTest {

    /** Plain, because the reader is declared on the type it reads rather than configured onto one. */
    private static final @NotNull Gson GSON = new Gson();

    /** The clip tables both spellings share - the play-site grammar is what differs, never these. */
    private static final @NotNull String CLIPS = """
        "clips": {
          "TestAnimation#JUMP": { "length": 1.0, "channels": [ { "bone": "body", "target": "position",
              "keyframes": [ { "time": 0.0, "value": [0.0, 1.0, 0.0], "curve": "linear" } ] } ] },
          "TestAnimation#WALK": { "length": 0.5, "looping": true, "channels": [ { "bone": "body", "target": "rotation",
              "keyframes": [ { "time": 0.0, "value": [10.0, 0.0, 0.0], "curve": "linear" } ] } ] },
          "TestAnimation#REST": { "length": 0.25, "channels": [ { "bone": "head", "target": "scale",
              "keyframes": [ { "time": 0.0, "value": [1.0, 1.0, 1.0], "curve": "catmullrom" } ] } ] }
        }""";

    /** One model, spelled in the format 2 grammar: gate/state play sites over a shared table. */
    private static final @NotNull String FORMAT_TWO = """
        { "format": 2,
          %s,
          "poses": { "TestModel": {
            "shared": [ { "const": 0.25 } ],
            "container": [ { "y": { "ref": 0 } } ],
            "bones": { "head": { "x_rot": { "ref": 0 }, "y_rot": { "input": "ageInTicks" } } },
            "clips": [
              { "clip": "TestAnimation#JUMP", "gate": "state", "state": "jumpAnimationState",
                "args": [ { "const": 1.0 } ] },
              { "clip": "TestAnimation#WALK", "gate": "walk",
                "args": [ { "const": 1.0 }, { "const": 2.5 },
                          { "input": "walkAnimationPos" }, { "input": "walkAnimationSpeed" } ] },
              { "clip": "TestAnimation#REST", "gate": "static" }
            ] } } }""".formatted(CLIPS);

    /** The same model in the format 3 grammar: drive/field play sites, container complete, no renderers. */
    private static final @NotNull String FORMAT_THREE = """
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
    @DisplayName("one table spelled in both grammars loads to equal poses")
    void bothGrammarsLoadToEqualPoses() {
        EntityPose two = GSON.fromJson(FORMAT_TWO, RawEntityPosesFile.class).poses().get("TestModel");
        EntityPose three = GSON.fromJson(FORMAT_THREE, RawEntityPosesFile.class).poses().get("TestModel");

        assertEquals(two, three, "the two spellings are one pose in memory");
        // Pinned member by member too, so a disagreement names the half that moved rather than
        // printing two whole poses.
        assertEquals(two.container(), three.container(), "the container arrives the same");
        assertEquals(two.bones(), three.bones(), "the bones arrive the same");
        assertEquals(two.clips(), three.clips(), "the play sites arrive the same");

        List<EntityPose.Clip> clips = three.clips();
        assertEquals(MotionSource.SELECT, clips.get(0).drive(), "a select site carries its drive");
        assertEquals(Optional.of("jumpAnimationState"), clips.get(0).field(), "and names its field");
        assertEquals(MotionSource.STRIDE, clips.get(1).drive(), "a stride site carries its drive");
        assertEquals(Optional.empty(), clips.get(1).field(), "and names no field");
        assertEquals(MotionSource.NONE, clips.get(2).drive(), "a still site carries its drive");
        assertEquals(Optional.empty(), clips.get(2).field(), "and names no field");
    }

    @Test
    @DisplayName("a format 3 table carrying a renderers member is refused")
    void formatThreeRefusesRenderers() {
        PipelineException raised = assertThrows(PipelineException.class, () -> GSON.fromJson("""
            { "format": 3, "renderers": {}, "poses": {} }""", RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("renderers"),
            "the refusal names the member: " + raised.getMessage());
    }

    @Test
    @DisplayName("a flag channel refuses under format 3, and skips under format 2")
    void flagChannelsAreFormatTwosAlone() {
        String bones = """
            { "format": %d, "poses": { "TestModel": {
              "bones": { "head": { "visible": { "const": 1.0 } } } } } }""";

        PipelineException raised = assertThrows(PipelineException.class,
            () -> GSON.fromJson(bones.formatted(3), RawEntityPosesFile.class));
        assertTrue(raised.getMessage().contains("not a channel"),
            "format 3 reads it as the unknown channel it is: " + raised.getMessage());

        EntityPose skipped = GSON.fromJson(bones.formatted(2), RawEntityPosesFile.class)
            .poses().get("TestModel");
        assertEquals(List.of(), List.copyOf(skipped.bones().get("head").keySet()),
            "format 2 skips the flag channel it still ships");
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

    /** A format 3 table whose one play site carries the given members beside its coordinate. */
    private static @NotNull String site(@NotNull String members) {
        return """
            { "format": 3,
              "clips": { "TestAnimation#JUMP": { "length": 1.0 } },
              "poses": { "TestModel": {
                "clips": [ { "clip": "TestAnimation#JUMP", %s } ] } } }""".formatted(members);
    }

}
