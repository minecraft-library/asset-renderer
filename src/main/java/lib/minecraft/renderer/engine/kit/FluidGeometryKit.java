package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Builds the triangle list for a single fluid cube.
 * <p>
 * Vanilla fluid rendering is entirely algorithmic - there is no {@code block/water.json} element
 * list to walk. The geometry is a 1x1x1 cube centred at the origin in the engine's
 * {@code [-0.5, +0.5]} unit-cube space, with an optionally sloped top (four independent corner
 * heights), flow-direction-rotated UVs on the side faces, and the still texture on top and bottom.
 * This kit sits between the renderer's texture lookup and the triangle rasterizer so
 * {@code FluidRenderer.Isometric3D} stays a thin dispatch layer.
 * <p>
 * The top face is emitted as a non-planar quad split along the NW-SE diagonal with a per-triangle
 * normal computed from the cross product of its edges, so a sloped surface shades correctly. Side
 * faces are planar quads whose two top corners take their Y from {@link FluidOptions.CornerHeights};
 * the V coordinate at each top corner is set to {@code 1 - heightFraction} so a partial-height
 * fluid appears to fill up from the bottom of the texture rather than squishing the sprite. When a
 * flow direction is supplied the side faces sample the flow texture with UVs rotated around
 * {@code (0.5, 0.5)} by the flow angle; when absent they fall back to the still texture. Every face
 * is shaded with the flat {@link Lighting#inventory inventory} scalar and marked
 * {@link SurfaceTraits#OPAQUE_BODY}.
 */
@UtilityClass
public class FluidGeometryKit {

    /**
     * Half the edge length of the unit cube - the {@code +/-0.5} extent of every vertex about the
     * origin in the engine's normalised block space.
     */
    private static final float CUBE_HALF = 0.5f;

    /**
     * Centre of the {@code [0, 1]} UV square, used as the pivot when flow-rotating side-face UVs.
     */
    private static final float UV_CENTRE = 0.5f;

    /**
     * Builds the fluid cube triangle list - non-planar top, flat bottom, and four side faces.
     *
     * @param top the four top-face corner heights in block space {@code [0, 1]}; each is shifted
     *     to {@code [-0.5, +0.5]} to place the vertex about the origin
     * @param still the current-frame still texture (top, bottom, and non-flowing side faces)
     * @param flow the current-frame flow texture - consulted only when {@code flowAngleRadians}
     *     is present
     * @param flowAngleRadians the flow direction in radians; when present, side faces use
     *     {@code flow} with UVs rotated by this angle; when empty, side faces use {@code still}
     * @param argbTint the ARGB tint applied to every face ({@code 0xFFFFFFFF} for no tint)
     * @return the triangle list, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildFluidCube(
        @NotNull FluidOptions.CornerHeights top,
        @NotNull PixelBuffer still,
        @NotNull PixelBuffer flow,
        @NotNull Optional<Float> flowAngleRadians,
        int argbTint
    ) {
        // TODO: bottom-face culling when an opaque neighbor block is present (requires scene context).
        // TODO: water-overlay texture on side faces against transparent-block neighbors (requires scene context).
        // TODO: flow texture V is typically scaled to 0..0.5 in vanilla because the 16x32 flow
        //   sprite has two halves; the top half is the "still-looking" portion used on tiles.
        //   Here we sample the full sprite - good enough to show directional flow, but not a
        //   pixel-for-pixel match to vanilla's LiquidBlockRenderer.

        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        float hNW = top.nw() - CUBE_HALF;
        float hNE = top.ne() - CUBE_HALF;
        float hSE = top.se() - CUBE_HALF;
        float hSW = top.sw() - CUBE_HALF;
        float yBot = -CUBE_HALF;

        Vector3f pNWt = new Vector3f(-CUBE_HALF, hNW, -CUBE_HALF);
        Vector3f pNEt = new Vector3f(+CUBE_HALF, hNE, -CUBE_HALF);
        Vector3f pSEt = new Vector3f(+CUBE_HALF, hSE, +CUBE_HALF);
        Vector3f pSWt = new Vector3f(-CUBE_HALF, hSW, +CUBE_HALF);
        Vector3f pNWb = new Vector3f(-CUBE_HALF, yBot, -CUBE_HALF);
        Vector3f pNEb = new Vector3f(+CUBE_HALF, yBot, -CUBE_HALF);
        Vector3f pSEb = new Vector3f(+CUBE_HALF, yBot, +CUBE_HALF);
        Vector3f pSWb = new Vector3f(-CUBE_HALF, yBot, +CUBE_HALF);

        addNonPlanarTop(triangles, pNWt, pSWt, pSEt, pNEt, still, argbTint);

        Vector3f down = Face.DOWN.normal();
        BlockGeometryKit.addQuad(triangles,
            new Vector3f[] {pSWb, pNWb, pNEb, pSEb},
            new Vector2f[] {new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f)},
            still, argbTint, down, Lighting.inventory(down), SurfaceTraits.OPAQUE_BODY, null);

        PixelBuffer sideTex = flowAngleRadians.isPresent() ? flow : still;

        addSide(triangles, pNEt, pNEb, pNWb, pNWt, top.ne(), top.nw(),
            sideTex, argbTint, Face.NORTH.normal(), flowAngleRadians);
        addSide(triangles, pSWt, pSWb, pSEb, pSEt, top.sw(), top.se(),
            sideTex, argbTint, Face.SOUTH.normal(), flowAngleRadians);
        addSide(triangles, pNWt, pNWb, pSWb, pSWt, top.nw(), top.sw(),
            sideTex, argbTint, Face.WEST.normal(), flowAngleRadians);
        addSide(triangles, pSEt, pSEb, pNEb, pNEt, top.se(), top.ne(),
            sideTex, argbTint, Face.EAST.normal(), flowAngleRadians);

        return triangles;
    }

    /**
     * Emits the top face as two triangles split along the NW-SE diagonal - the same corner-0 /
     * corner-2 pair {@link BlockGeometryKit}'s shared quad emitter splits on - with per-triangle
     * normals computed from edge cross products so a sloped top shades correctly.
     * <p>
     * <b>This is the renderer's one quad that does not route through that emitter, and the reason is
     * the geometry rather than the history.</b> A sloped top's four corners are not coplanar, so its
     * two halves face different ways: each carries its own normal and therefore its own shade, and
     * there is no shared per-quad value for the emitter to take. Every other quad in the renderer has
     * one normal and one shade for both triangles, which is exactly what the emitter's signature says.
     */
    private static void addNonPlanarTop(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f nw, @NotNull Vector3f sw, @NotNull Vector3f se, @NotNull Vector3f ne,
        @NotNull PixelBuffer texture, int argbTint
    ) {
        Vector2f uvNW = new Vector2f(0f, 0f);
        Vector2f uvSW = new Vector2f(0f, 1f);
        Vector2f uvSE = new Vector2f(1f, 1f);
        Vector2f uvNE = new Vector2f(1f, 0f);

        Vector3f normal1 = triangleNormal(nw, sw, se);
        Vector3f normal2 = triangleNormal(nw, se, ne);

        out.add(new VisibleTriangle(nw, sw, se, uvNW, uvSW, uvSE, texture, argbTint, normal1, Lighting.inventory(normal1), SurfaceTraits.OPAQUE_BODY));
        out.add(new VisibleTriangle(nw, se, ne, uvNW, uvSE, uvNE, texture, argbTint, normal2, Lighting.inventory(normal2), SurfaceTraits.OPAQUE_BODY));
    }

    /**
     * Emits a single side face, whose two top corners may sit at different heights (a sloped
     * fluid surface). The top-corner V coordinates are set to {@code 1 - height} so the sprite
     * fills up from the bottom edge as the fluid gets taller, rather than the whole sprite being
     * squished into the shorter face. When {@code flowAngleRadians} is present all four UV corners
     * are rotated around the sprite centre {@code (0.5, 0.5)} by the flow angle before the quad is
     * emitted.
     */
    private static void addSide(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f pTL, @NotNull Vector3f pBL, @NotNull Vector3f pBR, @NotNull Vector3f pTR,
        float topLeftHeight, float topRightHeight,
        @NotNull PixelBuffer texture, int argbTint, @NotNull Vector3f normal,
        @NotNull Optional<Float> flowAngleRadians
    ) {
        Vector2f[] uv = {
            new Vector2f(0f, 1f - topLeftHeight),
            new Vector2f(0f, 1f),
            new Vector2f(1f, 1f),
            new Vector2f(1f, 1f - topRightHeight)
        };

        if (flowAngleRadians.isPresent()) {
            float angle = flowAngleRadians.get();
            for (int i = 0; i < uv.length; i++)
                uv[i] = rotateUvAround(uv[i], UV_CENTRE, UV_CENTRE, angle);
        }

        BlockGeometryKit.addQuad(out, new Vector3f[] {pTL, pBL, pBR, pTR}, uv,
            texture, argbTint, normal, Lighting.inventory(normal), SurfaceTraits.OPAQUE_BODY, null);
    }

    /**
     * Rotates a UV coordinate about the centre {@code (cx, cy)} by {@code radians} using a
     * standard 2D rotation of the offset vector.
     *
     * @param uv the UV coordinate to rotate
     * @param cx the pivot X (sprite centre)
     * @param cy the pivot Y (sprite centre)
     * @param radians the rotation angle
     * @return the rotated UV coordinate
     */
    private static @NotNull Vector2f rotateUvAround(@NotNull Vector2f uv, float cx, float cy, float radians) {
        float dx = uv.x() - cx;
        float dy = uv.y() - cy;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new Vector2f(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos);
    }

    /**
     * Computes the unit normal of a triangle from three vertices using the right-hand rule -
     * the normalized cross product of edges {@code AB} and {@code AC}. The result points out of
     * the front face when {@code a}, {@code b}, {@code c} are wound counter-clockwise as viewed
     * from the positive-normal side.
     */
    private static @NotNull Vector3f triangleNormal(@NotNull Vector3f a, @NotNull Vector3f b, @NotNull Vector3f c) {
        Vector3f ab = b.subtract(a);
        Vector3f ac = c.subtract(a);
        return ab.cross(ac).normalize();
    }

}
