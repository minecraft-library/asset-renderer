package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The six boxes a vanilla humanoid player model is built from - each one's pixel extent, the bone
 * name it answers to, and the region of a skin sheet its faces are read out of.
 * <p>
 * Each constant declares an integer pixel box in the vanilla player lattice, where the body spans
 * {@code -16..+16} on Y - the 32-pixel vanilla player height - plus the two atlas origins its base
 * and overlay layers are unwrapped from. <b>Everything else is derived.</b> The six skin rectangles
 * per layer are {@link EntityFace#defaultUv} evaluated at that origin under the {@link Turn#HALF_X
 * model frame turn}, which is the same unwrap the entity cube path resolves its UVs from - the player
 * skin unwrap and the entity cube unwrap are one function, and the overlay column is that same
 * function against a different shipped cube (the head's is vanilla's own {@code hat} cube, the
 * torso's its {@code jacket}).
 * <p>
 * <b>Each endpoint is derived from its own integer pixel offset</b>, never as an origin plus an
 * extent. At the body lattice's {@code 0.03} units per pixel that is not a stylistic choice: every
 * limb is 12 pixels long and {@code 12 * 0.03f} is one ULP below the {@code 0.36f} the shipped
 * constants spelled, while every endpoint offset the lattice uses - 2, 4, 8 and 16 pixels -
 * multiplies bit-exactly.
 * <p>
 * Skin layout reference (vanilla 64x64):
 * <pre>
 * Head base        0..32, 0..16    Head overlay    32..64, 0..16
 * Torso base      16..40, 16..32   Torso overlay   16..40, 32..48
 * Right arm base  40..56, 16..32   Right arm overlay 40..56, 32..48
 * Left arm base   32..48, 48..64   Left arm overlay  48..64, 48..64
 * Right leg base   0..16, 16..32   Right leg overlay  0..16, 32..48
 * Left leg base   16..32, 48..64   Left leg overlay   0..16, 48..64
 * </pre>
 */
@Getter
@Accessors(fluent = true)
public enum HumanoidPart {

    //        bone name      min px          max px        base uv   overlay uv
    HEAD     ("head",       -4,   8, -4,     4, 16,  4,     0,  0,    32,  0),
    TORSO    ("body",       -4,  -4, -2,     4,  8,  2,    16, 16,    16, 32),
    RIGHT_ARM("right_arm",  -8,  -4, -2,    -4,  8,  2,    40, 16,    40, 32),
    LEFT_ARM ("left_arm",    4,  -4, -2,     8,  8,  2,    32, 48,    48, 48),
    RIGHT_LEG("right_leg",  -4, -16, -2,     0, -4,  2,     0, 16,     0, 32),
    LEFT_LEG ("left_leg",    0, -16, -2,     4, -4,  2,    16, 48,     0, 48);

    /**
     * Cached snapshot of {@link #values()} reused by lookups and iteration to avoid the per-call
     * defensive array clone the JLS mandates.
     */
    public static final HumanoidPart @NotNull [] CACHED_VALUES = values();

    /** Index of {@link #boneName bone names} to parts, powering {@link #byBoneName}. */
    private static final @NotNull Map<String, HumanoidPart> BY_BONE_NAME;

    static {
        Map<String, HumanoidPart> byName = new HashMap<>(CACHED_VALUES.length * 2);

        for (HumanoidPart part : CACHED_VALUES)
            byName.put(part.boneName, part);

        BY_BONE_NAME = Map.copyOf(byName);
    }

    /**
     * The armor-mesh bone this part is the player's counterpart of. The shell vanilla dresses a
     * humanoid in names its boxes with these, so a bone resolves to the body box it dresses.
     */
    private final @NotNull String boneName;

    /**
     * Lower corner of this part's box on X, in vanilla player-lattice pixels.
     */
    private final int minPixelX;

    /**
     * Lower corner of this part's box on Y, in vanilla player-lattice pixels.
     */
    private final int minPixelY;

    /**
     * Lower corner of this part's box on Z, in vanilla player-lattice pixels.
     */
    private final int minPixelZ;

    /**
     * Upper corner of this part's box on X, in vanilla player-lattice pixels.
     */
    private final int maxPixelX;

    /**
     * Upper corner of this part's box on Y, in vanilla player-lattice pixels.
     */
    private final int maxPixelY;

    /**
     * Upper corner of this part's box on Z, in vanilla player-lattice pixels.
     */
    private final int maxPixelZ;

    /**
     * Base-layer skin rectangle per {@link BlockFace}, derived from the base atlas origin and this
     * part's pixel extent.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<BlockFace, Rectangle> baseRects;

    /**
     * Overlay-layer (hat / jacket) skin rectangle per {@link BlockFace}, derived from the overlay
     * atlas origin and this part's pixel extent.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull EnumMap<BlockFace, Rectangle> overlayRects;

    HumanoidPart(
        @NotNull String boneName,
        int minPixelX, int minPixelY, int minPixelZ,
        int maxPixelX, int maxPixelY, int maxPixelZ,
        int baseU, int baseV, int overlayU, int overlayV
    ) {
        this.boneName = boneName;
        this.minPixelX = minPixelX;
        this.minPixelY = minPixelY;
        this.minPixelZ = minPixelZ;
        this.maxPixelX = maxPixelX;
        this.maxPixelY = maxPixelY;
        this.maxPixelZ = maxPixelZ;

        Vector3f size = new Vector3f(
            maxPixelX - minPixelX, maxPixelY - minPixelY, maxPixelZ - minPixelZ);
        this.baseRects = unwrap(new Vector2f(baseU, baseV), size);
        this.overlayRects = unwrap(new Vector2f(overlayU, overlayV), size);
    }

    /**
     * The part a bone name belongs to, or {@code null} when that bone has no player counterpart.
     * <p>
     * Only six of the twelve bone names in the armor corpus have one - a baby shell's {@code waist}
     * and its two feet have none, and the head's two overlay bones are drawn as this part's overlay
     * layer rather than as parts of their own.
     *
     * @param boneName the shell bone name
     * @return the matching part, or {@code null} when that bone dresses no player box
     */
    public static @Nullable HumanoidPart byBoneName(@NotNull String boneName) {
        return BY_BONE_NAME.get(boneName);
    }

    /**
     * This part's extent along the horizontal axis, in pixels.
     *
     * @return the pixel width
     */
    public int pixelWidth() {
        return this.maxPixelX - this.minPixelX;
    }

    /**
     * This part's extent along the vertical axis, in pixels.
     *
     * @return the pixel height
     */
    public int pixelHeight() {
        return this.maxPixelY - this.minPixelY;
    }

    /**
     * This part's box seated in the player lattice, at the given scale.
     * <p>
     * Each endpoint is its own pixel offset times the scale, never an origin plus a scaled extent -
     * the second form is a full ULP low on every 12-pixel limb at the body scale, while this one is
     * bit-exact on every endpoint the lattice uses.
     *
     * @param unitsPerPixel the model units one skin pixel spans in the target frame
     * @return this part's axis-aligned box
     */
    public @NotNull Box box(float unitsPerPixel) {
        return new Box(
            this.minPixelX * unitsPerPixel, this.minPixelY * unitsPerPixel, this.minPixelZ * unitsPerPixel,
            this.maxPixelX * unitsPerPixel, this.maxPixelY * unitsPerPixel, this.maxPixelZ * unitsPerPixel);
    }

    /**
     * This part's box centred on the origin, at the given scale.
     * <p>
     * <b>Not the same box as {@link #box} in a different frame.</b> A scope that draws one part alone
     * centres it rather than seating it in the body lattice, so the head - whose lattice box spans
     * {@code y 8..16} - becomes a cube about the origin instead.
     *
     * @param unitsPerPixel the model units one skin pixel spans in the target frame
     * @return this part's box, centred on the origin
     */
    public @NotNull Box centred(float unitsPerPixel) {
        float halfX = pixelWidth() * 0.5f * unitsPerPixel;
        float halfY = pixelHeight() * 0.5f * unitsPerPixel;
        float halfZ = (this.maxPixelZ - this.minPixelZ) * 0.5f * unitsPerPixel;
        return new Box(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);
    }

    /**
     * Crops a single face out of the given skin image using the base or overlay rectangle for this
     * part's face.
     * <p>
     * Legacy 64x32 skins and the 64x32 equipment atlases carry only the top half of the modern
     * layout - head, body, the <b>right</b> arm and the <b>right</b> leg - with no dedicated
     * left-limb (or overlay) regions; vanilla draws the left limb as the right limb mirrored across
     * the sagittal plane. When a base-layer rectangle falls past the bottom of the texture (the
     * tell-tale of that format) the left arm / leg is therefore sampled from the mirrored right
     * limb. A full 64x64 atlas keeps its own left regions and is cropped directly.
     * <p>
     * <b>That branch is the production armor path, not a legacy corner</b> - the armor atlas is
     * 64x32, so every armored player render takes it on the left arm and the left leg. It also
     * cannot be folded into a turn or a mirror flag: it substitutes a <em>different part's</em> atlas
     * origin, which no permutation of this part's own strip can reach.
     *
     * @param skin the source skin image
     * @param face the cube face to crop
     * @param overlayLayer whether to crop the overlay layer instead of the base layer
     * @return a new pixel buffer whose dimensions match the face's rectangle
     */
    public @NotNull PixelBuffer crop(
        @NotNull PixelBuffer skin,
        @NotNull BlockFace face,
        boolean overlayLayer
    ) {
        Rectangle rect = rect(face, overlayLayer);

        if (!overlayLayer && rect.y + rect.height > skin.height()) {
            HumanoidPart mirror = legacyMirrorSource();

            if (mirror != this)
                return cropRect(skin, mirror.rect(Turn.MIRROR_X.apply(face), false), true);
        }

        return cropRect(skin, rect, false);
    }

    /**
     * Crops all six faces of this part out of the skin image into a {@link SixFaces} ready to feed
     * the block geometry kit's box-triangle builder.
     *
     * @param skin the source skin image
     * @param overlayLayer whether to crop the overlay layer instead of the base layer
     * @return the six cropped faces keyed by {@link BlockFace} direction
     */
    public @NotNull SixFaces cropAll(@NotNull PixelBuffer skin, boolean overlayLayer) {
        return new SixFaces(
            crop(skin, BlockFace.DOWN, overlayLayer),
            crop(skin, BlockFace.UP, overlayLayer),
            crop(skin, BlockFace.NORTH, overlayLayer),
            crop(skin, BlockFace.SOUTH, overlayLayer),
            crop(skin, BlockFace.WEST, overlayLayer),
            crop(skin, BlockFace.EAST, overlayLayer)
        );
    }

    /**
     * The skin texture rectangle for the given face on the base or overlay layer.
     */
    private @NotNull Rectangle rect(@NotNull BlockFace face, boolean overlayLayer) {
        return overlayLayer ? this.overlayRects.get(face) : this.baseRects.get(face);
    }

    /**
     * Unwraps one cube onto a sheet, face by face, through the entity-atlas unwrap read in the model
     * frame - the same derivation every entity cube's UVs come from.
     */
    private static @NotNull EnumMap<BlockFace, Rectangle> unwrap(
        @NotNull Vector2f origin, @NotNull Vector3f size) {
        EnumMap<BlockFace, Rectangle> rects = new EnumMap<>(BlockFace.class);

        for (BlockFace face : BlockFace.CACHED_VALUES) {
            Vector4f strip = Turn.HALF_X.entityFace(face).defaultUv(origin, size);
            rects.put(face, new Rectangle((int) strip.x(), (int) strip.y(),
                (int) (strip.z() - strip.x()), (int) (strip.w() - strip.y())));
        }

        return rects;
    }

    /**
     * Copies a rectangle out of the skin image, optionally mirrored across its vertical centre line
     * (a sagittal-plane flip used to derive a left limb from the right limb on legacy atlases).
     * Out-of-bounds source pixels are left transparent.
     */
    private static @NotNull PixelBuffer cropRect(@NotNull PixelBuffer skin, @NotNull Rectangle rect, boolean mirrorX) {
        int w = rect.width;
        int h = rect.height;
        int[] pixels = new int[w * h];

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int sx = rect.x + dx;
                int sy = rect.y + dy;
                if (sx < 0 || sx >= skin.width() || sy < 0 || sy >= skin.height()) continue;
                int tx = mirrorX ? w - 1 - dx : dx;
                pixels[dy * w + tx] = skin.getPixel(sx, sy);
            }
        }

        return PixelBuffer.of(pixels, w, h);
    }

    /**
     * The right-limb part whose texture a left limb mirrors on a legacy 64x32 atlas, or this part
     * itself when no left-limb mirroring applies.
     */
    private @NotNull HumanoidPart legacyMirrorSource() {
        return switch (this) {
            case LEFT_ARM -> RIGHT_ARM;
            case LEFT_LEG -> RIGHT_LEG;
            default -> this;
        };
    }

}
