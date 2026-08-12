package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Every rectangle {@link HumanoidPart} reads out of a sheet - all six faces of all six body parts on
 * both layers, on a 64x64 player skin and on the 64x32 sheet the armour atlases ship as.
 * <p>
 * The sheets are synthetic: each texel encodes its own coordinates, so a cropped face's texels name
 * the region they came from and the assertion reads the rectangle back out of the crop rather than
 * trusting an accessor. That makes the table checkable end to end - origin, extent, and which texel
 * landed where.
 * <p>
 * The 64x32 half is the one that matters most and the one nothing else covers. That sheet is not a
 * legacy corner: the armour atlas <b>is</b> 64x32, so every armoured player render takes the
 * left-limb fallback on the left arm and the left leg. The fallback does two things at once - it
 * reads a <b>different part's</b> region (the right limb's) and it flips the texels left to right -
 * and both are asserted here per face. The head's overlay half survives on that sheet where every
 * other overlay region falls off the bottom, so an overlay crop is asserted on both outcomes.
 */
@DisplayName("HumanoidPart rectangles on a 64x64 skin and a 64x32 sheet")
class HumanoidPartCropTest {

    /**
     * The declared base and overlay origins per part, in {@link Face} declaration order, as
     * {@code {baseX, baseY, overlayX, overlayY}} rows. Transcribed rather than derived, so a change to
     * the table has to be made twice on purpose.
     */
    private static final int[][][] ORIGINS = {
        // HEAD
        { { 16, 0, 48, 0 }, { 8, 0, 40, 0 }, { 24, 8, 56, 8 }, { 8, 8, 40, 8 }, { 0, 8, 32, 8 }, { 16, 8, 48, 8 } },
        // TORSO
        { { 28, 16, 28, 32 }, { 20, 16, 20, 32 }, { 32, 20, 32, 36 }, { 20, 20, 20, 36 }, { 16, 20, 16, 36 }, { 28, 20, 28, 36 } },
        // RIGHT_ARM
        { { 48, 16, 48, 32 }, { 44, 16, 44, 32 }, { 52, 20, 52, 36 }, { 44, 20, 44, 36 }, { 40, 20, 40, 36 }, { 48, 20, 48, 36 } },
        // LEFT_ARM
        { { 40, 48, 56, 48 }, { 36, 48, 52, 48 }, { 44, 52, 60, 52 }, { 36, 52, 52, 52 }, { 32, 52, 48, 52 }, { 40, 52, 56, 52 } },
        // RIGHT_LEG
        { { 8, 16, 8, 32 }, { 4, 16, 4, 32 }, { 12, 20, 12, 36 }, { 4, 20, 4, 36 }, { 0, 20, 0, 36 }, { 8, 20, 8, 36 } },
        // LEFT_LEG
        { { 24, 48, 8, 48 }, { 20, 48, 4, 48 }, { 28, 52, 12, 52 }, { 20, 52, 4, 52 }, { 16, 52, 0, 52 }, { 24, 52, 8, 52 } }
    };

    /** Box dimensions per part, in declaration order, as {@code {width, height, depth}}. */
    private static final int[][] DIMENSIONS = {
        { 8, 8, 8 }, { 8, 12, 4 }, { 4, 12, 4 }, { 4, 12, 4 }, { 4, 12, 4 }, { 4, 12, 4 }
    };

    @Test
    @DisplayName("64x64 skin: every base rectangle reads its declared region unflipped")
    void baseRectanglesOn64x64() {
        PixelBuffer skin = sheet(64, 64);

        for (HumanoidPart part : HumanoidPart.values()) {
            for (Face face : Face.CACHED_VALUES) {
                int[] expected = origin(part, face, false);
                assertRegion(part + "." + face + " base", part.crop(skin, face, false),
                    expected[0], expected[1], size(part, face), false);
            }
        }
    }

    @Test
    @DisplayName("64x64 skin: every overlay rectangle reads its declared region unflipped")
    void overlayRectanglesOn64x64() {
        PixelBuffer skin = sheet(64, 64);

        for (HumanoidPart part : HumanoidPart.values()) {
            for (Face face : Face.CACHED_VALUES) {
                int[] expected = origin(part, face, true);
                assertRegion(part + "." + face + " overlay", part.crop(skin, face, true),
                    expected[0], expected[1], size(part, face), false);
            }
        }
    }

    @Test
    @DisplayName("64x32 sheet: the four right-hand parts keep their own regions")
    void rightHandPartsOn64x32() {
        PixelBuffer atlas = sheet(64, 32);

        for (HumanoidPart part : new HumanoidPart[]{ HumanoidPart.HEAD, HumanoidPart.TORSO, HumanoidPart.RIGHT_ARM, HumanoidPart.RIGHT_LEG }) {
            for (Face face : Face.CACHED_VALUES) {
                int[] expected = origin(part, face, false);
                assertRegion(part + "." + face + " on 64x32", part.crop(atlas, face, false),
                    expected[0], expected[1], size(part, face), false);
            }
        }
    }

