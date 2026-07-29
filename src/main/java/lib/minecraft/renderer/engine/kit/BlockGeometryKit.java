package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.CornerPhase;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.SixFaces;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates the canonical triangle lists needed by the engine layer for common 3D shapes.
 * <p>
 * The primary use case is constructing the six-face cube used by every isometric block and head
 * renderer. Each cube face is built as a pair of triangles with the correct CCW winding, UV
 * orientation, and surface normal so that back-face culling and inventory lighting produce the
 * expected result without caller-side fixups. All direction-aware logic lives in the face package:
 * normals on {@link Face}, vertex winding on {@link CornerPhase}, default UV derivation on
 * {@link Unwrap}.
 */
@UtilityClass
public class BlockGeometryKit {

    /**
     * Edge length of a full block in vanilla model-authoring units. Every vanilla {@code block/}
     * and {@code item/} model JSON authors coordinates against this grid - element
     * {@code from} / {@code to} values of {@code [0, 0, 0]} and {@code [16, 16, 16]} describe a
     * full unit cube, face UVs run from {@code 0} to {@code 16}, and {@code display.*.translation}
     * values are in the same space. This kit and its consumers ({@link Unwrap.Element#rect},
     * {@link BlockRenderer}, item renderer's display-transform path) divide by this constant to
     * normalise into the engine's {@code [-0.5, +0.5]} unit-cube space before projection.
     */
    public static final float VANILLA_PIXEL_UNITS_PER_BLOCK = 16f;

    /**
     * Maximum {@code light_emission} level (vanilla's {@code 0-15} block-light scale). This level
     * raises the baked shade floor to {@code 1.0} (full-bright); intermediate values scale linearly
     * ({@code emission / 15}). The floor survives on the no-relight Held3D item path; the block-icon
     * path recomputes shade per-normal ({@link Shading#relightForItems3d}) and so drops the floor - a
     * documented Held3D-only effect for intermediate emission.
     */
    private static final int FULL_LIGHT_EMISSION = 15;


