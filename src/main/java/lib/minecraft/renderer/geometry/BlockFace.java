package lib.minecraft.renderer.geometry;

import lib.minecraft.renderer.kit.BlockGeometryKit;
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
 * The six cardinal face directions of an axis-aligned Minecraft block element.
 * <p>
 * Each constant knows its lowercase {@link #direction direction name} ({@code "down"} etc., derived
 * from {@link #name()}), its four vertex indices into the canonical 8-corner box, its outward
 * unit normal, a {@link Layout} carrying the per-face axis / inversion data that
 * {@link #defaultUv} needs to project face geometry into a UV rectangle without a per-face
 * {@code switch}, and an inventory {@link #lighting shade factor}. The box vertex layout is:
 * <pre>
 * 0: (x0, y0, z0)   4: (x0, y0, z1)
 * 1: (x1, y0, z0)   5: (x1, y0, z1)
 * 2: (x1, y1, z0)   6: (x1, y1, z1)
 * 3: (x0, y1, z0)   7: (x0, y1, z1)
 * </pre>
 * The four indices per face are wound top-left, bottom-left, bottom-right, top-right when viewed
 * from the outward normal direction (CCW), matching vanilla's {@code FaceInfo} vertex order and
 * the convention used by {@link BlockGeometryKit}'s triangle builders.
 * <p>
 * Entity-cube unwrap (one shared skin image across six faces) is a different convention and
 * lives on {@link EntityFace} - this enum is block-model-only.
 * <p>
 * The {@link #lighting} field carries the shade factor applied to this face under vanilla's
 * {@code Lighting.ITEMS_3D} GUI pose. Note the per-axis values are <b>reversed</b> relative to
 * world-lit block brightness from {@code Direction.getBrightness}: vanilla's inventory pipeline
 * uses two directional lights offset in X so the left-hand (E/W) face ends up brighter than the
 * right-hand (N/S) face after the standard {@code [30, 225, 0]} gui rotation. Rather than
 * replicate the dual-directional light shader, each face carries a pre-baked scalar that
 * approximates the vanilla inventory output ({@code 0.8} for E/W, {@code 0.6} for N/S,
 * {@code 1.0} for UP, {@code 0.5} for DOWN). Callers that have a surface normal rather than a
 * face enum should resolve it via {@link #fromNormal(Vector3f)}; callers that have a direction
 * string should use {@link #fromName(String)}.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum BlockFace {

    DOWN(
        new int[]{ 4, 0, 1, 5 },
        new Vector3f(0f, -1f, 0f),
        new Layout(0, 2, false, true),
        0.5f
    ),
    UP(
        new int[]{ 3, 7, 6, 2 },
        new Vector3f(0f, 1f, 0f),
        new Layout(0, 2, false, false),
        1.0f
    ),
    NORTH(
        new int[]{ 2, 1, 0, 3 },
        new Vector3f(0f, 0f, -1f),
        new Layout(0, 1, true, true),
        0.6f
    ),
    SOUTH(
        new int[]{ 7, 4, 5, 6 },
        new Vector3f(0f, 0f, 1f),
        new Layout(0, 1, false, true),
        0.6f
    ),
    WEST(
        new int[]{ 3, 0, 4, 7 },
        new Vector3f(-1f, 0f, 0f),
        new Layout(2, 1, false, true),
        0.8f
    ),
    EAST(
        new int[]{ 6, 5, 1, 2 },
        new Vector3f(1f, 0f, 0f),
        new Layout(2, 1, true, true),
        0.8f
    );

    /**
     * Cached snapshot of {@link #values()} reused by lookups and iteration to avoid the per-call
     * defensive array clone the JLS mandates.
     */
    public static final BlockFace @NotNull [] CACHED_VALUES = values();

    /**
     * Index of {@link #direction direction names} to enum constants for O(1) lookup by lowercase
     * name. Powers {@link #fromName(String)}.
     */
    private static final @NotNull Map<String, BlockFace> BY_NAME;

    static {
        Map<String, BlockFace> byName = new HashMap<>(CACHED_VALUES.length * 2);

        for (BlockFace face : CACHED_VALUES)
            byName.put(face.direction, face);

        BY_NAME = Map.copyOf(byName);
    }

    /**
     * Lowercase direction name ({@code "down"}, {@code "up"}, ...), derived once at class-load
     * time from {@link #name()} so external callers don't pay a per-call {@code toLowerCase}.
     * Matches the vanilla block / item model JSON key for this face.
     */
    private final @NotNull String direction = this.name().toLowerCase(Locale.ROOT);

    /** Four vertex indices into the canonical 8-corner box (see the class javadoc diagram). */
    private final int @NotNull [] vertexIndices;

    /** Outward unit normal of this face in model space. */
    private final @NotNull Vector3f normal;

    /** Per-face axis-and-inversion data driving {@link #defaultUv}. */
    private final @NotNull Layout layout;

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
     * Returns the default UV rectangle for this face in pixel space ({@code [0, 16]}), derived
     * from the element bounds using vanilla's block-model projection formulas (see
     * {@code FaceBakery.defaultFaceUV}).
     * <p>
     * Block model elements reference an independent texture per face (via their {@code #var}
     * bindings), so every face samples the full {@code [0, 16]} UV rectangle projected onto its
     * cross-section. Callers compose
     * {@link Vector4f#toUvCorners(float, float, int, boolean)} with
     * {@link BlockGeometryKit#VANILLA_PIXEL_UNITS_PER_BLOCK} on the result to obtain normalized
     * per-vertex corners.
     *
     * @param element the element bounds in 0-16 space
     * @return the UV rectangle as {@code (uMin, vMin, uMax, vMax)} in 0-16 space
     */
    public @NotNull Vector4f defaultUv(@NotNull Box element) {
        int uAxis = this.layout.widthAxis();
        int vAxis = this.layout.heightAxis();
        float fromU = axisComponent(element, uAxis, false);
        float toU = axisComponent(element, uAxis, true);
        float fromV = axisComponent(element, vAxis, false);
        float toV = axisComponent(element, vAxis, true);
        float u0 = this.layout.uInverted() ? BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK - toU : fromU;
        float u1 = this.layout.uInverted() ? BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK - fromU : toU;
        float v0 = this.layout.vInverted() ? BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK - toV : fromV;
        float v1 = this.layout.vInverted() ? BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK - fromV : toV;
        return new Vector4f(u0, v0, u1, v1);
    }

    private static float axisComponent(@NotNull Box box, int axis, boolean max) {
        return switch (axis) {
            case 0 -> max ? box.maxX() : box.minX();
            case 1 -> max ? box.maxY() : box.minY();
            default -> max ? box.maxZ() : box.minZ();
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
     * {@code "south"}, {@code "west"}, {@code "east"}) into its {@code BlockFace} constant via
     * an O(1) lookup against {@link #BY_NAME}. Returns {@code null} when the name is
     * {@code null} or unrecognized.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable BlockFace fromName(@Nullable String name) {
        return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Per-face data used by {@link #defaultUv(Box)} for Java block-model element unwrap.
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
    public record Layout(
        int widthAxis,
        int heightAxis,
        boolean uInverted,
        boolean vInverted
    ) {}

}