    @Test
    @DisplayName("64x32 sheet: the left arm mirrors the right arm's regions, face for face")
    void leftArmFallsBackOn64x32() {
        assertMirroredLimb(HumanoidPart.LEFT_ARM, HumanoidPart.RIGHT_ARM);
    }

    @Test
    @DisplayName("64x32 sheet: the left leg mirrors the right leg's regions, face for face")
    void leftLegFallsBackOn64x32() {
        assertMirroredLimb(HumanoidPart.LEFT_LEG, HumanoidPart.RIGHT_LEG);
    }

    @Test
    @DisplayName("64x32 sheet: the head's overlay survives, every other overlay falls off the sheet")
    void overlaysOn64x32() {
        PixelBuffer atlas = sheet(64, 32);

        for (Face face : Face.CACHED_VALUES) {
            int[] expected = origin(HumanoidPart.HEAD, face, true);
            assertRegion("HEAD." + face + " overlay on 64x32", HumanoidPart.HEAD.crop(atlas, face, true),
                expected[0], expected[1], size(HumanoidPart.HEAD, face), false);
        }

        // The overlay layer never takes the fallback, so a region past the bottom edge reads as
        // transparent rather than as the mirrored right limb.
        PixelBuffer torso = HumanoidPart.TORSO.crop(atlas, Face.NORTH, true);
        assertThat("TORSO.NORTH overlay on 64x32 is empty", torso.getPixel(0, 0), is(0));
    }

    @Test
    @DisplayName("the fallback fires only where a base rectangle runs past the bottom edge")
    void fallbackIsBoundedByTheSheet() {
        PixelBuffer skin = sheet(64, 64);

        // On a full skin the left limbs keep their own regions - the fallback is a property of the
        // sheet, not of the part.
        for (Face face : Face.CACHED_VALUES) {
            int[] own = origin(HumanoidPart.LEFT_ARM, face, false);
            assertRegion("LEFT_ARM." + face + " on 64x64", HumanoidPart.LEFT_ARM.crop(skin, face, false),
                own[0], own[1], size(HumanoidPart.LEFT_ARM, face), false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * Asserts that a left limb on a 64x32 sheet reads its right-hand counterpart's rectangle, with
     * the two side faces swapped and the texels flipped left to right.
     */
    private static void assertMirroredLimb(HumanoidPart left, HumanoidPart right) {
        PixelBuffer atlas = sheet(64, 32);

        for (Face face : Face.CACHED_VALUES) {
            int[] source = origin(right, sagittal(face), false);
            assertRegion(left + "." + face + " on 64x32", left.crop(atlas, face, false),
                source[0], source[1], size(left, face), true);
        }
    }

    /** The face a sagittal-plane mirror reads through - the two sides trade, the other four stay. */
    private static Face sagittal(Face face) {
        return switch (face) {
            case WEST -> Face.EAST;
            case EAST -> Face.WEST;
            default -> face;
        };
    }

    /**
     * Asserts a crop's extent and, texel by texel, the region it came from - reading each texel's
     * encoded source coordinates back out. {@code flipped} asserts the left-to-right flip the legacy
     * fallback applies.
     */
    private static void assertRegion(
        String label, PixelBuffer crop, int originX, int originY, int[] extent, boolean flipped) {
        assertThat(label + " width", crop.width(), is(extent[0]));
        assertThat(label + " height", crop.height(), is(extent[1]));

        for (int y = 0; y < extent[1]; y++) {
            for (int x = 0; x < extent[0]; x++) {
                int sourceX = originX + (flipped ? extent[0] - 1 - x : x);
                assertThat(label + " texel (" + x + ", " + y + ")",
                    crop.getPixel(x, y), equalTo(encode(sourceX, originY + y)));
            }
        }
    }

    /** The declared {@code (x, y)} origin of one part's face on one layer. */
    private static int[] origin(HumanoidPart part, Face face, boolean overlay) {
        int[] row = ORIGINS[part.ordinal()][face.ordinal()];
        return overlay ? new int[]{ row[2], row[3] } : new int[]{ row[0], row[1] };
    }

    /** The {@code (width, height)} a face's rectangle takes from the part's box dimensions. */
    private static int[] size(HumanoidPart part, Face face) {
        int[] dims = DIMENSIONS[part.ordinal()];
        return switch (face) {
            case DOWN, UP -> new int[]{ dims[0], dims[2] };
            case NORTH, SOUTH -> new int[]{ dims[0], dims[1] };
            case WEST, EAST -> new int[]{ dims[2], dims[1] };
        };
    }

    /** A sheet whose every texel encodes its own coordinates, so a crop names where it came from. */
    private static PixelBuffer sheet(int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y * width + x] = encode(x, y);
        return PixelBuffer.of(pixels, width, height);
    }

    /** Packs a texel's own coordinates into an opaque colour, one byte each. */
    private static int encode(int x, int y) {
        return 0xFF000000 | (x << 8) | y;
    }

}
