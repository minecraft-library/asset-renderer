package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ItemModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.SixFaces;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Generates the canonical triangle lists needed by the engine layer for common 3D shapes.
 * <p>
 * The primary use case is constructing the six-face cube used by every isometric block and head
 * renderer. Each cube face is built as a pair of triangles with the correct CCW winding, UV
 * orientation, and surface normal so that back-face culling and inventory lighting produce the
 * expected result without caller-side fixups. All direction-aware logic - vertex winding, normals,
 * and default UV derivation - lives on {@link BlockFace}.
 */
@UtilityClass
public class BlockGeometryKit {

    /**
     * Edge length of a full block in vanilla model-authoring units. Every vanilla {@code block/}
     * and {@code item/} model JSON authors coordinates against this grid - element
     * {@code from} / {@code to} values of {@code [0, 0, 0]} and {@code [16, 16, 16]} describe a
     * full unit cube, face UVs run from {@code 0} to {@code 16}, and {@code display.*.translation}
     * values are in the same space. This kit and its consumers ({@link BlockFace#defaultUv},
     * {@link BlockRenderer}, item renderer's display-transform path) divide by this constant to
     * normalise into the engine's {@code [-0.5, +0.5]} unit-cube space before projection.
     */
    public static final float VANILLA_PIXEL_UNITS_PER_BLOCK = 16f;

