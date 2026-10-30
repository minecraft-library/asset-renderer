package lib.minecraft.renderer.bake.gui;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Pins what a screen's text field does with more text than it can hold, and where its caret opens.
 * <p>
 * Both answers are the client's and neither is arithmetic a caller should have to do: text past the
 * field's cap is dropped rather than refused, and text too wide to fit scrolls so its end stays in
 * view. The second is the one that matters for what is drawn - a field shows the end of what was
 * typed into it, so a name is recognised by its tail.
 * <p>
 * The caret opens where the client's field leaves its pen - at the text's origin in an empty field,
 * and a Minecraft pixel past where {@code Font#width} puts the end of a text, which rounds a run
 * ending on half a Minecraft pixel up to the next whole one; a full field's bar steps back that pixel
 * onto the text's own end.
 */
@ExtendWith(MinecraftFontsExtension.class)
@DisplayName("A text field cuts what it cannot hold and scrolls what it cannot fit")
class MenuFieldTextTest {

    /** the anvil's own well, which is the only one any shipped screen declares */
    private static final Mark.TextWell WELL = Mark.FIELD.textWell().orElseThrow();

    /** longer than the field's own cap, so the cut is observable */
    private static final String OVER_LENGTH = "x".repeat(WELL.maxLength() + 12);

    /** the name the parity subjects carry, which is exactly the cap and far wider than the field */
    private static final String AT_LENGTH = "The Quick Brown Fox Jumps Over The Lazy Dog 123456";

    private static LineSegment plain(String text) {
        return LineSegment.builder().segments(Concurrent.newList(TextSegment.literal(text))).build();
    }

    private static int widthOf(String text) {
        return TextKit.measureLineMcPixels(plain(text));
    }

    @Test
    @DisplayName("text past the cap is dropped rather than refused")
    void textPastTheCapIsDropped() {
        assertThat(TextField.typedInto(OVER_LENGTH, WELL.maxLength()).length(),
            is(equalTo(WELL.maxLength())));
        assertThat("a name within the cap arrives whole",
            TextField.typedInto("Excalibur", WELL.maxLength()),
            is(equalTo("Excalibur")));
        assertThat("and the parity subjects' own name sits exactly on it",
            AT_LENGTH.length(), is(equalTo(WELL.maxLength())));
    }

    @Test
    @DisplayName("a name that fits is shown whole")
    void aNameThatFitsIsShownWhole() {
        assertThat(TextField.visibleTail("Excalibur", WELL.innerWidth()), is(equalTo("Excalibur")));
        assertThat(TextField.visibleTail("", WELL.innerWidth()), is(equalTo("")));
    }

    @Test
    @DisplayName("a name too wide is shown from its end, at the widest tail that fits")
    void aNameTooWideIsShownFromItsEnd() {
        assertThat("the fixture is wider than the field", widthOf(AT_LENGTH),
            is(greaterThan(WELL.innerWidth())));

        String tail = TextField.visibleTail(AT_LENGTH, WELL.innerWidth());
        assertThat("what is shown is the end of what was typed", AT_LENGTH.endsWith(tail), is(true));
        assertThat("and it is not the whole of it", tail.length(), is(lessThanOrEqualTo(AT_LENGTH.length() - 1)));
        assertThat("it fits", widthOf(tail), is(lessThanOrEqualTo(WELL.innerWidth())));

        // The rule is the WIDEST tail that fits, so taking one more character has to overflow -
        // otherwise the field would be scrolled further than it needs to be and drop a character
        // that had room.
        String wider = AT_LENGTH.substring(AT_LENGTH.length() - tail.length() - 1);
        assertThat("and one character more does not", widthOf(wider),
            is(greaterThan(WELL.innerWidth())));
    }

    @Test
    @DisplayName("the anvil is the one screen that declares a field")
    void theAnvilIsTheOneScreenThatDeclaresAField() {
        for (ScreenMetrics screen : ScreenMetrics.measured()) {
            boolean hasField = MenuLayout.of(screen, true).marks().stream()
                .anyMatch(mark -> mark.kind().textWell().isPresent());

            assertThat(screen + " declares a field only if it is the anvil",
                hasField, is(equalTo(screen.equals(ScreenMetrics.anvil()))));
        }
    }

    @Test
    @DisplayName("the field's text sits inside its own well")
    void theFieldsTextSitsInsideItsOwnWell() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(), true);
        Mark.Placement field = layout.marks().stream()
            .filter(mark -> mark.kind().textWell().isPresent())
            .findFirst()
            .orElseThrow();

        // The insets clear the two-pixel bevel on each side, and the text runs no wider than what is
        // left between them - a field that let its text reach the well's edge would paint over it.
        assertThat("the text opens clear of the bevel", WELL.inset().x(), is(greaterThan(2)));
        assertThat("the run fits between the bevels",
            WELL.inset().x() + WELL.innerWidth(),
            is(lessThanOrEqualTo(Window.Extent.of(field.kind()).width() - 2)));
        assertThat("the client's one field draws its text in the same white editable or not",
            Mark.FIELD_DISABLED.textWell(), is(equalTo(Mark.FIELD.textWell())));
    }

    @Test
    @DisplayName("the caret opens where the client's field leaves its pen - at the origin of an empty field, a pixel past Font#width after text, and back on the text's end as a full field's bar")
    void caretOpensWhereTheClientsPenStands() {
        float exact = MinecraftFont.Vanilla.REGULAR.metrics().stringAdvanceMcPixels("✦");
        assertThat("the text ends on half an mcPixel", exact % 1f, is(0.5f));

        MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(), true);
        Mark.Placement field = layout.marks().stream()
            .filter(mark -> mark.kind().textWell().isPresent())
            .findFirst()
            .orElseThrow();
        int textX = field.x() + WELL.inset().x();

        assertThat("an empty field's caret opens at the text's origin",
            firstOpaqueColumn(fieldText(layout, "", true, TextPalette.VANILLA)),
            is(textX * MinecraftFont.MC_PIXEL_SCALE));

        // The client fills its bar for the parity subjects' name at Minecraft x 164 - the field's text
        // origin at 62 and the tail it shows 102 wide - which is where the anvil_named reference holds it.
        PixelBuffer withBar = fieldText(layout, AT_LENGTH, true, TextPalette.VANILLA);
        PixelBuffer withoutBar = fieldText(layout, AT_LENGTH, false, TextPalette.VANILLA);
        assertThat("a full field's bar sits on the text's own end", firstDifferingColumn(withBar, withoutBar),
            is((62 + 102) * MinecraftFont.MC_PIXEL_SCALE));

        PixelBuffer withCaret = fieldText(layout, "✦", true, TextPalette.VANILLA);
        PixelBuffer withoutCaret = fieldText(layout, "✦", false, TextPalette.VANILLA);
        assertThat("the caret after text opens a pixel past where Font#width puts its end",
            firstDifferingColumn(withCaret, withoutCaret),
            is((textX + (int) Math.ceil(exact) + 1) * MinecraftFont.MC_PIXEL_SCALE));
    }

    @Test
    @DisplayName("a field's text and its caret glyph draw in the palette's swap of the well colour, and a full field's caret bar keeps the well's own")
    void fieldTextDrawsInThePalettesSwap() {
        int wellRgb = WELL.argb() & 0xFFFFFF;
        int shadowRgb = TextKit.darken(WELL.argb()) & 0xFFFFFF;
        TextPalette palette = new TextPalette(new ResourceId("minecraft", "shaders/core/rendertype_text.vsh"), new PackId("pack"),
            Concurrent.newUnmodifiableMap(Map.of(wellRgb, 0x4CC2FF, shadowRgb, 0x13303F)));
        MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(), true);

        assertThat("a part-filled field with a caret draws its text, the underscore caret and their shadow in the two swaps",
            opaqueColours(fieldText(layout, "Excalibur", true, palette)), is(equalTo(Set.of(0xFF4CC2FF, 0xFF13303F))));
        assertThat("a full field draws its text in the two swaps and its caret bar in the well's own colour",
            opaqueColours(fieldText(layout, AT_LENGTH, true, palette)), is(equalTo(Set.of(0xFF4CC2FF, 0xFF13303F, WELL.argb()))));
    }

    /**
     * Collects every colour a layer holds at a pixel it covers.
     *
     * @param layer the layer
     * @return each distinct colour of a pixel whose alpha is not zero
     */
    private static Set<Integer> opaqueColours(PixelBuffer layer) {
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < layer.height(); y++)
            for (int x = 0; x < layer.width(); x++)
                if ((layer.getPixel(x, y) >>> 24) != 0) colours.add(layer.getPixel(x, y));

        return colours;
    }

    /**
     * Draws a field's text the way a menu render places it and reads the placement back.
     *
     * @param layout the laid-out panel carrying the field
     * @param text what the field holds
     * @param caret whether the caret is drawn
     * @param palette the colours the stack's text shader draws in place of the field text's own
     * @return the field's text layer
     */
    private static PixelBuffer fieldText(MenuLayout layout, String text, boolean caret, TextPalette palette) {
        LayerStack<FrameLayer> stack = new LayerStack<>();
        TextField.placeFieldText(
            MenuOptions.builder().type(MenuOptions.Type.ANVIL).fieldText(text).caret(caret).build(),
            layout,
            palette,
            stack
        );

        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        stack.ordered().getFirst().contribute(placements);
        return placements.getFirst().source().toPixelBuffer();
    }

    private static int firstDifferingColumn(PixelBuffer a, PixelBuffer b) {
        for (int x = 0; x < a.width(); x++)
            for (int y = 0; y < a.height(); y++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) return x;

        return -1;
    }

    /**
     * Finds the leftmost column a layer draws anything in.
     *
     * @param layer the layer
     * @return the first column holding a pixel whose alpha is not zero, or -1 where none does
     */
    private static int firstOpaqueColumn(PixelBuffer layer) {
        for (int x = 0; x < layer.width(); x++)
            for (int y = 0; y < layer.height(); y++)
                if ((layer.getPixel(x, y) >>> 24) != 0) return x;

        return -1;
    }

}
