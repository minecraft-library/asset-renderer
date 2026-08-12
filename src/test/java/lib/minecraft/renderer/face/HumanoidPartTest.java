package lib.minecraft.renderer.face;

import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.PlayerOptions.Type.BodyPart2D;
import lib.minecraft.renderer.tensor.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The pixel-lattice arithmetic behind {@link HumanoidPart#box} and {@link HumanoidPart#centred} - the
 * exact float every endpoint of every body part lands on at both shipped scales, and the one-ULP gap
 * between deriving an endpoint from its own integer pixel offset and adding a scaled extent to an
 * origin.
 * <p>
 * The rule is not stylistic. At the body lattice's {@code 0.03} units per pixel the offsets the
 * lattice actually uses - 2, 4, 8 and 16 pixels - multiply bit-exactly onto the decimal they spell,
 * while a 12-pixel span does not: {@code 12 * 0.03f} is one ULP below {@code 0.36f}, and five of the
 * six parts are 12 pixels tall. Rewriting {@code box} as an origin plus a scaled extent therefore
 * moves five of the six upper Y endpoints by one bit, which is why the enum stores an upper pixel
 * offset rather than a span. Assertions read {@link Float#floatToIntBits} wherever that single bit is
 * the whole claim, so a regression cannot pass by printing the same decimal.
 * <p>
 * {@code centred} is pinned for what it observably does rather than for the same guarantee. A box
 * centred on the origin has no integer endpoint offset to derive from, so its halves come off the
 * half-span and a 12-pixel limb's half at body scale is one ULP below {@code 0.18f}. It is also not
 * {@code box} in another frame - the head's lattice box spans {@code y 8..16} while its centred box
 * is a cube about the origin.
 * <p>
 * The scope half pins the claim that a scope's extent and both of its layouts fall out of the union
 * of its parts' boxes, which is why {@link PlayerOptions.Type} answers them rather than a renderer.
 */
@DisplayName("HumanoidPart - the lattice endpoints, the centred box, and the scope unions")
class HumanoidPartTest {

    /** Model units one skin pixel spans in the body lattice, shared by the two seated scopes. */
    private static final float BODY_SCALE = 0.03f;

    /** Model units one skin pixel spans in the head-only scope, an exact power of two. */
    private static final float SKULL_SCALE = 0.125f;

    /**
     * Every part's box at {@link #BODY_SCALE}, in declaration order, as
     * {@code {minX, minY, minZ, maxX, maxY, maxZ}}. Each value is the exact float the endpoint's own
     * pixel offset multiplies to, transcribed rather than recomputed from the offsets
     */
    private static final float[][] BODY_LATTICE = {
        { -0.12f,  0.24f, -0.12f,  0.12f,  0.48f,  0.12f },  // HEAD
        { -0.12f, -0.12f, -0.06f,  0.12f,  0.24f,  0.06f },  // TORSO
        { -0.24f, -0.12f, -0.06f, -0.12f,  0.24f,  0.06f },  // RIGHT_ARM
        {  0.12f, -0.12f, -0.06f,  0.24f,  0.24f,  0.06f },  // LEFT_ARM
        { -0.12f, -0.48f, -0.06f,   0.0f, -0.12f,  0.06f },  // RIGHT_LEG
        {   0.0f, -0.48f, -0.06f,  0.12f, -0.12f,  0.06f }   // LEFT_LEG
    };

    /** Each part's {@code {pixelWidth, pixelHeight}}, in declaration order */
    private static final int[][] PIXEL_SPANS = {
        { 8, 8 }, { 8, 12 }, { 4, 12 }, { 4, 12 }, { 4, 12 }, { 4, 12 }
    };

    @Test
    @DisplayName("twelve pixels at the body scale is one ULP below the decimal it spells")
    void twelvePixelsAtBodyScaleIsOneUlpBelowItsDecimal() {
        float twelve = 12 * BODY_SCALE;

        assertThat("12 * 0.03f does not reach 0.36f", twelve, is(not(equalTo(0.36f))));
        assertThat("12 * 0.03f", Float.floatToIntBits(twelve), is(0x3EB851EB));
        assertThat("0.36f", Float.floatToIntBits(0.36f), is(0x3EB851EC));
        assertThat("the gap is exactly one ULP", Math.nextUp(twelve), equalTo(0.36f));
    }

    @Test
    @DisplayName("every endpoint offset the lattice uses multiplies bit-exactly onto its decimal")
    void latticeEndpointOffsetsMultiplyBitExactly() {
        assertThat("2 px", 2 * BODY_SCALE, equalTo(0.06f));
        assertThat("4 px", 4 * BODY_SCALE, equalTo(0.12f));
        assertThat("8 px", 8 * BODY_SCALE, equalTo(0.24f));
        assertThat("16 px", 16 * BODY_SCALE, equalTo(0.48f));

        // The same four again on their bits, because the claim is bit equality and not agreement to
        // the printed decimal.
        assertThat("2 px bits", Float.floatToIntBits(2 * BODY_SCALE), is(Float.floatToIntBits(0.06f)));
        assertThat("4 px bits", Float.floatToIntBits(4 * BODY_SCALE), is(Float.floatToIntBits(0.12f)));
        assertThat("8 px bits", Float.floatToIntBits(8 * BODY_SCALE), is(Float.floatToIntBits(0.24f)));
        assertThat("16 px bits", Float.floatToIntBits(16 * BODY_SCALE), is(Float.floatToIntBits(0.48f)));

        // A zero offset is a positive zero, which matters because the two leg boxes each put one on an
        // X endpoint and a negative zero is a different float.
        assertThat("0 px bits", Float.floatToIntBits(0 * BODY_SCALE), is(0));
    }

    @Test
    @DisplayName("five of the six parts are twelve pixels tall, which is the span the rule was written for")
    void everyLimbAndTheTorsoAreTwelvePixelsTall() {
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            int[] expected = PIXEL_SPANS[part.ordinal()];
            assertThat(part + " pixel width", part.pixelWidth(), is(expected[0]));
            assertThat(part + " pixel height", part.pixelHeight(), is(expected[1]));
        }

        assertThat("only the head departs from a 12-pixel height", HumanoidPart.HEAD.pixelHeight(), is(8));
    }

    @Test
    @DisplayName("box seats every endpoint of every part on the exact float its own pixel offset spells")
    void boxSeatsEveryEndpointOnItsOwnPixelOffset() {
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            float[] expected = BODY_LATTICE[part.ordinal()];
            Box box = part.box(BODY_SCALE);

            assertThat(part + " minX", box.minX(), equalTo(expected[0]));
            assertThat(part + " minY", box.minY(), equalTo(expected[1]));
            assertThat(part + " minZ", box.minZ(), equalTo(expected[2]));
            assertThat(part + " maxX", box.maxX(), equalTo(expected[3]));
            assertThat(part + " maxY", box.maxY(), equalTo(expected[4]));
            assertThat(part + " maxZ", box.maxZ(), equalTo(expected[5]));
        }
    }

    @Test
    @DisplayName("box does not add a scaled extent to an origin - five upper Y endpoints move by a bit if it does")
    void boxNeverAddsAScaledExtentToAnOrigin() {
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            Box box = part.box(BODY_SCALE);
            float naiveY = part.minPixelY() * BODY_SCALE + part.pixelHeight() * BODY_SCALE;
            float naiveX = part.minPixelX() * BODY_SCALE + part.pixelWidth() * BODY_SCALE;

            // Widths of 4 and 8 pixels multiply exactly, so the X endpoint is the same float either
            // way and the divergence is specific to the 12-pixel span.
            assertThat(part + " maxX is reachable either way", naiveX, equalTo(box.maxX()));

            if (part.pixelHeight() == 12) {
                assertThat(part + " maxY diverges", naiveY, is(not(equalTo(box.maxY()))));
                assertThat(part + " maxY is one ULP low the other way", Math.nextUp(naiveY), equalTo(box.maxY()));
            } else {
                assertThat(part + " maxY agrees at 8 pixels", naiveY, equalTo(box.maxY()));
            }
        }
    }

    @Test
    @DisplayName("box seats the head where the lattice puts it, at either scale")
    void boxSeatsTheHeadInTheBodyLattice() {
        // The lattice spans -16..+16 on Y and the head occupies its top eight pixels, so the head's
        // box floats above the origin rather than straddling it.
        assertThat("head at body scale", HumanoidPart.HEAD.box(BODY_SCALE),
            equalTo(new Box(-0.12f, 0.24f, -0.12f, 0.12f, 0.48f, 0.12f)));

        // A power-of-two scale makes every product exact, so the head's 8..16 pixel span reads back as
        // a clean 1..2.
        assertThat("head at skull scale", HumanoidPart.HEAD.box(SKULL_SCALE),
            equalTo(new Box(-0.5f, 1.0f, -0.5f, 0.5f, 2.0f, 0.5f)));
    }

    @Test
    @DisplayName("centred is not the lattice box in another frame - the head becomes a cube about the origin")
    void centredIsNotTheLatticeBoxInAnotherFrame() {
        Box seated = HumanoidPart.HEAD.box(SKULL_SCALE);
        Box centred = HumanoidPart.HEAD.centred(SKULL_SCALE);

        assertThat("the seated head floats above the origin", seated,
            equalTo(new Box(-0.5f, 1.0f, -0.5f, 0.5f, 2.0f, 0.5f)));
        assertThat("the centred head straddles it", centred,
            equalTo(new Box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)));
        assertThat("the two are different boxes", centred, is(not(equalTo(seated))));
    }

    @Test
    @DisplayName("centred halves come off the half span, so a twelve-pixel part loses the bit box keeps")
    void centredHalvesComeOffTheHalfSpan() {
        // A box centred on the origin has no integer endpoint offset to derive from, so its half is
        // the half span times the scale and inherits that product's rounding rather than the
        // bit-exactness box gets.
        float half = HumanoidPart.TORSO.centred(BODY_SCALE).maxY();

        assertThat("the half is 6 px times the scale", half, equalTo(6 * BODY_SCALE));
        assertThat("which is one ULP below the decimal it spells", half, is(not(equalTo(0.18f))));
        assertThat("half bits", Float.floatToIntBits(half), is(0x3E3851EB));
        assertThat("0.18f bits", Float.floatToIntBits(0.18f), is(0x3E3851EC));
        assertThat("the gap is exactly one ULP", Math.nextUp(half), equalTo(0.18f));

        // Every 12-pixel part lands on that same half, the torso and the four limbs alike.
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES)
            if (part.pixelHeight() == 12)
                assertThat(part + " half height", part.centred(BODY_SCALE).maxY(), equalTo(half));
    }

    @Test
    @DisplayName("centred is symmetric about the origin on all three axes")
    void centredIsSymmetricAboutTheOrigin() {
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            Box centred = part.centred(BODY_SCALE);

            assertThat(part + " X", centred.minX(), equalTo(-centred.maxX()));
            assertThat(part + " Y", centred.minY(), equalTo(-centred.maxY()));
            assertThat(part + " Z", centred.minZ(), equalTo(-centred.maxZ()));
        }
    }

    @Test
    @DisplayName("at the body scale centred keeps each axis extent of the seated box bit for bit")
    void centredKeepsEachAxisExtentOfTheSeatedBox() {
        // Measured at the shipped body scale over the six shipped parts - centring moves both
        // endpoints of every axis and still reports the same extent, so a fit measured off either box
        // sizes the part identically.
        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            Box seated = part.box(BODY_SCALE);
            Box centred = part.centred(BODY_SCALE);

            assertThat(part + " X extent", centred.maxX() - centred.minX(), equalTo(seated.maxX() - seated.minX()));
            assertThat(part + " Y extent", centred.maxY() - centred.minY(), equalTo(seated.maxY() - seated.minY()));
            assertThat(part + " Z extent", centred.maxZ() - centred.minZ(), equalTo(seated.maxZ() - seated.minZ()));
        }
    }

    @Test
    @DisplayName("re-centring the seated box does not reproduce centred, so the two are not one derivation")
    void reCentringTheSeatedBoxDoesNotReproduceCentred() {
        // The torso and the two arms happen to agree, so agreement on a sample is not evidence the two
        // forms are interchangeable. The head lands one bit above its derived half and the legs, which
        // share a Y span, land one bit below theirs - so the error has no fixed sign either.
        assertThat("head maxY, re-centred", Float.floatToIntBits(reCentredMaxY(HumanoidPart.HEAD)), is(0x3DF5C290));
        assertThat("head maxY, derived", Float.floatToIntBits(HumanoidPart.HEAD.centred(BODY_SCALE).maxY()),
            is(0x3DF5C28F));

        for (HumanoidPart leg : new HumanoidPart[]{ HumanoidPart.RIGHT_LEG, HumanoidPart.LEFT_LEG }) {
            assertThat(leg + " maxY, re-centred", Float.floatToIntBits(reCentredMaxY(leg)), is(0x3E3851EA));
            assertThat(leg + " maxY, derived",
                Float.floatToIntBits(leg.centred(BODY_SCALE).maxY()), is(0x3E3851EB));
        }
    }

    @Test
    @DisplayName("each scope's pixel extent is the union of its own parts' boxes")
    void scopeExtentIsTheUnionOfItsPartsBoxes() {
        assertUnion(PlayerOptions.Type.SKULL, -4, 4, 8, 16);
        assertUnion(PlayerOptions.Type.BUST, -8, 8, -4, 16);
        assertUnion(PlayerOptions.Type.FULL, -8, 8, -16, 16);
    }

    @Test
    @DisplayName("the head-only scope centres its part while the two body scopes seat theirs")
    void skullCentresItsPartAndTheBodyScopesSeatTheirs() {
        assertThat("skull head", PlayerOptions.Type.SKULL.boxOf(HumanoidPart.HEAD),
            equalTo(HumanoidPart.HEAD.centred(SKULL_SCALE)));
        assertThat("bust head", PlayerOptions.Type.BUST.boxOf(HumanoidPart.HEAD),
            equalTo(HumanoidPart.HEAD.box(BODY_SCALE)));
        assertThat("full head", PlayerOptions.Type.FULL.boxOf(HumanoidPart.HEAD),
            equalTo(HumanoidPart.HEAD.box(BODY_SCALE)));

        // The tabulated map is the same answer, for exactly the parts the scope draws.
        for (PlayerOptions.Type type : PlayerOptions.Type.values()) {
            Map<HumanoidPart, Box> boxes = type.boxes();
            assertThat(type + " covers its parts", boxes.keySet(), equalTo(Set.copyOf(type.parts())));

            for (HumanoidPart part : type.parts())
                assertThat(type + " " + part, boxes.get(part), equalTo(type.boxOf(part)));
        }
    }

    @Test
    @DisplayName("the 2D layout counts the canvas downward where the lattice counts upward")
    void layoutInvertsTheLatticeYAxis() {
        // Scale is the canvas over the scope's 32-pixel union height, and the horizontal offset
        // centres its 16-pixel union width, so 64 pixels give a scale of 2 and an offset of 16.
        List<BodyPart2D> layout = PlayerOptions.Type.FULL.layout2D(64);

        assertThat("one rectangle per part", layout.size(), is(6));
        assertRect("head", layout.getFirst(), HumanoidPart.HEAD, 24, 0, 16, 16);
        assertRect("torso", layout.get(1), HumanoidPart.TORSO, 24, 16, 16, 24);
        assertRect("right arm", layout.get(2), HumanoidPart.RIGHT_ARM, 16, 16, 8, 24);
        assertRect("left arm", layout.get(3), HumanoidPart.LEFT_ARM, 40, 16, 8, 24);
        assertRect("right leg", layout.get(4), HumanoidPart.RIGHT_LEG, 24, 40, 8, 24);
        assertRect("left leg", layout.getLast(), HumanoidPart.LEFT_LEG, 32, 40, 8, 24);

        // The head owns the lattice's highest pixels and lands at the top of the canvas; the legs own
        // its lowest and land at the bottom, ending exactly on the canvas edge.
        assertThat("the legs close the canvas", layout.getLast().y() + layout.getLast().h(), is(64));
    }

    @Test
    @DisplayName("the bust layout is centred on its own union width, not on the full body's")
    void bustLayoutIsCentredOnItsOwnUnionWidth() {
        // The bust's union spans y -4..16, so 40 pixels give a scale of 2 over a 20-pixel height, and
        // its 16-pixel width leaves 4 pixels of margin on each side.
        List<BodyPart2D> layout = PlayerOptions.Type.BUST.layout2D(40);

        assertThat("one rectangle per part", layout.size(), is(4));
        assertRect("head", layout.getFirst(), HumanoidPart.HEAD, 12, 0, 16, 16);
        assertRect("torso", layout.get(1), HumanoidPart.TORSO, 12, 16, 16, 24);
        assertRect("right arm", layout.get(2), HumanoidPart.RIGHT_ARM, 4, 16, 8, 24);
        assertRect("left arm", layout.getLast(), HumanoidPart.LEFT_ARM, 28, 16, 8, 24);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------

    /** The upper Y endpoint a part's seated box reports once translated onto its own Y centre. */
    private static float reCentredMaxY(HumanoidPart part) {
        Box seated = part.box(BODY_SCALE);
        return seated.maxY() - (seated.minY() + seated.maxY()) * 0.5f;
    }

    /** Asserts a scope's union of its parts' pixel boxes on the two axes the layout reads. */
    private static void assertUnion(PlayerOptions.Type type, int minX, int maxX, int minY, int maxY) {
        int unionMinX = Integer.MAX_VALUE;
        int unionMaxX = Integer.MIN_VALUE;
        int unionMinY = Integer.MAX_VALUE;
        int unionMaxY = Integer.MIN_VALUE;

        for (HumanoidPart part : type.parts()) {
            unionMinX = Math.min(unionMinX, part.minPixelX());
            unionMaxX = Math.max(unionMaxX, part.maxPixelX());
            unionMinY = Math.min(unionMinY, part.minPixelY());
            unionMaxY = Math.max(unionMaxY, part.maxPixelY());
        }

        assertThat(type + " union minX", unionMinX, is(minX));
        assertThat(type + " union maxX", unionMaxX, is(maxX));
        assertThat(type + " union minY", unionMinY, is(minY));
        assertThat(type + " union maxY", unionMaxY, is(maxY));
    }

    /** Asserts one laid-out rectangle's part and its canvas placement and extent. */
    private static void assertRect(String label, BodyPart2D rect, HumanoidPart part, int x, int y, int w, int h) {
        assertThat(label + " part", rect.part(), is(part));
        assertThat(label + " x", rect.x(), is(x));
        assertThat(label + " y", rect.y(), is(y));
        assertThat(label + " width", rect.w(), is(w));
        assertThat(label + " height", rect.h(), is(h));
    }

}
