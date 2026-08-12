package lib.minecraft.renderer.engine.kit;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.tensor.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the elytra's baby re-seat, which lives in the mesh so the canvas-bounds walk and the
 * render read one seat - canvas sizing runs before any geometry is built, so a seat applied to built
 * triangles would size the canvas for wings drawn somewhere else and crop them.
 */
@DisplayName("ElytraKit baby wing seat")
class ElytraKitSeatTest {

    /** Tolerance for a float bounds comparison. */
    private static final double EPSILON = 1e-4;

    @Test
    @DisplayName("the seated baby wings hang from the body's top edge")
    void seatLandsWingTopOnBodyTop() {
        Box body = new Box(-4f, 3.5f, -2f, 4f, 15f, 2f);
        EntityModelData seated = ElytraKit.wingsMesh(true, Optional.of(body));

        assertThat("the wing top (minimum y in the Y-down frame) meets the body top",
            (double) EntityGeometryKit.computeBounds(seated).minY(), closeTo(body.minY(), EPSILON));
    }

    @Test
    @DisplayName("the seat is a pure Y translation - it moves the wings, it does not resize them")
    void seatTranslatesOnlyAlongY() {
        Box authored = EntityGeometryKit.computeBounds(ElytraKit.wingsMesh(true));
        Box seated = EntityGeometryKit.computeBounds(
            ElytraKit.wingsMesh(true, Optional.of(new Box(-4f, 3.5f, -2f, 4f, 15f, 2f))));

        assertThat("x extent unchanged", (double) seated.minX(), closeTo(authored.minX(), EPSILON));
        assertThat("x extent unchanged", (double) seated.maxX(), closeTo(authored.maxX(), EPSILON));
        assertThat("z extent unchanged", (double) seated.minZ(), closeTo(authored.minZ(), EPSILON));
        assertThat("z extent unchanged", (double) seated.maxZ(), closeTo(authored.maxZ(), EPSILON));
        assertThat("height unchanged", (double) (seated.maxY() - seated.minY()),
            closeTo(authored.maxY() - authored.minY(), EPSILON));
    }

    @Test
    @DisplayName("an adult never seats - the authored mesh already hangs from an adult body")
    void adultIsNeverSeated() {
        assertThat(ElytraKit.wingsMesh(false, Optional.of(new Box(-4f, 3.5f, -2f, 4f, 15f, 2f))),
            is(sameInstance(ElytraKit.wingsMesh(false))));
    }

    @Test
    @DisplayName("a body with no bounds never seats - the wings stay where they are authored")
    void absentBodyIsNeverSeated() {
        assertThat(ElytraKit.wingsMesh(true, Optional.empty()), is(sameInstance(ElytraKit.wingsMesh(true))));
    }

    @Test
    @DisplayName("seating does not mutate the shared authored mesh")
    void seatLeavesTheSharedMeshAlone() {
        // wingsMesh(baby) hands out a cached constant. A seat that translated its bones in place would
        // move every later render's wings, compounding once per baby rendered.
        Box before = EntityGeometryKit.computeBounds(ElytraKit.wingsMesh(true));
        ElytraKit.wingsMesh(true, Optional.of(new Box(-4f, 3.5f, -2f, 4f, 15f, 2f)));
        ElytraKit.wingsMesh(true, Optional.of(new Box(-4f, 9f, -2f, 4f, 20f, 2f)));
        Box after = EntityGeometryKit.computeBounds(ElytraKit.wingsMesh(true));

        assertThat("the authored mesh is unchanged after seating twice",
            (double) after.minY(), closeTo(before.minY(), EPSILON));
        assertThat((double) after.maxY(), closeTo(before.maxY(), EPSILON));
    }

}