    /**
     * Builds a list of 12 triangles (2 per face) describing a unit cube centered at the origin
     * with the given per-face textures.
     * <p>
     * Every face uses the full {@code [0, 1]} UV rectangle.
     *
     * @param faces the six face textures, keyed by {@link Face} direction
     * @param tintArgb the ARGB tint applied to every face, or {@code 0xFFFFFFFF} for no tint
     * @return the 12-triangle list, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> unitCube(
        @NotNull SixFaces faces,
        int tintArgb
    ) {
        return buildBoxTriangles(
            new Vector3f(-0.5f, -0.5f, -0.5f),
            new Vector3f(0.5f, 0.5f, 0.5f),
            faces,
            tintArgb
        );
    }

    /**
     * Builds a list of 12 triangles describing a box defined by minimum and maximum corners.
     *
     * @param min the minimum corner in model space
     * @param max the maximum corner in model space
     * @param faces the six face textures, keyed by {@link Face} direction
     * @param tintArgb the ARGB tint applied to every face
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildBoxTriangles(
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull SixFaces faces,
        int tintArgb
    ) {
        return buildBox(min, max, faces, tintArgb, true, false);
    }

    /**
     * Builds a list of 12 triangles describing a worn-armor box - two-sided, and glinted so the
     * rasterizer's foil mask covers the armor rather than the whole body silhouette.
     * <p>
     * Vanilla submits worn armor through a pipeline that binds no culling alongside an alpha cutout, so
     * where a box's near face is cut away by a transparent texel the far face of that same box shows
     * through the hole. Culling the far faces drops those faces whole, which reads as a missing wedge
     * wherever the armor stands clear of the body behind it.
     * <p>
     * On the entity render the flag additionally selects the per-face lighting form in
     * {@link Lighting.EntityLighting#shade}, so a face the camera sees from behind is shaded by its
     * camera-facing orientation - the choice vanilla's shader makes per pixel - while a face seen from
     * the front shades identically either way. A player's own armor is not turned back into the entity
     * frame, so it keeps the cull-blind {@link Lighting#inventory} shade baked here.
     * <p>
     * The result is emitted two triangles per face in {@link Face#CACHED_VALUES} order, which is
     * how {@code ArmorKit} recovers each triangle's face to name it for the per-pixel trace.
     *
     * @param min the minimum corner in model space
     * @param max the maximum corner in model space
     * @param faces the six face textures, keyed by {@link Face} direction
     * @param tintArgb the ARGB tint applied to every face
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildArmorBoxTriangles(
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull SixFaces faces,
        int tintArgb
    ) {
        return buildBox(min, max, faces, tintArgb, false, true);
    }

    private static @NotNull ConcurrentList<VisibleTriangle> buildBox(
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull SixFaces faces,
        int tintArgb,
        boolean cullBackFaces,
        boolean glinted
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Box box = Box.of(min, max);

        for (Face face : Face.CACHED_VALUES) {
            Vector3f[] corners = CornerPhase.BAKERY.corners(face, box);
            addQuad(
                triangles,
                corners[0], corners[1], corners[2], corners[3],
                faces.byFace(face), tintArgb,
                face.normal(),
                cullBackFaces,
                glinted
            );
        }

        return triangles;
    }

    /**
     * Builds block-frame triangles directly from a <b>relative</b> bone/cube tree
     * ({@link EntityModelData}), composing the parent hierarchy through the shared
     * {@link BoneKit} chain math - the hierarchical counterpart to {@link #buildFromElements},
     * for block entities whose geometry is stored as a relative bone tree rather than
     * pre-flattened block elements.
     * <p>
     * Each cube is walked with the same entity conventions the {@link EntityGeometryKit} uses -
     * bone-local origins scaled by the bone's {@code scale}, {@link Face} atlas-UV unwrap
     * (via {@link BoneKit#resolvePolygonUv}), inflate, mirror, and per-cube / bind-pose
     * rotation (via {@link BoneKit#composeCubeTransform}) - then emitted in the block engine's
     * {@code [-0.5, +0.5]} frame by dividing the composed pixel-space position by
     * {@link #VANILLA_PIXEL_UNITS_PER_BLOCK} and subtracting {@code 0.5}, matching
     * {@link #buildFromElements}'s normalization. Degenerate plane-cube faces are skipped; plane
     * cubes render two-sided; the inventory shade is baked via {@link Lighting#inventory}.
     * <p>
     * <b>Frame scope:</b> this emits in the bone tree's <b>native</b> orientation (Y-down, no entity
     * flip, no inventory transform). The per-block-entity presentation transforms - the entity-render
     * flip, the decomposed {@code inventory_transform} / {@code inventory_y_rotation}, and the iso
     * pose - are applied downstream at render time (the same knobs that were previously baked into the
     * block elements at tooling time). So this method is <b>not</b> triangle-identical
     * to {@code buildFromElements(elements)}; equivalence is a render-parity property validated once
     * the render path applies those transforms.
     *
     * @param model the relative bone/cube model (vanilla Y-down frame)
     * @param texture the entity texture the cube UVs sample
     * @param tintArgb the ARGB tint applied to every face, or {@code 0xFFFFFFFF} for no tint
     * @return the block-frame triangle list, ready for the downstream render transform
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromBones(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        int tintArgb
    ) {
        return buildFromBones(model, texture, tintArgb, Matrix4f.IDENTITY);
    }

    /**
     * {@code presentation}-aware variant of {@link #buildFromBones(EntityModelData, PixelBuffer, int)}
     * that applies a block-entity presentation transform to each composed cube corner
     * <b>before</b> the {@code /16 - 0.5} normalization - the same {@code [0, 16]}-space frame the
     * bake formerly produced at tooling time.
     * <p>
     * The presentation reproduces the render-time knobs vanilla's {@code BlockEntityRenderer}
     * applies around the bone geometry: the entity-render {@code scale(-1, -1, 1)} flip (or a
     * decomposed {@code inventory_transform}), then the inventory yaw about block centre
     * {@code (8, 8, 8)} that faces the model at the standard {@code [30, 225, 0]} iso pose (the
     * chest's baked {@code +180}). Since the bone chain, the presentation, and the normalization all
     * live in the same {@code [0, 16]} frame, this stays byte-compatible with the block element path
     * a caller would otherwise build.
     *
     * @param model the relative bone/cube model (vanilla Y-down frame)
     * @param texture the entity texture the cube UVs sample
     * @param tintArgb the ARGB tint applied to every face, or {@code 0xFFFFFFFF} for no tint
     * @param presentation the {@code [0, 16]}-space model-to-block transform applied after the bone
     *     chain and before normalization, or {@link Matrix4f#IDENTITY} for the native frame
     * @return the block-frame triangle list, ready for the downstream render transform
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromBones(
        @NotNull EntityModelData model,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Matrix4f presentation
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Map<String, Matrix4f> chains = BoneKit.buildChainTransforms(model.getBones());
        float texW = model.getTextureWidth() > 0 ? model.getTextureWidth() : Math.max(1f, texture.width());
        float texH = model.getTextureHeight() > 0 ? model.getTextureHeight() : Math.max(1f, texture.height());

        for (Map.Entry<String, EntityModelData.Bone> boneEntry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = boneEntry.getValue();
            Matrix4f boneChain = chains.get(boneEntry.getKey());
            float s = bone.getScale();
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f size = cube.getSize();
                Box cubeBounds = BoneKit.scaledCubeBounds(s, cube);
                // Column-vector chain: cubeTransform (the bone chain) applies first to a cube corner,
                // then presentation (flip / inventory transform / inventory yaw) in the same [0, 16]
                // block frame; the /16 - 0.5 normalization below matches buildFromElements.
                Matrix4f cubeTransform = presentation.multiply(BoneKit.composeCubeTransform(cube, bone, boneChain));
                boolean isPlaneCube = size.x() == 0f || size.y() == 0f || size.z() == 0f;

                for (Face face : Face.CACHED_VALUES) {
                    if (isPlaneCube && BoneKit.isDegeneratePlaneFace(size, face)) continue;
                    Vector3f[] corners = CornerPhase.POLYGON.corners(face, cubeBounds);
                    for (int i = 0; i < corners.length; i++) {
                        Vector3f t = corners[i].transform(cubeTransform);
                        corners[i] = new Vector3f(
                            t.x() / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f,
                            t.y() / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f,
                            t.z() / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f);
                    }
                    Vector3f normal = face.normal().transformNormal(cubeTransform).normalize();
                    Vector2f[] uv = BoneKit.resolvePolygonUv(face, cube, size, texW, texH);
                    boolean translucent = BoneKit.faceHasPartialAlpha(uv, texture);
                    addQuad(triangles,
                        corners[0], corners[1], corners[2], corners[3],
                        uv[0], uv[1], uv[2], uv[3],
                        texture, tintArgb, normal,
                        !isPlaneCube, translucent, true, 0, false);
                }
            }
        }

        return triangles;
    }

    /**
     * Resolves a per-face texture substitution, letting a caller swap the pre-loaded texture of a
     * given face for another (the Connected Textures block-face hook). The kit calls it per face with
     * the face direction and its raw {@code #ref}; an empty return leaves the pre-loaded texture in
     * place, so {@link #NONE} keeps every build byte-identical.
     */
    @FunctionalInterface
    public interface FaceTextureResolver {

