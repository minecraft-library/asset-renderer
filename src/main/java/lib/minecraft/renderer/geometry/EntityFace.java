package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
 * <li>The lowercase {@link #direction direction name} ({@code "down"} etc., derived from
 *     {@link #name()}) - matching vanilla {@code Direction} keys plus Bedrock per-face
 *     {@code cube.uv} overrides used when parsing entity geometry.</li>
 * <li>The four vertex indices into the canonical 8-corner box (see the layout diagram on
 *     {@link #corners}).</li>
 * <li>The outward unit {@link #normal} - used by the entity ENTITY_IN_UI lighting model
 *     ({@link lib.minecraft.renderer.engine.RenderEngine#computeEntityInUiLighting}) which dots
 *     this direction against two fixed inventory diffuse light vectors.</li>
 * <li>A {@link Layout} carrying the per-face axis-and-atlas-coefficient data
 *     {@link #defaultUv} needs to project a cube's UV strip into a pixel rectangle without a
 *     per-face {@code switch}.</li>
 * </ul>
 * <p>
 * The four vertex indices per face match vanilla's {@code ModelPart.Cube} polygon vertex
 * order verbatim (extracted from the cube ctor bytecode), so a cube's screen-space quad
 * triangulation - {@code (corners[0], 1, 2) + (corners[0], 2, 3)} - splits along the same
 * diagonal vanilla's GPU rasterizes. The convention is CCW when viewed from the outward
 * normal direction:
 * <ul>
 * <li>side faces (NORTH/SOUTH/EAST/WEST) start at the {@code (minY, "front")} corner of the
 *     face (or its mirror for back-facing sides), then wind {@code front-top -&gt;
 *     back-top -&gt; back-bottom -&gt; front-bottom} in CCW order around the face;</li>
 * <li>UP starts at {@code (maxX, maxY, minZ)} and DOWN at {@code (maxX, minY, maxZ)},
 *     winding CCW from the outward-normal viewpoint. The atlas strip's UP polygon is
 *     V-inverted vs DOWN ({@code v_top &gt; v_bottom} in the polygon ctor's
 *     {@code f3 / f5} args); the per-vertex UV slot permutation that compensates for this
 *     lives on each constant as {@link #polygonVertexSlots} and is applied by
 *     {@link #permuteToPolygonOrder}.</li>
 * </ul>
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
        new int[]{ 5, 4, 0, 1 }, new Vector3f(0f, -1f, 0f),
        new Layout(0, 2, 0, 1, 0, 0),
        new int[]{ 3, 0, 1, 2 }
    ),
    UP(
        new int[]{ 2, 3, 7, 6 }, new Vector3f(0f, 1f, 0f),
        new Layout(0, 2, 1, 1, 0, 0),
        new int[]{ 2, 1, 0, 3 }
    ),
    NORTH(
        new int[]{ 1, 0, 3, 2 }, new Vector3f(0f, 0f, -1f),
        new Layout(0, 1, 0, 1, 0, 1),
        new int[]{ 3, 0, 1, 2 }
    ),
    SOUTH(
        new int[]{ 4, 5, 6, 7 }, new Vector3f(0f, 0f, 1f),
        new Layout(0, 1, 1, 2, 0, 1),
        new int[]{ 3, 0, 1, 2 }
    ),
    WEST(
        new int[]{ 0, 4, 7, 3 }, new Vector3f(-1f, 0f, 0f),
        new Layout(2, 1, 0, 0, 0, 1),
        new int[]{ 3, 0, 1, 2 }
    ),
    EAST(
        new int[]{ 5, 1, 2, 6 }, new Vector3f(1f, 0f, 0f),
        new Layout(2, 1, 1, 1, 0, 1),
        new int[]{ 3, 0, 1, 2 }
    );

    /**
     * Cached snapshot of {@link #values()} reused by lookups and iteration to avoid the per-call
     * defensive array clone the JLS mandates.
     */
    public static final EntityFace @NotNull [] CACHED_VALUES = values();

    /**
     * Index of {@link #direction direction names} to enum constants for O(1) lookup by lowercase
     * name. Powers {@link #fromName(String)}.
     */
    private static final @NotNull Map<String, EntityFace> BY_NAME;

    static {
        Map<String, EntityFace> byName = new HashMap<>(CACHED_VALUES.length * 2);

        for (EntityFace face : CACHED_VALUES)
            byName.put(face.direction, face);

        BY_NAME = Map.copyOf(byName);
    }

    /**
     * Lowercase direction name ({@code "down"}, {@code "up"}, ...), derived once at class-load
     * time from {@link #name()} so external callers don't pay a per-call {@code toLowerCase}.
     * Matches vanilla {@code Direction} keys plus Bedrock per-face {@code cube.uv} overrides.
     */
    private final @NotNull String direction = this.name().toLowerCase(Locale.ROOT);

    /** Four vertex indices into the canonical 8-corner box (see the class javadoc diagram). */
    private final int @NotNull [] vertexIndices;

    /** Outward unit normal of this face in model space. */
    private final @NotNull Vector3f normal;

    /** Per-face axis-and-atlas-coefficient data driving {@link #defaultUv}. */
    private final @NotNull Layout layout;

    /**
     * Maps the indices of a normalized {@code [TL, BL, BR, TR]} corner array (the shape
     * {@link Vector4f#toUvCorners(float, float, int, boolean)} returns from a {@code v0 <= v1}
     * rect) into the per-vertex order vanilla's {@code ModelPart$Polygon} constructor produces -
     * {@code output[i]} is the UV vanilla pairs with this face's vertex {@code i}.
     * <p>
     * Five of the six faces share {@code [3, 0, 1, 2]} (cyclic shift placing TR first to match
     * the polygon ctor's TR-first walk). {@link #UP} alone uses {@code [2, 1, 0, 3]} because
     * vanilla's {@code ModelPart.Cube} ctor passes UP's v args in inverted order ({@code f3 = v},
     * {@code f5 = v + d}), making UP's atlas v walk backward relative to every other face. The
     * permutation lives here as data so both the renderer's
     * {@link lib.minecraft.renderer.kit.EntityGeometryKit#resolvePolygonUv resolvePolygonUv} and
     * the tooling-side bytecode-to-block-model converter can share one source of truth.
     */
    private final int @NotNull [] polygonVertexSlots;

    /**
     * Returns the four corners of this face on the given axis-aligned {@link Box}, ordered to
     * match vanilla's {@code ModelPart.Cube} polygon vertex sequence so the standard quad
     * triangulation {@code (corners[0], 1, 2) + (corners[0], 2, 3)} splits along the same
     * diagonal vanilla's GPU rasterizes.
     * <p>
     * The 8-corner box layout used to resolve the {@link #vertexIndices indices}:
     * <pre>
     * 0: (x0, y0, z0)   4: (x0, y0, z1)
     * 1: (x1, y0, z0)   5: (x1, y0, z1)
     * 2: (x1, y1, z0)   6: (x1, y1, z1)
     * 3: (x0, y1, z0)   7: (x0, y1, z1)
     * </pre>
     *
     * @param box the bounding box
     * @return the four corner positions in vanilla polygon vertex order
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
     * Picks this face's four corners out of an arbitrary 8-corner array indexed by the canonical
     * bit-pattern layout (see {@link #corners(Box)}). Overload of {@link #corners(Box)} for
     * callers - currently the tooling block-entity converter - that already hold a transformed
     * {@code float[8][3]} of cube corners and want to index by face without spelling out
     * {@code vertexIndices()[0..3]} four times.
     *
     * @param eightCorners the eight cube corners in canonical bit-pattern order
     * @return this face's four corners in vanilla polygon-vertex order (the same array entries,
     *     not copies)
     */
    public float @NotNull [] @NotNull [] cornersOf(float @NotNull [] @NotNull [] eightCorners) {
        return new float[][]{
            eightCorners[this.vertexIndices[0]], eightCorners[this.vertexIndices[1]],
            eightCorners[this.vertexIndices[2]], eightCorners[this.vertexIndices[3]]
        };
    }

    /**
     * Returns the default UV rectangle for this face in pixel space, using the <b>vanilla Java
     * Edition</b> entity-cube atlas unwrap where all six faces of a single cube share one
     * texture image. Mirrors {@link net.minecraft.client.model.geom.ModelPart.Cube}'s polygon
     * UV layout.
     * <p>
     * Vanilla lays out the strip with bottom (DOWN polygon) and top (UP polygon) in a first row
     * sized {@code sx x sz}, then west, north, east, south in a second row sized {@code sz, sx,
     * sz, sx} wide by {@code sy} tall - reading left-to-right that's {@code LEFT, FRONT, RIGHT,
     * BACK}:
     * <pre>
     *        +-------+--------+
     *        |  BOT  |  TOP   |                       row 1: height sz
     * +------+-------+--------+-------+
     * | WEST | NORTH |  EAST  | SOUTH |               row 2: height sy
     * +------+-------+--------+-------+
     * </pre>
     * Each face's pixel rectangle comes from the {@link Layout} coefficients:
     * {@code uOff = atlasUSxCoef*sx + atlasUSzCoef*sz}, {@code vOff = atlasVSxCoef*sx +
     * atlasVSzCoef*sz}, with width and height drawn from {@code size[widthAxis]} and
     * {@code size[heightAxis]}. The {@code sy} dimension never contributes to an atlas offset
     * because vertical extent on the strip is always expressed in terms of {@code sz} (top row)
     * or the face's own height.
     * <p>
     * Used by entity cube rendering (via {@link EntityGeometryKit}) where one skin image
     * supplies every face of a body part. Callers
     * compose {@link Vector4f#toUvCorners(float, float, int, boolean)} with the texture
     * dimensions on the result to obtain normalized per-vertex corners.
     *
     * @param uv the cube's texture origin in pixels on the source image ({@code [u, v]})
     * @param size the cube's extent along each axis in model units ({@code [sx, sy, sz]})
     * @return the UV rectangle as {@code (uMin, vMin, uMax, vMax)} in pixel space
     */
    public @NotNull Vector4f defaultUv(@NotNull Vector2f uv, @NotNull Vector3f size) {
        float sx = size.x();
        float sz = size.z();
        float uOff = this.layout.atlasUSxCoef() * sx + this.layout.atlasUSzCoef() * sz;
        float vOff = this.layout.atlasVSxCoef() * sx + this.layout.atlasVSzCoef() * sz;

        float u0 = uv.x() + uOff;
        float u1 = u0 + size.get(this.layout.widthAxis());
        float v0 = uv.y() + vOff;
        float v1 = v0 + size.get(this.layout.heightAxis());

        return new Vector4f(u0, v0, u1, v1);
    }

    /**
     * Reorders four atlas-position-labeled UV corners {@code (TL, BL, BR, TR)} into the
     * per-vertex order vanilla's {@code ModelPart$Polygon} constructor produces, using this
     * face's {@link #polygonVertexSlots}.
     * <p>
     * For non-UP faces this is a cyclic shift placing TR first; UP additionally swaps TL/BL
     * with BR/TR slots to compensate for vanilla's inverted v-args on the UP polygon. Both the
     * renderer's {@code resolvePolygonUv} and the tooling's
     * {@code ToolingBlockEntities.BlockModelConverter} call this so the per-face permutation
     * lives in exactly one place.
     *
     * @param tlBlBrTr the four UV corners in atlas-position order (top-left, bottom-left,
     *     bottom-right, top-right)
     * @return the four UVs in vanilla polygon-vertex order ({@code output[i]} pairs with
     *     {@code vertexIndices()[i]})
     */
    public @NotNull Vector2f @NotNull [] permuteToPolygonOrder(@NotNull Vector2f @NotNull [] tlBlBrTr) {
        int[] s = this.polygonVertexSlots;
        return new Vector2f[]{ tlBlBrTr[s[0]], tlBlBrTr[s[1]], tlBlBrTr[s[2]], tlBlBrTr[s[3]] };
    }

    /**
     * Returns the face whose UV strip should be sampled when the cube's {@code mirror} flag is
     * {@code true}, otherwise returns this face unchanged.
     * <p>
     * Vanilla's {@code ModelPart.Cube} ctor swaps the cube's {@code x} and {@code maxX}
     * variables before building polygon vertices when {@code mirror=true}, which has the net
     * effect of placing vanilla's WEST polygon UV onto the +X face and vanilla's EAST polygon
     * UV onto the -X face. Other faces ({@link #UP}/{@link #DOWN}/{@link #NORTH}/{@link #SOUTH})
     * stay on their natural UV strip; their U axis is U-flipped separately via
     * {@link Vector4f#toUvCorners}'s mirror arg at the call site.
     *
     * @param mirrored the cube's {@code mirror} flag
     * @return {@link #WEST} for {@link #EAST} (and vice versa) when {@code mirrored} is
     *     {@code true}; this face otherwise
     */
    public @NotNull EntityFace mirror(boolean mirrored) {
        if (!mirrored) return this;
        return switch (this) {
            case EAST -> WEST;
            case WEST -> EAST;
            default -> this;
        };
    }

    /**
     * Parses a lowercase direction name ({@code "down"}, {@code "up"}, {@code "north"},
     * {@code "south"}, {@code "west"}, {@code "east"}) into its {@code EntityFace} constant via
     * an O(1) lookup against {@link #BY_NAME}. Returns {@code null} when the name is
     * {@code null} or unrecognized.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable EntityFace fromName(@Nullable String name) {
        return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Per-face data used by {@link #defaultUv} for the vanilla Java entity-cube atlas unwrap.
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
     * The coefficients encode vanilla's {@code WEST, NORTH, EAST, SOUTH} side-strip order -
     * swapping any pair breaks the cow udder / mob-face tests.
     *
     * @param widthAxis the size-axis index that maps to U ({@code 0=x}, {@code 1=y}, {@code 2=z})
     * @param heightAxis the size-axis index that maps to V
     * @param atlasUSxCoef the {@code sx} coefficient in the atlas U offset formula
     * @param atlasUSzCoef the {@code sz} coefficient in the atlas U offset formula
     * @param atlasVSxCoef the {@code sx} coefficient in the atlas V offset formula
     * @param atlasVSzCoef the {@code sz} coefficient in the atlas V offset formula
     */
    public record Layout(
        int widthAxis,
        int heightAxis,
        int atlasUSxCoef,
        int atlasUSzCoef,
        int atlasVSxCoef,
        int atlasVSzCoef
    ) {}

}
