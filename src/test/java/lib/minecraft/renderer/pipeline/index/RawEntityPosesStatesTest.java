package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state silhouettes a pose row carries - read into the pose beside its channels, each over a
 * shared table of its own, and absent where the row spells none.
 */
@DisplayName("a pose row's state silhouettes read as the bones they place")
class RawEntityPosesStatesTest {

    private static final @NotNull Gson GSON = new Gson();

    /**
     * A row whose sitting state lowers the body to a literal, places the tail relative to its
     * authored pivot, and reaches one sub-expression from two channels.
     */
    private static final @NotNull String SITTING = """
        {"format": 3, "poses": {"Wolf": {
          "bones": {"body": {"y": {"bone": ["body", "y"]}, "x_rot": {"const": 1.5707964}}},
          "states": {"isSitting=true": {
            "shared": [{"add": [{"bone": ["tail", "y"]}, {"const": 9.0}]}],
            "bones": {
              "body": {"y": {"const": 18.0}, "x_rot": {"const": 0.7853982}},
              "tail": {"y": {"ref": 0}, "z": {"ref": 0}}
            }}}}}}
        """;

    @Test
    @DisplayName("each state's bones read under the answer that reaches it")
    void statesReadUnderTheirKeys() {
        EntityPose pose = load(SITTING, "Wolf");

        assertEquals(Set.of("isSitting=true"), pose.states().keySet());
        Map<String, Map<PoseChannel, PoseExpr>> placed = pose.states().get("isSitting=true").bones();
        assertEquals(List.of("body", "tail"), List.copyOf(placed.keySet()), "bones read in file order");
        PoseExpr.Constant lowered = assertInstanceOf(PoseExpr.Constant.class, placed.get("body").get(PoseChannel.Y));
        assertEquals(18f, (float) lowered.value(), "a literal reads at the width its token names");
        assertEquals(PoseOperator.Width.FLOAT, lowered.width());
        PoseExpr.Op relative = assertInstanceOf(PoseExpr.Op.class, placed.get("tail").get(PoseChannel.Y));
        assertInstanceOf(PoseExpr.BoneRead.class, relative.operands().getFirst(),
            "a placement relative to the authored pivot keeps its read of the mesh");
    }

    @Test
    @DisplayName("a state's shared table resolves to one instance per entry, scoped to that state")
    void stateSharedTableResolvesOnce() {
        EntityPose pose = load(SITTING, "Wolf");
        Map<PoseChannel, PoseExpr> tail = pose.states().get("isSitting=true").bones().get("tail");

        assertSame(tail.get(PoseChannel.Y), tail.get(PoseChannel.Z),
            "both references resolve to the same record instance");
    }

    /**
     * The same row spelling no state member.
     */
    private static final @NotNull String STANDING = """
        {"format": 3, "poses": {"Wolf": {
          "bones": {"body": {"y": {"bone": ["body", "y"]}, "x_rot": {"const": 1.5707964}}}}}}
        """;

    @Test
    @DisplayName("the row's own channels are read exactly as they are without the member")
    void rowChannelsAreUntouchedByTheMember() {
        EntityPose carrying = load(SITTING, "Wolf");
        EntityPose bare = load(STANDING, "Wolf");

        assertTrue(bare.states().isEmpty(), "a row spelling no member carries no silhouette");
        assertEquals(bare.bones(), carrying.bones(), "the row's bones read identically either way");
        assertEquals(bare.container(), carrying.container());
    }

    @Test
    @DisplayName("a state declaring a shared entry nothing names refuses, like a row would")
    void unreadStateEntryRefuses() {
        String dangling = """
            {"format": 3, "poses": {"Wolf": {"bones": {},
              "states": {"isSitting=true": {"shared": [{"const": 1.0}], "bones": {"body": {"y": {"const": 18.0}}}}}}}}
            """;

        PipelineException refused = assertThrows(PipelineException.class, () -> load(dangling, "Wolf"));
        assertTrue(refused.getMessage().contains("isSitting=true"), refused.getMessage());
        assertTrue(refused.getMessage().contains("nothing names"), refused.getMessage());
    }

    @Test
    @DisplayName("a refused row carries no silhouette")
    void refusedRowCarriesNone() {
        EntityPose refused = load("{\"format\": 3, \"poses\": {\"Wolf\": {\"refused\": \"no body\"}}}", "Wolf");

        assertTrue(refused.states().isEmpty());
        assertTrue(refused.refusal().isPresent());
    }

    private static @NotNull EntityPose load(@NotNull String text, @NotNull String model) {
        return GSON.fromJson(text, RawEntityPosesFile.class).poses().get(model);
    }

}
