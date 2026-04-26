package lib.minecraft.renderer.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.options.FluidOptions;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Builds the triangle list for a single fluid cube.
 * <p>
 * Vanilla fluid rendering is entirely algorithmic - there is no {@code block/water.json} element
 * list to walk. The geometry is a 1x1x1 cube with optionally sloped top (four independent corner
 * heights), flow-direction-rotated UVs on the side faces, and the still texture on top and bottom.
 * This kit sits between the renderer's texture lookup and the triangle rasterizer so
 * {@code FluidRenderer.Isometric3D} stays a thin dispatch layer.
 * <p>
 * The top face is emitted as a non-planar quad split along the NW-SE diagonal with a per-triangle
 * normal computed from the cross product of its edges. Side faces are planar quads with two
 * top-corner Y values taken from {@link FluidOptions.CornerHeights}; the V coordinate at each top
 * corner is set to {@code 1 - heightFraction} so a partial-height fluid appears to fill up from
 * the bottom of the texture rather than squishing it. When a flow direction is supplied the side
 * faces sample the flow texture with UVs rotated around {@code (0.5, 0.5)} by the flow angle; when
 * absent they fall back to the still texture.
 */
@UtilityClass
public class FluidGeometryKit {

    private static final float CUBE_HALF = 0.5f;
    private static final float UV_CENTRE = 0.5f;
    private static final float SHADING = 1f;

    /**
     * Builds the fluid cube triangle list.
     *
     * @param top the four top-face corner heights in block space {@code [0, 1]}
     * @param still the current-frame still texture
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
        addFlatQuad(triangles, pSWb, pNWb, pNEb, pSEb,
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f),
            still, argbTint, new Vector3f(0f, -1f, 0f));

        PixelBuffer sideTex = flowAngleRadians.isPresent() ? flow : still;

        addSide(triangles, pNEt, pNEb, pNWb, pNWt, top.ne(), top.nw(),
            sideTex, argbTint, new Vector3f(0f, 0f, -1f), flowAngleRadians);
        addSide(triangles, pSWt, pSWb, pSEb, pSEt, top.sw(), top.se(),
            sideTex, argbTint, new Vector3f(0f, 0f, +1f), flowAngleRadians);
        addSide(triangles, pNWt, pNWb, pSWb, pSWt, top.nw(), top.sw(),
            sideTex, argbTint, new Vector3f(-1f, 0f, 0f), flowAngleRadians);
        addSide(triangles, pSEt, pSEb, pNEb, pNEt, top.se(), top.ne(),
            sideTex, argbTint, new Vector3f(+1f, 0f, 0f), flowAngleRadians);

        return triangles;
    }

    /**
     * Emits the top face as two triangles split along the NW-SE diagonal. Per-triangle normals
     * are computed from edge cross products so a sloped top shades correctly.
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

        out.add(new VisibleTriangle(nw, sw, se, uvNW, uvSW, uvSE, texture, argbTint, normal1, SHADING, true));
        out.add(new VisibleTriangle(nw, se, ne, uvNW, uvSE, uvNE, texture, argbTint, normal2, SHADING, true));
    }

    /**
     * Emits a side face with two potentially different top-corner heights. UV V coords at the top
     * corners follow {@code 1 - heightFraction} so the sprite fills up from the bottom as the
     * fluid gets taller. When {@code flowAngleRadians} is present the UV corners are rotated
     * around {@code (0.5, 0.5)} by the given angle.
     */
    private static void addSide(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f pTL, @NotNull Vector3f pBL, @NotNull Vector3f pBR, @NotNull Vector3f pTR,
        float topLeftHeight, float topRightHeight,
        @NotNull PixelBuffer texture, int argbTint, @NotNull Vector3f normal,
        @NotNull Optional<Float> flowAngleRadians
    ) {
        Vector2f uvTL = new Vector2f(0f, 1f - topLeftHeight);
        Vector2f uvBL = new Vector2f(0f, 1f);
        Vector2f uvBR = new Vector2f(1f, 1f);
        Vector2f uvTR = new Vector2f(1f, 1f - topRightHeight);

        if (flowAngleRadians.isPresent()) {
            float angle = flowAngleRadians.get();
            uvTL = rotateUvAround(uvTL, UV_CENTRE, UV_CENTRE, angle);
            uvBL = rotateUvAround(uvBL, UV_CENTRE, UV_CENTRE, angle);
            uvBR = rotateUvAround(uvBR, UV_CENTRE, UV_CENTRE, angle);
            uvTR = rotateUvAround(uvTR, UV_CENTRE, UV_CENTRE, angle);
        }

        addFlatQuad(out, pTL, pBL, pBR, pTR, uvTL, uvBL, uvBR, uvTR, texture, argbTint, normal);
    }

    /**
     * Emits a planar quad as two triangles with a shared surface normal. Winding matches
     * {@link BlockModelGeometryKit}'s {@code addQuad} convention (TL, BL, BR, TR viewed from the positive
     * normal direction).
     */
    private static void addFlatQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f pTL, @NotNull Vector3f pBL, @NotNull Vector3f pBR, @NotNull Vector3f pTR,
        @NotNull Vector2f uvTL, @NotNull Vector2f uvBL, @NotNull Vector2f uvBR, @NotNull Vector2f uvTR,
        @NotNull PixelBuffer texture, int argbTint, @NotNull Vector3f normal
    ) {
        out.add(new VisibleTriangle(pTL, pBL, pBR, uvTL, uvBL, uvBR, texture, argbTint, normal, SHADING, true));
        out.add(new VisibleTriangle(pTL, pBR, pTR, uvTL, uvBR, uvTR, texture, argbTint, normal, SHADING, true));
    }

    /**
     * Rotates a UV coordinate around the given centre by the given angle in radians.
     */
    private static @NotNull Vector2f rotateUvAround(@NotNull Vector2f uv, float cx, float cy, float radians) {
        float dx = uv.x() - cx;
        float dy = uv.y() - cy;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new Vector2f(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos);
    }

    /**
     * Computes the unit normal of a triangle from three vertices using the right-hand rule
     * (cross product of edges AB and AC). Assumes CCW winding when viewed from the positive
     * normal direction.
     */
    private static @NotNull Vector3f triangleNormal(@NotNull Vector3f a, @NotNull Vector3f b, @NotNull Vector3f c) {
        Vector3f ab = b.subtract(a);
        Vector3f ac = c.subtract(a);
        return Vector3f.normalize(Vector3f.cross(ab, ac));
    }

}
