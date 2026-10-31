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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Pins what a screen's text field does with more text than it can hold, and where its caret opens.
 * <p>
 * Both answers are the client's and neither is arithmetic a caller should have to do: text past the
 * field's cap is dropped rather than refused, and text too wide to fit shows from where the client's
 * field, set to it whole, scrolls - three passes toward the cursor at its end - so a name shows from
 * near its end, and a name those passes leave with its cursor out of view draws no caret and the
 * client's highlight band instead.
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

    /** a vanilla item's own name, which the client's scroll shows from a character past the widest tail that fits */
    private static final String APPLE = "Enchanted Golden Apple";

    /** the one vanilla item or block name whose shown text the third of the client's scroll passes decides */
    private static final String SCULK = "Calibrated Sculk Sensor";

    /** a name the three scroll passes leave with its cursor out of view, where two would show other text and four would bring it into view */
    private static final String HIGHLIGHT = "lil' MAMMOTH WARHAMMER";

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
        assertThat(TextField.displayPos("Excalibur", WELL.innerWidth()), is(0));
        assertThat(TextField.displayPos("", WELL.innerWidth()), is(0));
    }

    @Test
    @DisplayName("a name too wide shows from where setting it whole scrolls a fresh field - three passes toward the cursor at its end")
    void aNameTooWideShowsFromWhereSettingItScrolls() {
        assertThat("the fixture is wider than the field", widthOf(AT_LENGTH),
            is(greaterThan(WELL.innerWidth())));

        assertThat("the parity subjects' name shows from where the anvil_named reference shows it",
            TextField.displayPos(AT_LENGTH, WELL.innerWidth()), is(32));
        // Two passes leave the field at 4 with the cursor out of view, and the third brings it to 5.
        assertThat("the one vanilla name the third pass decides",
            TextField.displayPos(SCULK, WELL.innerWidth()), is(5));
        assertThat("the enchanted golden apple's name shows from its fifth character",
            TextField.displayPos(APPLE, WELL.innerWidth()), is(4));
        assertThat("though one character more, 102 wide, also fits - the field does not show the widest tail that fits",
            widthOf(APPLE.substring(3)), is(102));
        // Two passes leave the field at 3, and four at 5 with the cursor back in view.
        assertThat("the highlight subject's name shows from its fifth character",
            TextField.displayPos(HIGHLIGHT, WELL.innerWidth()), is(4));
    }

    @Test
    @DisplayName("a name whose scroll leaves the cursor out of view draws no caret, whatever the option says, and adds the client's blue band from a pixel inside its shown text to the field's edge")
    void aCursorScrolledOutOfViewDrawsTheBandAndNoCaret() {
        MenuLayout layout = MenuLayout.of(ScreenMetrics.anvil(), true);
        int scale = MinecraftFont.MC_PIXEL_SCALE;

        assertThat("the caret option draws nothing over a cursor out of view",
            firstDifferingColumn(fieldText(layout, HIGHLIGHT, true, TextPalette.VANILLA), fieldText(layout, HIGHLIGHT, false, TextPalette.VANILLA)),
            is(-1));

        // The field shows ' MAMMOTH WARHAMME', 98 wide from the text's origin at 62, and the band runs from a
        // pixel inside that end to the field's edge at 165, from a pixel above the glyph cell at 24 to two
        // below its eight rows.
        ConcurrentList<FramePlacement> placements = fieldPlacements(layout, HIGHLIGHT, true, TextPalette.VANILLA);
        assertThat("the text and then the band", placements.size(), is(2));
        FramePlacement band = placements.getLast();
        PixelBuffer fill = band.source().toPixelBuffer();
        assertThat("the band is added to what lies under it", band.additive(), is(true));
        assertThat("it opens a pixel inside the shown text's end and a pixel above the glyph cell",
            List.of(band.x(), band.y()), is(equalTo(List.of((62 + 98 - 1) * scale, (24 - 1) * scale))));
        assertThat("it covers six Minecraft pixels by eleven",
            List.of(fill.width(), fill.height()), is(equalTo(List.of(6 * scale, 11 * scale))));
        assertThat("every pixel of it the client's blue",
            IntStream.of(fill.data()).allMatch(pixel -> pixel == 0xFF0000FF), is(true));

        for (String inView : List.of(AT_LENGTH, APPLE, "")) {
            assertThat("'" + inView + "' keeps its cursor in view and appends its text alone",
                fieldPlacements(layout, inView, true, TextPalette.VANILLA).size(), is(1));
        }
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
        // origin at 62 and the text it shows, 102 wide - which is where the anvil_named reference holds it.
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
    @DisplayName("a field's text and its caret glyph draw in the palette's swap of the well colour, a full field's caret bar keeps the well's own, and the highlight band the client's blue")
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
        assertThat("a field whose cursor has scrolled out of view fills its band in the client's blue, a fill and not text",
            opaqueColours(fieldPlacements(layout, HIGHLIGHT, true, palette).getLast().source().toPixelBuffer()), is(equalTo(Set.of(0xFF0000FF))));
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
     * Draws a field's text the way a menu render places it and reads its text layer back.
     *
     * @param layout the laid-out panel carrying the field
     * @param text what the field holds
     * @param caret whether the caret is drawn
     * @param palette the colours the stack's text shader draws in place of the field text's own
     * @return the field's text layer
     */
    private static PixelBuffer fieldText(MenuLayout layout, String text, boolean caret, TextPalette palette) {
        return fieldPlacements(layout, text, caret, palette).getFirst().source().toPixelBuffer();
    }

    /**
     * Draws a field's text the way a menu render places it and reads back every placement it appends.
     *
     * @param layout the laid-out panel carrying the field
     * @param text what the field holds
     * @param caret whether the caret is drawn
     * @param palette the colours the stack's text shader draws in place of the field text's own
     * @return the field's placements, in the order they are laid
     */
    private static ConcurrentList<FramePlacement> fieldPlacements(MenuLayout layout, String text, boolean caret, TextPalette palette) {
        LayerStack<FrameLayer> stack = new LayerStack<>();
        TextField.placeFieldText(
            MenuOptions.builder().type(MenuOptions.Type.ANVIL).fieldText(text).caret(caret).build(),
            layout,
            palette,
            stack
        );

        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        stack.ordered().forEach(layer -> layer.contribute(placements));
        return placements;
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
