package lib.minecraft.renderer.engine.light;

import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins {@link Lighting#resolveEntity}: the shipped {@code [210, 45, 0]} entity lighting frame yields the
 * legacy kit view direction, the resolver is pure, and a {@linkplain LightingFrame.Mirror#HORIZONTAL
 * horizontal mirror} swaps the lights (a screen left / right flip) while leaving the view direction - a
 * camera, not lighting, property - untouched. Byte-identity of the shipped-frame shade against the
 * pre-consolidation constants is covered empirically by the entity parity sweep.
 */
@DisplayName("Lighting.resolveEntity - entity inventory lighting from a LightingFrame")
class EntityLightingTest {

    private static final LightingFrame ENTITY = LightingFrame.fixed(new EulerRotation(210f, 45f, 0f));
    private static final float EPS = 1.0e-4f;

    @Test
    @DisplayName("the [210,45,0] frame yields the shipped kit view direction (0.6124, -0.5, 0.6124)")
    void viewDirection() {
        Vector3f v = Lighting.resolveEntity(ENTITY).viewDirection();
        assertEquals(0.6124f, v.x(), EPS, "view.x");
        assertEquals(-0.5f, v.y(), EPS, "view.y");
        assertEquals(0.6124f, v.z(), EPS, "view.z");
    }

    @Test
    @DisplayName("HORIZONTAL mirrors the lights but not the camera view direction")
    void horizontalMirror() {
        Lighting.EntityLighting plain = Lighting.resolveEntity(ENTITY);
        Lighting.EntityLighting mirrored = Lighting.resolveEntity(ENTITY.mirroredHorizontally());
        // The view direction (front / back selection) is a camera property - a lighting mirror leaves it be.
        assertEquals(plain.viewDirection(), mirrored.viewDirection(), "view direction unmirrored");
        // The lights swap their screen sides - a non-trivial change for the non-symmetric ENTITY_IN_UI pair.
        assertNotEquals(plain.light0(), mirrored.light0(), "light0 mirrored");
        assertNotEquals(plain.light1(), mirrored.light1(), "light1 mirrored");
    }

    @Test
    @DisplayName("resolveEntity is pure - the same frame yields an equal basis")
    void deterministic() {
        assertEquals(Lighting.resolveEntity(ENTITY), Lighting.resolveEntity(ENTITY));
    }
}
