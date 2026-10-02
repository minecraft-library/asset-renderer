package lib.minecraft.renderer.engine.geometry;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

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
 * unit {@link #normal}, and the per-face columns the two unwraps read. {@link #axis} and the two
 * in-plane axes are derived from the normal rather than tabulated: the shipped
 * {@code (widthAxis, heightAxis)} pairs are exactly a function of which axis the face faces along.
 * <p>
 * Callers that have a direction string use {@link #fromName(String)}.
 */
@EnumLookup
@Getter(style = NamingStyle.FLUENT)
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
public enum Face {

    //     outward normal                element inversion   atlas U coefficients
    DOWN (new Vector3f(0f, -1f, 0f),       false, true,        0, 1),
    UP   (new Vector3f(0f, 1f, 0f),        false, false,       1, 1),
    NORTH(new Vector3f(0f, 0f, -1f),       true, true,         0, 1),
    SOUTH(new Vector3f(0f, 0f, 1f),        false, true,        1, 2),
    WEST (new Vector3f(-1f, 0f, 0f),       false, true,        0, 0),
    EAST (new Vector3f(1f, 0f, 0f),        true, true,         1, 1);

    /**
     * Lowercase direction name ({@code "down"}, {@code "up"}, ...), derived once at class-load
     * time from {@link #name()} so external callers don't pay a per-call {@code toLowerCase}.
     * Matches the vanilla block / item model JSON key and the vanilla {@code Direction} key, and
     * is the key {@link #fromName(String)} resolves a face by.
     */
    @KeyField(ignoreCase = true)
    private final @NotNull String direction = this.name().toLowerCase(Locale.ROOT);

    /**
     * Outward unit normal of this face in model space.
     */
    private final @NotNull Vector3f normal;

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
        boolean elementUInverted,
        boolean elementVInverted,
        int atlasUSxCoef,
        int atlasUSzCoef
    ) {
        this.normal = normal;
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
     * {@link AxisSigns} name a frame relation without a table.
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
     * Parses a direction name ({@code "down"}, {@code "up"}, {@code "north"}, {@code "south"},
     * {@code "west"}, {@code "east"}) into its {@code Face} constant, matching the
     * {@link #direction} key without regard to case. Returns {@code null} when the name is
     * {@code null} or unrecognized.
     *
     * @param name the direction name, or {@code null}
     * @return the matching face, or {@code null} when the name is {@code null} or unrecognized
     */
    public static @Nullable Face fromName(@Nullable String name) {
        return ofDirection(name);
    }

}
