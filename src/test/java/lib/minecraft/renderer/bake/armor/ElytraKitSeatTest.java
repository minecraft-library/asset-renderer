package lib.minecraft.renderer.bake.armor;

import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.vanilla.mesh.ElytraMesh;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;

/**
 * Coverage of where the elytra's wings hang at each age. Vanilla bakes a baby's wings as
 * {@code ElytraModel.createLayer()} under {@code ElytraModel.BABY_TRANSFORMER}, which is
 * {@code MeshTransformer.scaling(0.5)} - the model's root scaled by half about the feet anchor - and
 * {@code WingsLayer} translates both ages {@code (0, 0, 0.125)} outside that root. Nothing on the path
 * reads the wearer's body, so a baby's wings are the adult's halved about
 * {@code (0, FEET_ANCHOR, BACK_OFFSET)} on every baby that wears them.
 */
@DisplayName("ElytraKit wing seat")
class ElytraKitSeatTest {

    /** Tolerance for a float bounds comparison. */
    private static final double EPSILON = 1e-4;

    /** The point vanilla's baby transform scales the wings about, with the back shift left whole. */
    private static final @NotNull Vector3f ANCHOR = new Vector3f(0f, EntityMesh.FEET_ANCHOR, ElytraMesh.BACK_OFFSET);

    @Test
    @DisplayName("the baby wings are the adult wings halved about the feet anchor")
    void babyWingsAreTheAdultHalvedAboutTheFeetAnchor() {
        Box adult = EntityGeometryKit.computeBounds(ElytraKit.wingsMesh(false));
        Box baby = EntityGeometryKit.computeBounds(ElytraKit.wingsMesh(true));

        assertThat("minX", (double) baby.minX(), closeTo(halved(adult.minX(), ANCHOR.x()), EPSILON));
        assertThat("maxX", (double) baby.maxX(), closeTo(halved(adult.maxX(), ANCHOR.x()), EPSILON));
        assertThat("minY", (double) baby.minY(), closeTo(halved(adult.minY(), ANCHOR.y()), EPSILON));
        assertThat("maxY", (double) baby.maxY(), closeTo(halved(adult.maxY(), ANCHOR.y()), EPSILON));
        assertThat("minZ", (double) baby.minZ(), closeTo(halved(adult.minZ(), ANCHOR.z()), EPSILON));
        assertThat("maxZ", (double) baby.maxZ(), closeTo(halved(adult.maxZ(), ANCHOR.z()), EPSILON));
    }

    @Test
    @DisplayName("an adult's wings pivot at createLayer's (+-5, 0) behind the layer's back shift")
    void adultWingsPivotWhereCreateLayerPutsThem() {
        assertPivot(ElytraKit.wingsMesh(false), "left_wing", 5f, 0f, 2f);
        assertPivot(ElytraKit.wingsMesh(false), "right_wing", -5f, 0f, 2f);
    }

    @Test
    @DisplayName("a baby's wings pivot at (+-2.5, 12.008, 2), where vanilla's baby transform puts them")
    void babyWingsPivotWhereTheBabyTransformPutsThem() {
        assertPivot(ElytraKit.wingsMesh(true), "left_wing", 2.5f, 12.008f, 2f);
        assertPivot(ElytraKit.wingsMesh(true), "right_wing", -2.5f, 12.008f, 2f);
    }

    /**
     * The coordinate a uniform half scale about {@code anchor} carries {@code value} to. A uniform
     * positive scale about a point commutes with an axis-aligned bound, so each bound of the baby mesh
     * is this of the adult's.
     *
     * @param value the adult coordinate
     * @param anchor the anchor's coordinate on the same axis
     * @return the coordinate at half scale about the anchor
     */
    private static double halved(float value, float anchor) {
        return anchor + 0.5 * (value - anchor);
    }

    private static void assertPivot(@NotNull EntityMesh mesh, @NotNull String bone, float x, float y, float z) {
        Vector3f pivot = mesh.getBones().get(bone).getPivot();
        assertThat(bone + " pivot x", (double) pivot.x(), closeTo(x, EPSILON));
        assertThat(bone + " pivot y", (double) pivot.y(), closeTo(y, EPSILON));
        assertThat(bone + " pivot z", (double) pivot.z(), closeTo(z, EPSILON));
    }

}