        /**
         * The inert resolver - every face falls through to its pre-loaded texture.
         */
        @NotNull FaceTextureResolver NONE = (face, rawRef) -> Optional.empty();

        /**
         * Resolves a substitute texture for one face, or empty to keep the pre-loaded one.
         *
         * @param face the face direction being built
         * @param rawRef the face's raw texture ref (the {@link ModelFace#getTexture()} key)
         * @return the substitute texture, or empty to fall through to the pre-loaded texture
         */
        @NotNull Optional<PixelBuffer> resolve(@NotNull Face face, @NotNull String rawRef);

    }

    /**
     * Per-build parameters for {@link #buildFromElements(ConcurrentList, Map, ElementBuildParams)}:
     * the per-face tints, the blockstate variant rotation, the {@code uvlock} flag, the
     * force-translucent face refs, and the per-face texture resolver. Bundles the values that vary per
     * build so callers name them instead of threading a positional overload cascade.
     *
     * @param tintedArgb ARGB applied to faces with {@code tintindex >= 0}
     * @param untintedArgb ARGB applied to faces with {@code tintindex = -1}
     * @param variantRotationX the variant's whole-model X rotation in degrees (0/90/180/270)
     * @param variantRotationY the variant's whole-model Y rotation in degrees (0/90/180/270)
     * @param uvLock whether the blockstate variant requested {@code uvlock}
     * @param forceTranslucentRefs raw face-texture refs whose model entry carried
     *     {@code force_translucent}, sorted into the translucent pass regardless of texel alpha
     * @param ctm the per-face texture resolver - {@link FaceTextureResolver#NONE} unless a block render
     *     supplies a Connected Textures resolver
     */
    public record ElementBuildParams(
        int tintedArgb,
        int untintedArgb,
        int variantRotationX,
        int variantRotationY,
        boolean uvLock,
        @NotNull Set<String> forceTranslucentRefs,
        @NotNull FaceTextureResolver ctm
    ) {}

