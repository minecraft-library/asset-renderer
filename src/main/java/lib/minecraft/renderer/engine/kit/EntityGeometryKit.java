package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.EntityFace;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Box;
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
import java.util.Map;

/**
 * Load-bearing bone/cube {@literal ->} triangle assembler. Builds rasterizer-ready triangles
 * from a Java-derived {@link EntityModelData} in vanilla's native {@code ModelPart}-style
 * coordinate frame. Consumes geometry produced by {@link ToolingEntityModels}
 * (entity_geometry.json) which ships in this frame natively (no parse-time conversion).
 * <p>
 * Authoring convention (input):
 * <ul>
 * <li><b>Y-down, right-handed</b> - matches vanilla Java's {@code PartPose} / {@code ModelPart}
 * authoring (positive Y points toward the entity's feet from its root).</li>
 * <li>{@link EntityModelData.Cube#getOrigin() Cube origins} are the min corner in
 * <b>bone-local</b> space (relative to the owning bone's pivot, Y-down) - the literal
 * {@code addBox(x, y, z, w, h, d)} args from {@code createBodyLayer}.</li>
 * <li>{@link EntityModelData.Bone#getPivot() Bone pivots} are in absolute entity-root space.</li>
 * <li>Bone transforms are pure pivot-centred rotations; translation-only bones contribute
 * identity.</li>
 * </ul>
 * <b>Chirality / de-flip:</b> the kit is <b>det=+1 internally</b> and emits geometry in the
 * model's native <b>Y-up</b> frame (the Y-down input is un-flipped during the fit chain). The
 * old fused Y-flip on positions (the {@code diag(1,-1,1)} boundary flip) has moved onto the
 * renderer's model-to-world {@code Placement} (see {@link EntityRenderer}), so the same
 * geometry now renders under any projection while still presenting the subject's front, upright.
 * The winding invariant is <b>emit-order cross product AGREES with the stored normal</b>
 * (camera- and projection-independent); the chirality reflection re-enters via the
 * {@code Placement}, so screen-space cull winding is unchanged. These invariants are pinned by
 * {@code EntityGeometryKitTest}.
 * <p>
 * <b>Fit:</b> the model is auto-centred and uniformly scaled to fit within the longest-axis
 * extent scaled by {@link #ENTITY_MODEL_FIT_EXTENT} (auto-fit overload), or centred on a
 * caller-supplied anchor at a caller-supplied NDC scale (native-resolution overload used by
 * {@link EntityRenderer}).
 */
@UtilityClass
public class EntityGeometryKit {

    /**
     * 90% unit-cube fit - leaves a small margin around the longest-axis extent.
     */
    private static final float ENTITY_MODEL_FIT_EXTENT = 0.9f;

    /**
     * Lower bound on extent before scaling - guards against zero-cube models producing infinity.
     */
    private static final float MIN_MODEL_EXTENT = 0.001f;

    /**
     * The vanilla entity-preview iso <b>lighting</b> angle {@code [210, 45, 0]} - the harness's
     * {@code ISO_ROTATION} - used to derive the per-face plane-cube view direction below. This is
     * distinct from {@link Projection#VANILLA_ISO}'s camera pose ({@code [30, 45, 0]}, the display
     * pose): the entity's Y-down-to-Y-up flip + facing live on the renderer's {@code ENTITY_FACING}
     * {@code Placement}, so the camera pose is a plain display pose while the lighting frame stays on the
     * harness iso angle. Pinned here so it does not track {@code VANILLA_ISO.basePose()}.
     */
    private static final @NotNull EulerRotation ENTITY_ISO_LIGHTING = new EulerRotation(210f, 45f, 0f);

    /**
     * Standard GL view direction expressed in the kit's Y-flipped shading frame: camera at origin
     * looking toward {@code -Z}, pre-rotated through the inverse iso pose chain plus the kit's
     * legacy Y-flip on positions ({@code diag(1,-1,1)}) so it lives in the same frame the
     * per-face <b>shading</b> normal does (a Y-flipped copy of the stored Y-up normal - see the
     * shading-normal derivation in {@link #buildTriangles(EntityModelData, PixelBuffer, EntityBuildParams)}).
     * <p>
     * Used by {@link #computeFaceShading} to pick front-vs-back {@code PER_FACE_LIGHTING} shade
     * for no-cull cubes. {@code dot(VIEW_DIRECTION_KIT, n) < 0} means the polygon's outward normal
     * points TOWARD the camera (front-facing per vanilla's {@code gl_FrontFacing}); {@code >= 0}
     * means it points AWAY (back-facing).
     * <p>
     * Derived as {@code diag(1,-1,1) * M_view^T * (0, 0, -1)} where {@code M_view = scale(1,1,-1)
     * * R_X(pitch) * R_Y(yaw) * R_X(180°)} is vanilla's iso transform chain (the trailing
     * {@code R_X(180°)} folds in vanilla's {@code LivingEntityRenderer.submit}'s
     * {@code rotateY(180°) + scale(-1,-1,1)} as a single equivalent X-axis rotation - see
     * {@link Projection#VANILLA_ISO} for the full derivation) evaluated at
     * {@link #ENTITY_ISO_LIGHTING} {@code [210°, 45°, 0°]}. This evaluates to approximately
     * {@code (0.6124, -0.5, 0.6124)}: the X and Z components are
     * {@code cos(30°) * sin(45°) = √6/4 ≈ 0.6124} (45° yaw splits horizontal direction
     * symmetrically into X and Z, modulated by {@code cos(30°)} from the pitch tilt); the Y
     * component is {@code -sin(30°) = -0.5} (30° pitch contribution, negated by the trailing
     * Y-flip compensation).
     */
    private static final @NotNull Vector3f VIEW_DIRECTION_KIT = computeKitFrameViewDirection();

