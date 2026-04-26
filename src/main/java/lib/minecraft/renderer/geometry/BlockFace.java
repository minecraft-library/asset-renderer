package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.kit.BlockModelGeometryKit;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The six cardinal face directions of an axis-aligned Minecraft box element.
 * <p>
 * Each constant knows its lowercase direction name (the key used in vanilla block and item model
 * JSON plus Bedrock per-face {@code cube.uv} overrides), its four vertex indices into the
 * canonical 8-corner box, its outward unit normal, a {@link BlockLayout} plus {@link EntityLayout}
 * that capture the data each {@link #defaultUv defaultUv} overload needs to project face geometry
 * into a UV rectangle without a per-face {@code switch}, and an inventory {@link #lighting shade
 * factor}. The box vertex layout is:
 * <pre>
 * 0: (x0, y0, z0)   4: (x0, y0, z1)
 * 1: (x1, y0, z0)   5: (x1, y0, z1)
 * 2: (x1, y1, z0)   6: (x1, y1, z1)
 * 3: (x0, y1, z0)   7: (x0, y1, z1)
 * </pre>
 * The four indices per face are wound top-left, bottom-left, bottom-right, top-right when viewed
 * from the outward normal direction (CCW), matching vanilla's {@code FaceInfo} vertex order and
 * the convention used by {@link BlockModelGeometryKit}'s triangle builders.
 * <p>
 * Two UV pipelines share this enum through two companion records:
 * <ul>
 * <li>{@link BlockLayout} feeds {@link #defaultUv(Box)} for Java block-model and held-item
 *     rendering, where every face samples its own texture from the cross-section of the
 *     element bounds.</li>
 * <li>{@link EntityLayout} feeds {@link #defaultUv(int[], float[], float, float, boolean)} for
 *     Bedrock entity-cube rendering, where all six faces share one skin image laid out in a
 *     standard strip.</li>
 * </ul>
 * The two unwraps disagree on which face lands in which atlas slot (Java puts BACK where
 * Bedrock expects FRONT, and LEFT where Bedrock expects RIGHT) so callers must pick the right
 * overload for their pipeline - crossing them sends front-face pixels to the back face and
 * mirrors right-face pixels onto the left.
 * <p>
 * The {@link #lighting} field carries the shade factor applied to this face under vanilla's
 * {@code Lighting.ITEMS_3D} GUI pose. Note the per-axis values are <b>reversed</b> relative to
 * world-lit block brightness from {@code Direction.getBrightness}: vanilla's inventory pipeline
 * uses two directional lights offset in X so the left-hand (E/W) face ends up brighter than the
 * right-hand (N/S) face after the standard {@code [30, 225, 0]} gui rotation. Rather than
 * replicate the dual-directional light shader, each face carries a pre-baked scalar that
 * approximates the vanilla inventory output ({@code 0.8} for E/W, {@code 0.6} for N/S,
 * {@code 1.0} for UP, {@code 0.5} for DOWN). Callers that have a surface normal rather than a
 * face enum should resolve it via {@link #fromNormal(Vector3f)}.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum BlockFace {

    DOWN(
        "down", new int[]{ 4, 0, 1, 5 }, new Vector3f(0f, -1f, 0f),
        new BlockLayout(0, 2, false, true),
        new EntityLayout(0, 2, 1, 1, 0, 0),
        0.5f
    ),
    UP(
        "up", new int[]{ 3, 7, 6, 2 }, new Vector3f(0f, 1f, 0f),
        new BlockLayout(0, 2, false, false),
        new EntityLayout(0, 2, 0, 1, 0, 0),
        1.0f
    ),
    NORTH(
        "north", new int[]{ 2, 1, 0, 3 }, new Vector3f(0f, 0f, -1f),
        new BlockLayout(0, 1, true, true),
        new EntityLayout(0, 1, 0, 1, 0, 1),
        0.6f
    ),
    SOUTH(
        "south", new int[]{ 7, 4, 5, 6 }, new Vector3f(0f, 0f, 1f),
        new BlockLayout(0, 1, false, true),
        new EntityLayout(0, 1, 1, 2, 0, 1),
        0.6f
    ),
    WEST(
        "west", new int[]{ 3, 0, 4, 7 }, new Vector3f(-1f, 0f, 0f),
        new BlockLayout(2, 1, false, true),
        new EntityLayout(2, 1, 1, 1, 0, 1),
        0.8f
    ),
    EAST(
        "east", new int[]{ 6, 5, 1, 2 }, new Vector3f(1f, 0f, 0f),
        new BlockLayout(2, 1, true, true),
        new EntityLayout(2, 1, 0, 0, 0, 1),
        0.8f
    );

    private final @NotNull String direction;
    private final int @NotNull [] vertexIndices;
    private final @NotNull Vector3f normal;

    @Getter(AccessLevel.NONE)
    private final @NotNull BlockLayout blockLayout;

    @Getter(AccessLevel.NONE)
    private final @NotNull EntityLayout entityLayout;

    /**
     * Shade factor applied to this face under vanilla {@code Lighting.ITEMS_3D} GUI pose. E/W
     * faces are intentionally brighter than N/S to match vanilla's dual-directional light rig
     * under the standard {@code [30, 225, 0]} display rotation - the opposite of world-lit block
     * brightness. Bottom is half-bright, top is fully bright.
     */
    private final float lighting;

    /**
     * Returns the four CCW-ordered (TL, BL, BR, TR) corners of this face on the given axis-aligned
     * {@link Box}, matching vanilla's {@code FaceInfo} vertex order.
     *
     * @param box the bounding box
     * @return the four corner positions, ordered TL, BL, BR, TR
     */
    public @NotNull Vector3f @NotNull [] corners(@NotNull Box box) {
        Vector3f[] cornersOfBox = {
            new Vector3f(box.minX(), box.minY(), box.minZ()), new Vector3f(box.maxX(), box.minY(), box.minZ()),
            new Vector3f(box.maxX(), box.maxY(), box.minZ()), new Vector3f(box.minX(), box.maxY(), box.minZ()),
            new Vector3f(box.minX(), box.minY(), box.maxZ()), new Vector3f(box.maxX(), box.minY(), box.maxZ()),
            new Vector3f(box.maxX(), box.maxY(), box.maxZ()), new Vector3f(box.minX(), box.maxY(), box.maxZ())
        };
        return new Vector3f[]{
            cornersOfBox[this.vertexIndices[0]], cornersOfBox[this.vertexIndices[1]],
            cornersOfBox[this.vertexIndices[2]], cornersOfBox[this.vertexIndices[3]]
        };
    }

    /**
     * Returns the four default UV corners (TL, BL, BR, TR) for this face in normalized
     * {@code [0, 1]} space, derived from the element bounds using vanilla's block-model
     * projection formulas (see {@code FaceBakery.defaultFaceUV}).
     * <p>
     * Block model elements reference an independent texture per face (via their {@code #var}
     * bindings), so every face samples the full {@code [0, 16]} UV rectangle projected onto its
     * cross-section. This overload is used by the block and held-item rendering paths and reads
     * from {@link BlockLayout}.
     *
     * @param element the element bounds in 0-16 space
     * @return the four UV corners, ordered TL, BL, BR, TR
     */
    public @NotNull Vector2f @NotNull [] defaultUv(@NotNull Box element) {
        int uAxis = this.blockLayout.widthAxis();
        int vAxis = this.blockLayout.heightAxis();
        float fromU = axisComponent(element, uAxis, false);
        float toU = axisComponent(element, uAxis, true);
        float fromV = axisComponent(element, vAxis, false);
        float toV = axisComponent(element, vAxis, true);
        float u0 = this.blockLayout.uInverted() ? ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - toU : fromU;
        float u1 = this.blockLayout.uInverted() ? ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - fromU : toU;
        float v0 = this.blockLayout.vInverted() ? ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - toV : fromV;
        float v1 = this.blockLayout.vInverted() ? ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK - fromV : toV;
        return uvRect(u0, v0, u1, v1, ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK, ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK, false);
    }

    private static float axisComponent(@NotNull Box box, int axis, boolean max) {
        return switch (axis) {
            case 0 -> max ? box.maxX() : box.minX();
            case 1 -> max ? box.maxY() : box.minY();
            default -> max ? box.maxZ() : box.minZ();
        };
    }

    /**
     * Returns the four default UV corners (TL, BL, BR, TR) for this face in normalized
     * {@code [0, 1]} space, using the <b>Bedrock Edition {@code geo.json}</b> entity-cube atlas
     * unwrap where all six faces of a single cube share one texture image.
     * <p>
     * Bedrock lays out the strip with top and bottom in a first row sized {@code sx x sz}, then
     * east, north, west, south in a second row sized {@code sz, sx, sz, sx} wide by {@code sy}
     * tall - reading left-to-right that's {@code RIGHT, FRONT, LEFT, BACK}:
     * <pre>
     *        +-------+--------+
     *        |  TOP  | BOTTOM |                      row 1: height sz
     * +------+-------+--------+-------+
     * | EAST | NORTH |  WEST  | SOUTH |              row 2: height sy
     * +------+-------+--------+-------+
     * </pre>
     * Each face's pixel rectangle comes from the {@link EntityLayout} coefficients:
     * {@code uOff = atlasUSxCoef*sx + atlasUSzCoef*sz}, {@code vOff = atlasVSxCoef*sx +
     * atlasVSzCoef*sz}, with width and height drawn from {@code size[widthAxis]} and
     * {@code size[heightAxis]}. The {@code sy} dimension never contributes to an atlas offset
     * because vertical extent on the strip is always expressed in terms of {@code sz} (top row)
     * or the face's own height.
     * <p>
     * Used by entity cube rendering (via {@link EntityGeometryKit}) where one skin image
     * supplies every face of a body part. Java block elements use the {@link #defaultUv(Box)}
     * overload instead - crossing the two sends front-face pixels to the back face and mirrors
     * right-face pixels onto the left, which is immediately visible on asymmetric textures
     * (cow udder, zombie face, pig snout, villager nose).
     * <p>
     * <b>Note:</b> Java Edition's {@code ModelPart$Cube} uses a third strip order that isn't
     * expressible via the same axis coefficients. That layout is owned by
     * {@code ToolingBlockEntities.BlockModelConverter.ModelPartPolygonFace.uvFormula}, which is
     * only used at tooling time to convert Java client-jar bytecode into block-model JSON.
     *
     * @param uv the cube's texture origin in pixels on the source image ({@code [u, v]})
     * @param size the cube's extent along each axis in model units ({@code [sx, sy, sz]})
     * @param texWidth the total texture width in pixels
     * @param texHeight the total texture height in pixels
     * @param mirror whether to mirror the U axis (classic MCBE cube {@code mirror} flag)
     * @return the four UV corners, ordered TL, BL, BR, TR
     */
    public @NotNull Vector2f @NotNull [] defaultUv(
        int @NotNull [] uv,
        float @NotNull [] size,
        float texWidth,
        float texHeight,
        boolean mirror
    ) {
        float sx = size[0];
        float sz = size[2];
        float uOff = this.entityLayout.atlasUSxCoef() * sx + this.entityLayout.atlasUSzCoef() * sz;
        float vOff = this.entityLayout.atlasVSxCoef() * sx + this.entityLayout.atlasVSzCoef() * sz;

        float u0 = uv[0] + uOff;
        float u1 = u0 + size[this.entityLayout.widthAxis()];
        float v0 = uv[1] + vOff;
        float v1 = v0 + size[this.entityLayout.heightAxis()];

        return uvRect(u0, v0, u1, v1, texWidth, texHeight, mirror);
    }

    /**
     * Normalizes a raw pixel-space UV rectangle {@code (u0, v0) - (u1, v1)} into {@code [0, 1]}
     * space and wraps the result as four corner vectors. When {@code mirror} is {@code true}
     * the U axis is reversed by swapping the left and right corners, matching the classic MCBE
     * cube mirror flag.
     * <p>
     * Used by both {@link #defaultUv defaultUv} overloads and by callers that have an explicit
     * pixel-space UV rectangle from a per-face override (e.g. Bedrock 1.12+ {@code cube.uv}
     * object form).
     *
     * @param u0 the rectangle's minimum U in pixels
     * @param v0 the rectangle's minimum V in pixels
     * @param u1 the rectangle's maximum U in pixels
     * @param v1 the rectangle's maximum V in pixels
     * @param uScale the total texture width in pixels
     * @param vScale the total texture height in pixels
     * @param mirror whether to mirror the U axis
     * @return the four UV corners, ordered TL, BL, BR, TR
     */
    public static @NotNull Vector2f @NotNull [] uvRect(
        float u0, float v0, float u1, float v1,
        float uScale, float vScale,
        boolean mirror
    ) {
        float nu0 = u0 / uScale;
        float nv0 = v0 / vScale;
        float nu1 = u1 / uScale;
        float nv1 = v1 / vScale;
        if (mirror)
            return uvCorners(nu1, nv0, nu0, nv1);
        return uvCorners(nu0, nv0, nu1, nv1);
    }

    /**
     * Builds four UV corners (TL, BL, BR, TR) from a UV rectangle already in normalized
     * {@code [0, 1]} space, matching vanilla's vertex-to-UV assignment order. Shared by the
     * derivation paths above and by callers that have explicit UVs to convert.
     *
     * @param u0 the minimum U
     * @param v0 the minimum V
     * @param u1 the maximum U
     * @param v1 the maximum V
     * @return the four UV corners, ordered TL, BL, BR, TR
     */
    public static @NotNull Vector2f @NotNull [] uvCorners(float u0, float v0, float u1, float v1) {
        return new Vector2f[]{
            new Vector2f(u0, v0),
            new Vector2f(u0, v1),
            new Vector2f(u1, v1),
            new Vector2f(u1, v0)
        };
    }

    /**
     * Resolves the dominant cardinal face for a surface normal by comparing the magnitude of its
     * components: the largest-magnitude axis wins, the sign of that component picks between the
     * two opposing faces on that axis. Ties between axes resolve in Y &gt; Z &gt; X order,
     * matching the original nested-{@code if} form in the inventory-lighting code.
     *
     * @param normal the surface normal (should be normalized, but magnitude is not required)
     * @return the closest cardinal face to the normal direction
     */
    public static @NotNull BlockFace fromNormal(@NotNull Vector3f normal) {
        float absX = Math.abs(normal.x());
        float absY = Math.abs(normal.y());
        float absZ = Math.abs(normal.z());

        if (absY > absX && absY > absZ)
            return normal.y() > 0f ? UP : DOWN;
        if (absZ > absX)
            return normal.z() > 0f ? SOUTH : NORTH;
        return normal.x() > 0f ? EAST : WEST;
    }

    /**
     * Parses a lowercase direction name ({@code "down"}, {@code "up"}, {@code "north"},
     * {@code "south"}, {@code "west"}, {@code "east"}) into its {@code BlockFace} constant.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable BlockFace fromName(@Nullable String name) {
        if (name == null) return null;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "down" -> DOWN;
            case "up" -> UP;
            case "north" -> NORTH;
            case "south" -> SOUTH;
            case "west" -> WEST;
            case "east" -> EAST;
            default -> null;
        };
    }

    /**
     * Per-face data used by {@link #defaultUv(Box)} for Java block-model element unwrap.
     * Self-contained - the element-unwrap overload never touches {@link EntityLayout}.
     * <ul>
     * <li>{@link #widthAxis()} / {@link #heightAxis()} - which of {@code [x, y, z]} map to U and V
     *     ({@code 0=x}, {@code 1=y}, {@code 2=z}). Picks the face's cross-section from the
     *     element's bounds.</li>
     * <li>{@link #uInverted()} / {@link #vInverted()} - whether the unwrap uses
     *     {@code 16 - value} instead of {@code value} on U or V for this face.</li>
     * </ul>
     *
     * @param widthAxis the size-axis index that maps to U ({@code 0=x}, {@code 1=y}, {@code 2=z})
     * @param heightAxis the size-axis index that maps to V
     * @param uInverted whether the element unwrap inverts U for this face
     * @param vInverted whether the element unwrap inverts V for this face
     */
    public record BlockLayout(
        int widthAxis,
        int heightAxis,
        boolean uInverted,
        boolean vInverted
    ) {}

    /**
     * Per-face data used by {@link #defaultUv(int[], float[], float, float, boolean)} for
     * Bedrock entity-cube atlas unwrap. Self-contained - the entity-atlas overload never
     * touches {@link BlockLayout}.
     * <ul>
     * <li>{@link #widthAxis()} / {@link #heightAxis()} - which of {@code [x, y, z]} map to U and V
     *     ({@code 0=x}, {@code 1=y}, {@code 2=z}). Picks the face's size along U and V from the
     *     cube's {@code size} array.</li>
     * <li>{@link #atlasUSxCoef()} / {@link #atlasUSzCoef()} - coefficients in the atlas U offset
     *     formula {@code uOff = atlasUSxCoef*sx + atlasUSzCoef*sz}.</li>
     * <li>{@link #atlasVSxCoef()} / {@link #atlasVSzCoef()} - coefficients in the atlas V offset
     *     formula {@code vOff = atlasVSxCoef*sx + atlasVSzCoef*sz}.</li>
     * </ul>
     * The {@code sy} dimension never contributes to an atlas offset because vertical extent on
     * the strip is always expressed in terms of {@code sz} (top row) or the face's own height.
     * The coefficients encode Bedrock's {@code EAST, NORTH, WEST, SOUTH} side-strip order -
     * swapping any pair breaks the cow udder / mob-face tests.
     *
     * @param widthAxis the size-axis index that maps to U ({@code 0=x}, {@code 1=y}, {@code 2=z})
     * @param heightAxis the size-axis index that maps to V
     * @param atlasUSxCoef the {@code sx} coefficient in the atlas U offset formula
     * @param atlasUSzCoef the {@code sz} coefficient in the atlas U offset formula
     * @param atlasVSxCoef the {@code sx} coefficient in the atlas V offset formula
     * @param atlasVSzCoef the {@code sz} coefficient in the atlas V offset formula
     */
    public record EntityLayout(
        int widthAxis,
        int heightAxis,
        int atlasUSxCoef,
        int atlasUSzCoef,
        int atlasVSxCoef,
        int atlasVSzCoef
    ) {}

}
