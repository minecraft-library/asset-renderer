package lib.minecraft.renderer.engine;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.ProjectionMath;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.pipeline.util.RendererDebug;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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

    /**
     * Sub-pixel grid resolution for {@link #snapToCoverageGrid}. Empirically tuned at
     * {@code 1/400}: a sweep across {@code 1/16, 1/64, 1/128, 1/256, 1/400, 1/512, 1/1024}
     * showed peak fleet parity at {@code 1/400}, with sharp local minima at {@code 1/396}
     * and {@code 1/404}; coarser grids ({@code 1/16-1/192}) shift silhouettes by whole
     * pixels; finer grids ({@code 1/448+}) re-introduce the exact-alignment cases.
     *
     * <p><b>Not a standard GPU sub-pixel precision</b> (real hardware uses {@code 1/16} or
     * {@code 1/256}). The {@code 1/400} value is INCOMMENSURATE with both our rasterizer's
     * {@code 1/256} fixed-point edge functions (see
     * {@link ProjectionMath ProjectionMath}) and with
     * texture grid sizes ({@code 1/16}, {@code 1/32}, {@code 1/64} for typical entity
     * textures), so quantized vertex positions almost never land at sample points that
     * produce exact-half barycentrics or exact-integer texel-coordinate interpolations -
     * precisely the cases the snap is here to break.
     *
     * <p>Overridable via {@code -Dsnap.grid=N} for empirical sweeps (e.g. confirming the block
     * pipeline shares the entity-tuned optimum). {@code N <= 0} disables the snap entirely
     * ({@link #snapToCoverageGrid} returns the vertex unchanged); the default {@code 400} is the
     * tuned value above. Both the entity ({@link ModelEngine}) and block
     * ({@link IsometricEngine}) pipelines read this single constant.
     */
    private static final float SUBPIXEL_PRECISION = Float.parseFloat(System.getProperty("snap.grid", "400"));
    private static final float SUBPIXEL_INV = SUBPIXEL_PRECISION > 0f ? 1f / SUBPIXEL_PRECISION : 0f;

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
        // Column-vector chain: modelRotation applies first to a vertex, then the camera.
        Matrix4f transform = this.camera.multiply(modelRotation);
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
        // Column-vector chain: modelTransform applies first to a vertex, then the camera.
        Matrix4f transform = this.camera.multiply(modelTransform);
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

        // Pass 1: transform + project + backface cull, in parallel. Each triangle's projection is
        // pure functional - reads only the per-triangle vertex data and the shared immutable
        // transform - so a parallelStream over the FJP common pool scales this across cores.
        // map().filter().toList() preserves encounter order, which Pass 2's painter's algorithm
        // requires: the rasterizer iterates `prepared` in original insertion order so the
        // DEPTH_EPSILON tie-break deterministically picks the first-drawn of any coplanar pair
        // (see the comment on the depth test below).
        List<Projected> rawPrepared = triangles.parallelStream()
            .map(triangle -> projectTriangle(triangle, transform, scale, offsetX, offsetY, perspective))
            .filter(Objects::nonNull)
            .toList();
        List<Projected> prepared = sortNoCullBackToFront(rawPrepared);

        // Pass 2: tiled rasterization. Split the framebuffer into N horizontal Y-bands and
        // rasterize each band in parallel. Every band owns its own depth-buffer slice, so the
        // inner raster loop never contends with sibling threads. Every band still iterates the
        // full prepared list in original insertion order so painter's semantics - the
        // DEPTH_EPSILON tie-break that makes the first-drawn coplanar face win - are preserved
        // within each tile; triangles rasterize into disjoint Y ranges across tiles, so the final
        // image is byte-identical to the serial path.
        //
        // Small framebuffers (height < MIN_TILED_HEIGHT) skip the tiled path - FJP overhead
        // outweighs the parallel speedup for sub-256-pixel images and the atlas tile path already
        // parallelises at the outer dispatch level.
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
     * Sorts translucent (partial-alpha shell) triangles back-to-front by centroid depth while
     * keeping all other triangles in their original emission order. The output is the original
     * non-translucent triangles (in emission order) followed by the translucent triangles
     * sorted by depth (farthest first / smallest depth value first, since our convention has
     * larger depth = closer to camera).
     * <p>
     * Vanilla's translucent render path (e.g. {@code ENTITY_TRANSLUCENT} for slime's outer shell)
     * uses {@code LESS_THAN_OR_EQUAL} depth-test with depth-write ON. The natural translucent
     * draw order is back-to-front so each closer fragment correctly blends OVER the farther one
     * that already wrote. Our pipeline emits triangles in bone/face enum order which doesn't
     * match vanilla's depth order; without this sort, the slime shell's near-camera DOWN face
     * was writing first and its far-camera SOUTH face was then depth-rejected, leaving a
     * single-pass blend (alpha 180) where vanilla writes a two-pass blend (alpha 233). Sorting
     * SOUTH to be drawn first then DOWN second matches vanilla's pixel: shell-only top-edge
     * pixel (78,15) goes from (73,123,63,180) to (96,161,82,233) which lands within 1-7 channel
     * units of vanilla's (95,158,75,233). Slime delta 14.86 -> 0.09.
     * <p>
     * Gates on {@link VisibleTriangle#translucent()} rather than {@link
     * VisibleTriangle#cullBackFaces()} so alpha-cutout no-cull cubes (warden tendrils, mushroom
     * block-overlays whose texels are strictly alpha 0 or 255) stay in emission order. Sorting
     * those would shuffle a base/overlay coplanar pair non-deterministically because their
     * alpha-255 fragments depth-resolve on emission-order tie-break rather than a true blend.
     * The narrower gate avoids the mooshroom-block-overlay regression an earlier
     * {@code !cullBackFaces()} version produced (mooshroom 0.56 -> 4.48).
     * <p>
     * Non-translucent triangles stay in emission order to preserve the painter's-algorithm
     * coplanar tie-break the {@link #DEPTH_EPSILON depth epsilon} relies on; reordering them
     * would non-deterministically pick among coplanar siblings (same-face quad halves, base
     * vs overlay at zero inflate).
     */
    private static @NotNull List<Projected> sortNoCullBackToFront(@NotNull List<Projected> prepared) {
        int total = prepared.size();
        int translucentCount = 0;
        for (Projected p : prepared)
            if (p.source().translucent()) translucentCount++;
        if (translucentCount == 0) return prepared;
        List<Projected> opaque = new ArrayList<>(total - translucentCount);
        List<Projected> translucent = new ArrayList<>(translucentCount);
        for (Projected p : prepared) {
            if (p.source().translucent()) translucent.add(p);
            else opaque.add(p);
        }
        // Smaller depth value = farther in our convention; we want farthest first so closer
        // fragments blend over them last. Sort by the parent QUAD's depth (via {@link #quadDepthKey}),
        // not the triangle centroid: vanilla sorts whole translucent quads back-to-front, but each
        // quad reaches us as two triangles split along its diagonal. Centroid-sorting them
        // independently lets nested geometry (honey_block's 1px-inset inner cube) reorder one
        // triangle of a face relative to the other, so the two halves layer differently and a seam
        // appears along the shared diagonal. The quad key is identical for both triangles, so a
        // stable sort keeps them adjacent and orders by quad centre, matching vanilla.
        translucent.sort((a, b) -> Float.compare(quadDepthKey(a), quadDepthKey(b)));
        List<Projected> out = new ArrayList<>(total);
        out.addAll(opaque);
        out.addAll(translucent);
        return out;
    }

    /**
     * Depth sort key for a translucent triangle that is stable across the two triangles a quad is
     * split into. A quad emits {@code (TL, BL, BR)} and {@code (TL, BR, TR)}, both sharing the
     * {@code TL-BR} diagonal - which is the hypotenuse, i.e. the longest edge of each triangle. The
     * midpoint depth of that longest edge is therefore the same value for both triangles (the quad's
     * diagonal centre), so sorting on it keeps a quad's halves together and orders quads back-to-front
     * the way vanilla's per-quad translucent sort does. Uses screen-space edge lengths to pick the
     * diagonal (the visual triangulation) and camera-space {@code z} for the depth value.
     *
     * @param t the projected translucent triangle
     * @return the parent quad's diagonal-midpoint depth (smaller = farther)
     */
    private static float quadDepthKey(@NotNull Projected t) {
        float e01 = screenDistSq(t.s0(), t.s1());
        float e12 = screenDistSq(t.s1(), t.s2());
        float e20 = screenDistSq(t.s2(), t.s0());
        if (e01 >= e12 && e01 >= e20) return (t.p0().z() + t.p1().z()) * 0.5f;
        if (e12 >= e20) return (t.p1().z() + t.p2().z()) * 0.5f;
        return (t.p2().z() + t.p0().z()) * 0.5f;
    }

    private static float screenDistSq(@NotNull Vector2f a, @NotNull Vector2f b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        return dx * dx + dy * dy;
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

            // Pineda incremental edge functions. Hoist the edge value computation to the
            // bbox top-left, then walk by stepX per pixel in X and stepY per pixel in Y. Per-
            // pixel coverage test drops to 3 add + sign check + at-most-3 top-left checks; no
            // re-quantization of the sample point. Bit-identical to per-pixel recompute -
            // integer addition is exact.
            ProjectionMath.EdgeCoefficients ec = t.edges;
            final boolean degenerate = ec.denom() == 0L;
            long sxStart = ProjectionMath.quantizeSample(bounds[0] + 0.5f);
            long syStart = ProjectionMath.quantizeSample(pyStart + 0.5f);
            long row12 = ec.a12() * sxStart + ec.b12() * syStart + ec.c12();
            long row20 = ec.a20() * sxStart + ec.b20() * syStart + ec.c20();
            long row01 = ec.a01() * sxStart + ec.b01() * syStart + ec.c01();

            for (int py = pyStart; py <= pyEnd; py++,
                    row12 += ec.stepY12(), row20 += ec.stepY20(), row01 += ec.stepY01()) {
                long e12 = row12;
                long e20 = row20;
                long e01 = row01;
                for (int px = bounds[0]; px <= bounds[2]; px++,
                        e12 += ec.stepX12(), e20 += ec.stepX20(), e01 += ec.stepX01()) {
                    ProjectionMath.barycentricInto(t.s0, t.s1, t.s2, px + 0.5f, py + 0.5f, bary);
                    boolean inside = !degenerate
                        && e12 >= 0L && e20 >= 0L && e01 >= 0L
                        && (e12 != 0L || ec.topLeft12())
                        && (e20 != 0L || ec.topLeft20())
                        && (e01 != 0L || ec.topLeft01());
                    if (!inside) {
                        RendererDebug.pixelSkipFill(px, py, t.source.debugTag(), bary[0], bary[1], bary[2]);
                        continue;
                    }

                    float depthVal = bary[0] * t.p0.z() + bary[1] * t.p1.z() + bary[2] * t.p2.z();
                    int idx = (py - tileStart) * width + px;
                    if (depthFails(depthVal, depth[idx], t.source.emissive())) {
                        RendererDebug.pixelSkipDepth(px, py, depthVal, t.source.debugTag(), depth[idx]);
                        continue;
                    }

                    float u = bary[0] * t.source.uv0().x() + bary[1] * t.source.uv1().x() + bary[2] * t.source.uv2().x();
                    float v = bary[0] * t.source.uv0().y() + bary[1] * t.source.uv1().y() + bary[2] * t.source.uv2().y();

                    PixelBuffer texture = t.source.texture();
                    int tx = Math.clamp((int) (u * texture.width()), 0, texture.width() - 1);
                    int ty = Math.clamp((int) (v * texture.height()), 0, texture.height() - 1);
                    int rawTexel = texture.getPixel(tx, ty);
                    if (ColorMath.alpha(rawTexel) == 0) {
                        RendererDebug.pixelSkipAlpha(px, py, depthVal, t.source.debugTag(), u, v, tx, ty, rawTexel);
                        continue;
                    }

                    int afterTint = t.source.tintArgb() != ColorMath.WHITE
                        ? ColorMath.blend(t.source.tintArgb(), rawTexel, BlendMode.MULTIPLY)
                        : rawTexel;

                    int afterShade = t.source.emissive()
                        ? afterTint
                        : RenderEngine.applyShading(afterTint, shading);
                    BlendMode blendMode = selectBlendMode(t.source.emissive());

                    int outArgb = ColorMath.blend(afterShade, buffer.getPixel(px, py), blendMode);
                    buffer.setPixel(px, py, outArgb);

                    RendererDebug.pixelWrite(px, py, depthVal, t.source.debugTag(),
                        u, v, tx, ty,
                        rawTexel, t.source.tintArgb(), afterTint,
                        shading, afterShade, blendMode, outArgb);
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
                    // {@link #sortTrianglesForRender} additionally sorts partial-alpha-no-cull
                    // triangles back-to-front so the closer face writes LAST, matching vanilla's
                    // translucent draw order.
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
     * <li><b>Standard</b>: matches vanilla's {@code GL_LEQUAL} - a fragment passes when it is at
     *     or in front of the stored depth, so a coplanar LATER fragment overwrites the earlier one
     *     (last-drawn wins), reproducing the way a model's overlay element paints over an
     *     identically-shaped base (grass_block tinted {@code #overlay} over its dirt
     *     {@code #side}).</li>
     * <li><b>Emissive</b>: same, plus a {@link #DEPTH_EPSILON} slack so an emissive overlay
     *     rendered AT - or within FP noise behind - the base it's painted on top of
     *     (spider/enderman eye overlays re-using the base entity's geometry post-bone_overrides)
     *     survives the test and blends additively. Behind-by-more-than-FP-noise is still rejected
     *     normally.</li>
     * </ul>
     *
     * @param depthVal the candidate fragment's depth
     * @param existingDepth the depth currently stored at this pixel
     * @param emissive whether the source triangle is an emissive overlay
     * @return {@code true} if the fragment should be rejected
     */
    private static boolean depthFails(float depthVal, float existingDepth, boolean emissive) {
        // Vanilla's GL_LEQUAL: a coplanar later fragment passes and overwrites the earlier one
        // (last-drawn wins). The previous `<= existingDepth + DEPTH_EPSILON` was first-drawn-wins,
        // which diverged from vanilla wherever a model paints a coplanar overlay over a base
        // (grass_block tinted `#overlay` over its dirt `#side`). Emissive overlays keep the
        // epsilon slack so one rendered at - or within FP noise behind - the base it sits on
        // (spider / enderman eye re-using base geometry) still blends instead of being culled.
        return depthVal < existingDepth - (emissive ? DEPTH_EPSILON : 0f);
    }

    /**
     * Quantizes a projected screen-space vertex position to the
     * {@link #SUBPIXEL_PRECISION 1/400 sub-pixel grid} to emulate the GPU's hardware
     * coverage / interpolation behaviour at edge and texel boundaries.
     *
     * <p><b>Why this is needed.</b> Our software rasterizer matches vanilla's CPU-side
     * vertex chain bit-for-bit (verified by per-vertex {@code [PX] TRI} dumps against the
     * vanilla harness) and uses the same {@code 1/256} fixed-point edge functions the GPU
     * does. At a typical pixel, the two pipelines agree. At <b>exact-alignment samples</b>,
     * they don't:
     * <ul>
     *   <li>When a triangle's sub-pixel-fixed-point bary works out to exactly {@code 0.5}
     *       on one axis (which happens for any symmetric cube geometry - tadpole tail,
     *       silverfish segments, witch hat - because the integer edge-function ratio
     *       collapses to {@code n / (2n)}), our UV interpolation hits exact texel
     *       boundaries like {@code v * texH = 8.0} and {@code (int)8.0 = 8} samples the
     *       adjacent (often transparent) texel. Vanilla's GPU computes the same exact
     *       bary but its hardware fragment-attribute interpolation rounds the result
     *       just below the boundary, so it samples texel {@code 7} instead.</li>
     *   <li>When the {@code 1/256} fixed-point edge function lands at {@code 0} (sample
     *       exactly on a triangle edge in fixed-point), our top-left fill rule resolves
     *       it deterministically; the GPU's resolution differs at column-vertical edges
     *       shared between two faces in iso projection (the canonical witch {@code x=21}
     *       column).</li>
     * </ul>
     * Snapping the projected vertex position to a {@code 1/400} grid before edge
     * classification perturbs both effects: the bary at sample {@code (px + 0.5, py + 0.5)}
     * shifts off the exact-{@code 0.5} line, and the edge function shifts off the exact
     * zero crossing. The perturbation is sub-pixel-small (max {@code 1/800} per axis -
     * about {@code 0.0013} canvas pixels) so no silhouette shifts, but it's enough to
     * dodge the exact-alignment cases.
     *
     * <p><b>What we tried and rejected.</b> Documented in
     * {@code [[project_tadpole_chain_structural_divergence]]}:
     * <ul>
     *   <li>Restructuring the asset pipeline to apply vanilla's pose-stack op sequence
     *       inline gave bit-perfect vertices vs the harness, but snap-off parity got
     *       WORSE (tadpole 0.22 -> 0.70) because bit-perfect symmetric vertices align
     *       cleanly with the {@code bary = 0.5} cases through MORE pixels than the
     *       legacy chain's accidentally-drifted output.</li>
     *   <li>Switching the per-bone matrix to vanilla's
     *       {@code translateAndRotate}-form (no pivot bake, T(pivot/16) as a matrix op)
     *       was bit-equivalent at the per-vertex level.</li>
     *   <li>Using vanilla's exact polygon triangulation diagonal (per-face cyclic shift
     *       in corner ordering) didn't help - the {@code bary = 0.5} sample lands on the
     *       diagonal regardless of which way the diagonal goes.</li>
     *   <li>Higher-precision (double) UV interpolation: same result, {@code 0.5} is exact
     *       in any float type.</li>
     *   <li>{@code 1/256} sub-pixel snap (matching GPU): regresses because it's
     *       commensurate with our fixed-point edge precision.</li>
     * </ul>
     * The conclusion is that the residual snap-off gap is in hardware-specific GPU coverage
     * and fragment-attribute interpolation that we cannot bit-reproduce in software at any
     * reasonable cost. Snap is the deterministic, cheap workaround: at the
     * {@code 0.04}-fleet-delta cost of perturbing every vertex by sub-pixel amounts,
     * it dodges the exact-alignment GPU-vs-software divergence entirely.
     */
    private static @NotNull Vector2f snapToCoverageGrid(@NotNull Vector2f v) {
        if (SUBPIXEL_PRECISION <= 0f) return v;
        return new Vector2f(Math.round(v.x() * SUBPIXEL_PRECISION) * SUBPIXEL_INV,
                            Math.round(v.y() * SUBPIXEL_PRECISION) * SUBPIXEL_INV);
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
        Vector3f p0 = triangle.position0().transform(transform);
        Vector3f p1 = triangle.position1().transform(transform);
        Vector3f p2 = triangle.position2().transform(transform);
        Vector3f normal = triangle.normal().transformNormal(transform).normalize();

        Vector2f s0 = snapToCoverageGrid(RenderEngine.projectPerspective(p0, scale, offsetX, offsetY, perspective));
        Vector2f s1 = snapToCoverageGrid(RenderEngine.projectPerspective(p1, scale, offsetX, offsetY, perspective));
        Vector2f s2 = snapToCoverageGrid(RenderEngine.projectPerspective(p2, scale, offsetX, offsetY, perspective));

        RendererDebug.pixelTriangle(triangle, s0, s1, s2, p0, p1, p2);

        if (triangle.cullBackFaces() && isBackFacing(s0, s1, s2)) return null;
        ProjectionMath.EdgeCoefficients edges = ProjectionMath.EdgeCoefficients.of(s0, s1, s2);
        return new Projected(triangle, p0, p1, p2, s0, s1, s2, normal, edges);
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
     * Column-vector chain: yaw applies first to a vertex, then pitch, then roll.
     */
    private static @NotNull Matrix4f buildModelRotation(@NotNull EulerRotation rotation) {
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f) return Matrix4f.IDENTITY;

        Matrix4f yaw = Matrix4f.createRotationY(rotation.yawRadians());
        Matrix4f pitch = Matrix4f.createRotationX(rotation.pitchRadians());
        Matrix4f roll = Matrix4f.createRotationZ(rotation.rollRadians());
        return roll.multiply(pitch).multiply(yaw);
    }

    /**
     * A per-frame triangle view that caches the model-space transformed vertices, their screen
     * projections, the transformed normal, and the precomputed
     * {@link ProjectionMath.EdgeCoefficients edge coefficients} for fast per-pixel coverage
     * testing. Not part of the public API - exists so the rasterization loop does not have to
     * recompute the transform or projection for every pixel and so the inside test reads
     * pre-quantized coefficients instead of re-quantizing 4 points each call.
     */
    private record Projected(
        @NotNull VisibleTriangle source,
        @NotNull Vector3f p0,
        @NotNull Vector3f p1,
        @NotNull Vector3f p2,
        @NotNull Vector2f s0,
        @NotNull Vector2f s1,
        @NotNull Vector2f s2,
        @NotNull Vector3f normal,
        @NotNull ProjectionMath.EdgeCoefficients edges
    ) {}

}
