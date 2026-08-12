package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.tensor.EulerRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Coverage of the {@link Projection} catalog's base-pose and {@link Projection#resolve() resolve}
 * contract: the two {@code VANILLA_*} members carry the angles vanilla itself defines, an unrotated
 * resolve preserves every member's base pose as its lighting pose, and resolve bundles the member's
 * {@link Lens} into the {@link Camera} (swappable via {@link Camera#withLens}). Only that pair has an
 * external angle to answer to; the other members' angles are this catalog's own graphical projections,
 * so asserting them would restate the declaration. Guards the invariant that {@code Projection} is the
 * sole owner of the vanilla iso angles - facing is applied per-renderer as a {@code Placement}, never
 * baked into the pose.
 */
@DisplayName("Projection base poses + resolve")
class ProjectionTest {

    @Test
    @DisplayName("VANILLA_* base poses carry the documented vanilla angles")
    void vanillaBasePosesCarryDocumentedAngles() {
        // Only the VANILLA_* pair answers to an angle vanilla defines; every other member's angles are
        // this catalog's own, so pinning them would restate the declaration.
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
            assertThat(projection + " lighting pose", projection.resolve().lighting(),
                equalTo(LightingFrame.tracking(projection.basePose())));
    }

    @Test
    @DisplayName("resolve() bundles each member's lens into the camera")
    void resolveBundlesLens() {
        assertThat(Projection.VANILLA_ISO.resolve().camera().lens(), equalTo(Lens.ISOMETRIC_BLOCK));
        assertThat(Projection.VANILLA_GUI_ITEM.resolve().camera().lens(), equalTo(Lens.GUI_ITEM));
    }

    @Test
    @DisplayName("withLens keeps the pose and swaps the lens")
    void withLensSwapsLens() {
        Camera block = Projection.VANILLA_ISO.resolve().camera();
        Camera relensed = block.withLens(Lens.NONE);
        assertThat("pose preserved", relensed.pose(), equalTo(block.pose()));
        assertThat("lens swapped", relensed.lens(), equalTo(Lens.NONE));
    }

}