    /**
     * Computes {@link #VIEW_DIRECTION_KIT} - the standard {@code (0, 0, -1)} GL view vector
     * rotated into the kit's shading frame - by composing the inverse iso view transform with
     * the kit Y-flip. See {@link #VIEW_DIRECTION_KIT} for the resulting value and its meaning.
     *
     * @return the view direction in the kit's Y-flipped shading frame
     */

    private static @NotNull Vector3f computeKitFrameViewDirection() {
        EulerRotation iso = ENTITY_ISO_LIGHTING;
        // Column-vector chain `diag(1,-1,1) * R_X(-180°) * R_Y(-yaw) * R_X(-pitch) *
        // scale(1,1,-1)` implements `Yflip * M_view^T * v` where M_view = scale(1,1,-1) *
        // R_X(pitch) * R_Y(yaw) * R_X(180°) is vanilla's iso transform. Each rotation transposes
        // to its negated-angle counterpart; scales are diagonal so transpose is identity.
        // Rightmost applies first.
        // Fluent scale/rotate path: bit-identical to vanilla's PoseStack composition, whereas the
        // createX().multiply(...) form drifts 1-4 ULPs per entry (see Matrix4f fluent-vs-multiply
        // note). Mirrors the same chain in Lighting.deriveEntityInUiLightKit. Composition is
        // unchanged - IDENTITY * S1 * R_X(-180) * R_Y(-yaw) * R_X(-pitch) * S2; rightmost applies
        // to the vector first.
        Matrix4f viewToKit = Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .rotate(Quaternionf.rotationXYZ((float) -Math.PI, 0f, 0f))
            .rotate(Quaternionf.rotationXYZ(0f, -iso.yawRadians(), 0f))
            .rotate(Quaternionf.rotationXYZ(-iso.pitchRadians(), 0f, 0f))
            .scale(1f, 1f, -1f);
        return new Vector3f(0f, 0f, -1f).transformNormal(viewToKit);
    }

    /**
     * Parameters for a native-resolution entity triangle build. Bundles the five values that vary
     * per render so callers spell them at a named call boundary instead of through a telescoping
     * positional overload cascade.
     *
     * @param centreAnchor model-space point that maps to the canvas centre
     * @param emissive whether every emitted triangle renders full-bright and additive (eyes,
     *     glowing spots)
     * @param ndcScale model-units-to-NDC scale applied after centring on {@code centreAnchor}
     * @param modelScale per-render vertex pre-scale folded in before the NDC scale (vanilla's
     *     combined renderer-scale + state-scale chain)
     * @param tintArgb ARGB tint multiplied into every sampled texel; {@link ColorMath#WHITE}
     *     ({@code 0xFFFFFFFF}) is a no-op tint
     */
    public record EntityBuildParams(
        @NotNull Vector3f centreAnchor,
        boolean emissive,
        float ndcScale,
        float modelScale,
        int tintArgb
    ) {}

