package lib.minecraft.renderer.vanilla.mesh;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.equipment.ArmorForm;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * The vanilla pixel lattice behind each player body scope - the parts a scope draws, the scale one
 * skin pixel spans in its frame, and the box each of those parts is seated in.
 *
 * <p>A scope names its own parts, in the order they are drawn. Its pixel extent is then the union of
 * those parts' own boxes rather than a second statement of the same numbers - {@link #SKULL} is one
 * 8x8 head, {@link #BUST} is 20 by 16 from the torso's floor to the head's ceiling, {@link #FULL} the
 * 32 by 16 of the whole vanilla body.
 *
 * <p>{@link #boxes} seats each part in this scope's own model frame, and a scope's four union
 * integers plus each part's own pixel box are all any reader of this lattice takes.
 *
 * <p>{@link #SKULL} is the one scope that {@link HumanoidPart#centred centres} its part instead of
 * seating it in the body lattice, which is why it also carries a different scale: an 8-pixel head
 * spanning one unit is {@code 0.125} per pixel against the body's {@code 0.03}.
 */
@Parity(as = PlayerRenderer.class)
@Getter(style = NamingStyle.FLUENT)
@Parity(claim = "option-surface")
public enum PlayerLattice {

    /**
     * Head only, centred on the origin.
     */
    SKULL(0.125f, true, HumanoidPart.HEAD),

    /**
     * Head, torso and arms.
     */
    BUST(0.03f, false,
        HumanoidPart.HEAD, HumanoidPart.TORSO, HumanoidPart.RIGHT_ARM, HumanoidPart.LEFT_ARM),

    /**
     * Full body - head, torso, arms and legs.
     */
    FULL(0.03f, false,
        HumanoidPart.HEAD, HumanoidPart.TORSO, HumanoidPart.RIGHT_ARM, HumanoidPart.LEFT_ARM,
        HumanoidPart.RIGHT_LEG, HumanoidPart.LEFT_LEG);

    /**
     * The model units one skin pixel spans in this scope's frame.
     */
    @Getter(AccessLevel.NONE)
    private final float unitsPerPixel;

    /**
     * Whether this scope centres its part on the origin rather than seating it in the body
     * lattice.
     */
    @Getter(AccessLevel.NONE)
    private final boolean centred;

    /**
     * The parts this scope draws, in draw order.
     */
    private final @NotNull ConcurrentList<HumanoidPart> parts;

    /** The left edge of this scope's union, in lattice pixels. */
    private final int minPixelX;

    /** The right edge of this scope's union, in lattice pixels. */
    private final int maxPixelX;

    /** The floor of this scope's union, in lattice pixels counted upward. */
    private final int minPixelY;

    /** The ceiling of this scope's union, in lattice pixels counted upward. */
    private final int maxPixelY;

    /**
     * Each part this scope draws, in the box this scope seats it in - the same answers
     * {@link #boxOf} gives, in an {@link EnumMap} so the iteration order is ordinal order.
     *
     * <p>Tabulated once per constant rather than assembled per render, because a scope's boxes
     * are a function of the scope alone.
     */
    private final @NotNull Map<HumanoidPart, Box> boxes;

    PlayerLattice(float unitsPerPixel, boolean centred, @NotNull HumanoidPart @NotNull ... parts) {
        this.unitsPerPixel = unitsPerPixel;
        this.centred = centred;
        this.parts = Concurrent.newUnmodifiableList(parts);

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (HumanoidPart part : parts) {
            minX = Math.min(minX, part.minPixelX());
            maxX = Math.max(maxX, part.maxPixelX());
            minY = Math.min(minY, part.minPixelY());
            maxY = Math.max(maxY, part.maxPixelY());
        }
        this.minPixelX = minX;
        this.maxPixelX = maxX;
        this.minPixelY = minY;
        this.maxPixelY = maxY;

        Map<HumanoidPart, Box> seated = new EnumMap<>(HumanoidPart.class);
        for (HumanoidPart part : parts) seated.put(part, boxOf(part));
        this.boxes = Collections.unmodifiableMap(seated);
    }

    /**
     * This scope's box for one of its parts - centred on the origin for a scope that draws a part
     * alone, seated in the body lattice otherwise.
     *
     * @param part the part to place
     * @return the part's box in this scope's frame
     */
    public @NotNull Box boxOf(@NotNull HumanoidPart part) {
        return this.centred ? part.centred(this.unitsPerPixel) : part.box(this.unitsPerPixel);
    }

    /**
     * Pixel width of the 2D body composite, before output scaling - the union pixel width of this
     * scope's parts.
     *
     * @return the union pixel width
     */
    public int bodyWidth() {
        return this.maxPixelX - this.minPixelX;
    }

    /**
     * Pixel height of the 2D body composite, before output scaling - the union pixel height of this
     * scope's parts.
     *
     * @return the union pixel height
     */
    public int bodyHeight() {
        return this.maxPixelY - this.minPixelY;
    }

    /**
     * The slots covering each of the player's own body parts, resolved once from
     * {@link ArmorForm#ADULT}'s part table by reading each bone name back to the body box it
     * dresses.
     */
    private static final @NotNull Map<HumanoidPart, ConcurrentSet<ArmorSlot>> PLAYER_SLOTS = new EnumMap<>(HumanoidPart.class);

    static {
        HumanoidPart.forEach(part -> {
            EnumSet<ArmorSlot> slots = EnumSet.noneOf(ArmorSlot.class);

            ArmorSlot.stream()
                .filter(slot -> ArmorForm.ADULT.parts(slot).contains(part.boneName()))
                .forEach(slots::add);

            PLAYER_SLOTS.put(part, Concurrent.newUnmodifiableSet(slots));
        });
    }

    /**
     * The slots whose armor covers one of the player's own body parts.
     *
     * <p><b>Restricted to {@link ArmorForm#ADULT}, and the restriction is the guard rather than an
     * oversight.</b> The player is always drawn at adult proportions, and only six of the twelve bone
     * names in the armor corpus have a body part at all - a baby shell's {@code waist} and its two
     * feet have none. A form-parameterised accessor would make those reachable, where the miss would
     * drop a box silently rather than raising anything.
     *
     * @param part the player body part
     * @return the slots whose armor draws that part, empty when none does
     */
    public static @NotNull ConcurrentSet<ArmorSlot> playerSlots(@NotNull HumanoidPart part) {
        return PLAYER_SLOTS.get(part);
    }

}
