package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The six cardinal face directions of an axis-aligned Minecraft <b>entity-cube</b> body part.
 * <p>
 * Mirrors {@link BlockFace}'s direction enum but is intentionally not derived from it: blocks and
 * entities share the same six cardinal directions and cube-vertex layout but disagree on UV
 * unwrap, lighting model, and downstream consumers, so the two enums duplicate the shared scaffold
 * (direction name, vertex indices, normal vectors) and own the per-domain data outright.
 * <p>
 * Each constant carries:
 * <ul>
 * <li>The lowercase direction name ({@code down}, {@code up}, {@code north}, ...) - matching
 *     vanilla {@code Direction} keys plus Bedrock per-face {@code cube.uv} overrides used when
 *     parsing entity geometry.</li>
 * <li>The four vertex indices into the canonical 8-corner box (see the layout diagram on
 *     {@link #corners}).</li>
 * <li>The outward unit {@link #normal} - used by the entity ENTITY_IN_UI lighting model
 *     ({@link lib.minecraft.renderer.engine.RenderEngine#computeEntityInUiLighting}) which dots
 *     this direction against two fixed inventory diffuse light vectors.</li>
 * <li>Atlas-strip layout coefficients ({@link #widthAxis}, {@link #heightAxis},
 *     {@link #atlasUSxCoef}, {@link #atlasUSzCoef}, {@link #atlasVSxCoef}, {@link #atlasVSzCoef})
 *     consumed by {@link #defaultUv} - inlined as fields rather than a nested record because no
 *     other layout shares this enum (compare {@link BlockFace} which has to disambiguate between a
 *     block-element layout and an entity-cube layout via two record companions).</li>
 * </ul>
 * <p>
 * The four vertex indices per face are wound top-left, bottom-left, bottom-right, top-right when
 * viewed from the outward normal direction (CCW), matching vanilla's {@code FaceInfo} order and
 * the convention used by {@link EntityGeometryKit}'s triangle builders.
 * <p>
 * <b>Lighting:</b> entity rendering uses vanilla's {@code Lighting.Entry.ENTITY_IN_UI}, which is
 * a dual-directional Lambertian shader (two normalized light vectors, summed with ambient and
 * clamped) rather than a per-face constant. This enum therefore deliberately does <b>not</b>
 * carry a {@code lighting} scalar - shading is computed per-vertex from the surface normal in
 * {@link lib.minecraft.renderer.engine.RenderEngine#computeEntityInUiLighting}, baked into each
 * triangle's {@link VisibleTriangle#shading} field at kit time, and the rasterizer applies it
 * directly without a second per-face lookup. Compare {@link BlockFace#lighting} which carries the
 * {@code Lighting.ITEMS_3D} per-face approximation suitable for block inventory icons.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum EntityFace {

    DOWN(
        "down", new int[]{ 4, 0, 1, 5 }, new Vector3f(0f, -1f, 0f),
        0, 2, 1, 1, 0, 0
    ),
    UP(
        "up", new int[]{ 3, 7, 6, 2 }, new Vector3f(0f, 1f, 0f),
        0, 2, 0, 1, 0, 0
    ),
    NORTH(
        "north", new int[]{ 2, 1, 0, 3 }, new Vector3f(0f, 0f, -1f),
        0, 1, 0, 1, 0, 1
    ),
    SOUTH(
        "south", new int[]{ 7, 4, 5, 6 }, new Vector3f(0f, 0f, 1f),
        0, 1, 1, 2, 0, 1
    ),
    WEST(
        "west", new int[]{ 3, 0, 4, 7 }, new Vector3f(-1f, 0f, 0f),
        2, 1, 1, 1, 0, 1
    ),
    EAST(
        "east", new int[]{ 6, 5, 1, 2 }, new Vector3f(1f, 0f, 0f),
        2, 1, 0, 0, 0, 1
    );

    private final @NotNull String direction;
    private final int @NotNull [] vertexIndices;
    private final @NotNull Vector3f normal;

    /**
     * Size-axis index that maps to U ({@code 0=x}, {@code 1=y}, {@code 2=z}). Picks the face's
     * size along U from the cube's {@code size} array.
     */
    @Getter(AccessLevel.PUBLIC)
    private final int widthAxis;

    /**
     * Size-axis index that maps to V ({@code 0=x}, {@code 1=y}, {@code 2=z}). Picks the face's
     * size along V from the cube's {@code size} array.
     */
    @Getter(AccessLevel.PUBLIC)
    private final int heightAxis;

    /** Coefficient on {@code sx} in the atlas U offset formula {@code uOff = sxCoef*sx + szCoef*sz}. */
    @Getter(AccessLevel.PUBLIC)
    private final int atlasUSxCoef;

    /** Coefficient on {@code sz} in the atlas U offset formula {@code uOff = sxCoef*sx + szCoef*sz}. */
    @Getter(AccessLevel.PUBLIC)
    private final int atlasUSzCoef;

    /** Coefficient on {@code sx} in the atlas V offset formula {@code vOff = sxCoef*sx + szCoef*sz}. */
    @Getter(AccessLevel.PUBLIC)
    private final int atlasVSxCoef;

    /** Coefficient on {@code sz} in the atlas V offset formula {@code vOff = sxCoef*sx + szCoef*sz}. */
    @Getter(AccessLevel.PUBLIC)
    private final int atlasVSzCoef;

    /**
     * Returns the four CCW-ordered (TL, BL, BR, TR) corners of this face on the given axis-aligned
     * {@link Box}, matching vanilla's {@code FaceInfo} vertex order.
     * <p>
     * The 8-corner box layout used to resolve the indices:
     * <pre>
     * 0: (x0, y0, z0)   4: (x0, y0, z1)
     * 1: (x1, y0, z0)   5: (x1, y0, z1)
     * 2: (x1, y1, z0)   6: (x1, y1, z1)
     * 3: (x0, y1, z0)   7: (x0, y1, z1)
     * </pre>
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
     * Returns the default UV rectangle for this face in pixel space, using the <b>Bedrock
     * Edition {@code geo.json}</b> entity-cube atlas unwrap where all six faces of a single cube
     * share one texture image.
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
     * Each face's pixel rectangle comes from the layout coefficients:
     * {@code uOff = atlasUSxCoef*sx + atlasUSzCoef*sz}, {@code vOff = atlasVSxCoef*sx +
     * atlasVSzCoef*sz}, with width and height drawn from {@code size[widthAxis]} and
     * {@code size[heightAxis]}. The {@code sy} dimension never contributes to an atlas offset
     * because vertical extent on the strip is always expressed in terms of {@code sz} (top row)
     * or the face's own height.
     * <p>
     * Used by entity cube rendering (via {@link EntityGeometryKit}) where one skin image
     * supplies every face of a body part. Callers compose
     * {@link Vector4f#toUvCorners(float, float, int, boolean)} with the texture dimensions and
     * the cube's {@code mirror} flag on the result to obtain normalized per-vertex corners.
     * <p>
     * <b>Note:</b> Java Edition's {@code ModelPart$Cube} uses a third strip order that isn't
     * expressible via these axis coefficients. That layout is owned by the tooling-time bytecode
     * converter ({@code ToolingBlockEntities}), so the kit consuming this enum sees Bedrock-style
     * pre-flattened UVs after the tooling step normalises the two layouts to one.
     *
     * @param uv the cube's texture origin in pixels on the source image ({@code [u, v]})
     * @param size the cube's extent along each axis in model units ({@code [sx, sy, sz]})
     * @return the UV rectangle as {@code (uMin, vMin, uMax, vMax)} in pixel space
     */
    public @NotNull Vector4f defaultUv(@NotNull Vector2f uv, @NotNull Vector3f size) {
        float sx = size.x();
        float sz = size.z();
        float uOff = this.atlasUSxCoef * sx + this.atlasUSzCoef * sz;
        float vOff = this.atlasVSxCoef * sx + this.atlasVSzCoef * sz;

        float u0 = uv.x() + uOff;
        float u1 = u0 + size.get(this.widthAxis);
        float v0 = uv.y() + vOff;
        float v1 = v0 + size.get(this.heightAxis);

        return new Vector4f(u0, v0, u1, v1);
    }

    /**
     * Parses a lowercase direction name ({@code "down"}, {@code "up"}, {@code "north"},
     * {@code "south"}, {@code "west"}, {@code "east"}) into its {@code EntityFace} constant.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable EntityFace fromName(@Nullable String name) {
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

}
