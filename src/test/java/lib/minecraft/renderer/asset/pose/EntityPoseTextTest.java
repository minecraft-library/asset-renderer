package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link EntityPose}'s text form, which is bounded because the pose nodes it holds are - each node
 * names itself and refers to its children, so the pose prints the nodes it holds rather than the paths
 * their shared graph stands for.
 */
@DisplayName("an entity pose prints bounded because the nodes it holds do")
class EntityPoseTextTest {

    @Test
    @DisplayName("a pose holding a ladder prints bounded too - the arms bind everything that carries them")
    void aPoseHoldingALadderPrintsBounded() {
        PoseExpr rung = new PoseExpr.Constant(0.25d, PoseWidth.DOUBLE);
        for (int height = 0; height < 40; height++)
            rung = new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(rung, rung));
        EntityPose pose = new EntityPose(
            Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", Map.of(PoseChannel.X_ROT, rung))),
            Concurrent.newUnmodifiableList(),
            Optional.empty());

        assertTrue(pose.toString().length() < 4096,
            "the pose grows with the nodes it names, never with the paths they stand for");
    }

}
