package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("Projection base poses + resolve")
class ProjectionTest {

    @Test
    @DisplayName("VANILLA_* base poses carry the documented vanilla angles")
    void vanillaBasePoses() {
        // Projection is the sole home of the vanilla iso angles (moved off EulerRotation).
        assertThat(Projection.VANILLA_BLOCK.basePose(), equalTo(new EulerRotation(30f, 225f, 0f)));
        assertThat(Projection.VANILLA_PLAYER.basePose(), equalTo(new EulerRotation(30f, 45f, 0f)));
        assertThat(Projection.VANILLA_ENTITY.basePose(), equalTo(new EulerRotation(210f, 45f, 0f)));
        assertThat(Projection.VANILLA_GUI_ITEM.basePose(), equalTo(EulerRotation.NONE));
    }

    @Test
    @DisplayName("resolve(NONE) keeps each member's base pose as the lighting pose")
    void resolveKeepsBasePose() {
        for (Projection projection : Projection.values())
            assertThat(projection + " lighting pose", projection.resolve().lightingPose(), equalTo(projection.basePose()));
    }

}
