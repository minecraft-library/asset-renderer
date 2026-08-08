package lib.minecraft.renderer.face;

import lib.minecraft.renderer.tensor.Vector3f;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The six cardinal face directions of an axis-aligned Minecraft cube.
 * <p>
 * One direction vocabulary serves every subject the renderer draws. What varies between a block
 * element, an entity cube and a player skin box is not the set of directions but two things that vary
 * <b>independently of each other and of the subject</b>: which of a face's four corners a quad starts
 * at, and how a texture rectangle is derived for it. Those are {@link CornerPhase} and {@link Unwrap},
 * and a caller picks one of each - the shield takes the bakery corner phase with the atlas unwrap and
 * is correct, while the block-entity path takes the polygon phase.
 * <p>
 * Each constant carries its lowercase {@link #direction direction name} ({@code "down"} etc., matching
 * the vanilla block / item model JSON key and the per-face {@code cube.uv} override key), its outward
 * unit {@link #normal}, an inventory {@link #lighting shade factor}, and the per-face columns the two
 * unwraps read. {@link #axis} and the two in-plane axes are derived from the normal rather than
 * tabulated: the shipped {@code (widthAxis, heightAxis)} pairs are exactly a function of which axis the
 * face faces along.
 * <p>
 * The {@link #lighting} field carries the shade factor applied to this face under vanilla's
 * {@code Lighting.ITEMS_3D} GUI pose. Note the per-axis values are <b>reversed</b> relative to
 * world-lit block brightness from {@code Direction.getBrightness}: vanilla's inventory pipeline uses
 * two directional lights offset in X so the left-hand (E/W) face ends up brighter than the right-hand
 * (N/S) face after the standard {@code [30, 225, 0]} gui rotation. Rather than replicate the
 * dual-directional light shader, each face carries a pre-baked scalar that approximates the vanilla
 * inventory output ({@code 0.8} for E/W, {@code 0.6} for N/S, {@code 1.0} for UP, {@code 0.5} for
 * DOWN). Entity rendering does not read it at all - vanilla's {@code Lighting.Entry.ENTITY_IN_UI} is a
 * dual-directional Lambertian shader, so an entity's shade is computed per-vertex from the surface
 * normal and baked into each triangle at kit time.
 * <p>
 * Callers that have a surface normal rather than a face resolve it via {@link #fromNormal(Vector3f)};
 * callers that have a direction string use {@link #fromName(String)}.
 */
@Getter
@Accessors(fluent = true)
public enum Face {

    //     outward normal                lighting   element inversion   atlas U coefficients
    DOWN (new Vector3f(0f, -1f, 0f),       0.5f,     false, true,        0, 1),
    UP   (new Vector3f(0f, 1f, 0f),        1.0f,     false, false,       1, 1),
    NORTH(new Vector3f(0f, 0f, -1f),       0.6f,     true, true,         0, 1),
    SOUTH(new Vector3f(0f, 0f, 1f),        0.6f,     false, true,        1, 2),
    WEST (new Vector3f(-1f, 0f, 0f),       0.8f,     false, true,        0, 0),
    EAST (new Vector3f(1f, 0f, 0f),        0.8f,     true, true,         1, 1);

    /**
     * Cached snapshot of {@link #values()} reused by lookups and iteration to avoid the per-call
     * defensive array clone the JLS mandates.
     */
    public static final Face @NotNull [] CACHED_VALUES = values();

    /**
     * Index of {@link #direction direction names} to enum constants for O(1) lookup by lowercase
     * name. Powers {@link #fromName(String)}.
     */
    private static final @NotNull Map<String, Face> BY_NAME;

    static {
        Map<String, Face> byName = new HashMap<>(CACHED_VALUES.length * 2);

        for (Face face : CACHED_VALUES)
            byName.put(face.direction, face);

        BY_NAME = Map.copyOf(byName);
    }

    /**
     * Lowercase direction name ({@code "down"}, {@code "up"}, ...), derived once at class-load
     * time from {@link #name()} so external callers don't pay a per-call {@code toLowerCase}.
     * Matches the vanilla block / item model JSON key and the vanilla {@code Direction} key.
     */
    private final @NotNull String direction = this.name().toLowerCase(Locale.ROOT);

    /**
     * Outward unit normal of this face in model space.
     */
    private final @NotNull Vector3f normal;

    /**
     * Shade factor applied to this face under vanilla {@code Lighting.ITEMS_3D} GUI pose. E/W
     * faces are intentionally brighter than N/S to match vanilla's dual-directional light rig
     * under the standard {@code [30, 225, 0]} display rotation - the opposite of world-lit block
     * brightness. Bottom is half-bright, top is fully bright.
     */
    private final float lighting;

    /**
     * Whether the block-element unwrap reads {@code 16 - value} instead of {@code value} on U for this
     * face, reproducing vanilla's {@code FaceBakery.defaultFaceUV}.
     */
    private final boolean elementUInverted;

    /**
     * Whether the block-element unwrap reads {@code 16 - value} instead of {@code value} on V for this
     * face.
     */
    private final boolean elementVInverted;

    /**
     * The {@code sx} coefficient in the entity-atlas U offset {@code uOff = uSx * sx + uSz * sz}.
     */
    private final int atlasUSxCoef;

    /**
     * The {@code sz} coefficient in the entity-atlas U offset {@code uOff = uSx * sx + uSz * sz}.
     */
    private final int atlasUSzCoef;

    /**
     * The axis this face faces along ({@code 0=x}, {@code 1=y}, {@code 2=z}), read off the normal's
     * one non-zero component.
     */
    private final int axis;

    Face(
        @NotNull Vector3f normal,
        float lighting,
        boolean elementUInverted,
        boolean elementVInverted,
        int atlasUSxCoef,
        int atlasUSzCoef
    ) {
        this.normal = normal;
        this.lighting = lighting;
        this.elementUInverted = elementUInverted;
        this.elementVInverted = elementVInverted;
        this.atlasUSxCoef = atlasUSxCoef;
        this.atlasUSzCoef = atlasUSzCoef;
        this.axis = normal.x() != 0f ? 0 : (normal.y() != 0f ? 1 : 2);
    }

    /**
     * The face on this face's own axis pointing the other way.
     * <p>
     * The constants are declared in opposing pairs - {@code DOWN, UP} on Y, {@code NORTH, SOUTH} on Z,
     * {@code WEST, EAST} on X - so an opposite is one bit of the ordinal. That layout is what lets
     * {@link Turn} name a frame relation without a table.
     *
     * @return the opposing face on the same axis
     */
    public @NotNull Face opposite() {
        return CACHED_VALUES[this.ordinal() ^ 1];
    }

    /**
     * Which of {@code [x, y, z]} maps to U for this face ({@code 0=x}, {@code 1=y}, {@code 2=z}).
     * <p>
     * A face's two in-plane axes follow from the axis it faces along, so this is a function rather than
     * a per-constant column: a Y face unwraps {@code (x, z)}, a Z face {@code (x, y)} and an X face
     * {@code (z, y)}.
     *
     * @return the size-axis index that maps to U
     */
    public int widthAxis() {
        return this.axis == 0 ? 2 : 0;
    }

    /**
     * Which of {@code [x, y, z]} maps to V for this face ({@code 0=x}, {@code 1=y}, {@code 2=z}).
     *
     * @return the size-axis index that maps to V
     */
    public int heightAxis() {
        return this.axis == 1 ? 2 : 1;
    }

    /**
     * Resolves the dominant cardinal face for a surface normal - the single {@code Face} vanilla would
     * assign a baked quad with this geometric normal. Replicates vanilla's
     * {@code FaceBakery.findClosestDirection} plus the {@code BakedQuad} constructor's
     * {@code requireNonNullElse(direction, UP)} degenerate fallback in one place: it is the shared
     * cardinal resolver for both inventory-style lighting and the block-icon relight snap.
     * <p>
     * The largest-magnitude axis wins; the sign of that component picks between the two opposing
     * faces on that axis. Only component-magnitude ordering matters, so the result is invariant to
     * a positive uniform scale of {@code normal} - an un-normalized normal works too. The
     * magnitude {@code if}-chain is used over an equivalent six-cardinal dot-product loop: it
     * resolves the same face with three {@code abs} calls and at most three comparisons, no array
     * or iteration.
     * <p>
     * <b>Ties</b> resolve to the earlier axis in {@code Y > Z > X} order (the {@code >=}
     * comparisons), matching the {@code Direction.values()} (DOWN, UP, NORTH, SOUTH, WEST, EAST)
     * first-wins iteration order of vanilla's {@code findClosestDirection}. This is load-bearing
     * for exact 45-degree faces: a sculk-sensor tendril whose authored normal has {@code |x| ==
     * |z|} must snap to the Z cardinal (shade 0.40), not EAST/WEST (0.65/0.49). The element-
     * rotation matrix yields a bit-symmetric {@code |x| == |z|} normal, so the tie falls to the
     * earlier (Z) axis and reproduces the reference shade. (Folding the relight snap onto the
     * opposite {@code Y > X > Z}-style tie-break regresses 8 cross / candle / stem blocks out of
     * the {@code <0.25} block-parity bucket - measured, not assumed.) A zero normal (cross of
     * collinear edges) has no winning axis and falls back to {@link #UP}.
     * <p>
     * Two consumers share this single resolver: {@code Lighting.inventory} bakes
     * the per-face {@code Lighting.ITEMS_3D}-style cardinal shade for block + fluid kits, and the
     * block-icon relight pass ({@code Shading.relightForItems3d}) snaps a plain-block quad's
     * authored normal to its nearest cardinal before lighting - matching vanilla's
     * {@code BlockFeatureRenderer.putBakedQuad}, which lights every quad by its single
     * {@code BakedQuad.direction} rather than the continuous tilted normal.
     *
     * @param normal the surface normal (should be normalized, but only magnitude ordering matters)
     * @return the closest cardinal face to the normal direction, or {@link #UP} for a zero normal
     */
    public static @NotNull Face fromNormal(@NotNull Vector3f normal) {
        float absX = Math.abs(normal.x());
        float absY = Math.abs(normal.y());
        float absZ = Math.abs(normal.z());

        // Degenerate (zero) normal: vanilla's FaceBakery.calculateFacing returns null, mapped to UP
        // by the BakedQuad constructor's requireNonNullElse(direction, UP). Match that fallback.
        if (absX == 0f && absY == 0f && absZ == 0f) return UP;

        if (absY >= absX && absY >= absZ)
            return normal.y() > 0f ? UP : DOWN;

        if (absZ >= absX)
            return normal.z() > 0f ? SOUTH : NORTH;

        return normal.x() > 0f ? EAST : WEST;
    }

    /**
     * Parses a lowercase direction name ({@code "down"}, {@code "up"}, {@code "north"},
     * {@code "south"}, {@code "west"}, {@code "east"}) into its {@code Face} constant via
     * an O(1) lookup against {@link #BY_NAME}. Returns {@code null} when the name is
     * {@code null} or unrecognized.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable Face fromName(@Nullable String name) {
        return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

}