    /**
     * Builds a list of 12 triangles (2 per face) describing a unit cube centered at the origin
     * with the given per-face textures.
     * <p>
     * Every face uses the full {@code [0, 1]} UV rectangle.
     *
     * @param faces the six face textures, keyed by {@link BlockFace} direction
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
     * @param faces the six face textures, keyed by {@link BlockFace} direction
     * @param tintArgb the ARGB tint applied to every face
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildBoxTriangles(
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull SixFaces faces,
        int tintArgb
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        Box box = Box.of(min, max);

        for (BlockFace face : BlockFace.CACHED_VALUES) {
            Vector3f[] corners = face.corners(box);
            addQuad(
                triangles,
                corners[0], corners[1], corners[2], corners[3],
                faces.byFace(face), tintArgb,
                face.normal()
            );
        }

        return triangles;
    }

    /**
     * Builds a triangle list from a resolved list of {@link ModelElement element boxes} using
     * pre-loaded face textures. Suitable for the held-item 3D path where the caller has already
     * walked the model's {@code #var} bindings and loaded every unique texture.
     * <p>
     * Each element's {@code from}/{@code to} bounds are converted from vanilla's 0-16 space to
     * the engine's normalized {@code [-0.5, +0.5]} cube space, matching the convention used by
     * {@link #unitCube}. Faces missing from an element's {@code faces} map - or carrying an
     * unrecognized direction name - are skipped. Face UV rectangles are converted from 0-16 to
     * {@code [0, 1]} space when present, otherwise derived via {@link BlockFace#defaultUv}. Face
     * {@code rotation} ({@code 0}/{@code 90}/{@code 180}/{@code 270} degrees) rotates the UV
     * corners clockwise.
     * <p>
     * Element-level rotation ({@link ModelElement.ElementRotation}) is supported: each element's
     * vertices are rotated around the specified origin on a single axis by the given angle. When
     * the {@code rescale} flag is set, the perpendicular axes are scaled by {@code 1/cos(angle)}
     * to preserve the element's axis-aligned footprint (used by cross-shaped plants).
     *
     * @param elements the fully-resolved element list from an
     *     {@link ItemModelData} or
     *     {@link BlockModelData}
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
        return buildFromElements(elements, faceTextures, tintArgb, tintArgb);
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
        return buildFromElements(elements, faceTextures, tintedArgb, untintedArgb, 0, false);
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
     * Only the Y axis is handled: blockstate variants drive horizontally-oriented blocks
     * (stairs, walls, fence gates) whose default-state {@code uvlock} is a pure Y rotation, and
     * a Y rotation keeps every side face's vertical axis vertical so only {@code up}/{@code down}
     * need correcting.
     *
     * @param variantRotationY the variant's whole-model Y rotation in degrees (0/90/180/270)
     * @param uvLock whether the blockstate variant requested {@code uvlock}
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFromElements(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull Map<String, PixelBuffer> faceTextures,
        int tintedArgb,
        int untintedArgb,
        int variantRotationY,
        boolean uvLock
    ) {
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
                BlockFace blockFace = BlockFace.fromName(entry.getKey());
                if (blockFace == null) continue;

                ModelFace face = entry.getValue();
                PixelBuffer texture = faceTextures.get(face.getTexture());
                if (texture == null) continue;

                int uvLockTurns = uvLock ? uvLockQuarterTurns(blockFace, variantRotationY) : 0;
                Vector2f[] uv = resolveFaceUv(face, blockFace, element, uvLockTurns);
                Vector3f[] corners = blockFace.corners(new Box(x0, y0, z0, x1, y1, z1));
                Vector3f faceNormal = blockFace.normal();

                if (elementTransform != null) {
                    for (int i = 0; i < corners.length; i++)
                        corners[i] = Vector3f.transform(corners[i], elementTransform);
                    faceNormal = Vector3f.normalize(Vector3f.transformNormal(faceNormal, normalTransform));
                }

                int faceTint = face.getTintIndex() >= 0 ? tintedArgb : untintedArgb;
                addQuad(
                    triangles,
                    corners[0], corners[1], corners[2], corners[3],
                    uv[0], uv[1], uv[2], uv[3],
                    texture, faceTint,
                    faceNormal,
                    !twoSided
                );
            }
        }

        return triangles;
    }

    /**
     * Resolves the four UV corners (TL, BL, BR, TR) for a face in normalized {@code [0, 1]}
     * space. When the face supplies an explicit UV rectangle in 0-16 space it is used directly;
     * otherwise the rectangle is delegated to {@link BlockFace#defaultUv}. Face rotation of
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
        @NotNull BlockFace blockFace,
        @NotNull ModelElement element,
        int uvLockQuarterTurnsCw
    ) {
        Vector4f rect = face.getUv()
            .orElseGet(() -> blockFace.defaultUv(Box.of(element.getFrom(), element.getTo())));
        Vector2f[] corners = rect.toUvCorners(
            VANILLA_PIXEL_UNITS_PER_BLOCK,
            VANILLA_PIXEL_UNITS_PER_BLOCK,
            face.getRotation(),
            false
        );
        return rotateUvAboutCenter(corners, uvLockQuarterTurnsCw);
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
     * blockstate variant Y rotation. The {@code up} and {@code down} faces are perpendicular to
     * the Y axis and would otherwise spin with the rotated model; rotating their UV about the
     * texture center by the variant angle keeps the texture aligned to the world grid. {@code down}
     * is viewed from the opposite side so it takes the opposite sense. Side faces keep vertical-up
     * under a Y rotation and need no correction.
     */
    private static int uvLockQuarterTurns(@NotNull BlockFace face, int variantRotationY) {
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
        @NotNull Vector3f normal
    ) {
        addQuad(out,
            topLeft, bottomLeft, bottomRight, topRight,
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f),
            texture, tintArgb, normal);
    }

    /**
     * Adds two triangles describing a quad with explicit UV corners. The vertex and UV order
     * follow the same CCW (top-left, bottom-left, bottom-right, top-right) convention as the
     * no-UV overload, matching vanilla's {@code FaceInfo} vertex order.
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
        @NotNull Vector3f normal
    ) {
        addQuad(out, topLeft, bottomLeft, bottomRight, topRight, uvTL, uvBL, uvBR, uvTR, texture, tintArgb, normal, true);
    }

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
        boolean cullBackFaces
    ) {
        // Bake the inventory shade factor into each triangle so the rasterizer can apply shading
        // directly without a per-triangle face lookup. {@link RenderEngine#computeInventoryLighting}
        // resolves the dominant cardinal of the (post-element-rotation) face normal and returns
        // the matching {@code Lighting.ITEMS_3D} approximation - cardinal-aligned faces produce
        // exactly the per-face values from {@link BlockFace#lighting} (1.0/0.5/0.6/0.8), and faces
        // rotated by {@code element.rotation} resolve to the closest cardinal's shade.
        float shading = RenderEngine.computeInventoryLighting(normal);
        out.add(new VisibleTriangle(topLeft, bottomLeft, bottomRight, uvTL, uvBL, uvBR, texture, tintArgb, normal, shading, cullBackFaces, false));
        out.add(new VisibleTriangle(topLeft, bottomRight, topRight, uvTL, uvBR, uvTR, texture, tintArgb, normal, shading, cullBackFaces, false));
    }

}
