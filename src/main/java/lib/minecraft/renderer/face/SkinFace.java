package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.EnumMap;

/**
 * Catalogs the six body parts on a vanilla 64x64 player skin and crops each one's six faces out of a
 * sheet.
 * <p>
 * Each constant declares its box dimensions (width, height, depth in skin pixels) and the
 * {@code [baseX, baseY, overlayX, overlayY]} skin coordinates for its six faces in {@link BlockFace}
 * declaration order. The constructor converts those coordinates into {@link Rectangle} mappings sized
 * from the body part's dimensions.
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
public enum SkinFace {

    HEAD(8, 8, 8, new int[][]{
        { 16, 0, 48, 0 },  // DOWN  (bottom of head)
        {  8, 0, 40, 0 },  // UP    (top of head)
        { 24, 8, 56, 8 },  // NORTH (back of head)
        {  8, 8, 40, 8 },  // SOUTH (front/face)
        {  0, 8, 32, 8 },  // WEST  (character's right, viewer's left)
        { 16, 8, 48, 8 }   // EAST  (character's left, viewer's right)
    }),
    TORSO(8, 12, 4, new int[][]{
        { 28, 16, 28, 32 },
        { 20, 16, 20, 32 },
        { 32, 20, 32, 36 },
        { 20, 20, 20, 36 },
        { 16, 20, 16, 36 },
        { 28, 20, 28, 36 }
    }),
    RIGHT_ARM(4, 12, 4, new int[][]{
        { 48, 16, 48, 32 },
        { 44, 16, 44, 32 },
        { 52, 20, 52, 36 },
        { 44, 20, 44, 36 },
        { 40, 20, 40, 36 },
        { 48, 20, 48, 36 }
    }),
    LEFT_ARM(4, 12, 4, new int[][]{
        { 40, 48, 56, 48 },
        { 36, 48, 52, 48 },
        { 44, 52, 60, 52 },
        { 36, 52, 52, 52 },
        { 32, 52, 48, 52 },
        { 40, 52, 56, 52 }
    }),
    RIGHT_LEG(4, 12, 4, new int[][]{
        {  8, 16,  8, 32 },
        {  4, 16,  4, 32 },
        { 12, 20, 12, 36 },
        {  4, 20,  4, 36 },
        {  0, 20,  0, 36 },
        {  8, 20,  8, 36 }
    }),
    LEFT_LEG(4, 12, 4, new int[][]{
        { 24, 48,  8, 48 },
        { 20, 48,  4, 48 },
        { 28, 52, 12, 52 },
        { 20, 52,  4, 52 },
        { 16, 52,  0, 52 },
        { 24, 52,  8, 52 }
    });

    /**
     * Base-layer skin rectangle per {@link BlockFace}, sized from the box dimensions and offset by
     * this body part's {@code baseX}/{@code baseY} coordinates.
     */
    private final @NotNull EnumMap<BlockFace, Rectangle> baseMappings;

    /**
     * Overlay-layer (hat / jacket) skin rectangle per {@link BlockFace}, sized from the box
     * dimensions and offset by this body part's {@code overlayX}/{@code overlayY} coordinates.
     */
    private final @NotNull EnumMap<BlockFace, Rectangle> overlayMappings;

    /**
     * Builds a body part from its box dimensions and the six per-face skin coordinates, populating
     * the base and overlay rectangle maps (sized via {@link #faceSize}).
     *
     * @param width the box width in skin pixels
     * @param height the box height in skin pixels
     * @param depth the box depth in skin pixels
     * @param faceCoords the six {@code [baseX, baseY, overlayX, overlayY]} skin coordinate rows in
     *     {@link BlockFace} declaration order
     */
    SkinFace(int width, int height, int depth, int @NotNull [] @NotNull [] faceCoords) {
        this.baseMappings = new EnumMap<>(BlockFace.class);
        this.overlayMappings = new EnumMap<>(BlockFace.class);

        for (int i = 0; i < BlockFace.CACHED_VALUES.length; i++) {
            BlockFace face = BlockFace.CACHED_VALUES[i];
            int[] xy = faceCoords[i];
            int[] size = faceSize(face, width, height, depth);
            this.baseMappings.put(face, new Rectangle(xy[0], xy[1], size[0], size[1]));
            this.overlayMappings.put(face, new Rectangle(xy[2], xy[3], size[0], size[1]));
        }
    }

    /**
     * Returns the skin texture rectangle for the given face on the base or overlay layer.
     */
    private @NotNull Rectangle mapping(@NotNull BlockFace face, boolean overlayLayer) {
        return overlayLayer ? this.overlayMappings.get(face) : this.baseMappings.get(face);
    }

    /**
     * Crops a single face out of the given skin image using the base or overlay rectangle for this
     * body part's face.
     * <p>
     * Legacy 64x32 skins and the 64x32 equipment atlases carry only the top half of the modern
     * layout - head, body, the <b>right</b> arm and the <b>right</b> leg - with no dedicated
     * left-limb (or overlay) regions; vanilla draws the left limb as the right limb mirrored across
     * the sagittal plane. When a base-layer rectangle falls past the bottom of the texture (the
     * tell-tale of that format) the left arm / leg is therefore sampled from the mirrored right
     * limb. A full 64x64 atlas keeps its own left regions and is cropped directly.
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
        Rectangle rect = mapping(face, overlayLayer);

        if (!overlayLayer && rect.y + rect.height > skin.height()) {
            SkinFace mirror = legacyMirrorSource();

            if (mirror != this)
                return cropRect(skin, mirror.mapping(Turn.MIRROR_X.apply(face), false), true);
        }

        return cropRect(skin, rect, false);
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
    private @NotNull SkinFace legacyMirrorSource() {
        return switch (this) {
            case LEFT_ARM -> RIGHT_ARM;
            case LEFT_LEG -> RIGHT_LEG;
            default -> this;
        };
    }

    /**
     * Crops all six faces of this body part out of the skin image into a {@link SixFaces} ready
     * to feed the block geometry kit's box-triangle builder.
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
     * Resolves the {@code (width, height)} of a single face rectangle given the body part's box
     * dimensions. Top and bottom faces project width and depth; north and south faces project
     * width and height; east and west faces project depth and height.
     */
    private static int @NotNull [] faceSize(@NotNull BlockFace face, int width, int height, int depth) {
        return switch (face) {
            case DOWN, UP -> new int[]{ width, depth };
            case NORTH, SOUTH -> new int[]{ width, height };
            case WEST, EAST -> new int[]{ depth, height };
        };
    }

}
