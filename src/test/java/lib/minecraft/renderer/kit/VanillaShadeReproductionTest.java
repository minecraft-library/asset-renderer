package lib.minecraft.renderer.kit;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reproduces vanilla's exact shade computation for entity faces using JOML, to
 * cross-check what shade vanilla's GPU produces for a face. The witch (21, 200)
 * pixel ratio test (0.566) implies vanilla picks a face with shade ~0.566 at that
 * pixel; we want to identify which face that is.
 *
 * <p>Method: take each cardinal face normal, run it through vanilla's full pose
 * chain (per {@code EntityFrameRenderer.render} + {@code LivingEntityRenderer.submit}),
 * compute the dual-directional Lambertian shade per vanilla's
 * {@code Lighting.Entry.ENTITY_IN_UI}. The face whose shade matches the observed
 * pixel ratio is the one vanilla picked at this pixel.
 */
class VanillaShadeReproductionTest {

    /** Vanilla {@code INVENTORY_DIFFUSE_LIGHT_0 = normalize(0.2, -1, 1)}. */
    private static final Vector3f LIGHT_0 = new Vector3f(0.2f, -1f, 1f).normalize();
    /** Vanilla {@code INVENTORY_DIFFUSE_LIGHT_1 = normalize(-0.2, -1, 0)}. */
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2f, -1f, 0f).normalize();

    @Test
    @DisplayName("[VANILLA_SHADE] cardinal face shades through full vanilla pose chain")
    void cardinalFaceShades() {
        // Build vanilla's full normal-transform chain (no translation - normals only see
        // the rotation/scale parts of the matrix).
        // Per EntityFrameRenderer.render:
        //   pose.translate(tx, ty, 0)      // ignored for normals
        //   pose.scale(scale, scale, scale) // uniform scale doesn't change normal direction
        //   pose.scale(1, 1, -1)
        //   pose.mulPose(rotationXYZ(210, 45, 0))
        // Then LER chain:
        //   pose.scale(1, 1, 1)            // identity (living.scale = 1.0)
        //   setupRotations: rotateY(180 - bodyRot) = rotateY(180)
        //   pose.scale(-1, -1, 1)
        //   pose.translate(0, -1.501, 0)   // ignored for normals
        // Then per-bone translateAndRotate (witch head: identity)
        Matrix4f normalChain = new Matrix4f();
        normalChain.scale(1f, 1f, -1f);
        normalChain.rotate(new Quaternionf().rotationXYZ(
            (float) Math.toRadians(210),
            (float) Math.toRadians(45),
            0f));
        // LER chain - no living scale for normal (uniform scale doesn't affect direction)
        normalChain.rotate(new Quaternionf().rotationY((float) Math.PI));
        normalChain.scale(-1f, -1f, 1f);

        System.out.println("[VANILLA_SHADE] vanilla normal-chain matrix:");
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                System.out.printf("  m(%d,%d) = %+.6f", col, row, normalChain.get(col, row));
            }
            System.out.println();
        }

        // For each cardinal face, compute final normal and shade
        Vector3f[] faces = new Vector3f[] {
            new Vector3f(0, -1, 0),  // DOWN
            new Vector3f(0,  1, 0),  // UP
            new Vector3f(0, 0, -1),  // NORTH
            new Vector3f(0, 0,  1),  // SOUTH
            new Vector3f(-1, 0, 0),  // WEST
            new Vector3f( 1, 0, 0),  // EAST
        };
        String[] names = { "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST" };

        System.out.println();
        System.out.println("[VANILLA_SHADE] face | camera-frame normal | L0·n | L1·n | shade");
        for (int i = 0; i < faces.length; i++) {
            Vector3f n = new Vector3f(faces[i]);
            normalChain.transformDirection(n);
            n.normalize();
            float dot0 = Math.max(0f, n.dot(LIGHT_0));
            float dot1 = Math.max(0f, n.dot(LIGHT_1));
            float shade = Math.min(1f, (dot0 + dot1) * 0.6f + 0.4f);
            System.out.printf("[VANILLA_SHADE] %5s | (%+.4f, %+.4f, %+.4f) | %.4f | %.4f | %.4f%n",
                names[i], n.x, n.y, n.z, dot0, dot1, shade);
        }
    }

    @Test
    @DisplayName("[VANILLA_SHADE_NO_LER] cardinal face shades WITHOUT vanilla's LER chain (iso only)")
    void cardinalFaceShadesIsoOnly() {
        // Just the iso part of the chain - no LER rotateY(180) or scale(-1,-1,1)
        Matrix4f normalChain = new Matrix4f();
        normalChain.scale(1f, 1f, -1f);
        normalChain.rotate(new Quaternionf().rotationXYZ(
            (float) Math.toRadians(210),
            (float) Math.toRadians(45),
            0f));

        Vector3f[] faces = new Vector3f[] {
            new Vector3f(0, -1, 0), new Vector3f(0, 1, 0),
            new Vector3f(0, 0, -1), new Vector3f(0, 0, 1),
            new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0),
        };
        String[] names = { "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST" };

        System.out.println();
        System.out.println("[VANILLA_SHADE_NO_LER] face | camera-frame normal | shade");
        for (int i = 0; i < faces.length; i++) {
            Vector3f n = new Vector3f(faces[i]);
            normalChain.transformDirection(n);
            n.normalize();
            float dot0 = Math.max(0f, n.dot(LIGHT_0));
            float dot1 = Math.max(0f, n.dot(LIGHT_1));
            float shade = Math.min(1f, (dot0 + dot1) * 0.6f + 0.4f);
            System.out.printf("[VANILLA_SHADE_NO_LER] %5s | (%+.4f, %+.4f, %+.4f) | %.4f%n",
                names[i], n.x, n.y, n.z, shade);
        }
    }
}
