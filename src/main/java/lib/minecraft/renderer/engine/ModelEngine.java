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
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        Matrix4f transform = modelRotation.multiply(this.camera);
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
        Matrix4f transform = modelTransform.multiply(this.camera);
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
        List<Projected> prepared = triangles.parallelStream()
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
        int tileCount = Math.clamp(cores, 1, height / MIN_ROWS_PER_TILE);
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

            // The kit baked the lighting term per-vanilla-render-path at geometry-build time
            // (RenderEngine.computeInventoryLighting for blocks/fluids, computeEntityInUiLighting
            // for entities); the rasterizer just multiplies it in.
            float shading = t.source.shading();

            for (int py = pyStart; py <= pyEnd; py++) {
                for (int px = bounds[0]; px <= bounds[2]; px++) {
                    ProjectionMath.barycentricInto(t.s0, t.s1, t.s2, px + 0.5f, py + 0.5f, bary);
                    if (!ProjectionMath.isInsideTriangleTopLeft(bary, t.s0, t.s1, t.s2)) continue;

                    float depthVal = bary[0] * t.p0.z() + bary[1] * t.p1.z() + bary[2] * t.p2.z();
                    int idx = (py - tileStart) * width + px;
                    if (depthFails(depthVal, depth[idx], t.source.emissive())) continue;

                    float u = bary[0] * t.source.uv0().x() + bary[1] * t.source.uv1().x() + bary[2] * t.source.uv2().x();
                    float v = bary[0] * t.source.uv0().y() + bary[1] * t.source.uv1().y() + bary[2] * t.source.uv2().y();

                    PixelBuffer texture = t.source.texture();
                    int tx = Math.clamp((int) (u * texture.width()), 0, texture.width() - 1);
                    int ty = Math.clamp((int) (v * texture.height()), 0, texture.height() - 1);
                    int sampled = texture.getPixel(tx, ty);
                    if (ColorMath.alpha(sampled) == 0) continue;

                    if (t.source.tintArgb() != ColorMath.WHITE)
                        sampled = ColorMath.blend(t.source.tintArgb(), sampled, BlendMode.MULTIPLY);

                    if (!t.source.emissive())
                        sampled = RenderEngine.applyShading(sampled, shading);
                    BlendMode blendMode = selectBlendMode(t.source.emissive());

                    sampled = ColorMath.blend(sampled, buffer.getPixel(px, py), blendMode);
                    buffer.setPixel(px, py, sampled);
                    // Depth written for non-emissive pixels. Emissive fragments deliberately
                    // skip the depth write so that overlapping translucent layers (breeze wind
                    // cone with 3 nested cubes at the same Y plane) can all accumulate via
                    // source-over instead of the first-drawn polygon's depth value rejecting
                    // every subsequent polygon at the same screen pixel. Mirrors vanilla's
                    // breeze pipeline behaviour where `sortOnUpload` + LESS_THAN_OR_EQUAL
                    // depth lets all wind polygons render in back-to-front order; our
                    // bone-order emission isn't depth-sorted, but skipping the depth write
                    // lets every emissive polygon compare against the original opaque depth
                    // (body / background) regardless of which emissive polygon drew first.
                    //
                    // Non-emissive partial-alpha layers (slime outer shell) still depend on
                    // painter's order - they must be inserted into the bone/triangle list
                    // AFTER any opaque content meant to be visible behind them. The slime
                    // outer-shell extra_bone is appended last for exactly this reason.
                    if (!t.source.emissive())
                        depth[idx] = depthVal;
                }
            }
        }
    }

    /**
     * Tests whether a fragment fails the depth test against the existing depth-buffer value at
     * its pixel. Two flavours:
     * <ul>
     * <li><b>Standard</b>: epsilon-tolerant rejection so coplanar faces (chest body SOUTH vs
     *     lid SOUTH at z=15) deterministically resolve in painter order without barycentric FP
     *     noise speckling. First-drawn wins on equal depth.</li>
     * <li><b>Emissive</b>: strict less-than, so an emissive overlay rendered AT the same depth
     *     as the base it's painted on top of (spider/enderman eye overlays re-using the base
     *     entity's geometry post-bone_overrides) survives the test and blends additively,
     *     instead of being eaten by the epsilon tie-break. Behind-by-more-than-FP-noise is
     *     still rejected normally.</li>
     * </ul>
     *
     * @param depthVal the candidate fragment's depth
     * @param existingDepth the depth currently stored at this pixel
     * @param emissive whether the source triangle is an emissive overlay
     * @return {@code true} if the fragment should be rejected
     */
    private static boolean depthFails(float depthVal, float existingDepth, boolean emissive) {
        return emissive
            ? depthVal < existingDepth
            : depthVal <= existingDepth + DEPTH_EPSILON;
    }

    /**
     * Picks the destination-blend mode for a fragment based on the source triangle's emissive
     * flag.
     * <p>
     * Both emissive and non-emissive overlays use {@link BlendMode#NORMAL} - the source-over
     * alpha blend, which at the {@code alpha == 255} cutout-texture-edge case collapses to a
     * straight REPLACE of the destination pixel. This matches vanilla's
     * {@code RenderPipelines.EYES} which composes with {@code BlendFunction.TRANSLUCENT}
     * ({@code glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)}) - not additive. The emissive
     * differentiator is the {@code EMISSIVE} + {@code NO_CARDINAL_LIGHTING} shader define
     * (the caller skips {@code applyShading} for emissive triangles) plus the strict-LT depth
     * test in {@link #depthFails} - the actual color composition is the same alpha-blend as
     * any other entity layer. Earlier revisions used {@link BlendMode#ADD} for emissive on the
     * assumption that {@code RenderType.eyes} was additive; sampling the rendered eye pixels
     * vs vanilla showed Java was producing {@code lit_skin + eye_texel} (e.g. enderman
     * {@code (255,144,255)} vs vanilla's pure {@code (204,0,250)}), confirming that vanilla
     * is replacing the base pixel rather than adding to it.
     *
     * @param emissive ignored - kept for call-site clarity until the parameter is removed
     * @return {@link BlendMode#NORMAL}
     */
    @SuppressWarnings("unused")
    private static @NotNull BlendMode selectBlendMode(boolean emissive) {
        return BlendMode.NORMAL;
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
    private static @Nullable Projected projectTriangle(
        @NotNull VisibleTriangle triangle,
        @NotNull Matrix4f transform,
        float scale,
        float offsetX,
        float offsetY,
        @NotNull PerspectiveParams perspective
    ) {
        // Per-vertex hot path: fires 4x per triangle (3 positions + 1 normal) on every rasterize
        // call, so it dominates Pass 1 cost on high-triangle models. Vector3f.transform /
        // transformNormal silently dispatch to a 4-lane SIMD implementation when the JDK Vector
        // API module is loaded.
        Vector3f p0 = Vector3f.transform(triangle.position0(), transform);
        Vector3f p1 = Vector3f.transform(triangle.position1(), transform);
        Vector3f p2 = Vector3f.transform(triangle.position2(), transform);
        Vector3f normal = Vector3f.normalize(Vector3f.transformNormal(triangle.normal(), transform));

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
