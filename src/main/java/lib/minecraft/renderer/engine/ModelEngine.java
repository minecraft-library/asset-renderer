package lib.minecraft.renderer.engine;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.FitRequest;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.engine.raster.RasterMath;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
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
 * ordering for coplanar tie-breaks.
 *
 * <p>The engine owns the whole world-to-screen half of the pipeline: a {@link Camera} supplies
 * the view {@link #pose} (the baked {@code display.*} rotation) and the {@link Lens} (the
 * 3D-to-2D flatten), and an optional {@link Placement} supplies the subject's model-to-world
 * facing / chirality / anchor. Every rasterize call composes {@code pose x placement x
 * modelTransform} (column-vector, right-to-left application) so callers never thread a lens
 * through each draw and the same triangle list can be reused across rotations without rebuilding
 * the geometry.
 *
 * <p>Every renderer composing this engine can supply pitch, yaw, and roll Euler angles at render
 * time (see {@link EulerRotation}). The rotation is pre-multiplied into the composed transform so
 * the inner rasterization loop stays hot.
 *
 * <p><b>Per-pixel path.</b> Pass 1 transforms, projects, snaps to the {@code 1/400} coverage grid
 * ({@link #snapToCoverageGrid}), and back-face culls in parallel; Pass 2 rasterizes into
 * horizontal Y-band tiles (each owning a private depth slice) using Pineda incremental edge
 * functions with a {@code 1/256} fixed-point sample and an OpenGL top-left fill rule
 * (see {@link RasterMath}). Fragments are depth-tested ({@link #depthFails}, vanilla
 * {@code GL_LEQUAL}), texture-sampled, tinted ({@link BlendMode#MULTIPLY}), shaded
 * ({@link Shading}), and composited through the triangle's {@link BlendMode}.
 *
 * <p><b>Back-face culling</b> uses a signed screen-space winding test after projection
 * ({@link #isBackFacing}), which is robust against camera and model rotations and does not depend
 * on the per-triangle surface normal. Individual triangles can opt out of culling by setting
 * {@link SurfaceTraits#cullBackFaces()} to {@code false} - used for two-sided geometry such as
 * glass panes, leaves, banners, and the interior faces of beds and other non-convex blocks.
 * Translucent (partial-alpha shell) triangles, and any pass whose render type declares vanilla's
 * {@code sortOnUpload}, are additionally sorted back-to-front by quad depth
 * ({@link #sortNoCullBackToFront}); a pass vanilla registers with the depth write disabled skips it
 * here too, so its nested layers accumulate against the opaque depth behind them.
 */
public class ModelEngine {

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
     * {@link RasterMath RasterMath}) and with
     * texture grid sizes ({@code 1/16}, {@code 1/32}, {@code 1/64} for typical entity
     * textures), so quantized vertex positions almost never land at sample points that
     * produce exact-half barycentrics or exact-integer texel-coordinate interpolations -
     * precisely the cases the snap is here to break.
     *
     * <p>Overridable via {@code -Dasset.snap.grid=N} for empirical sweeps (e.g. confirming the block
     * pipeline shares the entity-tuned optimum). {@code N <= 0} disables the snap entirely
     * ({@link #snapToCoverageGrid} returns the vertex unchanged); the default {@code 400} is the
     * tuned value above. Both the entity and block ({@link Projection#VANILLA_ISO})
     * pipelines read this single constant.
     */
    private static final float SUBPIXEL_PRECISION = Float.parseFloat(System.getProperty("asset.snap.grid", "400"));

    /**
     * Half-extent of the orthographic depth range the raster depth is resolved against - the span
     * vanilla's own picture-in-picture entity renderer projects through, {@code zNear = -1000} to
     * {@code zFar = +1000}.
     *
     * <p>It is what decides how far apart two surfaces have to be before vanilla can tell them apart.
     * Vanilla's viewport transform lands every window depth beside {@code 0.5}, where a {@code float}
     * step is {@code 2^-24}, so forming that value rounds away everything finer - about six bits, lost
     * purely to centring the range on {@code 0.5}. Two surfaces closer than one step are the same depth
     * to vanilla, and their order falls to whichever drew last. Comparing raw camera-space depth instead
     * resolves roughly two orders of magnitude finer than that, which sounds harmless and is not: it
     * decides coplanar contests outright that vanilla leaves to draw order.
     *
     * <p>Overridable via {@code -Dasset.depth.range=N} for empirical sweeps; {@code N <= 0} compares raw
     * camera-space depth. Swept over {@code 125} to {@code 4000}, fleet parity is a broad shallow basin
     * whose floor sits on this value.
     */
    private static final float VANILLA_DEPTH_RANGE = Float.parseFloat(System.getProperty("asset.depth.range", "1000"));


    /**
     * Reciprocal of {@link #SUBPIXEL_PRECISION} (the grid cell size), precomputed so
     * {@link #snapToCoverageGrid} multiplies rather than divides per vertex. Zero when the snap is
     * disabled ({@code SUBPIXEL_PRECISION <= 0}).
     */
    private static final float SUBPIXEL_INV = SUBPIXEL_PRECISION > 0f ? 1f / SUBPIXEL_PRECISION : 0f;

    /**
     * The camera view {@link Camera#pose()} - the baked {@code display.*} rotation applied to a vertex
     * after the model transform and {@link #placement}, forming the world-to-screen half of the chain.
     */
    private final @NotNull Matrix4f pose;

    /**
     * The subject's model-to-world {@link Placement} matrix, composed between the camera pose and the
     * caller's model transform, or {@code null} for the identity no-op ({@link Placement#IDENTITY}),
     * which the transform chain skips.
     */
    private final @Nullable Matrix4f placement;

    /**
     * The camera {@link Lens} - the 3D-to-2D flatten (scale + projection family) applied to each
     * transformed vertex during rasterization. Held on the engine so callers never pass a lens per draw.
     */
    private final @NotNull Lens lens;

    /**
     * The pack-aware texture-resolution service bound to this engine's context, shared with kits and
     * layers that resolve textures against the same render.
     */
    private final @NotNull Textures textures;

    /**
     * Constructs a model engine with a preset {@link Camera} and the identity {@link Placement} - its
     * pose (applied after the caller's model transform during rasterization) and its lens (the 3D-to-2D
     * flatten). Pass a resolved camera (e.g. {@link Projection#VANILLA_ISO} resolved to its camera, the
     * vanilla {@code [30, 225, 0]} block-icon pose with the iso block lens) so the engine holds the whole
     * view + projection and callers don't thread the lens through every {@code rasterize} call.
     *
     * @param context the renderer context
     * @param camera the camera (pose + lens) composed with every rasterization
     */
    public ModelEngine(@NotNull RendererContext context, @NotNull Camera camera) {
        this(context, camera, Placement.IDENTITY);
    }

    /**
     * Constructs a model engine with a preset {@link Camera} and a subject {@link Placement}. The
     * placement is the model-to-world half of the pipeline (the subject's facing / chirality / anchor);
     * the camera is the world-to-screen half. Rasterization composes {@code pose x placement x
     * modelTransform}. {@link Placement#IDENTITY} makes this identical to the two-arg constructor.
     *
     * @param context the renderer context
     * @param camera the camera (pose + lens) composed with every rasterization
     * @param placement the subject's model-to-world placement
     */
    public ModelEngine(@NotNull RendererContext context, @NotNull Camera camera, @NotNull Placement placement) {
        this.textures = new Textures(context);
        this.pose = camera.pose();
        this.placement = placement.isIdentity() ? null : placement.modelToWorld();
        this.lens = camera.lens();
    }

    /**
     * Composes the camera-side transform: the camera {@link #pose} times this engine's model-to-world
     * {@link #placement} times the caller's {@code modelTransform}. When the placement is the identity
     * no-op ({@link #placement} {@code null}) this is exactly {@code pose x modelTransform}.
     *
     * @param modelTransform the caller's model-space transform (model spin / fit), applied first to a vertex
     * @return the composed model-to-screen pose
     */
    private @NotNull Matrix4f cameraSide(@NotNull Matrix4f modelTransform) {
        return this.placement == null
            ? this.pose.multiply(modelTransform)
            : this.pose.multiply(this.placement).multiply(modelTransform);
    }

    /**
     * The model-to-world-to-screen orientation this engine applies to a fit-neutral triangle list for the
     * given Euler rotation - {@code pose x placement x modelRotation}, with no fit scale or centring. A
     * caller sizing its own canvas (the entity's native pixels-per-block fit) measures its silhouette
     * bounds through this exact transform so the measured extent matches what {@link #rasterizeFitted}
     * will render, then hands those bounds back via a {@link FitRequest.Mode#NATIVE_SCALE} request.
     *
     * @param rotation the Euler-angle model rotation applied before the camera pose
     * @return the composed orientation transform (no fit scale / centring)
     */
    public @NotNull Matrix4f orient(@NotNull EulerRotation rotation) {
        return cameraSide(buildModelRotation(rotation));
    }

    /**
     * The pack-aware texture-resolution service bound to this engine's context, for kits and layers
     * that resolve textures while sharing the engine's render.
     */
    public @NotNull Textures textures() {
        return this.textures;
    }

    /**
     * Rasterizes a triangle list onto the given buffer with no additional model rotation, through the
     * engine's camera (pose + lens).
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer
    ) {
        rasterize(triangles, buffer, EulerRotation.NONE);
    }

    /**
     * Rasterizes a triangle list onto the given buffer after applying an Euler-angle rotation
     * to the model before the camera pose.
     * <p>
     * Rotations are applied in yaw-pitch-roll order (yaw first around the Y axis, then pitch
     * around the X axis, then roll around the Z axis) and the combined rotation is then
     * composed with the engine's camera pose. Supplying {@link EulerRotation#NONE} is
     * equivalent to calling {@link #rasterize(ConcurrentList, PixelBuffer)}.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param rotation the Euler-angle rotation applied to the model before the camera pose
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull EulerRotation rotation
    ) {
        Matrix4f modelRotation = buildModelRotation(rotation);
        // Column-vector chain: modelRotation applies first to a vertex, then placement, then the camera pose.
        Matrix4f transform = cameraSide(modelRotation);
        rasterizeInternal(triangles, buffer, transform, null);
    }

    /**
     * Rasterizes a triangle list after pre-multiplying an arbitrary model transform with the
     * engine's camera pose. Used for item display transforms (e.g. {@code thirdperson_righthand}) and
     * any other caller that needs more than a pitch-yaw-roll Euler rotation.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param modelTransform the model-space transform applied before the camera pose
     */
    public void rasterize(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull Matrix4f modelTransform
    ) {
        // Column-vector chain: modelTransform applies first to a vertex, then placement, then the camera pose.
        Matrix4f transform = cameraSide(modelTransform);
        rasterizeInternal(triangles, buffer, transform, null);
    }

    /**
     * Rasterizes a fixed-size triangle list scaled and centred to fill {@code fill} of the buffer's
     * smaller dimension, under this engine's camera and the given model rotation. The plain
     * {@link #rasterize rasterize} overloads project at the perspective's fixed scale, which leaves
     * a subject smaller than the canvas; this measures the geometry's projected silhouette and
     * scales it to the frame, so renderers with fixed model-space geometry (e.g. the player's
     * hard-coded body cubes) fill the canvas regardless of the model's native extent.
     * <p>
     * The silhouette is measured by projecting every vertex through {@code pose x rotation}
     * (orthographic), which accounts for the iso foreshortening and any caller rotation. The
     * geometry is translated so its model-space bounds centre projects to the canvas centre, then
     * uniformly scaled so the tighter projected axis spans {@code fill} of the smaller canvas side.
     *
     * @param triangles the triangle list, in fixed model-space units
     * @param buffer the destination buffer
     * @param rotation the Euler-angle model rotation applied before the camera pose
     * @param fill the fraction in {@code (0, 1]} of the smaller canvas dimension the silhouette spans
     */
    public void rasterizeFitted(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull EulerRotation rotation,
        float fill
    ) {
        rasterizeFitted(triangles, buffer, rotation, FitRequest.autoFill(fill));
    }

    /**
     * Rasterizes a triangle list fitted to the buffer under this engine's camera and the given model
     * rotation, driven by a {@link FitRequest}. {@link FitRequest.Mode#AUTO_FILL} measures the
     * silhouette and scales it to fill the canvas (the player + entity perspective / oblique path);
     * {@link FitRequest.Mode#NATIVE_SCALE} applies the caller's explicit scale and centres on its
     * pre-measured bounds (the entity's native-resolution orthographic path). The fit forks on lens
     * family inside {@link #prepareFit}, so the shared {@link #rasterizeInternal} core stays
     * lens-agnostic.
     * <p>
     * When {@code buffer} carries a coverage mask (see {@link PixelBuffer#enableMask()}), each pixel
     * whose winning fragment came from a {@link SurfaceTraits#glinted() glinted} triangle is marked, so
     * the glint compositor can restrict the enchantment foil to that geometry.
     *
     * @param triangles the triangle list, in fixed model-space units
     * @param buffer the destination buffer
     * @param rotation the Euler-angle model rotation applied before the camera pose
     * @param request how to scale and centre the silhouette
     */
    public void rasterizeFitted(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull EulerRotation rotation,
        @NotNull FitRequest request
    ) {
        if (triangles.isEmpty()) return;
        FitPlan plan = prepareFit(triangles, buffer, rotation, request);
        rasterizeInternal(triangles, buffer, plan.transform(), plan.fit());
    }

    /**
     * Measures the triangle silhouette and produces the lens-derived {@link FitPlan} that fills the
     * canvas: the composed model-to-screen transform plus an optional post-projection 2D
     * {@link Fit2D auto-fit}. This is the single place the auto-fit forks on lens family. The split is
     * whether the flatten is a pure uniform scale of x/y (so raw model-rotated bounds are proportional
     * to the screen silhouette) or not:
     * <ul>
     * <li><b>{@link Lens.Kind#PERSPECTIVE} / {@link Lens.Kind#OBLIQUE}</b> - the flatten is <b>not</b> a
     *     pure scale (perspective foreshortens toward the camera; oblique shears the depth axis into
     *     x/y), so the silhouette must be measured in <b>projected screen space</b> through
     *     {@link Lens#project} and filled with a post-projection 2D {@link Fit2D}. Measuring raw
     *     pre-projection bounds would miss the foreshortening / shear and let the drawn geometry
     *     overflow the canvas. The plain {@code orient} transform is kept so the fit never rescales the
     *     model-space depth a perspective lens reads. Perspective vertices interpolate
     *     perspective-correctly and oblique vertices screen-linearly - both driven by the
     *     {@code perspectiveCorrect} flag {@link #projectTriangle} sets from the lens kind.</li>
     * <li><b>{@link Lens.Kind#ORTHOGRAPHIC}</b> - the flatten is a pure uniform scale, so raw
     *     post-rotation bounds are proportional to the screen silhouette and the fit bakes a 3D
     *     {@code scale(fit).translate(-centre)} into the model transform ({@code null} 2D fit). Keeping
     *     the fit in 3D leaves the depth in the fitted frame, which preserves the screen-linear
     *     no-divide interpolation path and leaves the {@link Projection#VANILLA_ISO} coplanar depth
     *     tie-breaks unaffected. A 2D fit would match on screen but silently shift those tie-breaks.</li>
     * </ul>
     *
     * <p>A {@link FitRequest.Mode#NATIVE_SCALE} request short-circuits the measurement: the caller has
     * already sized its canvas at a native pixels-per-block ratio and measured its (alpha-tight,
     * possibly family-unioned) silhouette bounds through {@link #orient}, so the engine bakes the
     * caller's explicit scale in 3D (keeping the depth frame like the orthographic auto-fill arm) and
     * centres the caller's measured bounds midpoint in screen space via a scale-{@code 1} {@link Fit2D}.
     * This is the entity's orthographic path; the {@link FitRequest.Mode#AUTO_FILL} arms below are the
     * player + entity perspective / oblique paths.
     *
     * @param triangles the triangle list, in fixed model-space units (non-empty)
     * @param buffer the destination buffer
     * @param rotation the Euler-angle model rotation applied before the camera pose
     * @param request how to scale and centre the silhouette (auto-fill vs caller-supplied native scale)
     * @return the composed model-to-screen transform and the optional post-projection 2D auto-fit
     */
    private @NotNull FitPlan prepareFit(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull EulerRotation rotation,
        @NotNull FitRequest request
    ) {
        Matrix4f modelRotation = buildModelRotation(rotation);
        Matrix4f orient = cameraSide(modelRotation);
        float baseScale = Math.min(buffer.width(), buffer.height()) * this.lens.projectionScale();

        if (request.mode() == FitRequest.Mode.NATIVE_SCALE) {
            // Caller-sized canvas: bake the explicit model-units-to-NDC scale in 3D (uniform scale
            // commutes through the rotation, so the depth frame scales with it, keeping this arm's
            // depth behaviour aligned with the orthographic auto-fill one) and centre the caller's measured
            // bounds midpoint in screen space. The bounds were measured through `orient` at the same
            // native geometry scale, WITHOUT this fit scale; after the S(fit) bake the projected midpoint
            // scales by `fit`, so project `fit * midpoint` to get the screen-space centre to subtract.
            float fit = request.explicitScale();
            Box b = Objects.requireNonNull(request.explicitBounds(), "NATIVE_SCALE requires explicitBounds");
            float midX = (b.minX() + b.maxX()) * 0.5f;
            float midY = (b.minY() + b.maxY()) * 0.5f;
            float midZ = (b.minZ() + b.maxZ()) * 0.5f;
            Vector2f centre = this.lens.project(new Vector3f(fit * midX, fit * midY, fit * midZ), baseScale, 0f, 0f);
            Matrix4f modelTransform = modelRotation.scale(fit, fit, fit);
            return new FitPlan(cameraSide(modelTransform), new Fit2D(centre.x(), centre.y(), 1f));
        }

        float fill = request.fill();
        return switch (this.lens.kind()) {
            case PERSPECTIVE, OBLIQUE -> {
                float minPx = Float.MAX_VALUE, minPy = Float.MAX_VALUE;
                float maxPx = -Float.MAX_VALUE, maxPy = -Float.MAX_VALUE;
                for (VisibleTriangle tri : triangles)
                    for (Vector3f v : new Vector3f[]{ tri.position0(), tri.position1(), tri.position2() }) {
                        Vector2f s = this.lens.project(v.transform(orient), baseScale, 0f, 0f);
                        minPx = Math.min(minPx, s.x()); maxPx = Math.max(maxPx, s.x());
                        minPy = Math.min(minPy, s.y()); maxPy = Math.max(maxPy, s.y());
                    }

                float halfProjX = Math.max((maxPx - minPx) * 0.5f, 1e-6f);
                float halfProjY = Math.max((maxPy - minPy) * 0.5f, 1e-6f);
                float fit = Math.min(fill * buffer.width() * 0.5f / halfProjX, fill * buffer.height() * 0.5f / halfProjY);
                // 2D post-projection fit; transform stays the plain orient so model-space depth is intact.
                yield new FitPlan(orient, new Fit2D((minPx + maxPx) * 0.5f, (minPy + maxPy) * 0.5f, fit));
            }
            case ORTHOGRAPHIC -> {
                float minMx = Float.MAX_VALUE, minMy = Float.MAX_VALUE, minMz = Float.MAX_VALUE;
                float maxMx = -Float.MAX_VALUE, maxMy = -Float.MAX_VALUE, maxMz = -Float.MAX_VALUE;
                float minPx = Float.MAX_VALUE, minPy = Float.MAX_VALUE;
                float maxPx = -Float.MAX_VALUE, maxPy = -Float.MAX_VALUE;
                for (VisibleTriangle tri : triangles)
                    for (Vector3f v : new Vector3f[]{ tri.position0(), tri.position1(), tri.position2() }) {
                        minMx = Math.min(minMx, v.x()); maxMx = Math.max(maxMx, v.x());
                        minMy = Math.min(minMy, v.y()); maxMy = Math.max(maxMy, v.y());
                        minMz = Math.min(minMz, v.z()); maxMz = Math.max(maxMz, v.z());
                        Vector3f p = v.transform(orient);
                        minPx = Math.min(minPx, p.x()); maxPx = Math.max(maxPx, p.x());
                        minPy = Math.min(minPy, p.y()); maxPy = Math.max(maxPy, p.y());
                    }

                float centreX = (minMx + maxMx) * 0.5f;
                float centreY = (minMy + maxMy) * 0.5f;
                float centreZ = (minMz + maxMz) * 0.5f;
                float halfProjX = Math.max((maxPx - minPx) * 0.5f, 1e-6f);
                float halfProjY = Math.max((maxPy - minPy) * 0.5f, 1e-6f);

                float fitX = fill * buffer.width() * 0.5f / (halfProjX * baseScale);
                float fitY = fill * buffer.height() * 0.5f / (halfProjY * baseScale);
                float fit = Math.min(fitX, fitY);

                // 3D fit: vertex -> centre at origin -> uniform fit scale -> model rotation -> placement -> (camera pose).
                Matrix4f modelTransform = modelRotation.scale(fit, fit, fit).translate(-centreX, -centreY, -centreZ);
                yield new FitPlan(cameraSide(modelTransform), null);
            }
        };
    }

    /**
     * Two-pass rasterization core shared by every {@code rasterize} entry point. Pass 1 projects and
     * back-face culls every triangle in parallel through the already-composed model-to-screen
     * {@code transform}, then sorts translucent triangles back-to-front; Pass 2 rasterizes the prepared
     * list into horizontal Y-band tiles, each on its own depth slice, and composites into {@code buffer}.
     * Serial fallback (single depth buffer) below {@link #MIN_TILED_HEIGHT}.
     *
     * @param triangles the triangle list
     * @param buffer the destination buffer
     * @param transform the composed model-to-screen pose (from {@link #cameraSide})
     */
    private void rasterizeInternal(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull PixelBuffer buffer,
        @NotNull Matrix4f transform,
        @Nullable Fit2D fit
    ) {
        int width = buffer.width();
        int height = buffer.height();
        float scale = Math.min(width, height) * this.lens.projectionScale();
        float offsetX = width * 0.5f;
        float offsetY = height * 0.5f;
        float depthGrid = VANILLA_DEPTH_RANGE > 0f ? scale / (2f * VANILLA_DEPTH_RANGE) : 0f;

        // Pass 1: transform + project + backface cull, in parallel. Each triangle's projection is
        // pure functional - reads only the per-triangle vertex data and the shared immutable
        // transform - so a parallelStream over the FJP common pool scales this across cores.
        // map().filter().toList() preserves encounter order, which Pass 2's painter's algorithm
        // requires: the rasterizer iterates `prepared` in original insertion order, and that order
        // is what decides a coplanar pair - GL_LEQUAL passes the tie, so the last drawn wins
        // (see the comment on the depth test below).
        List<Projected> rawPrepared = triangles.parallelStream()
            .map(triangle -> projectTriangle(triangle, transform, scale, offsetX, offsetY, this.lens, fit))
            .filter(Objects::nonNull)
            .toList();
        List<Projected> prepared = sortNoCullBackToFront(rawPrepared);

        // Pass 2: tiled rasterization. Split the framebuffer into N horizontal Y-bands and
        // rasterize each band in parallel. Every band owns its own depth-buffer slice, so the
        // inner raster loop never contends with sibling threads. Every band still iterates the
        // full prepared list in original insertion order so painter's semantics - the tie-break
        // that makes the last-drawn coplanar face win - are preserved
        // within each tile; triangles rasterize into disjoint Y ranges across tiles, so the final
        // image is byte-identical to the serial path.
        //
        // Small framebuffers (height < MIN_TILED_HEIGHT) skip the tiled path - FJP overhead
        // outweighs the parallel speedup for sub-256-pixel images and the atlas tile path already
        // parallelises at the outer dispatch level.
        if (height < MIN_TILED_HEIGHT) {
            float[] depthBuffer = new float[width * height];
            Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);
            rasterizeTile(prepared, buffer, depthBuffer, width, height, 0, height, depthGrid);
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
            rasterizeTile(prepared, buffer, depthSlice, width, height, tileStart, tileEnd, depthGrid);
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
     * Gates on {@link SurfaceTraits#translucent()} or the pass's declared
     * {@link SurfaceTraits#sorted()} rather than on {@link SurfaceTraits#cullBackFaces()}, so
     * alpha-cutout no-cull cubes (warden tendrils, mushroom block-overlays whose texels are strictly
     * alpha 0 or 255) stay in emission order. Sorting those would shuffle a base/overlay coplanar
     * pair non-deterministically because their alpha-255 fragments depth-resolve on emission-order
     * tie-break rather than a true blend. The narrower gate avoids the mooshroom-block-overlay
     * regression an earlier {@code !cullBackFaces()} version produced (mooshroom 0.56 -&gt; 4.48).
     * <p>
     * The {@code sorted} arm is vanilla's {@code RenderSetup.sortOnUpload}, which the energy swirl
     * declares. It only bites alongside {@link SurfaceTraits#writesDepth()} - with no depth write
     * every fragment passes, and saturating addition does not care what order it arrives in.
     * <p>
     * Non-translucent triangles stay in emission order because that order <em>is</em> the
     * painter's-algorithm coplanar tie-break - {@link #depthFails} passes an equal depth, so the
     * last drawn wins; reordering them would non-deterministically pick among coplanar siblings
     * (same-face quad halves, base vs overlay at zero inflate, a limb flush against its torso).
     */
    private static @NotNull List<Projected> sortNoCullBackToFront(@NotNull List<Projected> prepared) {
        int total = prepared.size();
        int translucentCount = 0;
        for (Projected p : prepared)
            if (sortsBackToFront(p)) translucentCount++;
        if (translucentCount == 0) return prepared;
        List<Projected> opaque = new ArrayList<>(total - translucentCount);
        List<Projected> translucent = new ArrayList<>(translucentCount);
        for (Projected p : prepared) {
            if (sortsBackToFront(p)) translucent.add(p);
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
     * Whether a triangle belongs to a pass drawn back-to-front - either partial-alpha geometry whose
     * translucency is in its texture, or a pass whose render type declares vanilla's
     * {@code sortOnUpload}.
     *
     * @param t the projected triangle
     * @return whether this triangle sorts rather than keeping emission order
     */
    private static boolean sortsBackToFront(@NotNull Projected t) {
        return t.source().traits().translucent() || t.source().traits().pass().sorted();
    }

    /**
     * Depth sort key for a triangle that is stable across the two triangles a quad is split into. A
     * quad emits {@code (TL, BL, BR)} and {@code (TL, BR, TR)}, both sharing the {@code TL-BR}
     * diagonal, which for the rectangle every cube face is is the longest of the three edges. The
     * midpoint depth of that diagonal is therefore the same value for both triangles - and for a
     * parallelogram it is also the quad's centroid, the point vanilla's own {@code MeshData.sortQuads}
     * keys on - so sorting on it keeps a quad's halves together and orders quads back-to-front the way
     * vanilla does.
     * <p>
     * <b>The longest edge is measured in camera space, not on screen.</b> An iso projection turns a
     * square face into a rhombus whose short diagonal is no longer than its sides, so a screen-space
     * test picks a different edge for each half of such a quad and splits it; the halves then sort
     * apart, and once a sorted pass also writes depth they fight along the diagonal they share. In
     * camera space a cube face is still a rectangle, and its diagonal is unambiguously longest.
     *
     * @param t the projected triangle
     * @return the parent quad's diagonal-midpoint depth (smaller = farther)
     */
    private static float quadDepthKey(@NotNull Projected t) {
        float e01 = camDistSq(t.p0(), t.p1());
        float e12 = camDistSq(t.p1(), t.p2());
        float e20 = camDistSq(t.p2(), t.p0());
        if (e01 >= e12 && e01 >= e20) return (t.p0().z() + t.p1().z()) * 0.5f;
        if (e12 >= e20) return (t.p1().z() + t.p2().z()) * 0.5f;
        return (t.p2().z() + t.p0().z()) * 0.5f;
    }

    /**
     * Squared Euclidean distance between two camera-space points, used by {@link #quadDepthKey} to
     * pick a triangle's longest edge (the shared quad diagonal) without a square root.
     *
     * @param a the first camera-space point
     * @param b the second camera-space point
     * @return the squared distance between {@code a} and {@code b}
     */
    private static float camDistSq(@NotNull Vector3f a, @NotNull Vector3f b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        float dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
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
     *
     * @param prepared the projected triangles in painter's (insertion) order
     * @param buffer the destination buffer
     * @param depth the tile-local depth slice, sized {@code width * (tileEnd - tileStart)}
     * @param width the full image width
     * @param height the full image height
     * @param tileStart the inclusive first scanline row this tile owns
     * @param tileEnd the exclusive last scanline row this tile owns
     * @param depthGrid the camera-space-to-window-depth scale to round each interpolated depth through,
     *     or {@code 0} to leave it unrounded
     */
    private static void rasterizeTile(
        @NotNull List<Projected> prepared,
        @NotNull PixelBuffer buffer,
        float @NotNull [] depth,
        int width,
        int height,
        int tileStart,
        int tileEnd,
        float depthGrid
    ) {
        // The buffer owns its coverage mask when recording is enabled (absent otherwise); read it once
        // per tile so the per-pixel mark below stays a plain null-checked field access, not a getter.
        PixelMask glintMask = buffer.mask().orElse(null);

        // Task A: a single barycentric scratch reused for every pixel in this tile. Each
        // rasterizeTile call runs on one FJP worker thread, so these arrays are thread-confined
        // by construction - no synchronisation needed. Replaces the per-pixel `new Vector2f(...)`
        // + `new float[3]` from barycentric's allocating variant.
        // Task B: one int[4] scratch reused for every triangle's clipped screen-space bounds,
        // replacing the per-triangle `new int[4]` from triangleBounds.
        final float[] bary = new float[3];
        final int[] bounds = new int[4];

        for (Projected t : prepared) {
            RasterMath.triangleBoundsInto(t.s0, t.s1, t.s2, width, height, bounds);
            int pyStart = Math.max(bounds[1], tileStart);
            int pyEnd = Math.min(bounds[3], tileEnd - 1);
            if (pyStart > pyEnd) continue;

            // The kit baked the lighting term per-vanilla-render-path at geometry-build time
            // (Lighting.inventory for blocks/fluids, Lighting.EntityLighting#shade
            // for entities); the rasterizer just multiplies it in.
            float shading = t.source.shading();
            // Hoist the surface traits and the pass declaration once per triangle; the per-pixel loop
            // below reads glinted off the one and emissive/blend/alpha/writesDepth off the other, so
            // neither deref lands in the hot path.
            SurfaceTraits tr = t.source.traits();
            PassDeclaration pass = tr.pass();
            BlendMode blendMode = pass.blend();
            // Depth comes off a plane solved once per triangle from the snapped screen positions, the
            // way graphics hardware sets one up, rather than blended per pixel from barycentric weights.
            // The two forms describe the same plane and differ only in where they round: solving once
            // into two gradients gives two coplanar triangles slightly different gradients, so their
            // depth planes cross somewhere inside any overlap - which is the shape vanilla's own coplanar
            // contests take, resolving one way over part of a seam and the other way over the rest. A
            // perspective lens keeps the barycentric form, its depth being the un-fitted model-space one.
            float dzdx = 0f;
            float dzdy = 0f;
            boolean planeDepth = !t.perspectiveCorrect;
            if (planeDepth) {
                float ax = t.s1.x() - t.s0.x();
                float ay = t.s1.y() - t.s0.y();
                float bx = t.s2.x() - t.s0.x();
                float by = t.s2.y() - t.s0.y();
                float det = ax * by - bx * ay;
                if (det == 0f) {
                    planeDepth = false;
                } else {
                    float az = t.z1 - t.z0;
                    float bz = t.z2 - t.z0;
                    dzdx = (az * by - bz * ay) / det;
                    dzdy = (ax * bz - bx * az) / det;
                }
            }
            float alphaScale = pass.alpha();

            // Last texel of the face's own rectangle on each axis, hoisted once per triangle. A
            // quad's two triangles each carry the diagonal's opposite corners, so the max over
            // three vertices is the whole face's extent either way.
            PixelBuffer texture = t.source.texture();
            int lastTexelX = lastTexel(t.source.uv0().x(), t.source.uv1().x(), t.source.uv2().x(), texture.width());
            int lastTexelY = lastTexel(t.source.uv0().y(), t.source.uv1().y(), t.source.uv2().y(), texture.height());

            // Pineda incremental edge functions. Hoist the edge value computation to the
            // bbox top-left, then walk by stepX per pixel in X and stepY per pixel in Y. Per-
            // pixel coverage test drops to 3 add + sign check + at-most-3 top-left checks; no
            // re-quantization of the sample point. Bit-identical to per-pixel recompute -
            // integer addition is exact.
            RasterMath.EdgeCoefficients ec = t.edges;
            final boolean degenerate = ec.denom() == 0L;
            long sxStart = RasterMath.quantizeSample(bounds[0] + 0.5f);
            long syStart = RasterMath.quantizeSample(pyStart + 0.5f);
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
                    RasterMath.barycentricInto(t.s0, t.s1, t.s2, px + 0.5f, py + 0.5f, bary);
                    boolean inside = !degenerate
                        && e12 >= 0L && e20 >= 0L && e01 >= 0L
                        && (e12 != 0L || ec.topLeft12())
                        && (e20 != 0L || ec.topLeft20())
                        && (e01 != 0L || ec.topLeft01());
                    if (!inside) {
                        RendererDebug.pixelSkipFill(px, py, t.source.debugTag(), bary[0], bary[1], bary[2]);
                        continue;
                    }

                    float depthVal = planeDepth
                        ? t.z0 + dzdx * (px + 0.5f - t.s0.x()) + dzdy * (py + 0.5f - t.s0.y())
                        : bary[0] * t.z0 + bary[1] * t.z1 + bary[2] * t.z2;
                    if (depthGrid > 0f) depthVal = onVanillaDepthGrid(depthVal, depthGrid);
                    int idx = (py - tileStart) * width + px;
                    if (depthFails(depthVal, depth[idx])) {
                        RendererDebug.pixelSkipDepth(px, py, depthVal, t.source.debugTag(), depth[idx]);
                        continue;
                    }

                    float u, v;
                    if (t.perspectiveCorrect) {
                        // Perspective-correct: the screen-linear barycentric weights are re-weighted by
                        // each vertex's inverse clip-w, then normalized, so uv tracks the surface instead
                        // of the screen (fixes the affine texture "swim" a perspective lens would show).
                        float w0 = bary[0] * t.iw0, w1 = bary[1] * t.iw1, w2 = bary[2] * t.iw2;
                        float invW = w0 + w1 + w2;
                        u = (w0 * t.source.uv0().x() + w1 * t.source.uv1().x() + w2 * t.source.uv2().x()) / invW;
                        v = (w0 * t.source.uv0().y() + w1 * t.source.uv1().y() + w2 * t.source.uv2().y()) / invW;
                    } else {
                        u = bary[0] * t.source.uv0().x() + bary[1] * t.source.uv1().x() + bary[2] * t.source.uv2().x();
                        v = bary[0] * t.source.uv0().y() + bary[1] * t.source.uv1().y() + bary[2] * t.source.uv2().y();
                    }

                    int tx = Math.clamp((int) (u * texture.width()), 0, lastTexelX);
                    int ty = Math.clamp((int) (v * texture.height()), 0, lastTexelY);
                    int rawTexel = texture.getPixel(tx, ty);
                    if (ColorMath.alpha(rawTexel) == 0) {
                        RendererDebug.pixelSkipAlpha(px, py, depthVal, t.source.debugTag(), u, v, tx, ty, rawTexel);
                        continue;
                    }

                    int afterTint = t.source.tintArgb() != ColorMath.WHITE
                        ? ColorMath.blend(t.source.tintArgb(), rawTexel, BlendMode.MULTIPLY)
                        : rawTexel;

                    int afterShade = pass.emissive()
                        ? afterTint
                        : Shading.apply(afterTint, shading);
                    // Per-overlay opacity multiplier: scale the fragment's alpha before compositing.
                    // Default 1.0 (no-op) for every body / cutout / texture-alpha surface; only an
                    // overlay declaring an explicit alpha node (the warden pulsating-spots glow at
                    // 0.25) reduces it. A fractional layer opacity has no home in the overlay tint's
                    // alpha byte - the MULTIPLY tint blend keeps the texel's alpha - so it rides this
                    // dedicated path.
                    int afterAlpha = alphaScale == 1f
                        ? afterShade
                        : ColorMath.withAlpha(afterShade, Math.round(ColorMath.alpha(afterShade) * alphaScale));
                    // Colour composition is per-overlay (hoisted above): NORMAL source-over for
                    // bodies / eyes / texture-alpha shells (matching vanilla's TRANSLUCENT fragment
                    // blend), ADDITIVE for the energy-swirl glow (creeper / wither), REPLACE for a
                    // pass vanilla draws through a pipeline declaring no blend function - which writes
                    // the fragment over the destination rather than into it, alpha included. NORMAL is
                    // the default; eyes stay NORMAL (an earlier blanket additive produced enderman eye
                    // (255,144,255) vs vanilla's (204,0,250)). NORMAL and REPLACE differ only where a
                    // fragment's alpha is strictly between 0 and 255 - source-over returns the source
                    // unchanged at 255, and a 0-alpha texel is discarded above - so declaring a pass
                    // cutout costs nothing on geometry whose texels are all one or the other.
                    int outArgb = ColorMath.blend(afterAlpha, buffer.getPixel(px, py), blendMode);
                    buffer.setPixel(px, py, outArgb);
                    // Mark the glint mask wherever a glinted (armor) fragment wins the pixel, so the
                    // foil compositor restricts the enchantment glint to the armor. Uses absolute
                    // (px, py); the depth `idx` below is per-tile and must not be reused here.
                    if (glintMask != null && tr.glinted()) glintMask.mark(px, py);

                    RendererDebug.pixelWrite(px, py, depthVal, t.source.debugTag(),
                        u, v, tx, ty,
                        rawTexel, t.source.tintArgb(), afterTint,
                        shading, afterShade, blendMode, outArgb);
                    // Depth is written when the pass declares it - vanilla's
                    // DepthStencilState.writeDepth, carried per overlay rather than inferred from
                    // any other flag. A pass registered write-disabled (the eyes pipelines) lets its
                    // nested polygons all compare against the original opaque depth behind them and
                    // accumulate whatever order they arrive in. A pass that DOES write - every body,
                    // and the energy swirl, which declares DepthStencilState.DEFAULT - occludes its
                    // own later fragments, which is what stops a two-sided inflated shell adding its
                    // far faces on top of its near ones.
                    //
                    // Partial-alpha layers (slime outer shell) still depend on painter's order -
                    // they must be inserted into the bone/triangle list AFTER any opaque content
                    // meant to be visible behind them. The slime outer-shell extra_bone is appended
                    // last for exactly this reason. {@link #sortNoCullBackToFront} additionally
                    // draws a pass vanilla sorts back-to-front, so the closer face writes LAST.
                    if (pass.writesDepth())
                        depth[idx] = depthVal;
                }
            }
        }
    }

    /**
     * Returns the last texel index the rectangle a face's three UV corners span reaches on one axis.
     *
     * <p>{@code (int) (coord * texels)} maps the half-open interval {@code [t, t+1)} to texel
     * {@code t}, which is right everywhere strictly inside a face and steps one texel <b>past</b> it
     * at the face's own closed upper edge - a value the interpolation produces <b>exactly</b>
     * whenever that edge lands exactly on a sample point, which for a left-right symmetric subject
     * on an odd-width canvas is its whole front corner. The texel it steps onto belongs to the
     * neighbouring face in the sheet, so the fragment is drawn in a colour from geometry it is not
     * part of. Bounding it is the guard the fetch carries against the texture's own edge, one level
     * finer.
     *
     * <p>Rounding <b>up</b> and stepping back one is what distinguishes the two: a rect ending on a
     * whole texel keeps the last one inside it ({@code 24.0 -> 23}) while one ending part-way keeps
     * the texel it reaches into ({@code 23.7 -> 23}). The floor at {@code 0} keeps the range
     * non-empty for a face whose UVs collapse to a line.
     *
     * @param c0 the first corner's coordinate on this axis, normalized
     * @param c1 the second corner's coordinate on this axis, normalized
     * @param c2 the third corner's coordinate on this axis, normalized
     * @param texels the texture's size along this axis
     * @return the highest texel index the face covers, clamped to the texture
     */
    private static int lastTexel(float c0, float c1, float c2, int texels) {
        float high = Math.max(c0, Math.max(c1, c2));
        return Math.clamp((int) Math.ceil(high * texels) - 1, 0, texels - 1);
    }

    /**
     * Tests whether a fragment fails the depth test against the existing depth-buffer value at its
     * pixel - vanilla's {@code GL_LEQUAL}, with no tolerance either way.
     *
     * <p>A fragment passes when it is at or in front of the stored depth, so a coplanar later fragment
     * overwrites the earlier one and the last drawn wins, reproducing the way a model's overlay element
     * paints over an identically-shaped base (grass_block's tinted {@code #overlay} over its dirt
     * {@code #side}).
     *
     * <p>Emissive fragments once carried a slack here, so an overlay drawn at - or a shade behind - the
     * base it sits on still blended rather than being culled. Nothing needs it: the overlays it was
     * written for are pushed clear of their base by the loader's depth clearance, and a pass vanilla
     * registers write-disabled skips the depth <em>write</em>, so a stack of its fragments accumulates
     * against the opaque depth behind whatever order they arrive in. Swept across four orders of
     * magnitude the slack decided two rows in the corpus, both charged auras, and both are closer
     * without it.
     *
     * @param depthVal the candidate fragment's depth
     * @param existingDepth the depth currently stored at this pixel
     * @return {@code true} if the fragment should be rejected
     */
    private static boolean depthFails(float depthVal, float existingDepth) {
        return depthVal < existingDepth;
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
     *       adjacent (often transparent) texel.</li>
     *   <li>When the {@code 1/256} fixed-point edge function lands at {@code 0} (sample
     *       exactly on a triangle edge in fixed-point), our fill rule resolves it
     *       deterministically, and so does the GPU's.</li>
     * </ul>
     * <p><b>The snap does not reach the canvas-centre column, and nothing tuned into it can.</b> A
     * left-right symmetric subject centred on an odd-width canvas has a front corner at a model
     * {@code x} of exactly {@code 0}, so the projection lands it on {@code offsetX} - a whole pixel
     * centre, {@code 90.5} on a 181-wide canvas - by construction and identically in both
     * renderers. That coordinate is already <em>on</em> the {@code 1/400} grid, so snapping returns
     * it unchanged and there is no perturbation to be had at any grid size. The tie it leaves is
     * settled by the two rules that own it: the fill-rule classification in
     * {@code RasterMath.EdgeCoefficients}, which decides which of the two faces meeting at the
     * corner takes the sample, and {@link #lastTexel}, which keeps the fetch inside the face that
     * won it.
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
     * Transforms a triangle's vertices into camera space, projects each vertex into
     * screen space (snapping to the {@link #snapToCoverageGrid coverage grid}), precomputes the
     * edge coefficients, and returns a {@link Projected} cache. Returns {@code null} when the
     * triangle opts into backface culling and the projected winding indicates a back face,
     * letting the caller drop it from the rasterization list.
     * <p>
     * Extracted as a static helper so Pass 1 can run as a pure parallel map: the function has
     * no shared state beyond the read-only {@code transform} and {@code perspective} inputs.
     *
     * @param triangle the draw-list triangle to project
     * @param transform the composed model-to-screen pose applied to each vertex
     * @param scale the projection scale (smaller canvas dimension times the lens projection scale)
     * @param offsetX the screen-space X centre offset (half canvas width)
     * @param offsetY the screen-space Y centre offset (half canvas height)
     * @param perspective the lens performing the 3D-to-2D flatten
     * @param fit the post-projection 2D auto-fit, or {@code null} for the plain projection
     * @return the projected cache, or {@code null} if the triangle is culled as back-facing
     */
    private static @Nullable Projected projectTriangle(
        @NotNull VisibleTriangle triangle,
        @NotNull Matrix4f transform,
        float scale,
        float offsetX,
        float offsetY,
        @NotNull Lens perspective,
        @Nullable Fit2D fit
    ) {
        // Per-vertex hot path: fires 3x per triangle on every rasterize call, so it dominates Pass 1
        // cost on high-triangle models. Vector3f.transform silently dispatches to a 4-lane SIMD
        // implementation when the JDK Vector API module is loaded. The surface normal is deliberately
        // not transformed alongside them: the cull below is the screen-space winding test this class
        // documents, and the shade is already baked onto the triangle by the relight pass.
        Vector3f p0 = triangle.position0().transform(transform);
        Vector3f p1 = triangle.position1().transform(transform);
        Vector3f p2 = triangle.position2().transform(transform);

        Vector2f r0 = project2D(perspective, p0, scale, offsetX, offsetY, fit);
        Vector2f r1 = project2D(perspective, p1, scale, offsetX, offsetY, fit);
        Vector2f r2 = project2D(perspective, p2, scale, offsetX, offsetY, fit);

        Vector2f s0 = snapToCoverageGrid(r0);
        Vector2f s1 = snapToCoverageGrid(r1);
        Vector2f s2 = snapToCoverageGrid(r2);

        RendererDebug.pixelTriangle(triangle, s0, s1, s2, p0, p1, p2);

        if (triangle.traits().cullBackFaces() && isBackFacing(s0, s1, s2)) return null;
        RasterMath.EdgeCoefficients edges = RasterMath.EdgeCoefficients.of(s0, s1, s2);
        float[] rasterDepth = depthOnUnsnappedPlane(r0, r1, r2, s0, s1, s2, p0.z(), p1.z(), p2.z());
        // Per-vertex inverse clip-w for perspective-correct interpolation. depthScale is a flat 1 for
        // parallel projections, where the perspectiveCorrect flag is false and the rasterizer keeps the
        // screen-linear no-divide path.
        boolean perspectiveCorrect = perspective.kind() == Lens.Kind.PERSPECTIVE;
        return new Projected(triangle, p0, p1, p2, s0, s1, s2, edges,
            rasterDepth[0], rasterDepth[1], rasterDepth[2],
            perspective.depthScale(p0.z()), perspective.depthScale(p1.z()), perspective.depthScale(p2.z()),
            perspectiveCorrect);
    }

    /**
     * Re-reads each vertex's depth off the triangle's <b>unsnapped</b> plane at its <b>snapped</b>
     * screen position, so the coverage snap moves coverage without moving depth.
     *
     * <p>The rasterizer interpolates depth as {@code bary . z} with the barycentric weights taken from
     * the snapped vertices ({@link #snapToCoverageGrid}) while the {@code z} values belong to the
     * unsnapped ones. Depth is affine in screen space here (there is no perspective-correct depth
     * path - see the rasterizer's {@code depthVal}), so that pairing tilts every triangle's depth plane
     * by an amount computed from its own vertices. Two <em>genuinely coplanar</em> triangles therefore
     * stop agreeing: the worn-armour chestplate's torso box and its arm box overlap by two model units
     * at identical {@code z}, and the tilt put the arm a consistent 60 ULP in front, so it won a
     * contest vanilla resolves the other way - not as a tie the draw order breaks, but outright,
     * whichever order they were drawn in.
     *
     * <p>Substituting the plane's own value at the snapped vertex restores it: barycentric
     * interpolation of an affine function over the snapped triangle reproduces that function exactly,
     * so the depth sampled anywhere inside is the unsnapped plane's depth there, and coplanar
     * triangles agree again to within float rounding. The solve runs in {@code double} because its
     * whole purpose is to leave no systematic residue between two triangles of one plane. A triangle
     * with no unsnapped screen area has no plane to read, and keeps its vertex depths unchanged.
     *
     * @param r0 the first vertex's unsnapped screen position
     * @param r1 the second vertex's unsnapped screen position
     * @param r2 the third vertex's unsnapped screen position
     * @param s0 the first vertex's snapped screen position
     * @param s1 the second vertex's snapped screen position
     * @param s2 the third vertex's snapped screen position
     * @param z0 the first vertex's camera-space depth
     * @param z1 the second vertex's camera-space depth
     * @param z2 the third vertex's camera-space depth
     * @return the three raster depths, in vertex order
     */
    private static float @NotNull [] depthOnUnsnappedPlane(
        @NotNull Vector2f r0, @NotNull Vector2f r1, @NotNull Vector2f r2,
        @NotNull Vector2f s0, @NotNull Vector2f s1, @NotNull Vector2f s2,
        float z0, float z1, float z2
    ) {
        double dx1 = (double) r1.x() - r0.x();
        double dy1 = (double) r1.y() - r0.y();
        double dx2 = (double) r2.x() - r0.x();
        double dy2 = (double) r2.y() - r0.y();
        double denominator = dx1 * dy2 - dx2 * dy1;
        if (denominator == 0d) return new float[]{ z0, z1, z2 };

        double dz1 = (double) z1 - z0;
        double dz2 = (double) z2 - z0;
        double slopeX = (dz1 * dy2 - dz2 * dy1) / denominator;
        double slopeY = (dx1 * dz2 - dx2 * dz1) / denominator;
        return new float[]{
            planeDepth(z0, slopeX, slopeY, r0, s0),
            planeDepth(z0, slopeX, slopeY, r0, s1),
            planeDepth(z0, slopeX, slopeY, r0, s2)
        };
    }

    /**
     * Rounds one camera-space depth onto the window-depth grid vanilla resolves, and returns it in
     * camera-space units so every consumer downstream is unchanged.
     *
     * <p>The round trip is the point: {@code 0.5f - depth * k} is vanilla's own window depth, and forming
     * it in {@code float} rounds away everything finer than an ULP at {@code 0.5}. Undoing the map
     * afterwards is exact - subtracting two nearby values loses nothing - so what comes back is the
     * original depth carrying vanilla's resolution rather than this renderer's.
     *
     * @param depth the camera-space depth
     * @param k the camera-space-to-window-depth scale, {@code scale / (2 * range)}
     * @return the depth rounded onto vanilla's grid, in camera-space units
     */
    private static float onVanillaDepthGrid(float depth, float k) {
        float window = 0.5f - depth * k;
        return (0.5f - window) / k;
    }

    /**
     * Evaluates a depth plane, anchored at {@code origin} with the given screen-space slopes, at one
     * snapped screen position.
     *
     * @param anchorDepth the depth at {@code origin}
     * @param slopeX the plane's depth gradient along screen X
     * @param slopeY the plane's depth gradient along screen Y
     * @param origin the unsnapped screen position the plane is anchored at
     * @param at the snapped screen position to read the plane at
     * @return the plane's depth at {@code at}
     */
    private static float planeDepth(
        float anchorDepth, double slopeX, double slopeY, @NotNull Vector2f origin, @NotNull Vector2f at) {
        return (float) (anchorDepth
            + slopeX * ((double) at.x() - origin.x())
            + slopeY * ((double) at.y() - origin.y()));
    }

    /**
     * Flattens a camera-space point to screen space, then applies the optional post-projection 2D
     * {@link Fit2D auto-fit}. With no fit this is exactly {@code lens.project(p, scale, offsetX,
     * offsetY)}. With a fit, the point is projected around the screen origin, re-centred on the fit's
     * measured bounds centre, uniformly scaled to fill the canvas, and offset to the canvas centre -
     * a <b>2D</b> operation, so it never rescales the model-space depth the perspective foreshortening
     * reads (unlike a 3D {@code scale(fit)} bake, which distorts perspective).
     *
     * @param lens the lens performing the 3D-to-2D flatten
     * @param p the camera-space point
     * @param scale the projection scale
     * @param offsetX the screen-space X centre offset
     * @param offsetY the screen-space Y centre offset
     * @param fit the post-projection 2D auto-fit, or {@code null} for the plain projection
     * @return the screen-space point
     */
    private static @NotNull Vector2f project2D(
        @NotNull Lens lens, @NotNull Vector3f p, float scale, float offsetX, float offsetY, @Nullable Fit2D fit
    ) {
        if (fit == null) return lens.project(p, scale, offsetX, offsetY);
        Vector2f raw = lens.project(p, scale, 0f, 0f);
        return new Vector2f(
            (raw.x() - fit.centreX()) * fit.scale() + offsetX,
            (raw.y() - fit.centreY()) * fit.scale() + offsetY
        );
    }

    /**
     * A post-projection 2D auto-fit - the centre of the projected silhouette's screen-space bounds and
     * the uniform scale that fills the canvas. Applied after the lens flatten so the fit is a pure 2D
     * screen operation that leaves perspective foreshortening (which reads model-space depth) intact.
     *
     * @param centreX the projected bounds centre X, in screen pixels at zero offset
     * @param centreY the projected bounds centre Y, in screen pixels at zero offset
     * @param scale the uniform fill scale applied about the centre
     */
    private record Fit2D(float centreX, float centreY, float scale) {}

    /**
     * The lens-derived auto-fit result from {@link #prepareFit}: the composed model-to-screen
     * {@code transform} to rasterize through, and the optional post-projection 2D {@link Fit2D}. A
     * parallel lens (orthographic / oblique) bakes the fit into {@code transform} and leaves
     * {@code fit} {@code null}; a perspective lens keeps the plain orient transform and carries the fit
     * in {@code fit}. Encapsulates the one place the fit forks on lens family so the shared
     * {@link #rasterizeInternal} core stays lens-agnostic.
     *
     * @param transform the composed model-to-screen pose (from {@link #cameraSide}); for a parallel
     *     lens this already bakes the 3D {@code scale(fit).translate(-centre)}
     * @param fit the post-projection 2D auto-fit for a perspective lens, or {@code null} when the fit
     *     is baked into {@code transform}
     */
    private record FitPlan(@NotNull Matrix4f transform, @Nullable Fit2D fit) {}

    /**
     * Computes a signed triangle area in screen space. The camera transform is a pure rotation
     * (det=+1, preserves winding), so the Y-down screen conversion is applied only in the
     * projection step. The projection negates Y, which reverses winding: front-facing CCW
     * triangles end up CW on screen with negative signed area. A non-negative result therefore
     * means back-facing.
     * <p>
     * This is more robust than a camera-space normal test because it correctly handles arbitrary
     * rotations, perspective foreshortening, and non-uniform scales.
     *
     * @param v0 the first screen-space vertex
     * @param v1 the second screen-space vertex
     * @param v2 the third screen-space vertex
     * @return {@code true} if the projected winding is back-facing (non-negative signed area)
     */
    private static boolean isBackFacing(@NotNull Vector2f v0, @NotNull Vector2f v1, @NotNull Vector2f v2) {
        float signedArea = (v1.x() - v0.x()) * (v2.y() - v0.y())
            - (v2.x() - v0.x()) * (v1.y() - v0.y());
        return signedArea >= 0f;
    }

    /**
     * Builds the model-space rotation matrix from the given Euler angles (in degrees). Column-vector
     * chain {@code roll x pitch x yaw}: yaw applies first to a vertex, then pitch, then roll. Returns
     * {@link Matrix4f#IDENTITY} for the all-zero rotation.
     *
     * @param rotation the pitch / yaw / roll Euler rotation
     * @return the composed model-space rotation matrix
     */
    private static @NotNull Matrix4f buildModelRotation(@NotNull EulerRotation rotation) {
        if (rotation.pitch() == 0f && rotation.yaw() == 0f && rotation.roll() == 0f) return Matrix4f.IDENTITY;

        Matrix4f yaw = Matrix4f.createRotationY(rotation.yawRadians());
        Matrix4f pitch = Matrix4f.createRotationX(rotation.pitchRadians());
        Matrix4f roll = Matrix4f.createRotationZ(rotation.rollRadians());
        return roll.multiply(pitch).multiply(yaw);
    }

    /**
     * A per-frame triangle view that caches the camera-space transformed vertices, their screen
     * projections, and the precomputed
     * {@link RasterMath.EdgeCoefficients edge coefficients} for fast per-pixel coverage
     * testing. Not part of the public API - exists so the rasterization loop does not have to
     * recompute the transform or projection for every pixel and so the inside test reads
     * pre-quantized coefficients instead of re-quantizing 4 points each call.
     *
     * @param source the originating draw-list triangle (texture, UVs, tint, traits, shading)
     * @param p0 the first vertex in camera space, {@code z} carrying depth (larger = closer)
     * @param p1 the second vertex in camera space
     * @param p2 the third vertex in camera space
     * @param s0 the first vertex projected and coverage-snapped to screen space
     * @param s1 the second vertex projected and coverage-snapped to screen space
     * @param s2 the third vertex projected and coverage-snapped to screen space
     * @param edges the precomputed fixed-point edge coefficients for the incremental coverage walk
     * @param z0 the first vertex's raster depth - its plane's depth at its snapped position (see
     *     {@link #depthOnUnsnappedPlane}), which is what the per-pixel depth test interpolates;
     *     {@code p0.z()} stays the true camera-space depth the translucent sort keys off
     * @param z1 the second vertex's raster depth
     * @param z2 the third vertex's raster depth
     * @param iw0 the first vertex's inverse clip-{@code w} ({@link Lens#depthScale}), for perspective-correct interpolation
     * @param iw1 the second vertex's inverse clip-{@code w}
     * @param iw2 the third vertex's inverse clip-{@code w}
     * @param perspectiveCorrect whether attributes must be interpolated perspective-correctly (a perspective lens); parallel projections interpolate screen-linearly
     */
    private record Projected(
        @NotNull VisibleTriangle source,
        @NotNull Vector3f p0,
        @NotNull Vector3f p1,
        @NotNull Vector3f p2,
        @NotNull Vector2f s0,
        @NotNull Vector2f s1,
        @NotNull Vector2f s2,
        @NotNull RasterMath.EdgeCoefficients edges,
        float z0,
        float z1,
        float z2,
        float iw0,
        float iw1,
        float iw2,
        boolean perspectiveCorrect
    ) {}

}
