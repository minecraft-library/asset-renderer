package dev.sbs.renderer.draw;

import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.math.Vector2f;
import dev.sbs.renderer.math.Vector3f;
import dev.sbs.renderer.model.asset.ModelElement;
import dev.sbs.renderer.model.asset.ModelFace;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
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
        float x0 = min.getX(), y0 = min.getY(), z0 = min.getZ();
        float x1 = max.getX(), y1 = max.getY(), z1 = max.getZ();

        for (BlockFace face : BlockFace.values()) {
            Vector3f[] corners = face.corners(x0, y0, z0, x1, y1, z1);
            addQuad(
                triangles,
                corners[0], corners[1], corners[2], corners[3],
                faces[face.ordinal()], tintArgb,
                face.normal(),
                face.ordinal()
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
     * Element-level rotation ({@link ModelElement.ElementRotation}) is not yet supported and is
     * ignored by this first pass. Vanilla block items do not rotate elements, which covers the
     * intended use case (held dirt, held stone, held planks).
     *
     * @param elements the fully-resolved element list from an
     *     {@link dev.sbs.renderer.model.asset.ItemModelData} or
     *     {@link dev.sbs.renderer.model.asset.BlockModelData}
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
        int renderPriority = 0;

        for (ModelElement element : elements) {
            float x0 = element.getFrom()[0] / 16f - 0.5f;
            float y0 = element.getFrom()[1] / 16f - 0.5f;
            float z0 = element.getFrom()[2] / 16f - 0.5f;
            float x1 = element.getTo()[0] / 16f - 0.5f;
            float y1 = element.getTo()[1] / 16f - 0.5f;
            float z1 = element.getTo()[2] / 16f - 0.5f;

            for (Map.Entry<String, ModelFace> entry : element.getFaces().entrySet()) {
                BlockFace blockFace = BlockFace.fromName(entry.getKey());
                if (blockFace == null) continue;

                ModelFace face = entry.getValue();
                PixelBuffer texture = faceTextures.get(face.getTexture());
                if (texture == null) continue;

                Vector2f[] uv = resolveFaceUv(face, blockFace, element);
                Vector3f[] corners = blockFace.corners(x0, y0, z0, x1, y1, z1);
                addQuad(
                    triangles,
                    corners[0], corners[1], corners[2], corners[3],
                    uv[0], uv[1], uv[2], uv[3],
                    texture, tintArgb,
                    blockFace.normal(),
                    renderPriority++
                );
            }
        }

        return triangles;
    }

    /**
     * Resolves the four UV corners (TL, TR, BR, BL) for a face in normalized {@code [0, 1]}
     * space. When the face supplies an explicit UV rectangle in 0-16 space it is converted
     * directly; otherwise the UV is delegated to {@link BlockFace#defaultUv}. Face rotation of
     * {@code 90}/{@code 180}/{@code 270} rotates the corners clockwise.
     */
    private static @NotNull Vector2f @NotNull [] resolveFaceUv(
        @NotNull ModelFace face,
        @NotNull BlockFace blockFace,
        @NotNull ModelElement element
    ) {
        Vector2f[] corners;
        if (face.getUv().isPresent()) {
            float[] raw = face.getUv().get();
            corners = BlockFace.uvCorners(raw[0] / 16f, raw[1] / 16f, raw[2] / 16f, raw[3] / 16f);
        } else {
            corners = blockFace.defaultUv(element.getFrom(), element.getTo());
        }

        int rotation = ((face.getRotation() % 360) + 360) % 360;
        int shifts = rotation / 90;
        for (int i = 0; i < shifts; i++) {
            Vector2f last = corners[3];
            corners[3] = corners[2];
            corners[2] = corners[1];
            corners[1] = corners[0];
            corners[0] = last;
        }
        return corners;
    }

    /**
     * Adds two triangles describing a quad defined by four CCW-ordered vertices to the given list.
     * <p>
     * Vertex order is top-left, top-right, bottom-right, bottom-left when viewed from the positive
     * normal direction. UV mapping is fixed to the full {@code [0, 1]} rectangle.
     */
    private static void addQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f topLeft,
        @NotNull Vector3f topRight,
        @NotNull Vector3f bottomRight,
        @NotNull Vector3f bottomLeft,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        int renderPriority
    ) {
        addQuad(out,
            topLeft, topRight, bottomRight, bottomLeft,
            new Vector2f(0f, 0f), new Vector2f(1f, 0f), new Vector2f(1f, 1f), new Vector2f(0f, 1f),
            texture, tintArgb, normal, renderPriority);
    }

    /**
     * Adds two triangles describing a quad with explicit UV corners. The vertex and UV order
     * follow the same CCW (top-left, top-right, bottom-right, bottom-left) convention as the
     * no-UV overload.
     */
    private static void addQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f topLeft,
        @NotNull Vector3f topRight,
        @NotNull Vector3f bottomRight,
        @NotNull Vector3f bottomLeft,
        @NotNull Vector2f uvTL,
        @NotNull Vector2f uvTR,
        @NotNull Vector2f uvBR,
        @NotNull Vector2f uvBL,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        int renderPriority
    ) {
        float shading = 1f;
        out.add(new VisibleTriangle(topLeft, topRight, bottomRight, uvTL, uvTR, uvBR, texture, tintArgb, normal, shading, renderPriority));
        out.add(new VisibleTriangle(topLeft, bottomRight, bottomLeft, uvTL, uvBR, uvBL, texture, tintArgb, normal, shading, renderPriority));
    }

}