    /**
     * Convenience overload that auto-computes bounds and the legacy auto-fit scale
     * ({@code ENTITY_MODEL_FIT_EXTENT / bounds.maxExtent}) for a single-layer render.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture
    ) {
        Box bounds = computeBounds(model);
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        Vector3f centre = new Vector3f(
            (bounds.minX() + bounds.maxX()) * 0.5f,
            (bounds.minY() + bounds.maxY()) * 0.5f,
            (bounds.minZ() + bounds.maxZ()) * 0.5f);
        return buildTriangles(model, texture,
            new EntityBuildParams(centre, false, ENTITY_MODEL_FIT_EXTENT / extent, 1f, ColorMath.WHITE));
    }

    /**
     * Native-resolution overload taking an explicit model-space centre anchor and per-render tint.
     * Used by {@link EntityRenderer} to match the vanilla-reference-harness's fixed
     * {@code pixelsPerBlock} convention and to centre the silhouette on the canvas via the
     * model-space point whose iso projection equals the screen-space silhouette midpoint.
     * Convenience wrapper over {@link #buildTriangles(EntityModelData, PixelBuffer, EntityBuildParams)}.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param modelCentreAnchor model-space point that maps to the canvas centre
     * @param emissive whether triangles render full-bright + additive
     * @param ndcScale model-units-to-NDC scale pre-computed from the canvas dimensions and target
     *     pixels-per-block
     * @param modelScale per-render vertex pre-scale (vanilla renderer-scale + state-scale chain)
     * @param tintArgb the ARGB tint applied to every triangle this call produces; use
     *     {@code 0xFFFFFFFF} ({@link ColorMath#WHITE}) for no tint
     * @return the build result containing triangles and per-bone bounds
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
        return buildTriangles(model, texture,
            new EntityBuildParams(modelCentreAnchor, emissive, ndcScale, modelScale, tintArgb));
    }

    /**
     * Builds rasterizer-ready triangles for an entity model at native resolution from the supplied
     * build parameters. Both the auto-fit convenience overload and the renderer's native-scale
     * overload delegate here.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param params the centre anchor, emissive flag, scale factors, and tint for this build
     * @return the build result containing triangles and per-bone bounds
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        @NotNull EntityBuildParams params
    ) {
        Vector3f centre = params.centreAnchor();
        boolean emissive = params.emissive();
        float scale = params.ndcScale();
        float modelScale = params.modelScale();
        int tintArgb = params.tintArgb();

        Map<String, Matrix4f> chainTransforms = BoneKit.buildChainTransforms(model.getBones());

        float cx = centre.x();
        float cy = centre.y();
        float cz = centre.z();

        // Pre-compose kitFit + each bone chain as a single fluent ops chain on identity. Bit-
        // identical to vanilla's PoseStack chain: scale * translate * scale * (per-bone
        // translate * rotate * translate). Eliminates the {@link Matrix4f#multiply} step at the
        // per-cube loop that drifts 1-4 ULPs vs the fluent path - see {@link Matrix4f} line
        // 313 commentary.
        // Kit emits positions in the model's native Y-up frame; the Y-down->screen flip
        // (diag(1,-1,1)) has moved onto the entity's model->world Placement (EntityRenderer), so the
        // kit-fit scale is now uniform. For the yaw-only parity pose the flip commutes past the model
        // spin, so relocating it from here to the placement is compose-equivalent (measured).
        Matrix4f kitFit = Matrix4f.IDENTITY
            .scale(scale, scale, scale)
            .translate(-cx, -cy, -cz)
            .scale(modelScale);
        Map<String, Matrix4f> kitFitChainTransforms = BoneKit.buildChainTransformsFrom(kitFit, model.getBones());

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
            // pivot (the literal addBox(x, y, z, w, h, d) args from createBodyLayer). The bone
            // chain ({@link BoneKit}) translates by the bone's pivot as its first
            // fluent op, so cube origins go through the matrix in BONE-LOCAL coords - no
            // pre-translate by bonePivot here. Matches vanilla's PoseStack flow exactly.
            // Bone-level uniform scale captured from {@code MeshTransformer.scaling(F)} /
            // {@code PartPose.scaled(F)}. Vanilla {@code ModelPart.render} translates by pivot,
            // rotates, then {@code poseStack.scale(s, s, s)} the local cube space - so each cube
            // vertex world-position is {@code pivot + R * (s * v_local)}. Our chain pivot-translates
            // post-rotation via {@link BoneKit#composeCubeTransform}; multiplying {@code origin}, {@code
            // size}, and {@code inflate} by {@code s} (in {@link BoneKit#scaledCubeBounds}) puts the
            // cube in scaled-local space
            // before the bone-pivot translate, which is algebraically equivalent for any rotation
            // R that commutes with uniform scale (every R does). UVs stay tied to the unscaled
            // {@code size} field, matching vanilla's per-vertex scale-after-UV-resolve order.
            float s = bone.getScale();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f size = cube.getSize();
                Box cubeBounds = BoneKit.scaledCubeBounds(s, cube);

                Matrix4f fullTransform = BoneKit.composeCubeTransform(cube, bone, boneChain);
                // Fluent-composed perCubeChain: kitFit chain + bone hierarchy + bind + cube rot,
                // all post-multiplied via {@link Matrix4f#translate} / {@link Matrix4f#rotate} so
                // every multiplication matches vanilla's {@code PoseStack} ops bit-for-bit. The
                // pre-baked {@code kitFitChainTransforms} maps already incorporate kitFit; here we
                // apply only the cube-local bind + cube rotation.
                Matrix4f kitFitBoneChain = kitFitChainTransforms.get(boneName);
                Matrix4f perCubeChainFluent = BoneKit.composeCubeTransform(cube, bone, kitFitBoneChain);

                // entityCutoutCull entities (bat, baby_turtle, ...) cull every face the way vanilla's
                // GL back-face cull does - including zero-thickness planes, whose two coincident sides
                // would otherwise both draw and let the LEQUAL depth tie-break pick the away side (the
                // bat ear's brown outer winning over its pink inner). Culling keeps only the camera-
                // facing side via the rasterizer's winding test, matching vanilla.
                boolean cubeCullBackFaces = model.isCull() || shouldCullBackFaces(cube, size, texture, texW, texH);
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
                        Vector3f transformed = corners[i].transform(perCubeChain);
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

                    // Positions and the STORED normal are both in the model's native Y-up frame now that
                    // the Y-flip lives on the placement (kit-internal geometry stays self-consistent).
                    // Shading, however, keeps the pre-de-flip tuned light frame (Y-flipped) - the entity
                    // light DIRECTIONS were empirically calibrated against it (see
                    // project_entity_light_gpu_calibration) - so the shade is computed from a Y-flipped
                    // copy of the normal while the stored normal stays Y-up. Lighting frame is a separate
                    // concern from the geometry Y-flip; only the geometry moves to the placement.
                    Vector3f normal = face.normal().transformNormal(fullTransform).normalize();
                    Vector3f shadingNormal = new Vector3f(normal.x(), -normal.y(), normal.z());

                    boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) continue;

                    Vector2f[] effUv = BoneKit.resolvePolygonUv(face, cube, size, texW, texH);
                    float shading = computeFaceShading(shadingNormal, isPlaneCube, cubeCullBackFaces);

                    // Natural CCW emission {@code (0, 1, 2)} and {@code (0, 2, 3)}. The kit itself
                    // is det=+1 (emit-order cross AGREES with the stored normal); the chirality
                    // reflection re-enters downstream via the renderer's Placement Y-flip. Total
                    // pipeline chirality: placement Y-flip (det -1) × engine_camera (det -1) ×
                    // projection's -y (det -1) = det -1. Model CCW → screen CW → rasterizer's
                    // {@code signedArea < 0} check correctly classifies these as front-facing.
                    String debugTag = boneName + ":" + face.direction();
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[1], corners[2],
                        effUv[0], effUv[1], effUv[2],
                        texture, tintArgb,
                        normal, shading,
                        new SurfaceTraits(cubeCullBackFaces, emissive, cubeIsTranslucent, false), debugTag
                    ));
                    triangles.add(new VisibleTriangle(
                        corners[0], corners[2], corners[3],
                        effUv[0], effUv[2], effUv[3],
                        texture, tintArgb,
                        normal, shading,
                        new SurfaceTraits(cubeCullBackFaces, emissive, cubeIsTranslucent, false), debugTag
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
     *
     * @param model the entity model definition (Java Y-down frame)
     * @return the model AABB in the Java Y-down frame
     */
    public static @NotNull Box computeBounds(@NotNull EntityModelData model) {
        return computeBounds(model, BoneKit.buildChainTransforms(model.getBones()));
    }

