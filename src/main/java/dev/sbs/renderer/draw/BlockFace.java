package dev.sbs.renderer.draw;

import dev.sbs.renderer.math.Vector2f;
import dev.sbs.renderer.math.Vector3f;
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
 * JSON), its four vertex indices into the canonical 8-corner box, its outward unit normal, and a
 * {@link FaceLayout} that captures the data both {@link #defaultUv defaultUv} overloads need to
 * project face geometry into a UV rectangle without a per-face {@code switch}. The box vertex
 * layout is:
 * <pre>
 * 0: (x0, y0, z0)   4: (x0, y0, z1)
 * 1: (x1, y0, z0)   5: (x1, y0, z1)
 * 2: (x1, y1, z0)   6: (x1, y1, z1)
 * 3: (x0, y1, z0)   7: (x0, y1, z1)
 * </pre>
 * The four indices per face are wound top-left, bottom-left, bottom-right, top-right when viewed
 * from the outward normal direction (CCW), matching vanilla's {@code FaceInfo} vertex order and
 * the convention used by {@link GeometryKit}'s triangle builders.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum BlockFace {

    DOWN(
        "down", new int[]{ 4, 0, 1, 5 }, new Vector3f(0f, -1f, 0f),
        new FaceLayout(0, 2, false, true, 1, 1, 0, 0)
    ),
    UP(
        "up", new int[]{ 3, 7, 6, 2 }, new Vector3f(0f, 1f, 0f),
        new FaceLayout(0, 2, false, false, 0, 1, 0, 0)
    ),
    NORTH(
        "north", new int[]{ 2, 1, 0, 3 }, new Vector3f(0f, 0f, -1f),
        new FaceLayout(0, 1, true, true, 1, 2, 0, 1)
    ),
    SOUTH(
        "south", new int[]{ 7, 4, 5, 6 }, new Vector3f(0f, 0f, 1f),
        new FaceLayout(0, 1, false, true, 0, 1, 0, 1)
    ),
    WEST(
        "west", new int[]{ 3, 0, 4, 7 }, new Vector3f(-1f, 0f, 0f),
        new FaceLayout(2, 1, false, true, 0, 0, 0, 1)
    ),
    EAST(
        "east", new int[]{ 6, 5, 1, 2 }, new Vector3f(1f, 0f, 0f),
        new FaceLayout(2, 1, true, true, 1, 1, 0, 1)
    );

    private final @NotNull String direction;
    private final int @NotNull [] vertexIndices;
    private final @NotNull Vector3f normal;

    @lombok.Getter(lombok.AccessLevel.NONE)
    private final @NotNull FaceLayout layout;

    /**
     * Returns the four CCW-ordered (TL, BL, BR, TR) corners of this face on an axis-aligned box
     * defined by the given minimum and maximum bounds, matching vanilla's {@code FaceInfo} vertex
     * order.
     *
     * @param x0 the box minimum X
     * @param y0 the box minimum Y
     * @param z0 the box minimum Z
     * @param x1 the box maximum X
     * @param y1 the box maximum Y
     * @param z1 the box maximum Z
     * @return the four corner positions, ordered TL, BL, BR, TR
     */
    public @NotNull Vector3f @NotNull [] corners(
        float x0, float y0, float z0,
        float x1, float y1, float z1
    ) {
        Vector3f[] box = {
            new Vector3f(x0, y0, z0), new Vector3f(x1, y0, z0),
            new Vector3f(x1, y1, z0), new Vector3f(x0, y1, z0),
            new Vector3f(x0, y0, z1), new Vector3f(x1, y0, z1),
            new Vector3f(x1, y1, z1), new Vector3f(x0, y1, z1)
        };
        return new Vector3f[]{
            box[this.vertexIndices[0]], box[this.vertexIndices[1]],
            box[this.vertexIndices[2]], box[this.vertexIndices[3]]
        };
    }

    /**
     * Returns the four default UV corners (TL, BL, BR, TR) for this face in normalized
     * {@code [0, 1]} space, derived from the element bounds using vanilla's block-model
     * projection formulas (see {@code FaceBakery.defaultFaceUV}).
     * <p>
     * Block model elements reference an independent texture per face (via their {@code #var}
     * bindings), so every face samples the full {@code [0, 16]} UV rectangle projected onto its
     * cross-section. This overload is used by the block and held-item rendering paths.
     *
     * @param from the element minimum in 0-16 space ({@code [x, y, z]})
     * @param to the element maximum in 0-16 space ({@code [x, y, z]})
     * @return the four UV corners, ordered TL, BL, BR, TR
     */
    public @NotNull Vector2f @NotNull [] defaultUv(float @NotNull [] from, float @NotNull [] to) {
        int uAxis = this.layout.widthAxis();
        int vAxis = this.layout.heightAxis();
        float u0 = this.layout.uInverted() ? 16f - to[uAxis] : from[uAxis];
        float u1 = this.layout.uInverted() ? 16f - from[uAxis] : to[uAxis];
        float v0 = this.layout.vInverted() ? 16f - to[vAxis] : from[vAxis];
        float v1 = this.layout.vInverted() ? 16f - from[vAxis] : to[vAxis];
        return uvRect(u0, v0, u1, v1, 16f, 16f, false);
    }

    /**
     * Returns the four default UV corners (TL, BL, BR, TR) for this face in normalized
     * {@code [0, 1]} space, using the Java-edition box atlas unwrap where all six faces of a
     * single cube share one texture image.
     * <p>
     * The strip layout places top and bottom in a first row sized {@code sx x sz}, then west,
     * south, east, north in a second row sized {@code sz, sx, sz, sx} wide by {@code sy} tall:
     * <pre>
     *     +--------+--------+
     *     |  TOP   | BOTTOM |          row 1: height sz
     * +---+--------+--------+---+
     * |WST|  SOUTH |  EAST  |NTH|      row 2: height sy
     * +---+--------+--------+---+
     * </pre>
     * Used by entity cube rendering where one skin image supplies every face of a body part,
     * and by any other caller that owns a cube-atlas texture.
     *
     * @param uv the cube's texture origin in pixels on the source image ({@code [u, v]})
     * @param size the cube's extent along each axis in model units ({@code [sx, sy, sz]})
     * @param texWidth the total texture width in pixels
     * @param texHeight the total texture height in pixels
     * @param mirror whether to mirror the U axis (classic MCBE {@code mirror} flag)
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
        float uOff = this.layout.atlasUSxCoef() * sx + this.layout.atlasUSzCoef() * sz;
        float vOff = this.layout.atlasVSxCoef() * sx + this.layout.atlasVSzCoef() * sz;

        float u0 = uv[0] + uOff;
        float u1 = u0 + size[this.layout.widthAxis()];
        float v0 = uv[1] + vOff;
        float v1 = v0 + size[this.layout.heightAxis()];

        return uvRect(u0, v0, u1, v1, texWidth, texHeight, mirror);
    }

    /**
     * Normalizes a raw pixel-space UV rectangle {@code (u0, v0) - (u1, v1)} into {@code [0, 1]}
     * space and wraps the result as four corner vectors. When {@code mirror} is {@code true}
     * the U axis is reversed by swapping the left and right corners, matching the classic MCBE
     * cube mirror flag.
     * <p>
     * Used by both {@link #defaultUv} overloads and by callers that have an explicit pixel-space
     * UV rectangle from a per-face override (e.g. Bedrock geo.json cube face UVs).
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
     * Per-face data used by both {@link #defaultUv defaultUv} overloads so the projection math
     * stays in one place and neither method has to {@code switch} on the face direction.
     *
     * <p>The first four fields describe the element unwrap used by block model rendering:
     * <ul>
     * <li>{@link #widthAxis()} / {@link #heightAxis()} - which of {@code [x, y, z]} map to U and V
     *     (0 = x, 1 = y, 2 = z). These are also used to pick the face's size along U and V
     *     when laying out a cube atlas.</li>
     * <li>{@link #uInverted()} / {@link #vInverted()} - whether the element-unwrap overload uses
     *     {@code 16 - value} instead of {@code value} on U or V for this face.</li>
     * </ul>
     *
     * <p>The last four fields describe the atlas unwrap used by entity cube rendering:
     * <ul>
     * <li>{@link #atlasUSxCoef()} and {@link #atlasUSzCoef()} - coefficients for computing the
     *     face's atlas U offset as {@code uSxCoef * sx + uSzCoef * sz}.</li>
     * <li>{@link #atlasVSxCoef()} and {@link #atlasVSzCoef()} - coefficients for computing the
     *     face's atlas V offset as {@code vSxCoef * sx + vSzCoef * sz}.</li>
     * </ul>
     * The {@code sy} dimension never contributes to an atlas offset because vertical extent on
     * the strip is always expressed in terms of {@code sz} (top row) or {@code sy} itself via
     * the face height.
     *
     * @param widthAxis the size-axis index that maps to U ({@code 0=x}, {@code 1=y}, {@code 2=z})
     * @param heightAxis the size-axis index that maps to V
     * @param uInverted whether the element-unwrap inverts U for this face
     * @param vInverted whether the element-unwrap inverts V for this face
     * @param atlasUSxCoef the {@code sx} coefficient in the atlas U offset formula
     * @param atlasUSzCoef the {@code sz} coefficient in the atlas U offset formula
     * @param atlasVSxCoef the {@code sx} coefficient in the atlas V offset formula
     * @param atlasVSzCoef the {@code sz} coefficient in the atlas V offset formula
     */
    public record FaceLayout(
        int widthAxis,
        int heightAxis,
        boolean uInverted,
        boolean vInverted,
        int atlasUSxCoef,
        int atlasUSzCoef,
        int atlasVSxCoef,
        int atlasVSzCoef
    ) {}

}
