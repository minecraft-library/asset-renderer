package lib.minecraft.renderer.bake.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.diagnostic.DebugChannel;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Load-bearing bone/cube {@literal ->} triangle assembler. Builds rasterizer-ready triangles
 * from a Java-derived {@link EntityMesh} in vanilla's native {@code ModelPart}-style
 * coordinate frame. Consumes geometry produced by {@code ToolingEntityModels}
 * (entity_geometry.json) which ships in this frame natively (no parse-time conversion).
 * <p>
 * Authoring convention (input):
 * <ul>
 * <li><b>Y-down, right-handed</b> - matches vanilla Java's {@code PartPose} / {@code ModelPart}
 * authoring (positive Y points toward the entity's feet from its root).</li>
 * <li>{@link EntityMesh.Cube#getOrigin() Cube origins} are the min corner in
 * <b>bone-local</b> space (relative to the owning bone's pivot, Y-down) - the literal
 * {@code addBox(x, y, z, w, h, d)} args from {@code createBodyLayer}.</li>
 * <li>{@link EntityMesh.Bone#getPivot() Bone pivots} are in absolute entity-root space.</li>
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
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
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
     * All six faces in declaration order, the order every face walk here takes and the index each
     * face's {@link Face#ordinal() ordinal} names. Read and never written, so every call on every
     * thread shares the one array.
     */
    private static final Face @NotNull [] FACES = Face.values();

    /**
     * The faces a cube carries, which is the stride of a cube's run of slots in {@link SharedFaces}.
     */
    private static final int FACES_PER_CUBE = FACES.length;

    /**
     * Parameters for a native-resolution entity triangle build. Bundles the values that vary
     * per render so callers spell them at a named call boundary rather than positionally.
     *
     * @param frame the {@link FitFrame} this build is fitted through
     * @param pass the {@link PassDeclaration} baked onto every triangle this build emits
     * @param tintArgb ARGB tint multiplied into every sampled texel; {@link ColorMath#WHITE}
     *     ({@code 0xFFFFFFFF}) is a no-op tint
     */
    public record EntityBuildParams(
        @NotNull FitFrame frame,
        @NotNull PassDeclaration pass,
        int tintArgb
    ) { }

    /**
     * Convenience overload that auto-computes bounds and the legacy auto-fit scale
     * ({@code ENTITY_MODEL_FIT_EXTENT / bounds.maxExtent}) for a single-layer render.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @return the build result containing the triangle list
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityMesh model,
        @NotNull PixelBuffer texture
    ) {
        Box bounds = computeBounds(model);
        float extent = Math.max(bounds.maxExtent(), MIN_MODEL_EXTENT);
        Vector3f centre = new Vector3f(
            (bounds.minX() + bounds.maxX()) * 0.5f,
            (bounds.minY() + bounds.maxY()) * 0.5f,
            (bounds.minZ() + bounds.maxZ()) * 0.5f);
        return buildTriangles(model, texture, new EntityBuildParams(
            new FitFrame(centre, ENTITY_MODEL_FIT_EXTENT / extent, 1f),
            PassDeclaration.DEFAULT, ColorMath.WHITE));
    }

    /**
     * Builds rasterizer-ready triangles for an entity model at native resolution from the supplied
     * build parameters. The auto-fit convenience overload delegates here.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param params the render frame, pass declaration and tint for this build
     * @return the build result containing the triangle list
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityMesh model,
        @NotNull PixelBuffer texture,
        @NotNull EntityBuildParams params
    ) {
        return build(model, texture, params, null);
    }

    /**
     * Builds rasterizer-ready triangles for an entity model at native resolution, sharing per-face
     * geometry with every other build handed the same table. A mesh the table already holds, fitted
     * through the same {@link FitFrame} instance, has its transformed corners, turned normals and debug
     * tags read back rather than rebuilt, and its polygon UVs too where this build resolves them against
     * the same texture width and height; a mesh the table does not hold is built in full and its
     * geometry entered.
     *
     * <p>Whatever reads this build's texture is this build's own either way: each cube's translucency
     * flag, the surface traits, the tint and the texture itself. Every build emits its own triangles, in
     * the bone, cube and face order a full build walks.
     *
     * <p>The table must answer a mesh by reference and never by equality, as an {@link IdentityHashMap}
     * does, because two meshes equal in every field equality reads can still differ in a bone's pose
     * scale, which it does not read. A mesh the table holds under another frame is built in full and
     * not entered again.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param params the render frame, pass declaration and tint for this build
     * @param sharedGeometry an {@link IdentityHashMap} the builds of one draw share, read by one thread
     *     at a time
     * @return the build result containing the triangle list
     */
    public static @NotNull BuildResult buildTriangles(
        @NotNull EntityMesh model,
        @NotNull PixelBuffer texture,
        @NotNull EntityBuildParams params,
        @NotNull Map<EntityMesh, Object> sharedGeometry
    ) {
        return build(model, texture, params, sharedGeometry);
    }

    /**
     * Creates the table the builds of one draw share their per-face geometry through, keyed by each
     * mesh's reference identity.
     *
     * @return an empty table for {@link #buildTriangles(EntityMesh, PixelBuffer, EntityBuildParams, Map)}
     */
    static @NotNull Map<EntityMesh, Object> sharedGeometry() {
        return new IdentityHashMap<>();
    }

    /**
     * Builds one pass's triangles, replaying the geometry {@code sharedGeometry} holds for the mesh
     * when it holds some for this frame, and otherwise building in full and entering what was built
     * where the table holds nothing for the mesh yet.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @param texture the shared texture atlas
     * @param params the render frame, pass declaration and tint for this build
     * @param sharedGeometry the table shared with the draw's other builds, or {@code null} to share
     *     nothing
     * @return the build result containing the triangle list
     */
    private static @NotNull BuildResult build(
        @NotNull EntityMesh model,
        @NotNull PixelBuffer texture,
        @NotNull EntityBuildParams params,
        @Nullable Map<EntityMesh, Object> sharedGeometry
    ) {
        FitFrame frame = params.frame();
        PassDeclaration pass = params.pass();
        int tintArgb = params.tintArgb();

        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        Object held = sharedGeometry == null ? null : sharedGeometry.get(model);
        if (held instanceof SharedFaces shared && shared.frame() == frame)
            return replay(model, texture, pass, tintArgb, texW, texH, shared);
        SharedFaces recording = sharedGeometry != null && held == null
            ? SharedFaces.empty(frame, texW, texH, faceSlots(model))
            : null;

        Vector3f centre = frame.anchor();
        float scale = frame.ndcScale();
        float modelScale = frame.modelScale();

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
        // (diag(1,-1,1)) lives on the entity's model->world Placement (EntityRenderer), so the
        // kit-fit scale is uniform. For the yaw-only parity pose the flip commutes past the model
        // spin, so applying it in the placement is compose-equivalent.
        Matrix4f kitFit = Matrix4f.IDENTITY
            .scale(scale, scale, scale)
            .translate(-cx, -cy, -cz)
            .scale(modelScale);
        Map<String, Matrix4f> kitFitChainTransforms = BoneKit.buildChainTransformsFrom(kitFit, model.getBones());

        // Room for two triangles on each of the six faces of every cube a visible bone holds. The walk
        // below skips a plane cube's degenerate faces, so this is a capacity and never a count.
        int capacity = 0;
        for (Map.Entry<String, EntityMesh.Bone> boneEntry : model.getBones().entrySet())
            if (boneEntry.getValue().isVisible()) capacity += boneEntry.getValue().getCubes().size() * 12;
        ArrayList<VisibleTriangle> triangles = new ArrayList<>(capacity);
        // The current bone's debug tag per face, indexed by ordinal and built the first time one of the
        // bone's cubes emits that face. It belongs to this call alone, because frames bake on parallel
        // threads, and is cleared as each bone starts.
        String[] faceTags = new String[FACES.length];
        int slot = 0;

        for (Map.Entry<String, EntityMesh.Bone> boneEntry : model.getBones().entrySet()) {
            String boneName = boneEntry.getKey();
            EntityMesh.Bone bone = boneEntry.getValue();
            // A bone the subject rests without emits nothing, and its subtree rests without it too -
            // the mesh carries the whole subtree marked, so this needs no closure of its own.
            if (!bone.isVisible()) continue;
            Arrays.fill(faceTags, null);
            Matrix4f boneChain = chainTransforms.get(boneName);
            // Answered once per bone: every face the bone draws sits under the same chain, so every one
            // of them turns its normal the same way.
            boolean nonUniform = scalesNonUniformly(model, boneName);

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
            for (EntityMesh.Cube cube : bone.getCubes()) {
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

                // The translucency test and the emit stage both read this cube's faces through one
                // holder, so each face's UV corners resolve once per build.
                BoneKit.CubeUv cubeUv = new BoneKit.CubeUv(cube, texW, texH);

                // Every cube culls exactly when its mesh does: vanilla's render type binds GL back-face
                // culling for the whole model and reads no texel to decide it. A culling mesh
                // (entityCutoutCull - bat, baby_turtle) culls every face, zero-thickness planes included,
                // whose two coincident sides would otherwise both draw and let the LEQUAL depth tie-break
                // pick the away side (the bat ear's brown outer winning over its pink inner); the
                // rasterizer's winding test keeps only the camera-facing side. Every other mesh draws
                // both sides of every cube, as the no-cull entityCutout default does.
                boolean cubeCullBackFaces = model.isCull();
                // True iff this cube is no-cull AND its visible-face UVs include any
                // 0<alpha<255 texels - signal for the rasterizer's back-to-front sort (slime
                // outer shell, glass-like shells). Alpha-cutout no-cull cubes (warden tendrils,
                // mushroom block-overlays) whose texels are strictly 0 or 255 stay
                // {@code translucent=false} and bypass the sort because their alpha-255 fragments
                // depth-fail farther fragments correctly without reordering.
                boolean cubeIsTranslucent = !cubeCullBackFaces
                    && uvPartialAlphaPresent(cubeUv, size, texture);
                // One traits value for every face of this cube, and for no other build: the translucency
                // flag reads this call's texture, which another pass, or another tick of an animated
                // texture, paints differently.
                SurfaceTraits cubeTraits = new SurfaceTraits(cubeCullBackFaces, cubeIsTranslucent, false, true, pass);
                int cubeSlot = slot;
                slot += FACES_PER_CUBE;

                for (Face face : FACES) {
                    Vector3f[] corners = CornerPhase.POLYGON.corners(face, cubeBounds);
                    for (int i = 0; i < 4; i++)
                        corners[i] = corners[i].transform(perCubeChainFluent);

                    // Positions and the STORED normal are both in the model's native Y-up frame now that
                    // the Y-flip lives on the placement (kit-internal geometry stays self-consistent).
                    // The shade is not resolved here: an entity's stack is lit as one draw after the fold
                    // (EntityRenderer), under the lighting entry vanilla binds once per GUI entity, so
                    // every triangle this kit emits carries the unlit scalar until that pass reads its
                    // stored normal and traits. The normal turns as vanilla's normal matrix turns it, which
                    // is the chain itself under uniform scales and its inverse-transpose under a
                    // non-uniform pose scale (chainNormal).
                    Vector3f normal = chainNormal(face.normal(), fullTransform, nonUniform);

                    boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) continue;

                    Vector2f[] effUv = cubeUv.polygon(face);

                    // Natural CCW emission - the shared {@code (0, 1, 2)} / {@code (0, 2, 3)} fan in
                    // {@link BoxKit#addQuad}, so an entity cube's coplanar seams split on
                    // the same diagonal every other quad in the renderer does. The kit itself
                    // is det=+1 (emit-order cross AGREES with the stored normal); the chirality
                    // reflection re-enters downstream via the renderer's Placement Y-flip. Total
                    // pipeline chirality: placement Y-flip (det -1) × engine_camera (det -1) ×
                    // projection's -y (det -1) = det -1. Model CCW → screen CW → rasterizer's
                    // {@code signedArea < 0} check correctly classifies these as front-facing.
                    String debugTag = faceTags[face.ordinal()];
                    if (debugTag == null)
                        debugTag = faceTags[face.ordinal()] = boneName + ":" + face.direction();
                    BoxKit.addQuad(triangles, corners, effUv, texture, tintArgb, normal, Shading.UNLIT,
                        cubeTraits, debugTag);
                    if (recording != null)
                        recording.hold(cubeSlot + face.ordinal(), corners, normal, debugTag, effUv);
                }
            }
        }

        // Entered only once every slot is filled, so nothing writes into what the table holds.
        if (recording != null) sharedGeometry.put(model, recording);
        // Adopted once, after the last add, and never written through the plain list again: the result
        // is the sink the renderer's layer fold and a caller's layer decorator append to.
        return new BuildResult(Concurrent.adoptList(triangles));
    }

    /**
     * Emits one pass's triangles over the geometry an earlier build of the same mesh held, walking the
     * bones, cubes and faces in the order that build walked them. Each cube culls as the mesh declares
     * and reads its translucency flag off this pass's texture, and a face takes the held polygon UVs
     * only where this pass resolves them against the width and height they were resolved at.
     *
     * @param model the mesh the held geometry was built from
     * @param texture the texture this pass samples
     * @param pass the pass declaration baked onto every triangle
     * @param tintArgb the ARGB tint multiplied into every sampled texel
     * @param texW the texture width this pass resolves UVs against
     * @param texH the texture height this pass resolves UVs against
     * @param shared the geometry the earlier build held
     * @return the build result containing this pass's triangles
     */
    private static @NotNull BuildResult replay(
        @NotNull EntityMesh model,
        @NotNull PixelBuffer texture,
        @NotNull PassDeclaration pass,
        int tintArgb,
        float texW,
        float texH,
        @NotNull SharedFaces shared
    ) {
        boolean sameUv = shared.texW() == texW && shared.texH() == texH;
        // Room for two triangles on each held face slot, which is six for every cube a visible bone
        // holds - the full build's capacity. A skipped face leaves its slot empty, so this is a capacity
        // and never a count.
        ArrayList<VisibleTriangle> triangles = new ArrayList<>(shared.corners().length * 2);
        int slot = 0;

        for (Map.Entry<String, EntityMesh.Bone> boneEntry : model.getBones().entrySet()) {
            EntityMesh.Bone bone = boneEntry.getValue();
            if (!bone.isVisible()) continue;
            for (EntityMesh.Cube cube : bone.getCubes()) {
                Vector3f size = cube.getSize();
                // The cull is the mesh's; this pass's own texture decides the cube's translucency, read
                // through a holder of this pass's own, which also answers a face whose held UVs were
                // resolved at another size.
                BoneKit.CubeUv cubeUv = new BoneKit.CubeUv(cube, texW, texH);
                boolean cubeCullBackFaces = model.isCull();
                boolean cubeIsTranslucent = !cubeCullBackFaces
                    && uvPartialAlphaPresent(cubeUv, size, texture);
                SurfaceTraits cubeTraits = new SurfaceTraits(cubeCullBackFaces, cubeIsTranslucent, false, true, pass);
                int cubeSlot = slot;
                slot += FACES_PER_CUBE;

                for (Face face : FACES) {
                    int at = cubeSlot + face.ordinal();
                    Vector3f[] corners = shared.corners()[at];
                    // An empty slot is a face the full build skipped as a degenerate plane.
                    if (corners == null) continue;

                    Vector2f[] effUv = sameUv ? shared.uvs()[at] : cubeUv.polygon(face);
                    BoxKit.addQuad(triangles, corners, effUv, texture, tintArgb, shared.normals()[at], Shading.UNLIT,
                        cubeTraits, shared.debugTags()[at]);
                }
            }
        }

        // Adopted once, after the last add, and never written through the plain list again: the result
        // is the sink the renderer's layer fold and a caller's layer decorator append to.
        return new BuildResult(Concurrent.adoptList(triangles));
    }

    /**
     * Counts the slots a build of {@code model} walks - one per face of every cube a visible bone
     * carries.
     *
     * @param model the mesh to count
     * @return the slot count
     */
    private static int faceSlots(@NotNull EntityMesh model) {
        int cubes = 0;
        for (EntityMesh.Bone bone : model.getBones().values())
            if (bone.isVisible()) cubes += bone.getCubes().size();
        return cubes * FACES_PER_CUBE;
    }

    /**
     * The per-face geometry one full build of a mesh produced, which a later build of the same mesh
     * through the same frame reads back. Every array holds one slot per face of every cube a visible
     * bone carries, in the order the build walks them - a run of
     * {@link EntityGeometryKit#FACES_PER_CUBE} slots per cube, each face at its ordinal - and a face
     * the degenerate-plane skip drops leaves its slot empty. Every slot is filled before the holder
     * enters a table, and none is written after.
     *
     * @param frame the fit frame the corners were transformed through
     * @param texW the texture width the polygon UVs were resolved against
     * @param texH the texture height the polygon UVs were resolved against
     * @param corners each face's four transformed corners, empty for a skipped face
     * @param normals each face's turned unit normal, empty for a skipped face
     * @param debugTags each face's {@code bone:direction} label, empty for a skipped face
     * @param uvs each face's four polygon UVs, empty for a skipped face
     */
    private record SharedFaces(
        @NotNull FitFrame frame,
        float texW,
        float texH,
        @NotNull Vector3f @NotNull [] @Nullable [] corners,
        @Nullable Vector3f @NotNull [] normals,
        @Nullable String @NotNull [] debugTags,
        @NotNull Vector2f @NotNull [] @Nullable [] uvs
    ) {

        /**
         * Creates a holder with every slot empty.
         *
         * @param frame the fit frame the build transforms through
         * @param texW the texture width the build resolves UVs against
         * @param texH the texture height the build resolves UVs against
         * @param slots the slot count {@link EntityGeometryKit#faceSlots} answers for the mesh
         * @return the empty holder
         */
        static @NotNull SharedFaces empty(@NotNull FitFrame frame, float texW, float texH, int slots) {
            return new SharedFaces(frame, texW, texH,
                new Vector3f[slots][], new Vector3f[slots], new String[slots], new Vector2f[slots][]);
        }

        /**
         * Fills one face's slot with what the full build emitted for it.
         *
         * @param at the face's slot
         * @param corners the face's four transformed corners
         * @param normal the face's turned unit normal
         * @param debugTag the face's {@code bone:direction} label
         * @param uv the face's four polygon UVs
         */
        void hold(
            int at,
            @NotNull Vector3f @NotNull [] corners,
            @NotNull Vector3f normal,
            @NotNull String debugTag,
            @NotNull Vector2f @NotNull [] uv
        ) {
            this.corners[at] = corners;
            this.normals[at] = normal;
            this.debugTags[at] = debugTag;
            this.uvs[at] = uv;
        }

    }

    /**
     * Computes the AABB of an entity model in the Java-native Y-down frame, after applying each
     * bone's ancestor anchor chain.
     *
     * @param model the entity model definition (Java Y-down frame)
     * @return the model AABB in the Java Y-down frame
     */
    public static @NotNull Box computeBounds(@NotNull EntityMesh model) {
        return computeBounds(model, BoneKit.buildChainTransforms(model.getBones()));
    }

    /**
     * Walks every visible cube face, alpha-clips each face to its opaque-texel sub-rectangle,
     * projects the resulting 4 bilinear-interpolated corners through the bone chain, the per-
     * render {@code modelScale}, and the supplied screen-space transform, and returns the
     * screen-space AABB of each face's opaque-texel sub-rectangle.
     *
     * <p>Used by {@link EntityRenderer#render render()} to size the output canvas. Instead of
     * taking the full cube AABB, each face contributes only the 3D extent of its opaque-pixel
     * sub-rectangle. Faces with fully-transparent UV regions contribute nothing; faces with sparse
     * opaque stickers (skeleton_horse rib-cage, warden tendrils, wither plane fins) tighten the
     * bounds to the opaque sub-rectangles rather than the authored cube extent. For fully-opaque
     * faces the alpha sub-rect equals the polygon UV box and the four bilinear corners collapse to
     * the polygon's four vertices, matching the legacy AABB walk. A face whose opaque texels touch
     * all four edges of its UV box contributes its full corners even where those corner texels are
     * transparent - a diagonal outline, such as the elytra wing's outward face, is measured to its
     * whole box.
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
     * @return the opaque-sub-rectangle screen-space bounds; the X and Y extents drive canvas sizing,
     *     the Z extent is depth and not consumed by the canvas-fit math
     */
    public static @NotNull Box computeScreenBounds(
        @NotNull EntityMesh model,
        @NotNull Matrix4f screenTransform,
        float modelScale,
        PixelBuffer texture
    ) {
        Map<String, Matrix4f> chainTransforms = BoneKit.buildChainTransforms(model.getBones());
        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture == null ? 1 : texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture == null ? 1 : texture.height());
        BoundsAccumulator acc = new BoundsAccumulator();
        // The trace toggle is per thread, and nothing this walk calls flips it.
        boolean tracingBounds = DebugChannel.tracingBounds();

        for (Map.Entry<String, EntityMesh.Bone> entry : model.getBones().entrySet()) {
            EntityMesh.Bone bone = entry.getValue();
            if (!bone.isVisible()) continue;
            String boneName = entry.getKey();
            Matrix4f boneChain = chainTransforms.get(boneName);
            float s = bone.getScale();
            int cubeIndex = 0;
            for (EntityMesh.Cube cube : bone.getCubes()) {
                Vector3f origin = cube.getOrigin();
                Vector3f size = cube.getSize();
                Vector3f grow = cube.getGrow();
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
                // A loop rather than a forEach: the body reads the mutable {@code cubeIndex} the
                // enclosing walk advances, which a lambda cannot capture.
                for (Face face : FACES) {
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) continue;
                    Vector3f[] corners3d = CornerPhase.POLYGON.corners(face, cubeBounds);
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
                    String dumpLabel = tracingBounds
                        ? DebugChannel.boundsFaceLabel(boneName, cubeIndex, face.direction(),
                            new float[]{ origin.x(), origin.y(), origin.z() },
                            new float[]{ size.x(), size.y(), size.z() },
                            new float[]{ grow.x(), grow.y(), grow.z() }, cube.isMirror())
                        : null;
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
     * @param screenTransform the render orientation ({@code Rasterizer.orient}) applied before
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
     * Per-face alpha-tight bounds contribution. Walks every texel inside the face's
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
     * @param dumpLabel optional debug label for {@link DebugChannel} tracing, or {@code null}
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
            DebugChannel.boundsDegenerateUv(dumpLabel);
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
            DebugChannel.boundsNonAxisUvFallback(dumpLabel);
            return;
        }

        int W = texture.width();
        int H = texture.height();
        if (W <= 0 || H <= 0) {
            for (Vector3f c : corners3d) projectAndAccumulate(c, cubeTransform, modelScale, screenTransform, acc);
            DebugChannel.boundsNoTextureFallback(dumpLabel);
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
            DebugChannel.boundsAllTransparent(dumpLabel, pxMin, pyMin, pxMax, pyMax);
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

        if (dumpLabel != null)
            DebugChannel.boundsFaceContribution(
                dumpLabel,
                pxMin, pyMin, pxMax, pyMax,
                firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
                new float[]{ bl.x(), bl.y(), bl.z(), br.x(), br.y(), br.z(),
                    tr.x(), tr.y(), tr.z(), tl.x(), tl.y(), tl.z() });
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
     * order mirrors the vertex path in {@link #buildTriangles(EntityMesh, PixelBuffer, EntityBuildParams)}.
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
     * extent - the same {@code ENTITY_MODEL_FIT_EXTENT / maxExtent} scale the {@link #buildTriangles(EntityMesh, PixelBuffer)}
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
     * {@code (v - modelCentre) * ndcScale} on each axis.
     *
     * <p>Caller-supplied scale rather than an auto-fit, so block overlays composed onto an entity's
     * frame match the scale the renderer used for the entity body. Used by {@link EntityRenderer} to
     * project block-model overlay triangles (mooshroom mushroom blocks, etc) at the same
     * silhouette-centred frame the entity body uses - the same {@link FitFrame} the body was
     * {@link #buildTriangles(EntityMesh, PixelBuffer, EntityBuildParams) built} through, with its
     * two scales pre-multiplied by the caller - so they render at the correct scale and orientation
     * alongside it.
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
     * Resolves the frame a carried block stands in: the steps the posed mesh is seated under, then the
     * attached part's own step and none of its ancestors'. Used by {@link EntityRenderer} to place a
     * block overlay - the mooshroom's head mushroom, the snow golem's pumpkin, the iron golem's poppy.
     *
     * <p>It is the stack vanilla's carrying layers draw on. {@code MushroomCowMushroomLayer},
     * {@code SnowGolemHeadLayer} and {@code IronGolemFlowerLayer} take the part off the model and call
     * that part's own {@code translateAndRotate} on the stack they were handed, which holds what the
     * renderer composed above the model - the seated container here - and neither the root's step nor
     * any intermediate part's, {@code ModelPart.render} having popped each after drawing it. The part's
     * own step is its pivot, its rotation and its pose scale, composed on the seat's chain the way
     * {@link #buildTriangles} composes a bone on its parent's.
     *
     * <p>A block attached to no part stands on the seat's chain alone, as a layer drawing on the stack it
     * was handed does, and with no seat the chain starts at the identity.
     *
     * @param model the posed entity mesh
     * @param seat the innermost step the mesh is seated under, empty where nothing seated it
     * @param part the bone the block is attached to, empty for a block drawn on the stack as handed
     * @return the anchor matrix in entity pixel units, or {@link Matrix4f#IDENTITY} for a part the mesh
     *     does not declare
     */
    public static @NotNull Matrix4f resolveBoneAnchorMatrix(
        @NotNull EntityMesh model,
        @NotNull Optional<String> seat,
        @NotNull Optional<String> part
    ) {
        return anchorChain(model, seat, part)
            .map(anchor -> BoneKit.buildChainTransform(anchor.bones(), anchor.bone()))
            .orElse(Matrix4f.IDENTITY);
    }

    /**
     * Tests whether the steps a carried block's anchor composes carry a pose scale whose three axes
     * differ in magnitude - the attached part's own and the seat's chain, as
     * {@link #resolveBoneAnchorMatrix} composes them, and never an ancestor of the part's.
     *
     * @param model the posed entity mesh
     * @param seat the innermost step the mesh is seated under, empty where nothing seated it
     * @param part the bone the block is attached to, empty for a block drawn on the stack as handed
     * @return {@code true} when any step of the anchor carries a non-uniform pose scale; {@code false}
     *     for a part the mesh does not declare
     */
    public static boolean anchorScalesNonUniformly(
        @NotNull EntityMesh model,
        @NotNull Optional<String> seat,
        @NotNull Optional<String> part
    ) {
        return anchorChain(model, seat, part)
            .map(anchor -> scalesNonUniformly(anchor.bones(), anchor.bone()))
            .orElse(false);
    }

    /**
     * Narrows a mesh to the chain a carried block's anchor composes: the part re-hung directly from the
     * seat, so no ancestor of the part's stands between them, or the seat itself where no part is
     * attached.
     *
     * @param model the posed entity mesh
     * @param seat the innermost step the mesh is seated under, empty where nothing seated it
     * @param part the bone the block is attached to, empty for a block drawn on the stack as handed
     * @return the bones and the bone the anchor composes down to, empty where it composes nothing
     */
    private static @NotNull Optional<AnchorChain> anchorChain(
        @NotNull EntityMesh model,
        @NotNull Optional<String> seat,
        @NotNull Optional<String> part
    ) {
        Map<String, EntityMesh.Bone> bones = model.getBones();
        if (part.isEmpty()) return seat.map(step -> new AnchorChain(bones, step));

        EntityMesh.Bone attached = bones.get(part.get());
        if (attached == null) return Optional.empty();

        Map<String, EntityMesh.Bone> narrowed = new HashMap<>(bones);
        narrowed.put(part.get(), attached.withParent(seat.orElse(null)));
        return Optional.of(new AnchorChain(narrowed, part.get()));
    }

    /**
     * The chain a carried block's anchor composes.
     *
     * @param bones the bones the chain is composed over
     * @param bone the bone the chain is composed down to
     */
    private record AnchorChain(@NotNull Map<String, EntityMesh.Bone> bones, @NotNull String bone) {}

    /**
     * Tests whether a bone's chain carries a pose scale whose three axes differ in magnitude, on the
     * bone itself or on any ancestor its chain composes - the chain {@link #buildTriangles} draws the
     * bone's cubes through.
     *
     * <p>It is the test vanilla's {@code PoseStack.Pose.scale} makes before it touches the normal
     * matrix. Where {@code |x|}, {@code |y|} and {@code |z|} agree it at most flips a sign, which turns a
     * normal to the direction the chain itself turns it; where they differ it scales the normal matrix
     * by the reciprocals and marks it untrusted, and both carry to everything drawn below the scale - the
     * scaled bone's own cubes and every descendant's. A rest scale is uniform by construction, so the
     * pose scale is the only factor that can answer {@code true}.
     *
     * <p>The walk follows the parents the chain composes and stops where the chain stops - at a root,
     * at a parent the mesh does not declare - and a parent cycle walks no further than the mesh has
     * bones.
     *
     * @param model the mesh the bone belongs to
     * @param boneName the bone whose chain to test
     * @return {@code true} when any step of the bone's chain carries a non-uniform pose scale;
     *     {@code false} for a bone the mesh does not declare
     */
    public static boolean scalesNonUniformly(@NotNull EntityMesh model, @NotNull String boneName) {
        return scalesNonUniformly(model.getBones(), boneName);
    }

    /** The non-uniform test over a bone map, walking the parents its chain composes. */
    private static boolean scalesNonUniformly(@NotNull Map<String, EntityMesh.Bone> bones, @NotNull String boneName) {
        String name = boneName;
        for (int depth = 0; name != null && depth <= bones.size(); depth++) {
            EntityMesh.Bone bone = bones.get(name);
            if (bone == null) return false;
            Vector3f scale = bone.getPoseScale();
            if (Math.abs(scale.x()) != Math.abs(scale.y()) || Math.abs(scale.y()) != Math.abs(scale.z()))
                return true;
            name = bone.getParent();
        }
        return false;
    }

    /**
     * Turns a face normal through the chain the face is drawn through and normalises it, to the
     * direction vanilla's {@code PoseStack.Pose.transformNormal} answers for the same stack.
     *
     * <p>Vanilla turns a normal by a matrix of its own that each rotation and scale updates beside the
     * pose. Under a chain whose scales are all uniform in magnitude that matrix is the chain's own
     * linear part over a positive factor - such a scale reaches it only as the signs it flips - so the
     * chain turns the normal to the same direction, and the turn taken is the chain's own,
     * {@link Vector3f#transformNormal}, normalised. Under a non-uniform scale the matrix is the
     * inverse-transpose of the chain's linear part, again over a positive factor, and vanilla
     * normalises what it turns once the scale has marked it untrusted. The two part on every face whose
     * normal leaves the scale's axes: the chain leans such a normal toward the stretched axis, and the
     * inverse-transpose leans it away, as vanilla does.
     *
     * @param normal the face normal in the frame the chain maps from
     * @param chain the chain the face is drawn through
     * @param nonUniform whether the chain carries a non-uniform scale, as {@link #scalesNonUniformly}
     *     answers for a bone
     * @return the unit normal in the frame the chain maps into
     */
    public static @NotNull Vector3f chainNormal(@NotNull Vector3f normal, @NotNull Matrix4f chain, boolean nonUniform) {
        return (nonUniform ? inverseTransposeNormal(normal, chain) : normal.transformNormal(chain)).normalize();
    }

    /**
     * Turns a direction by the inverse-transpose of a matrix's linear part, unnormalised - the cofactor
     * matrix of that part applied to the direction, over its determinant, so no inverse is formed.
     * Rows of the linear part are read as {@link Vector3f#transformNormal} reads them.
     *
     * @param n the direction to turn
     * @param m the matrix whose upper 3x3 is inverted and transposed
     * @return {@code n} turned by the inverse-transpose of {@code m}'s linear part
     */
    private static @NotNull Vector3f inverseTransposeNormal(@NotNull Vector3f n, @NotNull Matrix4f m) {
        float a = m.get(1, 1), b = m.get(2, 1), c = m.get(3, 1);
        float d = m.get(1, 2), e = m.get(2, 2), f = m.get(3, 2);
        float g = m.get(1, 3), h = m.get(2, 3), i = m.get(3, 3);
        float c00 = e * i - f * h, c01 = f * g - d * i, c02 = d * h - e * g;
        float c10 = c * h - b * i, c11 = a * i - c * g, c12 = b * g - a * h;
        float c20 = b * f - c * e, c21 = c * d - a * f, c22 = a * e - b * d;
        float det = a * c00 + b * c01 + c * c02;
        return new Vector3f(
            (c00 * n.x() + c01 * n.y() + c02 * n.z()) / det,
            (c10 * n.x() + c11 * n.y() + c12 * n.z()) / det,
            (c20 * n.x() + c21 * n.y() + c22 * n.z()) / det);
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
        @NotNull EntityMesh model,
        @NotNull Map<String, Matrix4f> chainTransforms
    ) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (Map.Entry<String, EntityMesh.Bone> entry : model.getBones().entrySet()) {
            EntityMesh.Bone bone = entry.getValue();
            if (!bone.isVisible()) continue;
            Matrix4f boneChain = chainTransforms.get(entry.getKey());
            // Same pivot-translation + bone-scale as in {@link #buildTriangles}.
            float s = bone.getScale();
            for (EntityMesh.Cube cube : bone.getCubes()) {
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
     * Returns {@code true} when any of the cube's visible-face UVs include a partial-alpha
     * texel ({@code 0 < alpha < 255}). Distinguishes truly translucent shells (slime,
     * glass-like) from alpha-cutout no-cull cubes (warden tendrils, mushroom block-overlays)
     * whose texels are strictly 0 or 255. The rasterizer's back-to-front sort gates on this
     * flag to avoid re-ordering opaque-cutout no-cull triangles that don't need sorted blend.
     *
     * @param cubeUv the UV corners of the cube whose visible faces to scan, at the model texture size
     * @param size the cube's size vector
     * @param texture the entity texture to sample
     * @return {@code true} if any non-degenerate visible face includes a partial-alpha texel
     */
    private static boolean uvPartialAlphaPresent(
        @NotNull BoneKit.CubeUv cubeUv,
        @NotNull Vector3f size,
        @NotNull PixelBuffer texture
    ) {
        for (Face face : FACES) {
            if ((size.x() == 0f || size.y() == 0f || size.z() == 0f)
                && BoneKit.isDegeneratePlaneFace(size, face)) continue;
            if (BoneKit.faceHasPartialAlpha(cubeUv.face(face), texture))
                return true;
        }
        return false;
    }

    /**
     * The result of building triangles from an entity model.
     *
     * @param triangles the triangle list ready for rasterization
     */
    public record BuildResult(
        @NotNull ConcurrentList<VisibleTriangle> triangles
    ) {}

}
