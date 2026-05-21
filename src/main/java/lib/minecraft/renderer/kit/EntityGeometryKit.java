package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EntityFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds rasterizer-ready triangles from a Java-derived {@link EntityModelData} in vanilla's
 * native {@code ModelPart}-style coordinate frame. Consumes geometry produced by
 * {@link ToolingEntityModels} (entity_geometry.json) which ships in this frame natively
 * (no parse-time conversion).
 * <p>
 * Convention:
 * <ul>
 * <li><b>Y-down, right-handed</b> - matches vanilla Java's {@code PartPose} / {@code ModelPart}
 * authoring (positive Y points toward the entity's feet from its root).</li>
 * <li>{@link EntityModelData.Cube#getOrigin() Cube origins} are the min corner in absolute
 * entity-root space (Y-down).</li>
 * <li>{@link EntityModelData.Bone#getPivot() Bone pivots} are in absolute entity-root space.</li>
 * <li>Bone transforms are pure rotations about pivots; translation-only bones contribute
 * identity.</li>
 * </ul>
 * The model is auto-centered and uniformly scaled to fit within {@code [-0.5, +0.5]} on the
 * longest axis. Output triangles undergo a single Y-axis flip at the boundary so the shared
 * isometric rasterizer (which expects screen-Y-up) renders them correctly without needing a
 * separate camera setup.
 */
@UtilityClass
public class EntityGeometryKit {

    /** 90% unit-cube fit - leaves a small margin around the longest-axis extent. */
    private static final float ENTITY_MODEL_FIT_EXTENT = 0.9f;

    /** Lower bound on extent before scaling - guards against zero-cube models producing infinity. */
    private static final float MIN_MODEL_EXTENT = 0.001f;

    /**
     * Per-axis flip / negation switches - debug/test only. Defaults to the production setting
     * (Y-flip on positions and Y-flip on normals; other axes identity). Override at runtime
     * via {@code -Dentity.flipX=true} etc. for parity sweeps; do not reference these for
     * production logic.
     */
    private static final boolean FLIP_X = Boolean.getBoolean("entity.flipX");
    private static final boolean FLIP_Y = !"false".equalsIgnoreCase(System.getProperty("entity.flipY", "true"));
    private static final boolean FLIP_Z = Boolean.getBoolean("entity.flipZ");
    private static final boolean FLIP_NORMAL_X = Boolean.getBoolean("entity.flipNX");
    private static final boolean FLIP_NORMAL_Y = !"false".equalsIgnoreCase(System.getProperty("entity.flipNY", "true"));
    private static final boolean FLIP_NORMAL_Z = Boolean.getBoolean("entity.flipNZ");
    /** Toggle whether the kit translates cube origins by their bone's pivot. Default true. */
    private static final boolean TRANSLATE_BY_PIVOT = !"false".equalsIgnoreCase(System.getProperty("entity.translateByPivot", "true"));

    /**
     * Standard GL view direction in vanilla's screen frame: camera at origin looking toward
     * {@code -Z}. Pre-rotated through the inverse iso pose chain plus our kit's {@code FLIP_Y}
     * to land in the same coordinate frame our face normals live in (after
     * {@link Vector3f#transformNormal(Vector3f, Matrix4f)} and the kit's
     * {@link #FLIP_NORMAL_Y}).
     * <p>
     * Used by {@link #computeViewAlignedShade} to pick front-vs-back PER_FACE_LIGHTING shade
     * for plane no-cull cubes. {@code dot(VIEW_DIRECTION_KIT, n_kit) < 0} means the polygon's
     * outward normal points TOWARD the camera (front-facing per vanilla's
     * {@code gl_FrontFacing}); {@code >= 0} means it points AWAY (back-facing).
     * <p>
     * Derived as {@code FLIP_Y * M_view^T * (0, 0, -1)} where {@code M_view = scale(1,1,-1) *
     * R_X(pitch) * R_Y(yaw) * R_X(180°)} is vanilla's iso transform chain (the trailing
     * {@code R_X(180°)} folds in vanilla's {@code LivingEntityRenderer.submit}'s
     * {@code rotateY(180°) + scale(-1,-1,1)} as a single equivalent X-axis rotation - see
     * {@link lib.minecraft.renderer.engine.IsometricEngine#CAMERA_ENTITY} for the full
     * derivation). For the standard {@code [210°, 45°, 0°]} iso pose this evaluates to
     * approximately {@code (0.6124, -0.5, 0.6124)}; the X and Z components are
     * {@code cos(30°) * sin(45°) = √6/4 ≈ 0.6124} (45° yaw splits horizontal direction
     * symmetrically into X and Z, modulated by {@code cos(30°)} from the pitch tilt), the Y
     * component is {@code -sin(30°) = -0.5} (30° pitch contribution, negated by the trailing
     * FLIP_Y compensation).
     */
    private static final @NotNull Vector3f VIEW_DIRECTION_KIT = computeKitFrameViewDirection();

    private static @NotNull Vector3f computeKitFrameViewDirection() {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        // Column-vector chain `FLIP_Y * R_X(-180°) * R_Y(-yaw) * R_X(-pitch) * scale(1,1,-1)`
        // implements `FLIP_Y * M_view^T * v` where M_view = scale(1,1,-1) * R_X(pitch) * R_Y(yaw)
        // * R_X(180°) is vanilla's iso transform. Each rotation transposes to its negated-angle
        // counterpart; scales are diagonal so transpose is identity. Rightmost applies first.
        Matrix4f viewToKit = Matrix4f.createScale(1f, -1f, 1f)
            .multiply(Matrix4f.createRotationX((float) -Math.PI))
            .multiply(Matrix4f.createRotationY(-iso.yawRadians()))
            .multiply(Matrix4f.createRotationX(-iso.pitchRadians()))
            .multiply(Matrix4f.createScale(1f, 1f, -1f));
        return Vector3f.transformNormal(new Vector3f(0f, 0f, -1f), viewToKit);
    }

    /**
     * Convenience overload that auto-computes bounds for a single-layer render.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture
    ) {
        return buildTriangles(model, texture, computeBounds(model), false);
    }

    /**
     * Variant accepting caller-supplied bounds for layered renders that must share one auto-fit
     * across base + overlay meshes.
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds
    ) {
        return buildTriangles(model, texture, bounds, false);
    }

    /**
     * Full variant tagging every triangle with the supplied {@code emissive} flag for overlay
     * layers that should render full-bright + additive (eyes / glowing spots). Uses the legacy
     * auto-fit scale ({@code 0.9 / bounds.maxExtent}); see the {@code ndcScale} overload for
     * native-resolution rendering aligned to the vanilla harness's pixels-per-block convention.
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive
    ) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ENTITY_MODEL_FIT_EXTENT / extent, 1f, ColorMath.WHITE);
    }

    /**
     * Native-resolution variant: caller supplies the model-units-to-NDC scale instead of the kit
     * auto-fitting via {@link #ENTITY_MODEL_FIT_EXTENT}. Used by
     * {@link lib.minecraft.renderer.EntityRenderer} to match the vanilla-reference-harness's
     * fixed {@code pixelsPerBlock} convention so two pipelines render at the same screen scale.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param bounds caller-supplied bounds (used for the centre anchor only - not for scale)
     * @param emissive whether triangles render full-bright + additive
     * @param ndcScale model-units-to-NDC scale; pre-computed by the renderer from the canvas
     *     dimensions and target pixels-per-block so {@code (vec - centre) * ndcScale} lands in NDC
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive,
        float ndcScale
    ) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ndcScale, 1f, ColorMath.WHITE);
    }

    /**
     * Native-resolution variant with a per-render model pre-scale. {@code modelScale} multiplies
     * every model vertex before the {@code (vec - centre) * ndcScale} step so vanilla's combined
     * renderer-scale + state-scale chain (e.g. wither's {@code scale(2,2,2)}, giant's
     * {@code state.scale=6}) lands at the harness's submit-time size. Caller-supplied bounds
     * must be the K-scaled bounds (so the centre matches the scaled vertices).
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Box bounds,
        boolean emissive,
        float ndcScale,
        float modelScale
    ) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildTrianglesWithScale(model, texture, new Vector3f(cx, cy, cz), emissive, ndcScale, modelScale, ColorMath.WHITE);
    }

    /**
     * Native-resolution variant taking an explicit model-space centre anchor (replacing the
     * {@code bounds.centre()} default). Used by
     * {@link lib.minecraft.renderer.EntityRenderer} to centre the silhouette on the canvas
     * by passing the model-space point whose iso projection equals the screen-space silhouette
     * midpoint - the {@code bounds.centre()} default over-pads non-brick-shaped silhouettes.
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Vector3f modelCentreAnchor,
        boolean emissive,
        float ndcScale,
        float modelScale
    ) {
        return buildTrianglesWithScale(model, texture, modelCentreAnchor, emissive, ndcScale, modelScale, ColorMath.WHITE);
    }

    /**
     * Variant taking a per-render ARGB tint that the rasterizer multiplies into every sampled
     * texel ({@code MULTIPLY} blend). Mirrors vanilla {@code LivingEntityRenderer.getModelTint}
     * (passed via {@code coloredCutoutModelRender}) and the per-layer pattern colors in
     * {@code TropicalFishPatternLayer} / {@code SheepWoolLayer}. {@link
     * dev.simplified.image.pixel.ColorMath#WHITE} is a no-op tint - same as the
     * non-tint overload.
     *
     * @param tintArgb the ARGB tint applied to every triangle this call produces. Use
     *     {@code 0xFFFFFFFF} ({@link dev.simplified.image.pixel.ColorMath#WHITE}) for no tint
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Vector3f modelCentreAnchor,
        boolean emissive,
        float ndcScale,
        float modelScale,
        int tintArgb
    ) {
        return buildTrianglesWithScale(model, texture, modelCentreAnchor, emissive, ndcScale, modelScale, tintArgb);
    }

    private static @NotNull BuildResult buildTrianglesWithScale(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull Vector3f centre,
        boolean emissive,
        float scale,
        float modelScale,
        int tintArgb
    ) {
        Map<String, Matrix4f> chainTransforms = buildChainTransforms(model.getBones());

        float cx = centre.x();
        float cy = centre.y();
        float cz = centre.z();

        // Pre-compose kitFit + each bone chain as a single fluent ops chain on identity. Bit-
        // identical to vanilla's PoseStack chain: scale * translate * scale * (per-bone
        // translate * rotate * translate). Eliminates the {@link Matrix4f#multiply} step at the
        // per-cube loop that drifts 1-4 ULPs vs the fluent path - see {@link Matrix4f} line
        // 313 commentary.
        Matrix4f kitFit = Matrix4f.IDENTITY
            .scale(FLIP_X ? -scale : scale, FLIP_Y ? -scale : scale, FLIP_Z ? -scale : scale)
            .translate(-cx, -cy, -cz)
            .scale(modelScale);
        Map<String, Matrix4f> kitFitChainTransforms = buildChainTransformsFrom(kitFit, model.getBones());

        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Map<String, Vector3f[]> boneBounds = new HashMap<>();

        for (Map.Entry<String, EntityModelData.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityModelData.Bone bone = boneEntry.getValue();
            Matrix4f boneChain = chainTransforms.get(boneName);

            float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY, bMinZ = Float.POSITIVE_INFINITY;
            float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY, bMaxZ = Float.NEGATIVE_INFINITY;

            // Java's PartPose / ModelPart authoring stores cube origins LOCAL to the bone's
            // pivot (the literal addBox(x, y, z, w, h, d) args from createBodyLayer); Bedrock
            // stores them in absolute entity-root space. The Java pipeline's parser produces
            // local origins (to match what bytecode literally encodes); the kit translates by
            // the bone's absolute pivot here so the rendered cubes land in world space.
            // {@link #TRANSLATE_BY_PIVOT} can disable this for the iteration harness.
            Vector3f bonePivot = bone.getPivot();
            // Bone-level uniform scale captured from {@code MeshTransformer.scaling(F)} /
            // {@code PartPose.scaled(F)}. Vanilla {@code ModelPart.render} translates by pivot,
            // rotates, then {@code poseStack.scale(s, s, s)} the local cube space - so each cube
            // vertex world-position is {@code pivot + R * (s * v_local)}. Our chain pivot-translates
            // post-rotation via {@link #composeCubeTransform}; multiplying {@code origin}, {@code
            // size}, and {@code inflate} by {@code s} here puts the cube in scaled-local space
            // before the bone-pivot translate, which is algebraically equivalent for any rotation
            // R that commutes with uniform scale (every R does). UVs stay tied to the unscaled
            // {@code size} field, matching vanilla's per-vertex scale-after-UV-resolve order.
            float s = bone.getScale();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();

                float scaledInflate = s * inflate;
                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + s * origin.x() : s * origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + s * origin.y() : s * origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + s * origin.z() : s * origin.z();
                Box cubeBounds = new Box(
                    ox - scaledInflate, oy - scaledInflate, oz - scaledInflate,
                    ox + s * size.x() + scaledInflate, oy + s * size.y() + scaledInflate, oz + s * size.z() + scaledInflate
                );

                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);
                // Fluent-composed perCubeChain: kitFit chain + bone hierarchy + bind + cube rot,
                // all post-multiplied via {@link Matrix4f#translate} / {@link Matrix4f#rotate} so
                // every multiplication matches vanilla's {@code PoseStack} ops bit-for-bit. The
                // pre-baked {@code kitFitChainTransforms} maps already incorporate kitFit; here we
                // apply only the cube-local bind + cube rotation.
                Matrix4f kitFitBoneChain = kitFitChainTransforms.get(boneName);
                Matrix4f perCubeChainFluent = composeCubeTransform(cube, bone, kitFitBoneChain);

                boolean cubeCullBackFaces = shouldCullBackFaces(cube, size, texture, texW, texH);
                // True iff this cube is no-cull AND its visible-face UVs include any
                // 0<alpha<255 texels - signal for the rasterizer's back-to-front sort (slime
                // outer shell, glass-like shells). Alpha-cutout no-cull cubes (warden tendrils,
                // mushroom block-overlays) whose texels are strictly 0 or 255 stay
                // {@code translucent=false} and bypass the sort because their alpha-255 fragments
                // depth-fail farther fragments correctly without reordering.
                boolean cubeIsTranslucent = !cubeCullBackFaces
                    && uvPartialAlphaPresent(cube, size, texture, texW, texH);

                Matrix4f perCubeChain = perCubeChainFluent;
                for (EntityFace face : EntityFace.CACHED_VALUES) {
                    Vector3f[] corners = face.corners(cubeBounds);
                    for (int i = 0; i < 4; i++) {
                        Vector3f transformed = Vector3f.transform(corners[i], perCubeChain);
                        float nx = transformed.x();
                        float ny = transformed.y();
                        float nz = transformed.z();
                        corners[i] = new Vector3f(nx, ny, nz);

                        bMinX = Math.min(bMinX, nx);
                        bMinY = Math.min(bMinY, ny);
                        bMinZ = Math.min(bMinZ, nz);
                        bMaxX = Math.max(bMaxX, nx);
                        bMaxY = Math.max(bMaxY, ny);
                        bMaxZ = Math.max(bMaxZ, nz);
                    }

                    Vector3f rawNormal = Vector3f.normalize(Vector3f.transformNormal(face.normal(), fullTransform));
                    Vector3f normal = new Vector3f(
                        (FLIP_NORMAL_X ? -1f : 1f) * rawNormal.x(),
                        (FLIP_NORMAL_Y ? -1f : 1f) * rawNormal.y(),
                        (FLIP_NORMAL_Z ? -1f : 1f) * rawNormal.z()
                    );

                    boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                    if (isPlaneCube && isDegeneratePlaneFace(size, face)) continue;

                    Vector2f[] effUv = resolvePolygonUv(face, cube, size, texW, texH);
                    float shading = computeFaceShading(normal, isPlaneCube, cubeCullBackFaces);

                    // Natural CCW emission {@code (0, 1, 2)} and {@code (0, 2, 3)}. Total
                    // pipeline chirality: kit FLIP_Y (det -1) × engine_camera (det -1) ×
                    // projection's -y (det -1) = det -1. Model CCW → screen CW → rasterizer's
                    // {@code signedArea < 0} check correctly classifies these as front-facing.
                    String debugTag = boneName + ":" + face.direction();
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[1], corners[2],
                        effUv[0], effUv[1], effUv[2],
                        texture, tintArgb,
                        normal, shading,
                        cubeCullBackFaces, emissive, cubeIsTranslucent,
                        debugTag
                    ));
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[2], corners[3],
                        effUv[0], effUv[2], effUv[3],
                        texture, tintArgb,
                        normal, shading,
                        cubeCullBackFaces, emissive, cubeIsTranslucent,
                        debugTag
                    ));
                }
            }

            if (bMinX != Float.POSITIVE_INFINITY)
                boneBounds.put(boneName, new Vector3f[]{
                    new Vector3f(bMinX, bMinY, bMinZ),
                    new Vector3f(bMaxX, bMaxY, bMaxZ)
                });
        }

        return new BuildResult(triangles, boneBounds);
    }

    /**
     * Computes the AABB of an entity model in the Java-native Y-down frame, after applying each
     * bone's ancestor anchor chain.
     */
    public static @NotNull Box computeBounds(@NotNull EntityModelData model) {
        return computeBounds(model, buildChainTransforms(model.getBones()));
    }

    /**
     * Walks every visible cube face, alpha-clips each face to its opaque-texel sub-rectangle,
     * projects the resulting 4 bilinear-interpolated corners through the bone chain, the per-
     * render {@code modelScale}, and the supplied screen-space transform, and returns the tight
     * screen-space AABB of the rendered silhouette.
     *
     * <p>Used by {@link lib.minecraft.renderer.EntityRenderer#render render()} to size the
     * output canvas. Mirrors the vanilla-reference-harness's
     * {@code EntityFrameRenderer.contributePolygonExtents}: instead of taking the full cube AABB,
     * each face contributes only the 3D extent of its opaque-pixel sub-rectangle. Faces with
     * fully-transparent UV regions contribute nothing; faces with sparse opaque stickers
     * (skeleton_horse rib-cage, warden tendrils, wither plane fins) tighten the bounds to the
     * actual rendered silhouette rather than the authored cube extent. For fully-opaque faces
     * the alpha sub-rect equals the polygon UV box and the four bilinear corners collapse to the
     * polygon's four vertices, matching the legacy AABB walk.
     *
     * <p>When {@code texture} is {@code null}, falls back to walking the 8 outer-AABB corners
     * per cube (the pre-alpha-tight behaviour) - used by callers that don't have a texture
     * resolved (slow tests, tooling).
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param screenTransform model-to-screen transform to apply BEFORE bounds accumulation;
     *     callers compose iso rotation + any chirality / flips here so the result lives in
     *     screen space (X = horizontal, Y = vertical, Z = depth - ignored)
     * @param modelScale per-entity scale applied to every cube vertex before the screen
     *     transform; pass 1 when no per-renderer scale is in effect
     * @param texture the entity texture used for per-face alpha-tight clipping; pass
     *     {@code null} to fall back to AABB-corner walk
     * @return tight screen-space bounds; the X and Y extents drive canvas sizing, the Z extent
     *     is depth and not consumed by the canvas-fit math
     */
    public static @NotNull Box computeScreenBounds(
        @NotNull EntityModelData model,
        @NotNull Matrix4f screenTransform,
        float modelScale,
        PixelBuffer texture
    ) {
        Map<String, Matrix4f> chainTransforms = buildChainTransforms(model.getBones());
        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture == null ? 1 : texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture == null ? 1 : texture.height());
        BoundsAccumulator acc = new BoundsAccumulator();
        boolean dump = BOUNDS_DUMP.get();

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            String boneName = entry.getKey();
            Matrix4f boneChain = chainTransforms.get(boneName);
            Vector3f bonePivot = bone.getPivot();
            float s = bone.getScale();
            int cubeIndex = 0;
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f cubeTransform = composeCubeTransform(cube, bone, boneChain);

                float scaledInflate = s * inflate;
                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + s * origin.x() : s * origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + s * origin.y() : s * origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + s * origin.z() : s * origin.z();
                Box cubeBounds = new Box(
                    ox - scaledInflate, oy - scaledInflate, oz - scaledInflate,
                    ox + s * size.x() + scaledInflate, oy + s * size.y() + scaledInflate, oz + s * size.z() + scaledInflate
                );

                if (texture == null) {
                    float[] xs = { cubeBounds.minX(), cubeBounds.maxX() };
                    float[] ys = { cubeBounds.minY(), cubeBounds.maxY() };
                    float[] zs = { cubeBounds.minZ(), cubeBounds.maxZ() };
                    for (float x : xs) for (float y : ys) for (float z : zs)
                        accumulate(new Vector3f(x, y, z), cubeTransform, modelScale, screenTransform, acc);
                    cubeIndex++;
                    continue;
                }

                boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                for (EntityFace face : EntityFace.CACHED_VALUES) {
                    if (isPlaneCube && isDegeneratePlaneFace(size, face)) continue;
                    Vector3f[] corners3d = face.corners(cubeBounds);
                    // Must match the renderer's UV resolver. {@link #resolveFaceUv} alone
                    // pairs uvs[i] with corners3d[i] at DIAGONALLY OPPOSITE vertices of the
                    // face (kit corner order is cyclic-shifted by 1 from vanilla's polygon
                    // vertex array); the BL/BR/TR/TL classifier then maps each 3D corner to
                    // the wrong UV role and the bilinear sub-rect corners land outside the
                    // visible silhouette whenever the opaque sub-rect is strictly smaller
                    // than the face UV bbox (warden tendrils, silverfish setae, fish fins).
                    // Fully-opaque faces are unaffected: the 4 contributed positions are then
                    // the 4 cube corners regardless of pairing.
                    Vector2f[] uvs = resolvePolygonUv(face, cube, size, texW, texH);
                    String dumpLabel = dump
                        ? String.format("bone=%s cube=%d face=%s orig=(%g,%g,%g) size=(%g,%g,%g) inflate=%g mirror=%s",
                            boneName, cubeIndex, face.direction(),
                            origin.x(), origin.y(), origin.z(),
                            size.x(), size.y(), size.z(),
                            inflate, cube.isMirror())
                        : null;
                    contributeFaceAlphaTight(corners3d, uvs, cubeTransform, modelScale, screenTransform, texture, acc, dumpLabel);
                }
                cubeIndex++;
            }
        }

        return acc.toBox();
    }

    /**
     * Thread-local toggle for per-polygon screen-bounds diagnostic dumps. Use
     * {@link #setBoundsDump(boolean)} from the calling thread (visual / parity test) to
     * activate; reset to {@code false} when done. Output goes to {@code System.out} as a
     * single line per polygon prefixed {@code [BD]} so callers can capture and diff against
     * the equivalent {@code vanilla-reference-harness} dump (see
     * {@code EntityFrameRenderer.dumpPolygonExtents}).
     */
    private static final ThreadLocal<Boolean> BOUNDS_DUMP = ThreadLocal.withInitial(() -> false);

    /** Enable / disable per-polygon screen-bounds dump on the current thread. */
    public static void setBoundsDump(boolean enable) {
        BOUNDS_DUMP.set(enable);
    }

    /**
     * Per-face alpha-tight bounds contribution mirroring
     * {@code EntityFrameRenderer.contributePolygonExtents}. Walks every texel inside the face's
     * UV bounding box on {@code texture}, accumulates the opaque sub-rectangle, bilinearly
     * interpolates the sub-rect's four corners through the polygon's TL/BL/BR/TR 3D positions,
     * and forwards each to {@code acc} via {@code cubeTransform → modelScale → screenTransform}.
     * <p>
     * Skips degenerate polygons ({@code uMin==uMax} or {@code vMin==vMax}). Falls back to
     * contributing all four raw 3D corners when the polygon's UVs aren't axis-aligned (rare for
     * vanilla cube faces; an upstream invariant breakage rather than a real geometry case).
     */
    private static void contributeFaceAlphaTight(
        @NotNull Vector3f[] corners3d, @NotNull Vector2f[] uvs,
        @NotNull Matrix4f cubeTransform, float modelScale, @NotNull Matrix4f screenTransform,
        @NotNull PixelBuffer texture, @NotNull BoundsAccumulator acc,
        @Nullable String dumpLabel
    ) {
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY, vMax = Float.NEGATIVE_INFINITY;
        for (Vector2f uv : uvs) {
            if (uv.x() < uMin) uMin = uv.x();
            if (uv.x() > uMax) uMax = uv.x();
            if (uv.y() < vMin) vMin = uv.y();
            if (uv.y() > vMax) vMax = uv.y();
        }
        if (uMin == uMax || vMin == vMax) {
            if (dumpLabel != null) System.out.println("[BD] " + dumpLabel + " DEGEN_UV");
            return;
        }

        Vector3f bl3 = null, br3 = null, tr3 = null, tl3 = null;
        float eps = 1e-4f;
        for (int i = 0; i < 4; i++) {
            boolean atUMin = Math.abs(uvs[i].x() - uMin) < eps;
            boolean atUMax = Math.abs(uvs[i].x() - uMax) < eps;
            boolean atVMin = Math.abs(uvs[i].y() - vMin) < eps;
            boolean atVMax = Math.abs(uvs[i].y() - vMax) < eps;
            if (atUMin && atVMin) bl3 = corners3d[i];
            else if (atUMax && atVMin) br3 = corners3d[i];
            else if (atUMax && atVMax) tr3 = corners3d[i];
            else if (atUMin && atVMax) tl3 = corners3d[i];
        }
        if (bl3 == null || br3 == null || tr3 == null || tl3 == null) {
            for (Vector3f c : corners3d) accumulate(c, cubeTransform, modelScale, screenTransform, acc);
            if (dumpLabel != null) System.out.println("[BD] " + dumpLabel + " NON_AXIS_UV_FALLBACK_4_CORNERS");
            return;
        }

        int W = texture.width();
        int H = texture.height();
        if (W <= 0 || H <= 0) {
            for (Vector3f c : corners3d) accumulate(c, cubeTransform, modelScale, screenTransform, acc);
            if (dumpLabel != null) System.out.println("[BD] " + dumpLabel + " NO_TEX_FALLBACK_4_CORNERS");
            return;
        }
        // Texel at integer pixel position {@code (px, py)} covers the half-open UV rect
        // {@code [px/W, (px+1)/W) x [py/H, (py+1)/H)}. The polygon's UV rect is
        // {@code [uMin, uMax] x [vMin, vMax]} (closed). A texel partially overlaps the polygon
        // when {@code (px+1)/W > uMin AND px/W < uMax}, which gives
        // {@code px >= floor(uMin*W) AND px < ceil(uMax*W)} (or equivalently
        // {@code px <= ceil(uMax*W) - 1}). Using {@code floor(uMax*W)} as the inclusive upper
        // bound over-includes the next-row texel when {@code uMax*W} lands exactly on an
        // integer boundary (e.g. piglin_brute right_sleeve face has {@code vMax = 48/64 = 0.75}
        // exactly, so {@code floor(48) = 48} admits texel row 48 which is part of the adjacent
        // left_arm UP face's UV region - that wraparound made our walker count the sleeve as
        // opaque even though its own UV bbox is fully transparent).
        int pxMin = clampPixel((int) Math.floor(uMin * W), W);
        int pxMax = clampPixel((int) Math.ceil(uMax * W) - 1, W);
        int pyMin = clampPixel((int) Math.floor(vMin * H), H);
        int pyMax = clampPixel((int) Math.ceil(vMax * H) - 1, H);
        int firstOpaquePx = Integer.MAX_VALUE, lastOpaquePx = Integer.MIN_VALUE;
        int firstOpaquePy = Integer.MAX_VALUE, lastOpaquePy = Integer.MIN_VALUE;
        for (int py = pyMin; py <= pyMax; py++) {
            for (int px = pxMin; px <= pxMax; px++) {
                if (ColorMath.alpha(texture.getPixel(px, py)) == 0) continue;
                if (px < firstOpaquePx) firstOpaquePx = px;
                if (px > lastOpaquePx) lastOpaquePx = px;
                if (py < firstOpaquePy) firstOpaquePy = py;
                if (py > lastOpaquePy) lastOpaquePy = py;
            }
        }
        if (firstOpaquePx == Integer.MAX_VALUE) {
            if (dumpLabel != null) System.out.printf(
                "[BD] %s uv_px=%d,%d,%d,%d ALL_TRANSPARENT%n",
                dumpLabel, pxMin, pyMin, pxMax, pyMax);
            return;
        }

        float opaqueUMin = Math.max(uMin, (float) firstOpaquePx / W);
        float opaqueUMax = Math.min(uMax, (float) (lastOpaquePx + 1) / W);
        float opaqueVMin = Math.max(vMin, (float) firstOpaquePy / H);
        float opaqueVMax = Math.min(vMax, (float) (lastOpaquePy + 1) / H);

        Vector3f bl = contributeBilinear(opaqueUMin, opaqueVMin, uMin, uMax, vMin, vMax, bl3, br3, tr3, tl3, cubeTransform, modelScale, screenTransform, acc);
        Vector3f br = contributeBilinear(opaqueUMax, opaqueVMin, uMin, uMax, vMin, vMax, bl3, br3, tr3, tl3, cubeTransform, modelScale, screenTransform, acc);
        Vector3f tr = contributeBilinear(opaqueUMax, opaqueVMax, uMin, uMax, vMin, vMax, bl3, br3, tr3, tl3, cubeTransform, modelScale, screenTransform, acc);
        Vector3f tl = contributeBilinear(opaqueUMin, opaqueVMax, uMin, uMax, vMin, vMax, bl3, br3, tr3, tl3, cubeTransform, modelScale, screenTransform, acc);

        if (dumpLabel != null) System.out.printf(
            "[BD] %s uv_px=%d,%d,%d,%d opaque_px=%d,%d,%d,%d screen_bl=(%g,%g,%g) screen_br=(%g,%g,%g) screen_tr=(%g,%g,%g) screen_tl=(%g,%g,%g)%n",
            dumpLabel,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            bl.x(), bl.y(), bl.z(),
            br.x(), br.y(), br.z(),
            tr.x(), tr.y(), tr.z(),
            tl.x(), tl.y(), tl.z());
    }

    private static @NotNull Vector3f contributeBilinear(
        float u, float v, float uMin, float uMax, float vMin, float vMax,
        @NotNull Vector3f bl3, @NotNull Vector3f br3, @NotNull Vector3f tr3, @NotNull Vector3f tl3,
        @NotNull Matrix4f cubeTransform, float modelScale, @NotNull Matrix4f screenTransform,
        @NotNull BoundsAccumulator acc
    ) {
        float sBar = (u - uMin) / (uMax - uMin);
        float tBar = (v - vMin) / (vMax - vMin);
        float w00 = (1f - sBar) * (1f - tBar);
        float w10 = sBar * (1f - tBar);
        float w11 = sBar * tBar;
        float w01 = (1f - sBar) * tBar;
        float px = w00 * bl3.x() + w10 * br3.x() + w11 * tr3.x() + w01 * tl3.x();
        float py = w00 * bl3.y() + w10 * br3.y() + w11 * tr3.y() + w01 * tl3.y();
        float pz = w00 * bl3.z() + w10 * br3.z() + w11 * tr3.z() + w01 * tl3.z();
        return accumulate(new Vector3f(px, py, pz), cubeTransform, modelScale, screenTransform, acc);
    }

    private static @NotNull Vector3f accumulate(
        @NotNull Vector3f p, @NotNull Matrix4f cubeTransform, float modelScale,
        @NotNull Matrix4f screenTransform, @NotNull BoundsAccumulator acc
    ) {
        Vector3f cubeSpace = Vector3f.transform(p, cubeTransform);
        Vector3f scaled = new Vector3f(cubeSpace.x() * modelScale, cubeSpace.y() * modelScale, cubeSpace.z() * modelScale);
        Vector3f screen = Vector3f.transform(scaled, screenTransform);
        acc.add(screen);
        return screen;
    }

    private static int clampPixel(int value, int size) {
        if (value < 0) return 0;
        if (value >= size) return size - 1;
        return value;
    }

    private static final class BoundsAccumulator {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        void add(@NotNull Vector3f p) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
            if (p.z() < minZ) minZ = p.z();
            if (p.z() > maxZ) maxZ = p.z();
        }

        @NotNull Box toBox() {
            if (minX == Float.POSITIVE_INFINITY)
                return new Box(0f, 0f, 0f, 0f, 0f, 0f);
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    /**
     * Builds a Matrix4f that maps a vertex in the entity's working pixel-unit frame
     * (post-bone-chain, post-pivot-translation, pre-rasterizer) into the entity-fit space
     * shared with {@link #buildTriangles}'s output. The transform is
     * {@code (v - center) * scale} on each axis, with {@link #FLIP_Y} (and {@link #FLIP_X} /
     * {@link #FLIP_Z}) applied.
     *
     * <p>Used by {@link lib.minecraft.renderer.EntityRenderer} to project block-model
     * overlay triangles (mooshroom mushroom blocks, etc) into the same entity-fit frame the
     * primary entity geometry has been baked into so they render at the correct scale and
     * orientation alongside the entity body.
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        return buildEntityFitMatrix(bounds, ENTITY_MODEL_FIT_EXTENT / extent);
    }

    /**
     * Native-resolution variant of {@link #buildEntityFitMatrix(Box)}: caller supplies the
     * model-units-to-NDC scale so block overlays composed onto an entity's frame match the same
     * scale the renderer used for the entity body (no auto-fit).
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds, float ndcScale) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildEntityFitMatrix(new Vector3f(cx, cy, cz), ndcScale);
    }

    /**
     * Native-resolution variant taking an explicit model-space centre anchor. Used by
     * {@link lib.minecraft.renderer.EntityRenderer} so block overlays composite at the
     * same silhouette-centred frame the entity body uses (the
     * {@link #buildTriangles(EntityModelData, PixelBuffer, Vector3f, boolean, float, float)
     * Vector3f overload} above).
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Vector3f modelCentre, float ndcScale) {
        Matrix4f translateToCentre = Matrix4f.createTranslation(-modelCentre.x(), -modelCentre.y(), -modelCentre.z());
        Matrix4f scaleAndFlip = Matrix4f.createScale(
            FLIP_X ? -ndcScale : ndcScale,
            FLIP_Y ? -ndcScale : ndcScale,
            FLIP_Z ? -ndcScale : ndcScale
        );
        // Translate to centre first (innermost), then scale + flip.
        return scaleAndFlip.multiply(translateToCentre);
    }

    /**
     * Resolves a bone's world transform (the ancestor-chain anchor used internally by
     * {@link #buildTriangles}). Returns identity when the bone is absent. Used by
     * {@link lib.minecraft.renderer.EntityRenderer} to anchor a block-overlay's transform
     * chain to a specific entity bone (mooshroom's third mushroom which sits on the head).
     */
    public static @NotNull Matrix4f resolveBoneAnchorMatrix(
        @NotNull EntityModelData model,
        @NotNull String boneName
    ) {
        return buildChainTransforms(model.getBones()).getOrDefault(boneName, Matrix4f.IDENTITY);
    }

    /**
     * Returns the bone's pivot point in the entity's working frame for callers that need to
     * pre-translate a block overlay relative to the bone's anchor. {@link Vector3f#ZERO} when
     * the bone is absent.
     */
    public static @NotNull Vector3f resolveBonePivot(
        @NotNull EntityModelData model,
        @NotNull String boneName
    ) {
        EntityModelData.Bone bone = model.getBones().get(boneName);
        return bone != null ? bone.getPivot() : Vector3f.ZERO;
    }

    private static @NotNull Box computeBounds(
        @NotNull EntityModelData model,
        @NotNull Map<String, Matrix4f> chainTransforms
    ) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            Matrix4f boneChain = chainTransforms.get(entry.getKey());
            // Same pivot-translation + bone-scale as in {@link #buildTriangles}.
            Vector3f bonePivot = bone.getPivot();
            float s = bone.getScale();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f fullTransform = composeCubeTransform(cube, bone, boneChain);

                float scaledInflate = s * inflate;
                float ox = TRANSLATE_BY_PIVOT ? bonePivot.x() + s * origin.x() : s * origin.x();
                float oy = TRANSLATE_BY_PIVOT ? bonePivot.y() + s * origin.y() : s * origin.y();
                float oz = TRANSLATE_BY_PIVOT ? bonePivot.z() + s * origin.z() : s * origin.z();
                float[] xs = { ox - scaledInflate, ox + s * size.x() + scaledInflate };
                float[] ys = { oy - scaledInflate, oy + s * size.y() + scaledInflate };
                float[] zs = { oz - scaledInflate, oz + s * size.z() + scaledInflate };

                for (float x : xs) for (float y : ys) for (float z : zs) {
                    Vector3f c = Vector3f.transform(new Vector3f(x, y, z), fullTransform);
                    minX = Math.min(minX, c.x());
                    minY = Math.min(minY, c.y());
                    minZ = Math.min(minZ, c.z());
                    maxX = Math.max(maxX, c.x());
                    maxY = Math.max(maxY, c.y());
                    maxZ = Math.max(maxZ, c.z());
                }
            }
        }

        if (minX == Float.POSITIVE_INFINITY)
            return new Box(0f, 0f, 0f, 0f, 0f, 0f);

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Builds the ancestor-anchor chain matrix for every bone. See {@link EntityGeometryKit#buildChainTransforms}. */
    private static @NotNull Map<String, Matrix4f> buildChainTransforms(
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        return buildChainTransformsFrom(Matrix4f.IDENTITY, bones);
    }

    /**
     * Builds the ancestor-anchor chain matrix for every bone starting from a non-identity base
     * matrix (typically the kit-fit chain). Each bone's chain is built by replaying its ancestor
     * pivot-centred rotations as fluent {@link Matrix4f#translate} + {@link Matrix4f#rotate}
     * post-multiplies on top of {@code base}, matching vanilla's {@code PoseStack} chain
     * bit-for-bit. Eliminates the {@code kitFit.multiply(boneChain)} step at the per-cube
     * loop that drifts 1-4 ULPs versus the fluent path - see {@link Matrix4f} line 313.
     */
    private static @NotNull Map<String, Matrix4f> buildChainTransformsFrom(
        @NotNull Matrix4f base,
        @NotNull Map<String, EntityModelData.Bone> bones
    ) {
        Map<String, Matrix4f> cache = new HashMap<>();
        for (String name : bones.keySet())
            resolveChainFrom(name, bones, cache, new LinkedHashSet<>(), base);
        return cache;
    }

    private static @NotNull Matrix4f resolveChain(
        @NotNull String name,
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull Map<String, Matrix4f> cache,
        @NotNull Set<String> visiting
    ) {
        return resolveChainFrom(name, bones, cache, visiting, Matrix4f.IDENTITY);
    }

    /**
     * Variant of {@link #resolveChain} that builds each bone's chain matrix starting from
     * {@code root} via fluent {@link Matrix4f#translate} / {@link Matrix4f#rotate} ops. Bone-only
     * chains use {@link Matrix4f#IDENTITY} as root; kit-fit-pre-baked chains pass the kit-fit
     * matrix so the fluent op sequence matches vanilla's PoseStack chain exactly.
     */
    private static @NotNull Matrix4f resolveChainFrom(
        @NotNull String name,
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull Map<String, Matrix4f> cache,
        @NotNull Set<String> visiting,
        @NotNull Matrix4f root
    ) {
        Matrix4f cached = cache.get(name);
        if (cached != null) return cached;
        EntityModelData.Bone bone = bones.get(name);
        if (bone == null) return root;
        if (visiting.contains(name)) return applyPivotCenteredRotation(root, bone.getPivot(), bone.getRotation());
        visiting.add(name);

        String parent = bone.getParent();
        Matrix4f base;
        if (parent == null || parent.equals(name) || !bones.containsKey(parent)) {
            base = root;
        } else {
            base = resolveChainFrom(parent, bones, cache, visiting, root);
        }
        Matrix4f composed = applyPivotCenteredRotation(base, bone.getPivot(), bone.getRotation());

        visiting.remove(name);
        cache.put(name, composed);
        return composed;
    }

    private static @NotNull Matrix4f composeCubeTransform(
        @NotNull EntityModelData.Cube cube,
        @NotNull EntityModelData.Bone bone,
        @NotNull Matrix4f boneChain
    ) {
        EulerRotation cubeRot = cube.getRotation();
        EulerRotation bindPose = bone.getBindPoseRotation();
        boolean hasCube = !isZero(cubeRot);
        boolean hasBind = !isZero(bindPose);
        if (!hasCube && !hasBind) return boneChain;

        // Cube rotation applies first to the vertex, then the bone's bind pose, then the bone
        // chain. Each fluent post-multiply mirrors vanilla's PoseStack.translate/mulPose/translate
        // sequence, so the chain composes as `boneChain * bindPose * cubeRot` with cubeRot
        // innermost (rightmost) on a column vector while staying bit-identical to JOML.
        Matrix4f acc = boneChain;
        if (hasBind) acc = applyPivotCenteredRotation(acc, bone.getPivot(), bindPose);
        if (hasCube) acc = applyPivotCenteredRotation(acc, cube.getPivot(), cubeRot);
        return acc;
    }

    private static boolean isZero(@NotNull EulerRotation r) {
        return r.pitch() == 0f && r.yaw() == 0f && r.roll() == 0f;
    }

    /**
     * Java-frame {@code T(+pivot) * R(rotation) * T(-pivot)} column-vector matrix that rotates
     * a vertex around {@code pivot}. Rightmost {@code T(-pivot)} applies first, moving the
     * pivot to the origin; then {@code R}; then {@code T(+pivot)} moves the pivot back.
     * <p>
     * <b>Rotation composition:</b> the rotation is built from a {@link Quaternionf#rotationZYX}
     * quaternion so the resulting matrix is bit-identical to vanilla
     * {@code ModelPart.translateAndRotate}'s {@code mulPose(new Quaternionf().rotationZYX(zRot,
     * yRot, xRot))}. Vanilla applies pitch (X) first, then yaw (Y), then roll (Z) to the bone
     * vertex; the quaternion encodes that same order without going through any
     * matrix-multiplication chain whose float result depends on associativity.
     * <p>
     * <b>Sign convention:</b> Java's {@code +xRot} (pitch) tilts a bone forward, {@code +yRot}
     * (yaw) turns right, {@code +zRot} (roll) rolls right, applied directly with no negation
     * since the kit operates in vanilla Java's native Y-down frame.
     */
    private static @NotNull Matrix4f pivotCenteredRotation(
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation
    ) {
        // Build T(+p) * R * T(-p) via fluent ops so the matrix entries match JOML's PoseStack
        // pose.translate(p); pose.mulPose(quat); pose.translate(-p) bit-for-bit.
        return applyPivotCenteredRotation(Matrix4f.IDENTITY, pivot, rotation);
    }

    /**
     * Returns {@code base * T(+pivot) * R * T(-pivot)} computed via the fluent post-multiply
     * path ({@link Matrix4f#translate}, {@link Matrix4f#rotate}). This composes a pivot-centred
     * bone rotation onto an existing chain matrix in the same op sequence vanilla's
     * {@code PoseStack} uses: {@code pose.translate(+p); pose.mulPose(q); pose.translate(-p)}.
     * <p>
     * Returning a fresh chain via the fluent ops is 0 ULP vs vanilla; building each bone's
     * {@code T(+p)*R*T(-p)} in isolation and then composing via {@link Matrix4f#multiply}
     * drifts by 1-4 ULPs per entry (full 4x4 multiply rounds differently than the specialized
     * translate / rotate post-multiplies that JOML's {@code Matrix4f} uses internally).
     * <p>
     * <b>Why not drop the T(-p) and use vanilla's bare T(p) * R shape?</b> The cube origin
     * convention in {@code entity_geometry.json} stores ABSOLUTE-frame positions (bone pivot
     * pre-added by the tooling); vanilla MC uses bone-LOCAL cube origins. Hand-edits like
     * the donkey/mule ear entries write {@code cube.pivot} in absolute coords too, so the
     * un-translate is load-bearing for that convention. Switching to bare {@code T(p)*R}
     * would require translating every absolute-frame coordinate (cube origin, cube pivot,
     * any other geometry hand-edit) back to bone-local. Tested 2026-05-21: drops donkey to
     * 163.8, mule to 185.4 (compensating absolute-pivot hand-edits no longer align). The
     * un-translate stays.
     */
    private static @NotNull Matrix4f applyPivotCenteredRotation(
        @NotNull Matrix4f base,
        @NotNull Vector3f pivot,
        @NotNull EulerRotation rotation
    ) {
        if (isZero(rotation)) return base;
        Quaternionf quat = Quaternionf.rotationZYX(
            rotation.rollRadians(), rotation.yawRadians(), rotation.pitchRadians()
        );
        return base
            .translate(pivot.x(), pivot.y(), pivot.z())
            .rotate(quat)
            .translate(-pivot.x(), -pivot.y(), -pivot.z());
    }

    /** UV resolution. Forwards the cube's {@code mirror} flag to {@link Vector4f#toUvCorners} for the U-flip. */
    private static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull EntityFace face,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        float texWidth,
        float texHeight
    ) {
        EntityModelData.FaceUv override = cube.getFaceUv().get(face.direction());
        Vector4f rect;
        if (override == null) {
            rect = face.defaultUv(cube.getUv(), size);
        } else {
            Vector2f uv = override.getUv();
            Vector2f uvSize = override.getUvSize();
            rect = new Vector4f(uv.x(), uv.y(), uv.x() + uvSize.x(), uv.y() + uvSize.y());
        }
        return rect.toUvCorners(texWidth, texHeight, 0, cube.isMirror());
    }

    /**
     * Resolves the per-vertex UV array for one polygon, including mirror handling and the
     * vanilla-spec slot permutation. The output is indexed in the kit's corner order
     * ({@link EntityFace#vertexIndices}) so each {@code corners[i]} pairs with the UV vanilla's
     * cube ctor assigns to the same world-space vertex.
     * <p>
     * For {@code cube.isMirror()} cubes, vanilla's {@code ModelPart.Cube} ctor swaps the cube's
     * {@code x} and {@code maxX} variables before building the 8 vertices, which has the net
     * effect of swapping which UV strip is applied to the cube's +X vs -X face (vanilla's WEST
     * polygon UV ends up on the +X face, EAST polygon UV on the -X face). The polygon ctor also
     * reverses each polygon's vertex array, which U-flips every face's UV mapping. Both effects
     * are replicated for {@code mirror=true} cubes via {@link EntityFace#mirror} and the
     * {@link Vector4f#toUvCorners} mirror flag inside {@link #resolveFaceUv}.
     * <p>
     * The per-face slot permutation maps {@link #resolveFaceUv}'s {@code (TL, BL, BR, TR)}
     * output to the (max-u, top-v)-first ordering vanilla's {@code Polygon} ctor produces. For
     * non-UP faces, vanilla's vertex 0 lands in the TR slot; for UP, it lands in BR because the
     * polygon ctor's {@code f3 / f5} parameters are V-inverted on the atlas strip. The exact
     * slot mapping per face lives on {@link EntityFace#polygonVertexSlots} and is applied via
     * {@link EntityFace#permuteToPolygonOrder} so the tooling-side block-model converter can
     * share the same source of truth.
     * <p>
     * Independent of {@link #FLIP_X} / {@link #FLIP_Y}: those change where vertices project to
     * screen, but each vertex's vanilla-spec UV is unchanged.
     *
     * @param face the geometric face being rendered
     * @param cube the cube whose UV is being resolved
     * @param size the cube's size vector
     * @param texWidth the texture width
     * @param texHeight the texture height
     * @return the four per-vertex UVs in the kit's corner order
     */
    private static @NotNull Vector2f @NotNull [] resolvePolygonUv(
        @NotNull EntityFace face,
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        float texWidth,
        float texHeight
    ) {
        Vector2f[] uv = resolveFaceUv(face.mirror(cube.isMirror()), cube, size, texWidth, texHeight);
        return face.permuteToPolygonOrder(uv);
    }

    /**
     * Tests whether a plane cube's face polygon is degenerate - its 4 vertices collapse to 2
     * distinct points because the face's plane normal lies along the cube's zero-extent axis.
     * <p>
     * E.g. for a vertical-plane top_fin ({@code size.x=0}), the UP/DOWN/NORTH/SOUTH faces all
     * collapse - only WEST/EAST have full area. Vanilla emits these polygons too but the GPU
     * rasterizer drops them at 0-area; ours rasterizes a thin line worth a few pixels due to FP
     * error in the barycentric inside-test, then paints wrong-shade artifact pixels (cod top_fin
     * UP painted x=133-135 strip at shade 1.0 over the body's WEST shade 0.45). Caller uses this
     * predicate to skip emitting these triangles entirely.
     *
     * @param size the cube's size vector
     * @param face the geometric face being rendered
     * @return {@code true} if the polygon collapses to a line; {@code false} when the face has
     *     full plane area
     */
    private static boolean isDegeneratePlaneFace(@NotNull Vector3f size, @NotNull EntityFace face) {
        if (size.x() == 0f) return face != EntityFace.WEST && face != EntityFace.EAST;
        if (size.y() == 0f) return face != EntityFace.UP && face != EntityFace.DOWN;
        if (size.z() == 0f) return face != EntityFace.NORTH && face != EntityFace.SOUTH;
        return false;
    }

    /**
     * Computes the per-face shade factor baked into emitted triangles, modeling vanilla's
     * {@code Lighting.ENTITY_IN_UI} two-directional Lambertian shader plus
     * {@code PER_FACE_LIGHTING} for plane no-cull cubes.
     * <p>
     * The shade is computed against the post-flip screen-frame normal because
     * {@link RenderEngine#ENTITY_IN_UI_LIGHT_0} and {@link RenderEngine#ENTITY_IN_UI_LIGHT_1}
     * are likewise expressed in screen Y-up. The continuous (rather than per-face-bucketed)
     * result matters for bones whose rotation produces non-cardinal normals (running zombie
     * legs, bee leashes) where a {@code BlockFace.fromNormal} approximation would collapse
     * adjacent faces to the same shade.
     * <p>
     * <b>Plane no-cull cubes</b> ({@code !cubeCullBackFaces && isPlaneCube}) require an extra
     * tweak. Vanilla's entity render types (entityCutoutNoCull for fish fins, warden tendrils,
     * skeleton_horse rib cage) bind a shader with {@code #define PER_FACE_LIGHTING}: the vertex
     * stage pre-computes both a front-color ({@code max(0, dot(L, n))}) and a back-color
     * ({@code max(0, dot(-L, n))}) per vertex; the fragment shader picks one via
     * {@code gl_FrontFacing} based on screen-space winding. For PLANE cubes (one size component
     * == 0) the two opposing-normal polygons (e.g. DOWN and UP) cover the same screen pixels
     * with opposite windings - one is {@code gl_FrontFacing=true}, the other false. By
     * construction front color of A == back color of B for opposite-normal polygons
     * ({@code n_B = -n_A}, {@code dot(L, n_B) = dot(-L, n_A)}) - so both polygons of a plane
     * cube compute the same shade, namely the shade against the CAMERA-FACING normal.
     * <p>
     * For face F we bake whichever shade matches the camera-facing direction.
     * {@code dot(VIEW_DIRECTION_KIT, n_F_kit) < 0} means n_F points TOWARD camera (front-facing),
     * so {@code shade(n_F_kit)}. {@code >= 0} means n_F points AWAY (back-facing), so
     * {@code shade(-n_F_kit)}. Either way the value equals what vanilla's
     * {@code PER_FACE_LIGHTING} resolves to for whichever polygon's UV is opaque.
     * <p>
     * <b>3D no-cull cubes</b> (skeleton_horse rib cage, chicken legs - both 3D cubes flagged
     * {@code cullBackFaces=false} because a visible face's UV region exceeds the alpha-cutout
     * threshold) ALSO need the picker. Vanilla's {@code ENTITY_CUTOUT} pipeline composes
     * {@code withCull(false) + withShaderDefine("PER_FACE_LIGHTING")} - the same
     * shader-side per-pixel front/back-color choice applies regardless of whether the cube is
     * a plane or solid. For interior cube faces whose back-facing polygons are geometrically
     * behind the front-facing ones, depth-test rejects the back fragments before the shade
     * difference can over-brighten (the rasterizer's strict {@code <=} depth rejection mirrors
     * vanilla's {@code GL_LEQUAL} default - first-drawn wins on coplanar, deeper rejects on
     * separated faces). For polygons that DON'T overlap a front-facing companion in screen
     * (chicken leg's UP face = foot underside, visible at the canvas bottom after iso flip),
     * the back-facing triangle survives depth-test and the back color is what vanilla shows.
     * Empirical sweep (2026-05-16) confirmed that lifting the {@code !isPlaneCube} guard
     * fixes chicken_cold 15.13 -> sub-1 and chicken_warm 8.32 -> sub-1 without regressing
     * skeleton_horse - the earlier {@code 60 → 64} concern was a pre-Task-#42 measurement
     * confounded by walker / canvas-bound bugs since fixed.
     *
     * @param normal the post-flip kit-frame outward face normal
     * @param isPlaneCube unused as of the chicken-family fix; retained for call-site clarity
     * @param cubeCullBackFaces the cube's effective back-face culling flag
     * @return the shade factor in {@code [0.4, 1.0]}
     */
    private static float computeFaceShading(
        @NotNull Vector3f normal,
        @SuppressWarnings("unused") boolean isPlaneCube,
        boolean cubeCullBackFaces
    ) {
        if (cubeCullBackFaces)
            return RenderEngine.computeEntityInUiLighting(normal);

        Vector3f cameraFacing = Vector3f.dot(VIEW_DIRECTION_KIT, normal) < 0f
            ? normal
            : new Vector3f(-normal.x(), -normal.y(), -normal.z());
        return RenderEngine.computeEntityInUiLighting(cameraFacing);
    }

    /**
     * Threshold of alpha-cutout texels on a cube's visible faces above which back-face culling is
     * disabled - the cube's texture is then treated as {@code entityCutoutNoCull} (vanilla's
     * render type for skeletons / skeleton_horse / mob armor / leashed bees etc.) rather than
     * the more common {@code entityCutout}. {@code 0.20} = 20%: well above the small-edge
     * transparency typical of solid-skinned entities (zombie face cubes have a couple of eye
     * pixels at alpha=0, far below 20%), well below the large alpha-cutout patterns that vanilla
     * deliberately renders no-cull ({@code horse_skeleton.png} is 75% transparent, with the
     * body's side-face UV region similarly perforated for the ribcage silhouette).
     */
    private static final float NO_CULL_TRANSPARENCY_THRESHOLD = 0.20f;

    /**
     * Back-face culling heuristic. Disables culling for plane cubes (any size component equal
     * to zero - e.g. tadpole tail, top fins, warden tendrils) since vanilla treats these as
     * double-sided geometry. Also disables culling for cubes whose visible-face UV regions
     * contain {@link #NO_CULL_TRANSPARENCY_THRESHOLD significant alpha-cutout or partial-alpha
     * texels} so vanilla's see-through behaviour is mirrored for both render-type families:
     * <ul>
     * <li>{@code entityCutoutNoCull} (alpha=0 holes): skeleton-horse ribcage through transparent
     *     body cube, wither-skeleton armour through bone outlines</li>
     * <li>{@code entityTranslucent} (partial-alpha shells): slime outer cube alpha-stacks
     *     back+front faces at silhouette pixels, matching vanilla's {@code withCull(false)}
     *     pipeline setting</li>
     * </ul>
     * Solid cubes use the legacy content-based heuristic
     * (identical to {@link EntityGeometryKit#shouldCullBackFaces}).
     */
    private static boolean shouldCullBackFaces(
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        @NotNull PixelBuffer texture,
        float texW,
        float texH
    ) {
        if (size.x() == 0f || size.y() == 0f || size.z() == 0f) return false;
        // entityCutoutNoCull / entityTranslucent detection: cubes with significant non-opaque
        // texels on visible faces need their back faces to render too - either to peek through
        // alpha=0 cutouts (cutout family) or to alpha-stack at silhouette pixels (translucent
        // family). Sampling the three iso-visible faces (UP/NORTH/EAST) is sufficient -
        // non-opaque textures are typically symmetric across face pairs and we'd rather miss a
        // one-sided non-opaque face than over-disable culling.
        if (uvNonOpaqueExceeds(resolveFaceUv(EntityFace.UP, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD)
            || uvNonOpaqueExceeds(resolveFaceUv(EntityFace.NORTH, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD)
            || uvNonOpaqueExceeds(resolveFaceUv(EntityFace.EAST, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD))
            return false;
        boolean visibleHasContent =
               uvHasContent(resolveFaceUv(EntityFace.UP, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.NORTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.EAST, cube, size, texW, texH), texture);
        if (visibleHasContent) return true;
        boolean hiddenHasContent =
               uvHasContent(resolveFaceUv(EntityFace.DOWN, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.SOUTH, cube, size, texW, texH), texture)
            || uvHasContent(resolveFaceUv(EntityFace.WEST, cube, size, texW, texH), texture);
        return !hiddenHasContent;
    }

    /**
     * Returns {@code true} when the proportion of non-opaque texels in the supplied face UV
     * region exceeds {@code threshold}. Walks the rectangle bounded by the face UVs and counts
     * {@code alpha<255} pixels; returns {@code false} when the UV region is empty (zero area).
     * Used by {@link #shouldCullBackFaces} to detect both cutout ({@code alpha==0} holes,
     * matching vanilla's {@code entityCutoutNoCull}) and translucent ({@code 0<alpha<255}
     * shells, matching vanilla's {@code entityTranslucent withCull(false)}) families - either
     * requires the back faces to render to mirror vanilla's see-through compositing.
     */
    /**
     * Returns {@code true} when any of the cube's visible-face UVs include a partial-alpha
     * texel ({@code 0 < alpha < 255}). Distinguishes truly translucent shells (slime,
     * glass-like) from alpha-cutout no-cull cubes (warden tendrils, mushroom block-overlays)
     * whose texels are strictly 0 or 255. The rasterizer's back-to-front sort gates on this
     * flag to avoid re-ordering opaque-cutout no-cull triangles that don't need sorted blend.
     */
    private static boolean uvPartialAlphaPresent(
        @NotNull EntityModelData.Cube cube,
        @NotNull Vector3f size,
        @NotNull PixelBuffer texture,
        float texW,
        float texH
    ) {
        for (EntityFace face : EntityFace.CACHED_VALUES) {
            if ((size.x() == 0f || size.y() == 0f || size.z() == 0f)
                && isDegeneratePlaneFace(size, face)) continue;
            if (faceHasPartialAlpha(resolveFaceUv(face, cube, size, texW, texH), texture))
                return true;
        }
        return false;
    }

    private static boolean faceHasPartialAlpha(@NotNull Vector2f @NotNull [] uv, @NotNull PixelBuffer texture) {
        int W = texture.width();
        int H = texture.height();
        Vector4f bounds = Vector4f.bounds(uv);
        int x0 = Math.max(0, (int) Math.floor(bounds.x() * W));
        int y0 = Math.max(0, (int) Math.floor(bounds.y() * H));
        int x1 = Math.min(W, (int) Math.ceil(bounds.z() * W));
        int y1 = Math.min(H, (int) Math.ceil(bounds.w() * H));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int a = ColorMath.alpha(texture.getPixel(x, y));
                if (a > 0 && a < 255) return true;
            }
        }
        return false;
    }

    private static boolean uvNonOpaqueExceeds(
        @NotNull Vector2f @NotNull [] uv,
        @NotNull PixelBuffer texture,
        float threshold
    ) {
        int W = texture.width();
        int H = texture.height();
        Vector4f bounds = Vector4f.bounds(uv);
        int x0 = Math.max(0, (int) Math.floor(bounds.x() * W));
        int y0 = Math.max(0, (int) Math.floor(bounds.y() * H));
        int x1 = Math.min(W, (int) Math.ceil(bounds.z() * W));
        int y1 = Math.min(H, (int) Math.ceil(bounds.w() * H));
        int total = (x1 - x0) * (y1 - y0);
        if (total <= 0) return false;
        int nonOpaque = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (ColorMath.alpha(texture.getPixel(x, y)) < 255) nonOpaque++;
            }
        }
        return (float) nonOpaque / total > threshold;
    }

    private static boolean uvHasContent(
        @NotNull Vector2f @NotNull [] uv,
        @NotNull PixelBuffer texture
    ) {
        int W = texture.width();
        int H = texture.height();
        Vector4f bounds = Vector4f.bounds(uv);
        int x0 = Math.max(0, (int) Math.floor(bounds.x() * W));
        int y0 = Math.max(0, (int) Math.floor(bounds.y() * H));
        int x1 = Math.min(W, (int) Math.ceil(bounds.z() * W));
        int y1 = Math.min(H, (int) Math.ceil(bounds.w() * H));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (ColorMath.alpha(texture.getPixel(x, y)) > 0) return true;
            }
        }
        return false;
    }

    /**
     * The result of building triangles from an entity model, carrying the triangle list and
     * per-bone bounding boxes used by the armor overlay system.
     *
     * @param triangles the triangle list ready for rasterization
     * @param boneBounds per-bone axis-aligned bounding boxes keyed by bone name
     */
    public record BuildResult(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull Map<String, Vector3f[]> boneBounds
    ) {}

}
