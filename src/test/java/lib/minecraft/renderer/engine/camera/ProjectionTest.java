package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the {@link Projection} catalog's base-pose and {@link Projection#resolve() resolve} contract:
 * each member's documented vanilla angle, that an unrotated resolve preserves the base pose as the
 * lighting pose, and that resolve bundles the member's {@link Lens} into the {@link Camera} (swappable
 * via {@link Camera#withLens}). Guards the invariant that {@code Projection} is the sole owner of the
 * vanilla iso angles - facing is applied per-renderer as a {@code Placement}, never baked into the pose.
 */
@DisplayName("Projection base poses + resolve")
class ProjectionTest {

    @Test
    @DisplayName("VANILLA_* base poses carry the documented vanilla angles")
    void vanillaBasePoses() {
        // Projection is the sole home of the vanilla iso angles (moved off EulerRotation). VANILLA_ISO is
        // the single facing-neutral block-icon [30,225,0] pose shared by blocks, players, and entities;
        // each renderer applies its own facing as a Placement (block IDENTITY; player R_Y(180); entity
        // R_Y(180)·flip180 = diag(-1,-1,1)), so every projection presents the subject's front. The harness
        // [210,45,0] lives on only as the entity kit's lighting angle (EntityGeometryKit.ENTITY_ISO_LIGHTING).
        assertThat(Projection.VANILLA_ISO.basePose(), equalTo(new EulerRotation(30f, 225f, 0f)));
        assertThat(Projection.VANILLA_GUI_ITEM.basePose(), equalTo(new EulerRotation(0f, 180f, 0f)));
    }

    /**
     * Pins that an unrotated resolve keeps each member's lighting pose equal to its base pose - the
     * {@code NONE}-rotation short-circuit does no float arithmetic, so lighting sits exactly at the base
     * angle across the whole catalog.
     */
    @Test
    @DisplayName("resolve(NONE) keeps each member's base pose as the lighting pose")
    void resolveKeepsBasePose() {
        for (Projection projection : Projection.values())
            assertThat(projection + " lighting pose", projection.resolve().lightingPose(), equalTo(projection.basePose()));
    }

    @Test
    @DisplayName("resolve() bundles each member's lens into the camera")
    void resolveBundlesLens() {
        assertThat(Projection.VANILLA_ISO.resolve().lens(), equalTo(Lens.ISOMETRIC_BLOCK));
        assertThat(Projection.VANILLA_GUI_ITEM.resolve().lens(), equalTo(Lens.GUI_ITEM));
    }

    @Test
    @DisplayName("withLens keeps the pose and swaps the lens")
    void withLensSwapsLens() {
        Camera block = Projection.VANILLA_ISO.resolve();
        Camera relensed = block.withLens(Lens.NONE);
        assertThat("pose preserved", relensed.pose(), equalTo(block.pose()));
        assertThat("lens swapped", relensed.lens(), equalTo(Lens.NONE));
    }

}