    /**
     * Walks every visible cube face, alpha-clips each face to its opaque-texel sub-rectangle,
     * projects the resulting 4 bilinear-interpolated corners through the bone chain, the per-
     * render {@code modelScale}, and the supplied screen-space transform, and returns the tight
     * screen-space AABB of the rendered silhouette.
     *
     * <p>Used by {@link EntityRenderer#render render()} to size the
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
     *     screen space (X = horizontal, Y = vertical, Z = depth - ignored). Only the entity's
     *     orthographic path measures bounds this way (the perspective / oblique fit measures its
     *     silhouette in the engine via {@code rasterizeFitted}), so the raw rotated point is the
     *     screen-space contribution.
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
        Map<String, Matrix4f> chainTransforms = BoneKit.buildChainTransforms(model.getBones());
        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture == null ? 1 : texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture == null ? 1 : texture.height());
        BoundsAccumulator acc = new BoundsAccumulator();

        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            String boneName = entry.getKey();
            Matrix4f boneChain = chainTransforms.get(boneName);
            float s = bone.getScale();
            int cubeIndex = 0;
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                float inflate = cube.getInflate();
                Matrix4f cubeTransform = BoneKit.composeCubeTransform(cube, bone, boneChain);

                Box cubeBounds = BoneKit.scaledCubeBounds(s, cube);

                if (texture == null) {
                    float[] xs = { cubeBounds.minX(), cubeBounds.maxX() };
                    float[] ys = { cubeBounds.minY(), cubeBounds.maxY() };
                    float[] zs = { cubeBounds.minZ(), cubeBounds.maxZ() };
                    for (float x : xs) for (float y : ys) for (float z : zs)
                        projectAndAccumulate(new Vector3f(x, y, z), cubeTransform, modelScale, screenTransform, acc);
                    cubeIndex++;
                    continue;
                }

                boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                for (EntityFace face : EntityFace.CACHED_VALUES) {
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) continue;
                    Vector3f[] corners3d = face.corners(cubeBounds);
                    // Must match the renderer's UV resolver. {@link BoneKit#resolveFaceUv} alone
                    // pairs uvs[i] with corners3d[i] at DIAGONALLY OPPOSITE vertices of the
                    // face (kit corner order is cyclic-shifted by 1 from vanilla's polygon
                    // vertex array); the BL/BR/TR/TL classifier then maps each 3D corner to
                    // the wrong UV role and the bilinear sub-rect corners land outside the
                    // visible silhouette whenever the opaque sub-rect is strictly smaller
                    // than the face UV bbox (warden tendrils, silverfish setae, fish fins).
                    // Fully-opaque faces are unaffected: the 4 contributed positions are then
                    // the 4 cube corners regardless of pairing.
                    Vector2f[] uvs = BoneKit.resolvePolygonUv(face, cube, size, texW, texH);
                    String dumpLabel = RendererDebug.boundsFaceLabel(boneName, cubeIndex, face.direction(), origin, size, inflate, cube.isMirror());
                    contributeFaceAlphaTight(corners3d, uvs, cubeTransform, modelScale, screenTransform, texture, acc, dumpLabel);
                }
                cubeIndex++;
            }
        }

        return acc.toBox();
    }

    /**
     * Measures the alpha-tight screen-space bounds of a pre-built block-overlay triangle list
     * (mooshroom mushrooms, snow-golem carved_pumpkin), so block overlays extend the entity's
     * canvas exactly as far as their <b>opaque texels</b> reach - not their full authored quad
     * extent. The mushroom-cross block texture is mostly transparent, so a naive whole-quad AABB
     * would over-size the canvas by the transparent margin; walking the opaque sub-rectangle per
     * face keeps the canvas tight to what actually renders, matching the vanilla harness's
     * {@code contributePolygonExtents}.
     *
     * <p>The {@code overlayTris} come from
     * {@link EntityRenderer#buildBlockOverlayTriangles} built with a <b>fit-neutral</b> entity fit
     * ({@code buildEntityFitMatrix(ZERO, modelScale)}) - the same geometry the render produces
     * before the canvas fit - so their positions are already in the entity-pixel frame the body
     * bounds live in; only {@code screenTransform} (the render orientation) is applied here.
     * {@link BlockGeometryKit} emits each face as the consecutive triangle pair
     * {@code (TL,BL,BR) + (TL,BR,TR)}; this walks those pairs, reconstructs each face's four
     * UV-rect corners, and delegates to {@link #contributeFaceAlphaTight} with an identity cube
     * transform and unit model scale (both already baked into the positions).
     *
     * @param overlayTris the fit-neutral block-overlay triangles, in emission (paired) order
     * @param screenTransform the render orientation ({@code ModelEngine.orient}) applied before
     *     bounds accumulation - the same transform the body bounds use
     * @return the alpha-tight screen-space bounds of the overlay, or a zero box when empty / all
     *     transparent
     */
    public static @NotNull Box computeBlockOverlayScreenBounds(
        @NotNull java.util.List<VisibleTriangle> overlayTris,
        @NotNull Matrix4f screenTransform
    ) {
        BoundsAccumulator acc = new BoundsAccumulator();
        for (int i = 0; i + 1 < overlayTris.size(); i += 2) {
            VisibleTriangle t0 = overlayTris.get(i);
            VisibleTriangle t1 = overlayTris.get(i + 1);
            if (t0.texture() == null) continue;
            // Reconstruct the face quad from the (TL,BL,BR) + (TL,BR,TR) pair. The 3D corners are
            // already in the fit-neutral entity-pixel frame; contributeFaceAlphaTight classifies
            // corner<->UV roles by UV value, so the array order only needs to pair each corner
            // with its own UV.
            Vector3f[] corners = { t0.position0(), t0.position1(), t0.position2(), t1.position2() };
            Vector2f[] uvs = { t0.uv0(), t0.uv1(), t0.uv2(), t1.uv2() };
            contributeFaceAlphaTight(corners, uvs, Matrix4f.IDENTITY, 1f, screenTransform, t0.texture(), acc, null);
        }
        return acc.toBox();
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
     *
     * @param corners3d the polygon's four world-space corners in kit corner order
     * @param uvs the polygon's four per-vertex UVs paired with {@code corners3d}
     * @param cubeTransform cube-space transform applied before scaling
     * @param modelScale per-render vertex pre-scale
     * @param screenTransform model-to-screen transform applied last
     * @param texture the entity texture used for alpha sampling
     * @param acc accumulator collecting the contributed corners
     * @param dumpLabel optional debug label for {@link RendererDebug} tracing, or {@code null}
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
            RendererDebug.boundsDegenerateUv(dumpLabel);
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
            for (Vector3f c : corners3d) projectAndAccumulate(c, cubeTransform, modelScale, screenTransform, acc);
            RendererDebug.boundsNonAxisUvFallback(dumpLabel);
            return;
        }

