package dev.sbs.renderer.engine;

import dev.sbs.renderer.draw.BlendMode;
import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.ProjectionMath;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector2f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * A 3D triangle rasterizer that projects a list of {@link VisibleTriangle triangles} onto a 2D
 * {@link Canvas} using a depth buffer, barycentric interpolation, and painter's-algorithm
 * ordering for back-to-front draw order.
 * <p>
 * Every renderer composing this engine can supply pitch, yaw, and roll Euler angles at render
 * time. The rotation is pre-multiplied into the engine's {@link #cameraTransform()} so the inner
 * rasterization loop stays hot and the existing triangle list can be reused across multiple
 * rotations without rebuilding the geometry.
 * <p>
 * Back-face culling uses a signed screen-space winding test after projection, which is robust
 * against camera and model rotations and does not depend on the per-triangle surface normal.
 * Individual triangles can opt out of culling by setting {@link VisibleTriangle#cullBackFaces()}
 * to {@code false} - used for two-sided geometry such as glass panes, leaves, banners, and the
 * interior faces of beds and other non-convex blocks.
 */
public class ModelEngine extends TextureEngine {

    public ModelEngine(@NotNull RendererContext context) {
        super(context);
    }

    /**
     * The camera transform applied to every vertex before projection. The default implementation
     * returns the identity matrix, matching a view directly down the negative Z axis. Subclasses
     * like {@link IsometricEngine} override this with a fixed dimetric camera.
     *
     * @return the camera transform matrix
     */
    public @NotNull Matrix4f cameraTransform() {
        return Matrix4f.IDENTITY;
    }

    /**
     * Rasterizes a triangle list onto the given canvas with no additional model rotation.
     *
     * @param triangles the triangle list
     * @param canvas the destination canvas
     * @param perspective the perspective blend parameters
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Canvas canvas,
        @NotNull PerspectiveParams perspective
    ) {
        rasterize(triangles, canvas, perspective, 0f, 0f, 0f);
    }

    /**
     * Rasterizes a triangle list onto the given canvas after applying a pitch/yaw/roll rotation
     * to the model before the camera transform.
     * <p>
     * Rotations are applied in yaw-pitch-roll order (yaw first around the Y axis, then pitch
     * around the X axis, then roll around the Z axis) and the combined rotation is then
     * composed with the engine's camera transform. Supplying three zeros is equivalent to
     * calling {@link #rasterize(ConcurrentList, Canvas, PerspectiveParams)}.
     *
     * @param triangles the triangle list
     * @param canvas the destination canvas
     * @param perspective the perspective blend parameters
     * @param pitchDegrees rotation around the X axis, in degrees
     * @param yawDegrees rotation around the Y axis, in degrees
     * @param rollDegrees rotation around the Z axis, in degrees
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Canvas canvas,
        @NotNull PerspectiveParams perspective,
        float pitchDegrees,
        float yawDegrees,
        float rollDegrees
    ) {
        Matrix4f modelRotation = buildModelRotation(pitchDegrees, yawDegrees, rollDegrees);
        Matrix4f transform = modelRotation.multiply(cameraTransform());
        rasterizeInternal(triangles, canvas, perspective, transform);
    }

    /**
     * Rasterizes a triangle list after pre-multiplying an arbitrary model transform with the
     * engine's camera. Used for item display transforms (e.g. {@code thirdperson_righthand}) and
     * any other caller that needs more than a pitch-yaw-roll Euler rotation.
     *
     * @param triangles the triangle list
     * @param canvas the destination canvas
     * @param perspective the perspective blend parameters
     * @param modelTransform the model-space transform applied before the camera transform
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Canvas canvas,
        @NotNull PerspectiveParams perspective,
        @NotNull Matrix4f modelTransform
    ) {
        Matrix4f transform = modelTransform.multiply(cameraTransform());
        rasterizeInternal(triangles, canvas, perspective, transform);
    }

    private void rasterizeInternal(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Canvas canvas,
        @NotNull PerspectiveParams perspective,
        @NotNull Matrix4f transform
    ) {
        int width = canvas.width();
        int height = canvas.height();
        float scale = Math.min(width, height) * 0.4f;
        float offsetX = width * 0.5f;
        float offsetY = height * 0.5f;

        // Pass 1: transform every triangle and project its vertices. This pre-projection cache
        // lets us compute screen-space winding once per triangle for both the cull pass and
        // the rasterization loop.
        ConcurrentList<Projected> prepared = Concurrent.newList();
        for (VisibleTriangle triangle : triangles) {
            Vector3f p0 = Vector3f.transform(triangle.position0(), transform);
            Vector3f p1 = Vector3f.transform(triangle.position1(), transform);
            Vector3f p2 = Vector3f.transform(triangle.position2(), transform);
            Vector3f normal = Vector3f.normalize(Vector3f.transformNormal(triangle.normal(), transform));

            Vector2f s0 = RenderEngine.projectPerspective(p0, scale, offsetX, offsetY, perspective);
            Vector2f s1 = RenderEngine.projectPerspective(p1, scale, offsetX, offsetY, perspective);
            Vector2f s2 = RenderEngine.projectPerspective(p2, scale, offsetX, offsetY, perspective);

            if (triangle.cullBackFaces() && isBackFacing(s0, s1, s2)) continue;

            prepared.add(new Projected(triangle, p0, p1, p2, s0, s1, s2, normal));
        }

        prepared.sort((a, b) -> Float.compare(averageDepth(a), averageDepth(b)));

        float[] depthBuffer = new float[width * height];
        Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);

        for (Projected t : prepared) {
            int[] bounds = ProjectionMath.triangleBounds(t.s0, t.s1, t.s2, width, height);
            float shading = t.source.shading() * RenderEngine.computeInventoryLighting(t.source.normal());

            for (int py = bounds[1]; py <= bounds[3]; py++) {
                for (int px = bounds[0]; px <= bounds[2]; px++) {
                    Vector2f pt = new Vector2f(px + 0.5f, py + 0.5f);
                    float[] bary = ProjectionMath.barycentric(t.s0, t.s1, t.s2, pt);
                    if (!ProjectionMath.isInsideTriangle(bary)) continue;

                    float depth = bary[0] * t.p0.z() + bary[1] * t.p1.z() + bary[2] * t.p2.z();
                    int idx = py * width + px;
                    if (depth <= depthBuffer[idx]) continue;

                    float u = bary[0] * t.source.uv0().x() + bary[1] * t.source.uv1().x() + bary[2] * t.source.uv2().x();
                    float v = bary[0] * t.source.uv0().y() + bary[1] * t.source.uv1().y() + bary[2] * t.source.uv2().y();

                    PixelBuffer texture = t.source.texture();
                    int tx = Math.clamp((int) (u * texture.width()), 0, texture.width() - 1);
                    int ty = Math.clamp((int) (v * texture.height()), 0, texture.height() - 1);
                    int sampled = texture.getPixel(tx, ty);
                    if (ColorKit.alpha(sampled) == 0) continue;

                    if (t.source.tintArgb() != ColorKit.WHITE)
                        sampled = ColorKit.blend(t.source.tintArgb(), sampled, BlendMode.MULTIPLY);

                    sampled = RenderEngine.applyShading(sampled, shading);
                    canvas.getBuffer().setPixel(px, py, sampled);
                    depthBuffer[idx] = depth;
                }
            }
        }
    }

    /**
     * Computes a signed triangle area in screen space. The camera transform is a pure rotation
     * (det=+1, preserves winding), so the Y-down screen conversion is applied only in the
     * projection step. The projection negates Y, which reverses winding: front-facing CCW
     * triangles end up CW on screen with negative signed area. A non-negative result therefore
     * means back-facing.
     * <p>
     * This is more robust than a camera-space normal test because it correctly handles arbitrary
     * rotations, perspective foreshortening, and non-uniform scales.
     */
    private static boolean isBackFacing(@NotNull Vector2f v0, @NotNull Vector2f v1, @NotNull Vector2f v2) {
        float signedArea = (v1.x() - v0.x()) * (v2.y() - v0.y())
            - (v2.x() - v0.x()) * (v1.y() - v0.y());
        return signedArea >= 0f;
    }

    /**
     * Builds the model-space rotation matrix from yaw, pitch, and roll Euler angles in degrees.
     * Applied yaw first, then pitch, then roll using the row-vector convention.
     */
    private static @NotNull Matrix4f buildModelRotation(float pitchDegrees, float yawDegrees, float rollDegrees) {
        if (pitchDegrees == 0f && yawDegrees == 0f && rollDegrees == 0f) return Matrix4f.IDENTITY;

        Matrix4f yaw = Matrix4f.createRotationY((float) Math.toRadians(yawDegrees));
        Matrix4f pitch = Matrix4f.createRotationX((float) Math.toRadians(pitchDegrees));
        Matrix4f roll = Matrix4f.createRotationZ((float) Math.toRadians(rollDegrees));
        return yaw.multiply(pitch).multiply(roll);
    }

    private static float averageDepth(@NotNull Projected t) {
        return (t.p0.z() + t.p1.z() + t.p2.z()) / 3f;
    }

    /**
     * A per-frame triangle view that caches the model-space transformed vertices, their screen
     * projections, and the transformed normal. Not part of the public API - exists so the
     * rasterization loop does not have to recompute the transform or projection for every pixel.
     */
    private record Projected(
        @NotNull VisibleTriangle source,
        @NotNull Vector3f p0,
        @NotNull Vector3f p1,
        @NotNull Vector3f p2,
        @NotNull Vector2f s0,
        @NotNull Vector2f s1,
        @NotNull Vector2f s2,
        @NotNull Vector3f normal
    ) {}

}