    /**
     * Builds a triangle list from a resolved list of {@link ModelElement element boxes} using
     * pre-loaded face textures. Suitable for the held-item 3D path where the caller has already
     * walked the model's {@code #var} bindings and loaded every unique texture.
     * <p>
     * Each element's {@code from}/{@code to} bounds are converted from vanilla's 0-16 space to
     * the engine's normalized {@code [-0.5, +0.5]} cube space, matching the convention used by
     * {@link #unitCube}. Faces missing from an element's {@code faces} map - or carrying an
     * unrecognized direction name - are skipped. Face UV rectangles are converted from 0-16 to
     * {@code [0, 1]} space when present, otherwise derived via {@link Unwrap.Element#rect}. Face
     * {@code rotation} ({@code 0}/{@code 90}/{@code 180}/{@code 270} degrees) rotates the UV
     * corners clockwise.
     * <p>
     * Element-level rotation ({@link ModelElement.ElementRotation}) is supported: each element's
     * vertices are rotated around the specified origin on a single axis by the given angle. When
     * the {@code rescale} flag is set, the perpendicular axes are scaled by {@code 1/cos(angle)}
     * to preserve the element's axis-aligned footprint (used by cross-shaped plants).
     *
     * @param elements the fully-resolved element list from a parent-walked, deep-merged
     *     {@link ModelData} (block and item models share the same shape)
     * @param faceTextures a map keyed by the exact {@link ModelFace#getTexture()} string
     *     (including any leading {@code #}) to a pre-loaded {@link PixelBuffer}. The caller is
     *     responsible for dereferencing {@code #var} chains against the model's texture
     *     bindings before populating this map.
     * @param tintArgb the ARGB tint applied uniformly to every face
     * @return the triangle list, ready for rasterization - empty when the elements list is empty
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintArgb
    ) {
        return buildFromElements(elements, faceTextures, new ElementBuildParams(tintArgb, tintArgb, 0, 0, false, Set.of(), FaceTextureResolver.NONE));
    }

    /**
     * Per-face-tint variant of {@link #buildFromElements(ConcurrentList, Map, int)}. Faces whose
     * {@link ModelFace#getTintIndex() tintindex} is {@code >= 0} receive {@code tintedArgb}; faces
     * with {@code tintindex = -1} (the default) receive {@code untintedArgb}. Callers that want
     * uniform tinting pass the same value for both, which is what the single-argument overload
     * does.
     * <p>
     * Used by {@link BlockRenderer} to honour vanilla's
     * {@code "tintindex": 0} on banner-flag faces: the flag receives the dye colour, the pole
     * and bar stay wood-brown. Biome-tinted blocks (grass_block, leaves) continue to call the
     * uniform overload so every face still picks up the biome colormap sample.
     *
     * @param tintedArgb ARGB applied to faces with {@code tintindex >= 0}
     * @param untintedArgb ARGB applied to faces with {@code tintindex = -1}
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintedArgb,
        int untintedArgb
    ) {
        return buildFromElements(elements, faceTextures, new ElementBuildParams(tintedArgb, untintedArgb, 0, 0, false, Set.of(), FaceTextureResolver.NONE));
    }

    /**
     * {@code uvlock}-aware variant of
     * {@link #buildFromElements(ConcurrentList, Map, int, int)}. When {@code uvLock} is set, the
     * UV of every face whose normal lies along the blockstate variant's Y-rotation axis (the
     * {@code up} and {@code down} faces) is counter-rotated by the variant's Y angle so the
     * texture stays aligned to the world grid rather than spinning with the rotated model -
     * matching vanilla's per-face {@code uvlock} baking. The caller still applies the variant's
     * position rotation separately (the UV lock is independent of where the vertices land), so
     * passing {@code uvLock = false} reproduces the plain overload byte-for-byte.
     * <p>
     * Both rotation axes are handled. A Y rotation spins the {@code up}/{@code down} faces in
     * place (stairs, walls, fence gates), so only those are counter-rotated. An X rotation tips
     * every face onto a new world direction, so each takes its own per-face turn - half-turns on the
     * up/down planes of the multipart {@code vine}/{@code sculk_vein}/{@code glow_lichen}/
     * {@code resin_clump} blocks and the single-face {@code mushroom_block}/{@code mushroom_stem}
     * skins, plus the quarter-turn {@code east}/{@code west} side corrections a thick box such as a
     * wall button needs (see {@link #uvLockQuarterTurns} for the full per-face table).
     *
     * @param variantRotationX the variant's whole-model X rotation in degrees (0/90/180/270)
     * @param variantRotationY the variant's whole-model Y rotation in degrees (0/90/180/270)
     * @param uvLock whether the blockstate variant requested {@code uvlock}
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintedArgb,
        int untintedArgb,
        int variantRotationX,
        int variantRotationY,
        boolean uvLock
    ) {
        return buildFromElements(elements, faceTextures,
            new ElementBuildParams(tintedArgb, untintedArgb, variantRotationX, variantRotationY, uvLock, Set.of(), FaceTextureResolver.NONE));
    }

    /**
     * {@code force_translucent}-aware variant of {@link #buildFromElements(ConcurrentList, Map, int, int)}.
     * Refs present in {@code forceTranslucentRefs} join the translucent pass regardless of texel alpha.
     *
     * @param forceTranslucentRefs raw face-texture refs flagged {@code force_translucent} by the model
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintedArgb,
        int untintedArgb,
        @NotNull Set<String> forceTranslucentRefs
    ) {
        return buildFromElements(elements, faceTextures,
            new ElementBuildParams(tintedArgb, untintedArgb, 0, 0, false, forceTranslucentRefs, FaceTextureResolver.NONE));
    }

    /**
     * {@code force_translucent}-aware variant of
     * {@link #buildFromElements(ConcurrentList, Map, int, int, int, int, boolean)}. Refs present in
     * {@code forceTranslucentRefs} join the translucent pass regardless of texel alpha.
     *
     * @param forceTranslucentRefs raw face-texture refs flagged {@code force_translucent} by the model
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintedArgb,
        int untintedArgb,
        int variantRotationX,
        int variantRotationY,
        boolean uvLock,
        @NotNull Set<String> forceTranslucentRefs
    ) {
        return buildFromElements(elements, faceTextures,
            new ElementBuildParams(tintedArgb, untintedArgb, variantRotationX, variantRotationY, uvLock, forceTranslucentRefs, FaceTextureResolver.NONE));
    }

    /**
     * Core build that converts a resolved element list into rasterizer-ready triangles from the
     * supplied {@link ElementBuildParams}. The three positional overloads delegate here. See
     * {@link #buildFromElements(ConcurrentList, Map, int)} for the element-to-triangle conversion
     * details (bounds normalization, UV derivation, element rotation) and the {@code uvlock}
     * overload for the variant-rotation UV handling.
     *
     * @param elements the fully-resolved element list
     * @param faceTextures a map keyed by the exact {@link ModelFace#getTexture()} string to a
     *     pre-loaded {@link PixelBuffer}
     * @param params the per-face tints, blockstate variant rotation, and {@code uvlock} flag
     * @return the triangle list, ready for rasterization - empty when the elements list is empty
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        @NotNull ElementBuildParams params
    ) {
        int tintedArgb = params.tintedArgb();
        int untintedArgb = params.untintedArgb();
        int variantRotationX = params.variantRotationX();
        int variantRotationY = params.variantRotationY();
        boolean uvLock = params.uvLock();
        Set<String> forceTranslucentRefs = params.forceTranslucentRefs();
        FaceTextureResolver ctm = params.ctm();

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (ModelElement element : elements) {
            float x0 = element.getFrom()[0] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float y0 = element.getFrom()[1] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float z0 = element.getFrom()[2] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float x1 = element.getTo()[0] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float y1 = element.getTo()[1] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float z1 = element.getTo()[2] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;

            // Build element rotation transform if present. The rotation is applied around
            // an arbitrary origin on a single axis. When rescale is set, the two axes
            // perpendicular to the rotation axis are scaled by 1/cos(angle) to preserve
            // the element's axis-aligned footprint.
            Matrix4f elementTransform = null;
            Matrix4f normalTransform = null;
            if (element.getRotation().isPresent()) {
                ModelElement.ElementRotation rot = element.getRotation().get();
                if (rot.angle() != 0f) {
                    float[] rawOrigin = rot.origin();
                    float ox = rawOrigin[0] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
                    float oy = rawOrigin[1] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
                    float oz = rawOrigin[2] / VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;

                    Vector3f axisVec = switch (rot.axis()) {
                        case "x" -> new Vector3f(1, 0, 0);
                        case "y" -> new Vector3f(0, 1, 0);
                        default -> new Vector3f(0, 0, 1);
                    };
                    float radians = (float) Math.toRadians(rot.angle());

                    Matrix4f toOrigin = Matrix4f.createTranslation(-ox, -oy, -oz);
                    Matrix4f rotation = Matrix4f.createFromAxisAngle(axisVec, radians);
                    Matrix4f fromOrigin = Matrix4f.createTranslation(ox, oy, oz);

                    if (rot.rescale()) {
                        float s = 1f / (float) Math.cos(radians);
                        Matrix4f scale = switch (rot.axis()) {
                            case "x" -> Matrix4f.createScale(1f, s, s);
                            case "y" -> Matrix4f.createScale(s, 1f, s);
                            default -> Matrix4f.createScale(s, s, 1f);
                        };
                        // Column-vector chain: toOrigin (rightmost) applies first to a vertex,
                        // then rotation, then scale, then fromOrigin moves the pivot back.
                        elementTransform = fromOrigin.multiply(scale).multiply(rotation).multiply(toOrigin);
                    } else {
                        elementTransform = fromOrigin.multiply(rotation).multiply(toOrigin);
                    }
                    normalTransform = rotation;
                }
            }

            // Flat planes (zero thickness on any axis) must disable backface culling so
            // both sides render - used by brewing stand bottles, banners, item frames, etc.
            boolean twoSided = x0 == x1 || y0 == y1 || z0 == z1;

            for (Map.Entry<String, ModelFace> entry : element.getFaces().entrySet()) {
                Face blockFace = Face.fromName(entry.getKey());
                if (blockFace == null) continue;

                ModelFace face = entry.getValue();
                PixelBuffer texture = ctm.resolve(blockFace, face.getTexture())
                    .orElseGet(() -> faceTextures.get(face.getTexture()));
                if (texture == null) continue;

                int uvLockTurns = uvLock ? uvLockQuarterTurns(blockFace, variantRotationX, variantRotationY) : 0;
                Vector2f[] uv = resolveFaceUv(face, blockFace, element, uvLockTurns);
                Vector3f[] corners = CornerPhase.BAKERY.corners(blockFace, new Box(x0, y0, z0, x1, y1, z1));
                Vector3f faceNormal = blockFace.normal();

                if (elementTransform != null) {
                    for (int i = 0; i < corners.length; i++)
                        corners[i] = corners[i].transform(elementTransform);
                    faceNormal = faceNormal.transformNormal(normalTransform).normalize();
                }

                int faceTint = face.getTintIndex() >= 0 ? tintedArgb : untintedArgb;
                // Faces sampling partial-alpha texels (glass, ice, slime/honey shells) are flagged
                // translucent so the rasterizer sorts them back-to-front. A block with stacked
                // translucent layers (honey_block's #down outer over its #up inner) emits them in
                // model order, which can be front-to-back; without the sort the farther inner face
                // is depth-rejected and only one layer blends instead of vanilla's two. A 26.1 model
                // may also flag a texture force_translucent; those faces join the pass even when the
                // sprite is fully opaque - vanilla glass already qualifies via its alpha, so this is
                // additive for pack content only.
                boolean translucent = BoneKit.faceHasPartialAlpha(uv, texture)
                    || forceTranslucentRefs.contains(face.getTexture());
                addQuad(
                    triangles,
                    corners[0], corners[1], corners[2], corners[3],
                    uv[0], uv[1], uv[2], uv[3],
                    texture, faceTint,
                    faceNormal,
                    !twoSided,
                    translucent,
                    element.isShade(),
                    element.getLightEmission(),
                    false
                );
            }
        }

        return triangles;
    }

    /**
     * Resolves the four UV corners (TL, BL, BR, TR) for a face in normalized {@code [0, 1]}
     * space. When the face supplies an explicit UV rectangle in 0-16 space it is used directly;
     * otherwise the rectangle is delegated to {@link Unwrap.Element#rect}. Face rotation of
     * {@code 90}/{@code 180}/{@code 270} is applied by
     * {@link Vector4f#toUvCorners(float, float, int, boolean)} via a forward cyclic shift
     * matching vanilla's {@code Quadrant}-based UV rotation.
     * <p>
     * {@code uvLockQuarterTurnsCw} applies a blockstate {@code uvlock} rotation by spinning the
     * resolved UV coordinates about the <b>texture center</b> {@code (0.5, 0.5)} rather than the
     * authored rectangle's center. For a full-face square UV the two are identical, but for a
     * partial face (e.g. a stair step's {@code [8, 0, 16, 16]} top) only the texture-center spin
     * keeps the actual texels world-locked the way vanilla's {@code getUVLockTransform} does -
     * a {@code Vector4f#toUvCorners} {@code faceRotation} would rotate within the rectangle and
     * shift the sampled texels.
     */
    private static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull ModelFace face,
        @NotNull Face blockFace,
        @NotNull ModelElement element,
        int uvLockQuarterTurnsCw
    ) {
        Vector4f rect = face.getUv()
            .orElseGet(() -> new Unwrap.Element(Box.of(element.getFrom(), element.getTo())).rect(blockFace));
        Vector2f[] corners = rect.toUvCorners(
            VANILLA_PIXEL_UNITS_PER_BLOCK,
            VANILLA_PIXEL_UNITS_PER_BLOCK,
            face.getRotation(),
            false
        );
        return CornerPhase.BAKERY.permuteUv(blockFace, rotateUvAboutCenter(corners, uvLockQuarterTurnsCw));
    }

    /**
     * Spins the four UV coordinates clockwise about the texture center {@code (0.5, 0.5)} by
     * {@code quarterTurns} right angles, keeping each value in its TL/BL/BR/TR vertex slot so the
     * texture content rotates while the vertex winding is untouched. {@code quarterTurns == 0}
     * returns the input array unchanged.
     */
    private static @NotNull Vector2f @NotNull [] rotateUvAboutCenter(@NotNull Vector2f @NotNull [] corners, int quarterTurns) {
        int k = ((quarterTurns % 4) + 4) % 4;
        if (k == 0) return corners;
        Vector2f[] out = new Vector2f[corners.length];
        for (int i = 0; i < corners.length; i++) {
            float u = corners[i].x();
            float v = corners[i].y();
            for (int t = 0; t < k; t++) {
                // Clockwise quarter turn about (0.5, 0.5): (u, v) -> (0.5 + (v - 0.5), 0.5 - (u - 0.5)).
                float nu = 0.5f + (v - 0.5f);
                float nv = 0.5f - (u - 0.5f);
                u = nu;
                v = nv;
            }
            out[i] = new Vector2f(u, v);
        }
        return out;
    }

    /**
     * Returns the {@code uvlock} UV rotation (in clockwise quarter turns) for a face under a
     * blockstate variant rotation. X is checked first because vanilla never combines X and Y on a
     * single {@code uvlock} part.
     * <p>
     * An X rotation tips each face onto a new world direction, and vanilla's per-direction
     * {@code uvlock} bake ({@code BlockMath.getFaceTransformation} composed through
     * {@code FaceBakery}) counter-rotates that face's UV so the texture stays world-aligned. The
     * per-face correction is a property of the face direction and the X angle alone (it holds
     * identically for a zero-thickness {@code north}/{@code south} billboard - the up/down planes of
     * {@code vine}/{@code sculk_vein}/{@code glow_lichen}/{@code resin_clump} and the single-face
     * {@code mushroom_block}/{@code mushroom_stem} skins - and for a thick box like a wall button,
     * whose six faces each need their own turn). The table below is the reconstructed result. Each
     * cell shows the human-readable correction and, in parentheses, the value this method returns -
     * the number of <b>clockwise</b> quarter turns {@link #rotateUvAboutCenter} applies to the UV
     * coordinates. Note {@code 90 CW} maps to {@code 3} and {@code 90 CCW} to {@code 1}, because
     * {@code rotateUvAboutCenter}'s clockwise UV-coordinate spin rotates the sampled texture content
     * the opposite way:
     * <pre>
     *          UP        DOWN      NORTH     SOUTH     EAST        WEST
     *   x:90   180 (2)   - (0)     180 (2)   - (0)     90 CW (3)   90 CCW (1)
     *   x:180  - (0)     - (0)     180 (2)   180 (2)   180 (2)     180 (2)
     *   x:270  - (0)     180 (2)   180 (2)   - (0)     90 CCW (1)  90 CW (3)
     * </pre>
     * For a single-sided plane only the camera-facing derived face survives back-face culling, so
     * the up plane shows the {@code north}-derived {@code 180 (2)} and the down plane the
     * {@code south}-derived {@code - (0)}.
     * <p>
     * A Y rotation instead spins the {@code up}/{@code down} faces in place (stairs, walls, fence
     * gates); their UV is counter-rotated by the variant angle. {@code down} is viewed from the
     * opposite side so it takes the opposite sense. Side faces keep their vertical axis under Y and
     * need no correction.
     */
    private static int uvLockQuarterTurns(@NotNull Face face, int variantRotationX, int variantRotationY) {
        if (variantRotationX != 0)
            return switch (variantRotationX) {
                case 90 -> switch (face) {
                    case UP, NORTH -> 2;
                    case EAST -> 3;
                    case WEST -> 1;
                    default -> 0; // DOWN, SOUTH
                };
                case 180 -> switch (face) {
                    case NORTH, SOUTH, EAST, WEST -> 2;
                    default -> 0; // UP, DOWN
                };
                case 270 -> switch (face) {
                    case DOWN, NORTH -> 2;
                    case EAST -> 1;
                    case WEST -> 3;
                    default -> 0; // UP, SOUTH
                };
                default -> 0;
            };
        int turns = variantRotationY / 90;
        return switch (face) {
            case UP -> -turns;
            case DOWN -> turns;
            default -> 0;
        };
    }

    /**
     * Adds two triangles describing a quad defined by four CCW-ordered vertices to the given list.
     * <p>
     * Vertex order is top-left, bottom-left, bottom-right, top-right when viewed from the
     * positive normal direction, matching vanilla's {@code FaceInfo} convention. UV mapping is
     * fixed to the full {@code [0, 1]} rectangle.
     */
    private static void addQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f topLeft,
        @NotNull Vector3f bottomLeft,
        @NotNull Vector3f bottomRight,
        @NotNull Vector3f topRight,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        boolean cullBackFaces,
        boolean glinted
    ) {
        addQuad(out,
            topLeft, bottomLeft, bottomRight, topRight,
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f),
            texture, tintArgb, normal, cullBackFaces, false, true, 0, glinted);
    }

    /**
     * Terminal quad emitter: splits a CCW quad into its two triangles and appends them to
     * {@code out}, baking the inventory shade factor and {@link SurfaceTraits surface traits} into
     * each so the rasterizer needs no per-triangle face lookup.
     * <p>
     * {@link Lighting#inventory} resolves the dominant cardinal of the (post-element-rotation) face
     * normal and returns the matching vanilla {@code Lighting.ITEMS_3D} approximation -
     * cardinal-aligned faces reproduce the per-face values on {@link Face#lighting}
     * ({@code 1.0}/{@code 0.5}/{@code 0.6}/{@code 0.8}), and faces tipped by {@code element.rotation}
     * resolve to the closest cardinal's shade. When {@code directionalLight} is {@code false} (a face
     * of a {@code "shade": false} element) the shade is instead {@link Shading#DISABLED}, so the
     * relight pass renders it full-bright to match vanilla's in-world {@code getShade(dir, false) == 1.0}.
     *
     * @param cullBackFaces whether the rasterizer culls the away-facing side of these triangles
     * @param translucent whether the face samples partial-alpha texels and must sort back-to-front
     * @param directionalLight whether the face receives {@code ITEMS_3D} shading, or full-bright when
     *     {@code false} (a {@code "shade": false} element)
     * @param lightEmission the element's {@code light_emission} level {@code 0-15}: raises the baked
     *     shade floor to {@code emission / 15}, full-bright at {@code 15};
     *     {@code 0} leaves the shade untouched. The floor is a real {@code [0,1]} scalar (not the
     *     {@link Shading#DISABLED} sentinel, which renders black on the un-relit Held3D path)
     * @param glinted whether the face is worn-armor geometry receiving the enchantment foil
     */
    private static void addQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f topLeft,
        @NotNull Vector3f bottomLeft,
        @NotNull Vector3f bottomRight,
        @NotNull Vector3f topRight,
        @NotNull Vector2f uvTL,
        @NotNull Vector2f uvBL,
        @NotNull Vector2f uvBR,
        @NotNull Vector2f uvTR,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        boolean cullBackFaces,
        boolean translucent,
        boolean directionalLight,
        int lightEmission,
        boolean glinted
    ) {
        // Shade baked per triangle (see the javadoc): inventory cardinal shade, or full-bright
        // DISABLED for a "shade": false element. light_emission folds on top by
        // raising the shade FLOOR to emission/15 (capped at 1.0 for the full 15) - NOT by mapping to
        // the DISABLED sentinel, which renders BLACK on the no-relight Held3D item path (apply(-1)).
        // So an emissive shade:true face bakes a real [0,1] scalar that renders full-bright on Held3D
        // and, on the block-icon path, is recomputed by Shading.relightForItems3d (the floor is a
        // documented Held3D-side effect there). Vanilla's only emissive elements are shade:false, so
        // they take the DISABLED branch below unchanged - the fold is a no-op on vanilla.
        float shading;
        if (!directionalLight) {
            shading = Shading.DISABLED;
        } else if (lightEmission > 0) {
            float emissionFloor = Math.min(lightEmission, FULL_LIGHT_EMISSION) / (float) FULL_LIGHT_EMISSION;
            shading = Math.max(Lighting.inventory(normal), emissionFloor);
        } else {
            shading = Lighting.inventory(normal);
        }
        SurfaceTraits traits = new SurfaceTraits(cullBackFaces, false, translucent, glinted);
        out.add(new VisibleTriangle(topLeft, bottomLeft, bottomRight, uvTL, uvBL, uvBR, texture, tintArgb, normal, shading, traits, null));
        out.add(new VisibleTriangle(topLeft, bottomRight, topRight, uvTL, uvBR, uvTR, texture, tintArgb, normal, shading, traits, null));
    }

}
