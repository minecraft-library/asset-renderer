package lib.minecraft.renderer.engine;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.ProjectionMath;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Matrix4fOps;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector3fOps;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A 3D triangle rasterizer that projects a list of {@link VisibleTriangle triangles} onto a 2D
 * {@link PixelBuffer} using a depth buffer, barycentric interpolation, and painter's-algorithm
 * ordering for back-to-front draw order.
 * <p>
 * Every renderer composing this engine can supply pitch, yaw, and roll Euler angles at render
 * time. The rotation is pre-multiplied into the engine's camera transform so the inner
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

    /**
     * Per-pixel depth comparison epsilon. Absorbs floating-point noise between mathematically
     * equal coplanar depths so the deterministic insertion-order paint sequence survives the
     * strict {@code depth <= depthBuffer} rejection. Chosen small enough that legitimate
     * geometry separation (e.g. lock front at z=16 vs body SOUTH at z=15, a one-unit gap in
     * model space) stays resolvable but large enough that float-precision jitter around a
     * shared plane is collapsed.
     */
    private static final float DEPTH_EPSILON = 1e-4f;

    /**
     * Minimum framebuffer height (in pixels) before tiled parallel rasterization kicks in.
     * Below this threshold the tiled path's overhead (ForkJoin splits, per-tile depth-slice
     * allocation, triangle iteration per tile) outweighs the parallel speedup. Small renders
     * stay serial - relevant for the atlas tile path where each block renders at 128x128 inside
     * an already-parallel outer dispatch.
     */
    private static final int MIN_TILED_HEIGHT = 256;

    /**
     * Target minimum rows per tile. Cap the tile count so each tile still has enough rasterization
     * work to amortise the per-tile depth-slice allocation + triangle loop setup.
     */
    private static final int MIN_ROWS_PER_TILE = 32;

    private final @NotNull Matrix4f camera;

    /**
     * Constructs a model engine whose camera transform is the identity matrix - geometry is
     * viewed directly down the negative Z axis with no pre-rotation. Callers that want a
     * preset pose (e.g. the standard block inventory icon) should use {@link IsometricEngine}
     * instead of composing the pose into their {@code modelTransform}.
     *
     * @param context the renderer context
     */
    public ModelEngine(@NotNull RendererContext context) {
        this(context, Matrix4f.IDENTITY);
    }

    /**
     * Constructs a model engine with a preset camera transform, applied after the caller's
     * model transform during rasterization. Intended as the {@code super(...)} entry point for
     * subclasses that bake a named pose (e.g. {@link IsometricEngine} with the vanilla
     * {@code [30, 225, 0]} block-icon camera) into every render.
     *
     * @param context the renderer context
     * @param camera the camera transform matrix composed with every rasterization
     */
    protected ModelEngine(@NotNull RendererContext context, @NotNull Matrix4f camera) {
        super(context);
        this.camera = camera;
    }

    /**
     * Rasterizes a triangle list onto the given buffer with no additional model rotation.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param perspective the perspective blend parameters
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull PerspectiveParams perspective
    ) {
        rasterize(triangles, buffer, perspective, EulerRotation.NONE);
    }

    /**
     * Rasterizes a triangle list onto the given buffer after applying an Euler-angle rotation
     * to the model before the camera transform.
     * <p>
     * Rotations are applied in yaw-pitch-roll order (yaw first around the Y axis, then pitch
     * around the X axis, then roll around the Z axis) and the combined rotation is then
     * composed with the engine's camera transform. Supplying {@link EulerRotation#NONE} is
     * equivalent to calling {@link #rasterize(ConcurrentList, PixelBuffer, PerspectiveParams)}.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param perspective the perspective blend parameters
     * @param rotation the Euler-angle rotation applied to the model before the camera transform
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull PerspectiveParams perspective,
        @NotNull EulerRotation rotation
    ) {
        Matrix4f modelRotation = buildModelRotation(rotation);
        Matrix4f transform = Matrix4fOps.multiply(modelRotation, this.camera);
        rasterizeInternal(triangles, buffer, perspective, transform);
    }

    /**
     * Rasterizes a triangle list after pre-multiplying an arbitrary model transform with the
     * engine's camera. Used for item display transforms (e.g. {@code thirdperson_righthand}) and
     * any other caller that needs more than a pitch-yaw-roll Euler rotation.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param perspective the perspective blend parameters
     * @param modelTransform the model-space transform applied before the camera transform
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull PerspectiveParams perspective,
        @NotNull Matrix4f modelTransform
    ) {
        Matrix4f transform = Matrix4fOps.multiply(modelTransform, this.camera);
        rasterizeInternal(triangles, buffer, perspective, transform);
    }

    private void rasterizeInternal(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull PerspectiveParams perspective,
        @NotNull Matrix4f transform
    ) {
        int width = buffer.width();
        int height = buffer.height();
        float scale = Math.min(width, height) * perspective.projectionScale();
        float offsetX = width * 0.5f;
        float offsetY = height * 0.5f;

        // Pass 1 (Task 7): transform + project + backface cull, in parallel. Each triangle's
        // projection is pure functional - reads only the per-triangle vertex data and the shared
        // immutable transform - so a parallelStream over the FJP common pool scales this across
        // cores. map().filter().toList() preserves encounter order, which Pass 2's painter's
        // algorithm requires: the rasterizer iterates `prepared` in original insertion order so
        // the DEPTH_EPSILON tie-break deterministically picks the first-drawn of any coplanar
        // pair (see the comment on the depth test below).
        List<Projected> prepared = triangles.stream().parallel()
            .map(triangle -> projectTriangle(triangle, transform, scale, offsetX, offsetY, perspective))
            .filter(Objects::nonNull)
            .toList();

        // Pass 2 (Task 8): tiled rasterization. Split the framebuffer into N horizontal Y-bands
        // and rasterize each band in parallel. Every band owns its own depth-buffer slice, so the
        // inner raster loop never contends with sibling threads. Every band still iterates the
        // full prepared list in original insertion order so painter's semantics - the
        // DEPTH_EPSILON tie-break that makes the first-drawn coplanar face win - are preserved
        // within each tile; triangles rasterize into disjoint Y ranges across tiles, so the final
        // image is byte-identical to the serial path.
        //
        // Small framebuffers (height < MIN_TILED_HEIGHT) skip the tiled path - FJP overhead
        // outweighs the parallel speedup for sub-256-pixel images and the atlas tile path already
        // parallelises at the outer dispatch level via Task 1.
        if (height < MIN_TILED_HEIGHT) {
            float[] depthBuffer = new float[width * height];
            Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);
            rasterizeTile(prepared, buffer, depthBuffer, width, height, 0, height);
            return;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        int tileCount = Math.max(1, Math.min(cores, height / MIN_ROWS_PER_TILE));
        int tileHeight = (height + tileCount - 1) / tileCount;

        IntStream.range(0, tileCount).parallel().forEach(tileIdx -> {
            int tileStart = tileIdx * tileHeight;
            int tileEnd = Math.min(height, tileStart + tileHeight);
            if (tileStart >= tileEnd) return;

            float[] depthSlice = new float[width * (tileEnd - tileStart)];
            Arrays.fill(depthSlice, Float.NEGATIVE_INFINITY);
            rasterizeTile(prepared, buffer, depthSlice, width, height, tileStart, tileEnd);
        });
    }

    /**
     * Rasterizes every triangle in {@code prepared} into the Y-range {@code [tileStart, tileEnd)}
     * of {@code buffer}, using {@code depth} as a local depth buffer indexed by
     * {@code (py - tileStart) * width + px}. Triangle bounds are computed against the full image
     * dimensions and then clipped to the tile's Y range, so triangles that span a tile boundary
     * naturally contribute to each overlapping tile without any pre-binning.
     * <p>
     * Callers are responsible for pre-filling {@code depth} with {@link Float#NEGATIVE_INFINITY}
     * and for ensuring no two concurrent invocations share overlapping {@code [tileStart, tileEnd)}
     * ranges - that is what keeps the {@code buffer.setPixel} writes race-free across tiles.
     */
    private static void rasterizeTile(
        @NotNull List<Projected> prepared,
        @NotNull PixelBuffer buffer,
        float @NotNull [] depth,
        int width,
        int height,
        int tileStart,
        int tileEnd
    ) {
        // Task A: a single barycentric scratch reused for every pixel in this tile. Each
        // rasterizeTile call runs on one FJP worker thread, so these arrays are thread-confined
        // by construction - no synchronisation needed. Replaces the per-pixel `new Vector2f(...)`
        // + `new float[3]` from barycentric's allocating variant.
        // Task B: one int[4] scratch reused for every triangle's clipped screen-space bounds,
        // replacing the per-triangle `new int[4]` from triangleBounds.
        final float[] bary = new float[3];
        final int[] bounds = new int[4];

        for (Projected t : prepared) {
            ProjectionMath.triangleBoundsInto(t.s0, t.s1, t.s2, width, height, bounds);
            int pyStart = Math.max(bounds[1], tileStart);
            int pyEnd = Math.min(bounds[3], tileEnd - 1);
            if (pyStart > pyEnd) continue;

            float shading = t.source.shading() * RenderEngine.computeInventoryLighting(t.source.normal());

            for (int py = pyStart; py <= pyEnd; py++) {
                for (int px = bounds[0]; px <= bounds[2]; px++) {
                    ProjectionMath.barycentricInto(t.s0, t.s1, t.s2, px + 0.5f, py + 0.5f, bary);
                    if (!ProjectionMath.isInsideTriangle(bary)) continue;

                    float depthVal = bary[0] * t.p0.z() + bary[1] * t.p1.z() + bary[2] * t.p2.z();
                    int idx = (py - tileStart) * width + px;
                    // Epsilon-tolerant rejection: coplanar faces (e.g. chest body SOUTH and lid
                    // SOUTH both at z=15 before camera) project to mathematically equal per-pixel
                    // depths, but barycentric interpolation over triangles with different vertex
                    // sets produces small floating-point differences. Without the epsilon, the
                    // later-drawn face occasionally wins in scattered pixels and the result is
                    // visible speckle z-fighting in the coplanar region. The insertion-order
                    // iteration above guarantees the intended painter sequence (body < lid < lock
                    // for a chest), so absorbing FP noise in the depth test makes the first-drawn
                    // coplanar face deterministically win.
                    if (depthVal <= depth[idx] + DEPTH_EPSILON) continue;

                    float u = bary[0] * t.source.uv0().x() + bary[1] * t.source.uv1().x() + bary[2] * t.source.uv2().x();
                    float v = bary[0] * t.source.uv0().y() + bary[1] * t.source.uv1().y() + bary[2] * t.source.uv2().y();

                    PixelBuffer texture = t.source.texture();
                    int tx = Math.clamp((int) (u * texture.width()), 0, texture.width() - 1);
                    int ty = Math.clamp((int) (v * texture.height()), 0, texture.height() - 1);
                    int sampled = texture.getPixel(tx, ty);
                    if (ColorMath.alpha(sampled) == 0) continue;

                    if (t.source.tintArgb() != ColorMath.WHITE)
                        sampled = ColorMath.blend(t.source.tintArgb(), sampled, BlendMode.MULTIPLY);

                    sampled = RenderEngine.applyShading(sampled, shading);
                    buffer.setPixel(px, py, sampled);
                    depth[idx] = depthVal;
                }
            }
        }
    }

    /**
     * Transforms a triangle's vertices and normal into camera space, projects each vertex into
     * screen space, and returns a {@link Projected} cache. Returns {@code null} when the
     * triangle opts into backface culling and the projected winding indicates a back face,
     * letting the caller drop it from the rasterization list.
     * <p>
     * Extracted as a static helper so Pass 1 can run as a pure parallel map: the function has
     * no shared state beyond the read-only {@code transform} and {@code perspective} inputs.
     */
    private static @org.jetbrains.annotations.Nullable Projected projectTriangle(
        @NotNull VisibleTriangle triangle,
        @NotNull Matrix4f transform,
        float scale,
        float offsetX,
        float offsetY,
        @NotNull PerspectiveParams perspective
    ) {
        // SIMD transforms (Task 10) - bit-identical to Vector3f.transform/transformNormal under
        // IEEE-754 round-to-nearest-even but perform one horizontal 4-lane accumulation instead
        // of three scalar dot products. This is the per-vertex hot path: fires 4x per triangle
        // (3 positions + 1 normal) on every rasterize call, so it dominates Pass 1 cost on
        // high-triangle models.
        Vector3f p0 = Vector3fOps.transform(triangle.position0(), transform);
        Vector3f p1 = Vector3fOps.transform(triangle.position1(), transform);
        Vector3f p2 = Vector3fOps.transform(triangle.position2(), transform);
        Vector3f normal = Vector3f.normalize(Vector3fOps.transformNormal(triangle.normal(), transform));

        Vector2f s0 = RenderEngine.projectPerspective(p0, scale, offsetX, offsetY, perspective);
        Vector2f s1 = RenderEngine.projectPerspective(p1, scale, offsetX, offsetY, perspective);
        Vector2f s2 = RenderEngine.projectPerspective(p2, scale, offsetX, offsetY, perspective);

        if (triangle.cullBackFaces() && isBackFacing(s0, s1, s2)) return null;
        return new Projected(triangle, p0, p1, p2, s0, s1, s2, normal);
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
     * Builds the model-space rotation matrix from the given Euler angles (in degrees).
     * Applied yaw first, then pitch, then roll using the row-vector convention.
     */
    private static @NotNull Matrix4f buildModelRotation(@NotNull EulerRotation rotation) {
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f) return Matrix4f.IDENTITY;

        Matrix4f yaw = Matrix4f.createRotationY(rotation.yawRadians());
        Matrix4f pitch = Matrix4f.createRotationX(rotation.pitchRadians());
        Matrix4f roll = Matrix4f.createRotationZ(rotation.rollRadians());
        return yaw.multiply(pitch).multiply(roll);
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
