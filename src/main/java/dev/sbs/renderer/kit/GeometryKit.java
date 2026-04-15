package dev.sbs.renderer.kit;

import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.geometry.ModelGrid;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector2f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
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
public class GeometryKit {

    /**
     * Builds a list of 12 triangles (2 per face) describing a unit cube centered at the origin
     * with the given per-face textures.
     * <p>
     * Texture array order must match the declaration order of {@link BlockFace} (DOWN, UP, NORTH,
     * SOUTH, WEST, EAST). Every face uses the full {@code [0, 1]} UV rectangle.
     *
     * @param faces the six face textures in canonical {@link BlockFace} order
     * @param tintArgb the ARGB tint applied to every face, or {@code 0xFFFFFFFF} for no tint
     * @return the 12-triangle list, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> unitCube(
        @NotNull PixelBuffer @NotNull [] faces,
        int tintArgb
    ) {
        return box(
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
     * @param faces the six face textures in canonical {@link BlockFace} order
     * @param tintArgb the ARGB tint applied to every face
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> box(
        @NotNull Vector3f min,
        @NotNull Vector3f max,
        @NotNull PixelBuffer @NotNull [] faces,
        int tintArgb
    ) {
        if (faces.length != 6)
            throw new IllegalArgumentException("Box requires exactly 6 face textures");

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        float x0 = min.x(), y0 = min.y(), z0 = min.z();
        float x1 = max.x(), y1 = max.y(), z1 = max.z();

        for (BlockFace face : BlockFace.values()) {
            Vector3f[] corners = face.corners(x0, y0, z0, x1, y1, z1);
            addQuad(
                triangles,
                corners[0], corners[1], corners[2], corners[3],
                faces[face.ordinal()], tintArgb,
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
     *     {@link dev.sbs.renderer.asset.model.ItemModelData} or
     *     {@link dev.sbs.renderer.asset.model.BlockModelData}
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
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (ModelElement element : elements) {
            float x0 = element.getFrom()[0] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float y0 = element.getFrom()[1] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float z0 = element.getFrom()[2] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float x1 = element.getTo()[0] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float y1 = element.getTo()[1] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
            float z1 = element.getTo()[2] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;

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
                    float ox = rawOrigin[0] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
                    float oy = rawOrigin[1] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;
                    float oz = rawOrigin[2] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - 0.5f;

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
                        elementTransform = toOrigin.multiply(rotation).multiply(scale).multiply(fromOrigin);
                    } else {
                        elementTransform = toOrigin.multiply(rotation).multiply(fromOrigin);
                    }
                    normalTransform = rotation;
                }
            }

            // Flat planes (zero thickness on any axis) must disable backface culling so
            // both sides render — used by brewing stand bottles, banners, item frames, etc.
            boolean twoSided = x0 == x1 || y0 == y1 || z0 == z1;

            for (Map.Entry<String, ModelFace> entry : element.getFaces().entrySet()) {
                BlockFace blockFace = BlockFace.fromName(entry.getKey());
                if (blockFace == null) continue;

                ModelFace face = entry.getValue();
                PixelBuffer texture = faceTextures.get(face.getTexture());
                if (texture == null) continue;

                Vector2f[] uv = resolveFaceUv(face, blockFace, element);
                Vector3f[] corners = blockFace.corners(x0, y0, z0, x1, y1, z1);
                Vector3f faceNormal = blockFace.normal();

                if (elementTransform != null) {
                    for (int i = 0; i < corners.length; i++)
                        corners[i] = Vector3f.transform(corners[i], elementTransform);
                    faceNormal = Vector3f.normalize(Vector3f.transformNormal(faceNormal, normalTransform));
                }

                addQuad(
                    triangles,
                    corners[0], corners[1], corners[2], corners[3],
                    uv[0], uv[1], uv[2], uv[3],
                    texture, tintArgb,
                    faceNormal,
                    !twoSided
                );
            }
        }

        return triangles;
    }

    /**
     * Resolves the four UV corners (TL, BL, BR, TR) for a face in normalized {@code [0, 1]}
     * space. When the face supplies an explicit UV rectangle in 0-16 space it is converted
     * directly; otherwise the UV is delegated to {@link BlockFace#defaultUv}. Face rotation of
     * {@code 90}/{@code 180}/{@code 270} rotates the corners clockwise via a forward cyclic
     * shift matching vanilla's {@code Quadrant}-based UV rotation.
     */
    private static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull ModelFace face,
        @NotNull BlockFace blockFace,
        @NotNull ModelElement element
    ) {
        Vector2f[] corners;
        if (face.getUv().isPresent()) {
            float[] raw = face.getUv().get();
            corners = BlockFace.uvCorners(
                raw[0] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK,
                raw[1] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK,
                raw[2] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK,
                raw[3] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK
            );
        } else {
            corners = blockFace.defaultUv(element.getFrom(), element.getTo());
        }

        int rotation = ((face.getRotation() % 360) + 360) % 360;
        int shifts = rotation / 90;
        for (int i = 0; i < shifts; i++) {
            Vector2f first = corners[0];
            corners[0] = corners[1];
            corners[1] = corners[2];
            corners[2] = corners[3];
            corners[3] = first;
        }
        return corners;
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
        float shading = 1f;
        out.add(new VisibleTriangle(topLeft, bottomLeft, bottomRight, uvTL, uvBL, uvBR, texture, tintArgb, normal, shading, cullBackFaces));
        out.add(new VisibleTriangle(topLeft, bottomRight, topRight, uvTL, uvBR, uvTR, texture, tintArgb, normal, shading, cullBackFaces));
    }

}
