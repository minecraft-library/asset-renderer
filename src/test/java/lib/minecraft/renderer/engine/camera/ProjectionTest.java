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
        // Projection is the sole home of the vanilla iso angles (moved off EulerRotation). All three
        // VANILLA_* iso members share the facing-NEUTRAL block-icon [30,225,0] angle; each renderer applies
        // its own facing as a Placement (block IDENTITY; player R_Y(180); entity R_Y(180)·flip180 =
        // diag(-1,-1,1)), so every projection presents the subject's front. The harness [210,45,0] lives on
        // only as the entity kit's lighting angle (EntityGeometryKit.ENTITY_ISO_LIGHTING).
        assertThat(Projection.VANILLA_BLOCK.basePose(), equalTo(new EulerRotation(30f, 225f, 0f)));
        assertThat(Projection.VANILLA_PLAYER.basePose(), equalTo(new EulerRotation(30f, 225f, 0f)));
        assertThat(Projection.VANILLA_ENTITY.basePose(), equalTo(new EulerRotation(30f, 225f, 0f)));
        assertThat(Projection.VANILLA_GUI_ITEM.basePose(), equalTo(new EulerRotation(0f, 180f, 0f)));
    }

    @Test
    @DisplayName("resolve(NONE) keeps each member's base pose as the lighting pose")
    void resolveKeepsBasePose() {
        for (Projection projection : Projection.values())
            assertThat(projection + " lighting pose", projection.resolve().lightingPose(), equalTo(projection.basePose()));
    }

    @Test
    @DisplayName("resolve() bundles each member's lens into the camera")
    void resolveBundlesLens() {
        assertThat(Projection.VANILLA_BLOCK.resolve().lens(), equalTo(Lens.ISOMETRIC_BLOCK));
        assertThat(Projection.VANILLA_PLAYER.resolve().lens(), equalTo(Lens.NONE));
        assertThat(Projection.VANILLA_GUI_ITEM.resolve().lens(), equalTo(Lens.GUI_ITEM));
        assertThat(Projection.VANILLA_ENTITY.resolve().lens(), equalTo(Lens.ISOMETRIC_BLOCK));
    }

    @Test
    @DisplayName("withLens keeps the pose and swaps the lens")
    void withLensSwapsLens() {
        Camera block = Projection.VANILLA_BLOCK.resolve();
        Camera relensed = block.withLens(Lens.NONE);
        assertThat("pose preserved", relensed.pose(), equalTo(block.pose()));
        assertThat("lens swapped", relensed.lens(), equalTo(Lens.NONE));
    }

}