        int W = texture.width();
        int H = texture.height();
        if (W <= 0 || H <= 0) {
            for (Vector3f c : corners3d) projectAndAccumulate(c, cubeTransform, modelScale, screenTransform, acc);
            RendererDebug.boundsNoTextureFallback(dumpLabel);
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
            RendererDebug.boundsAllTransparent(dumpLabel, pxMin, pyMin, pxMax, pyMax);
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

        RendererDebug.boundsFaceContribution(
            dumpLabel,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            bl, br, tr, tl);
    }

    /**
     * Bilinearly interpolates a single opaque-sub-rect UV corner {@code (u, v)} through the
     * polygon's four world-space corners, then projects and accumulates the result. The UV
     * fractions {@code (sBar, tBar)} within the polygon's {@code [uMin, uMax] x [vMin, vMax]}
     * box weight the four corners {@code bl3/br3/tr3/tl3}.
     *
     * @param u opaque-sub-rect corner U coordinate
     * @param v opaque-sub-rect corner V coordinate
     * @param uMin polygon UV box minimum U
     * @param uMax polygon UV box maximum U
     * @param vMin polygon UV box minimum V
     * @param vMax polygon UV box maximum V
     * @param bl3 polygon bottom-left world-space corner
     * @param br3 polygon bottom-right world-space corner
     * @param tr3 polygon top-right world-space corner
     * @param tl3 polygon top-left world-space corner
     * @param cubeTransform cube-space transform applied before scaling
     * @param modelScale per-render vertex pre-scale
     * @param screenTransform model-to-screen transform applied last
     * @param acc accumulator collecting the projected point
     * @return the projected screen-space point that was accumulated
     */
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
        return projectAndAccumulate(new Vector3f(px, py, pz), cubeTransform, modelScale, screenTransform, acc);
    }

    /**
     * Projects a bone-local point through {@code cubeTransform} then {@code modelScale} then
     * {@code screenTransform} and feeds the screen-space result to {@code acc}. The three-stage
     * order mirrors the vertex path in {@link #buildTriangles(EntityModelData, PixelBuffer, EntityBuildParams)}.
     * The screen transform is always orthographic here (the entity's perspective / oblique fit measures
     * its silhouette in the engine via {@code rasterizeFitted}), so the raw rotated point is the
     * screen-space bounds contribution.
     *
     * @param p the bone-local point to project
     * @param cubeTransform cube-space transform (bone chain + cube bind + cube rotation)
     * @param modelScale per-render vertex pre-scale
     * @param screenTransform model-to-screen transform applied last
     * @param acc accumulator collecting the projected point
     * @return the projected screen-space point that was accumulated
     */
    private static @NotNull Vector3f projectAndAccumulate(
        @NotNull Vector3f p, @NotNull Matrix4f cubeTransform, float modelScale,
        @NotNull Matrix4f screenTransform, @NotNull BoundsAccumulator acc
    ) {
        Vector3f cubeSpace = p.transform(cubeTransform);
        Vector3f scaled = new Vector3f(cubeSpace.x() * modelScale, cubeSpace.y() * modelScale, cubeSpace.z() * modelScale);
        Vector3f screen = scaled.transform(screenTransform);
        acc.add(screen);
        return screen;
    }

    /**
     * Clamps a pixel index into {@code [0, size)}.
     *
     * @param value the raw pixel index
     * @param size the exclusive upper bound (texture width or height)
     * @return the index clamped into range
     */
    private static int clampPixel(int value, int size) {
        if (value < 0) return 0;
        if (value >= size) return size - 1;
        return value;
    }

    /**
     * Running min/max accumulator building a tight AABB from a stream of screen-space points.
     * Collapses to a zero box when no point was ever added.
     */
    private static final class BoundsAccumulator {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        /**
         * Widens the running bounds to include {@code p}.
         *
         * @param p the screen-space point to accumulate
         */
        void add(@NotNull Vector3f p) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
            if (p.z() < minZ) minZ = p.z();
            if (p.z() > maxZ) maxZ = p.z();
        }

        /**
         * Materializes the accumulated bounds, or a zero box when nothing was accumulated.
         *
         * @return the tight AABB of every accumulated point
         */
        @NotNull Box toBox() {
            if (minX == Float.POSITIVE_INFINITY)
                return new Box(0f, 0f, 0f, 0f, 0f, 0f);
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    /**
     * The centre anchor + model-units-to-NDC scale that normalize a model to the auto-fit unit-cube
     * extent - the same {@code ENTITY_MODEL_FIT_EXTENT / maxExtent} scale the {@link #buildTriangles(EntityModelData, PixelBuffer)}
     * convenience overload bakes, but returned as data so a caller can pre-scale geometry to a
     * well-behaved unit range before a perspective / oblique {@code rasterizeFitted} pass fills the
     * canvas in screen space.
     *
     * @param centre the model-space centre anchor mapped to the fit origin
     * @param ndcScale the model-units-to-NDC scale that fits the longest axis to {@link #ENTITY_MODEL_FIT_EXTENT}
     */
    public record UnitFit(@NotNull Vector3f centre, float ndcScale) {}

    /**
     * Computes the {@link UnitFit} that normalizes {@code bounds} to the auto-fit unit-cube extent -
     * the centre of the bounds and the {@code ENTITY_MODEL_FIT_EXTENT / maxExtent} scale (guarded by
     * {@link #MIN_MODEL_EXTENT}). Used by the entity's perspective / oblique render path to bring
     * native pixel-scale geometry into a unit range where the perspective foreshortening stays
     * well-behaved, leaving the final screen fill to {@code rasterizeFitted}'s 2D auto-fit.
     *
     * @param bounds the model bounds to normalize
     * @return the centre + scale that fit {@code bounds} to the unit-cube extent
     */
    public static @NotNull UnitFit unitFit(@NotNull Box bounds) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        Vector3f centre = new Vector3f(
            (bounds.minX() + bounds.maxX()) * 0.5f,
            (bounds.minY() + bounds.maxY()) * 0.5f,
            (bounds.minZ() + bounds.maxZ()) * 0.5f);
        return new UnitFit(centre, ENTITY_MODEL_FIT_EXTENT / extent);
    }

    /**
     * Builds a Matrix4f that maps a vertex in the entity's working pixel-unit frame
     * (post-bone-chain, post-pivot-translation, pre-rasterizer) into the entity-fit space
     * shared with {@link #buildTriangles}'s output. The transform is
     * {@code (v - center) * scale} on each axis, with the overlay-fit path's Y-flip on positions
     * applied (vanilla Y-up {@literal ->} screen Y-down).
     *
     * <p>Used by {@link EntityRenderer} to project block-model
     * overlay triangles (mooshroom mushroom blocks, etc) into the same entity-fit frame the
     * primary entity geometry has been baked into so they render at the correct scale and
     * orientation alongside the entity body.
     *
     * @param bounds the model bounds whose centre and extent drive the auto-fit
     * @return the model-to-entity-fit matrix
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds) {
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        return buildEntityFitMatrix(bounds, ENTITY_MODEL_FIT_EXTENT / extent);
    }

    /**
     * Native-resolution variant of {@link #buildEntityFitMatrix(Box)}: caller supplies the
     * model-units-to-NDC scale so block overlays composed onto an entity's frame match the same
     * scale the renderer used for the entity body (no auto-fit).
     *
     * @param bounds the model bounds whose centre is the fit anchor
     * @param ndcScale the caller-supplied model-units-to-NDC scale
     * @return the model-to-entity-fit matrix at the supplied scale
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Box bounds, float ndcScale) {
        float cx = (bounds.minX() + bounds.maxX()) * 0.5f;
        float cy = (bounds.minY() + bounds.maxY()) * 0.5f;
        float cz = (bounds.minZ() + bounds.maxZ()) * 0.5f;
        return buildEntityFitMatrix(new Vector3f(cx, cy, cz), ndcScale);
    }

    /**
     * Native-resolution variant taking an explicit model-space centre anchor. Used by
     * {@link EntityRenderer} so block overlays composite at the
     * same silhouette-centred frame the entity body uses (the
     * {@link #buildTriangles(EntityModelData, PixelBuffer, Vector3f, boolean, float, float, int)
     * Vector3f overload} above).
     *
     * @param modelCentre the model-space point that maps to the canvas centre
     * @param ndcScale the caller-supplied model-units-to-NDC scale
     * @return the model-to-entity-fit matrix centred on {@code modelCentre}
     */
    public static @NotNull Matrix4f buildEntityFitMatrix(@NotNull Vector3f modelCentre, float ndcScale) {
        Matrix4f translateToCentre = Matrix4f.createTranslation(-modelCentre.x(), -modelCentre.y(), -modelCentre.z());
        // NO Y-flip: block-overlay triangles are appended to the entity's shared triangle list and
        // rasterized through the SAME model-to-world Placement (ENTITY_FACING = diag(-1,-1,1)) as the
        // base body, which owns the single entity Y-flip. When the kit still baked its own diag(1,-1,1)
        // on base positions this matrix flipped Y to match that baked frame; the kit de-flip moved that
        // flip onto the Placement, so a -Y here now flips the overlay TWICE (its own -Y + the Placement)
        // and sinks it into / inverts it relative to the body (mooshroom mushrooms, copper/iron golem
        // flower). A plain uniform scale keeps the overlay in the same fit-neutral frame as the base.
        Matrix4f scale = Matrix4f.createScale(ndcScale, ndcScale, ndcScale);
        // Translate to centre first (innermost), then scale.
        return scale.multiply(translateToCentre);
    }

    /**
     * Resolves a bone's world transform (the ancestor-chain anchor used internally by
     * {@link #buildTriangles}). Returns identity when the bone is absent. Used by
     * {@link EntityRenderer} to anchor a block-overlay's transform
     * chain to a specific entity bone (mooshroom's third mushroom which sits on the head).
     *
     * @param model the entity model definition
     * @param boneName the bone to resolve
     * @return the bone's ancestor-anchor chain matrix, or {@link Matrix4f#IDENTITY} when absent
     */
    public static @NotNull Matrix4f resolveBoneAnchorMatrix(
        @NotNull EntityModelData model,
        @NotNull String boneName
    ) {
        return BoneKit.buildChainTransforms(model.getBones()).getOrDefault(boneName, Matrix4f.IDENTITY);
    }

    /**
     * Computes the AABB of the model in the Java-native Y-down frame using pre-built bone chains.
     * Walks the 8 inflated corners of every cube through its cube transform - the plain-AABB
     * counterpart to {@link #computeScreenBounds}'s alpha-tight screen walk.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param chainTransforms the pre-built per-bone ancestor-anchor chains
     * @return the model AABB in the Java Y-down frame
     */
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
            float s = bone.getScale();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Matrix4f fullTransform = BoneKit.composeCubeTransform(cube, bone, boneChain);

                Box cubeBounds = BoneKit.scaledCubeBounds(s, cube);
                float[] xs = { cubeBounds.minX(), cubeBounds.maxX() };
                float[] ys = { cubeBounds.minY(), cubeBounds.maxY() };
                float[] zs = { cubeBounds.minZ(), cubeBounds.maxZ() };

                for (float x : xs) for (float y : ys) for (float z : zs) {
                    Vector3f c = new Vector3f(x, y, z).transform(fullTransform);
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

    /**
     * Computes the per-face shade factor baked into emitted triangles, modeling vanilla's
     * {@code Lighting.ENTITY_IN_UI} two-directional Lambertian shader plus
     * {@code PER_FACE_LIGHTING} for plane no-cull cubes.
     * <p>
     * The shade is computed against the post-flip screen-frame normal because
     * {@link Lighting#ENTITY_IN_UI_LIGHT_0} and {@link Lighting#ENTITY_IN_UI_LIGHT_1}
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
     * Empirical sweep confirmed that lifting the {@code !isPlaneCube} guard fixes
     * chicken_cold and chicken_warm without regressing skeleton_horse.
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
            return Lighting.entityInUi(normal);

        Vector3f cameraFacing = VIEW_DIRECTION_KIT.dot(normal) < 0f
            ? normal
            : new Vector3f(-normal.x(), -normal.y(), -normal.z());
        return Lighting.entityInUi(cameraFacing);
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
     * Solid cubes fall through to the content-based tail of this method: cull when any
     * iso-visible face ({@code UP}/{@code NORTH}/{@code EAST}) has content, otherwise cull only
     * when the hidden faces are also empty (so a fully-empty cube culls harmlessly).
     *
     * @param cube the cube whose culling flag to decide
     * @param size the cube's size vector
     * @param texture the entity texture used for alpha sampling
     * @param texW the model texture width
     * @param texH the model texture height
     * @return {@code true} to back-face cull this cube; {@code false} to render both sides
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
        if (uvNonOpaqueExceeds(BoneKit.resolveFaceUv(EntityFace.UP, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD)
            || uvNonOpaqueExceeds(BoneKit.resolveFaceUv(EntityFace.NORTH, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD)
            || uvNonOpaqueExceeds(BoneKit.resolveFaceUv(EntityFace.EAST, cube, size, texW, texH), texture, NO_CULL_TRANSPARENCY_THRESHOLD))
            return false;
        boolean visibleHasContent =
               uvHasContent(BoneKit.resolveFaceUv(EntityFace.UP, cube, size, texW, texH), texture)
            || uvHasContent(BoneKit.resolveFaceUv(EntityFace.NORTH, cube, size, texW, texH), texture)
            || uvHasContent(BoneKit.resolveFaceUv(EntityFace.EAST, cube, size, texW, texH), texture);
        if (visibleHasContent) return true;
        boolean hiddenHasContent =
               uvHasContent(BoneKit.resolveFaceUv(EntityFace.DOWN, cube, size, texW, texH), texture)
            || uvHasContent(BoneKit.resolveFaceUv(EntityFace.SOUTH, cube, size, texW, texH), texture)
            || uvHasContent(BoneKit.resolveFaceUv(EntityFace.WEST, cube, size, texW, texH), texture);
        return !hiddenHasContent;
    }

    /**
     * Returns {@code true} when any of the cube's visible-face UVs include a partial-alpha
     * texel ({@code 0 < alpha < 255}). Distinguishes truly translucent shells (slime,
     * glass-like) from alpha-cutout no-cull cubes (warden tendrils, mushroom block-overlays)
     * whose texels are strictly 0 or 255. The rasterizer's back-to-front sort gates on this
     * flag to avoid re-ordering opaque-cutout no-cull triangles that don't need sorted blend.
     *
     * @param cube the cube whose visible faces to scan
     * @param size the cube's size vector
     * @param texture the entity texture to sample
     * @param texW the model texture width
     * @param texH the model texture height
     * @return {@code true} if any non-degenerate visible face includes a partial-alpha texel
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
                && BoneKit.isDegeneratePlaneFace(size, face)) continue;
            if (faceHasPartialAlpha(BoneKit.resolveFaceUv(face, cube, size, texW, texH), texture))
                return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when any texel inside the face's UV bounding box has partial alpha
     * ({@code 0 < alpha < 255}). Per-face helper backing {@link #uvPartialAlphaPresent}.
     *
     * @param uv the face's four UV corners
     * @param texture the entity texture to sample
     * @return {@code true} if any covered texel is partially transparent
     */
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

    /**
     * Returns {@code true} when the proportion of non-opaque texels in the supplied face UV
     * region exceeds {@code threshold}. Walks the rectangle bounded by the face UVs and counts
     * {@code alpha<255} pixels; returns {@code false} when the UV region is empty (zero area).
     * Used by {@link #shouldCullBackFaces} to detect both cutout ({@code alpha==0} holes,
     * matching vanilla's {@code entityCutoutNoCull}) and translucent ({@code 0<alpha<255}
     * shells, matching vanilla's {@code entityTranslucent withCull(false)}) families - either
     * requires the back faces to render to mirror vanilla's see-through compositing.
     *
     * @param uv the face's four UV corners
     * @param texture the entity texture to sample
     * @param threshold the non-opaque fraction above which the method returns {@code true}
     * @return {@code true} when the non-opaque texel fraction exceeds {@code threshold};
     *     {@code false} for an empty (zero-area) UV region
     */
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

    /**
     * Returns {@code true} when any texel inside the face's UV bounding box is non-transparent
     * ({@code alpha > 0}). Backs the content-based culling heuristic in
     * {@link #shouldCullBackFaces} (a face draws nothing when its whole UV region is transparent).
     *
     * @param uv the face's four UV corners
     * @param texture the entity texture to sample
     * @return {@code true} if any covered texel is at least partially opaque
     */
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
