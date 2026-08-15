package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.compose.Decoration;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.engine.compose.MenuScreen;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Pins what a screen's text field does with more text than it can hold.
 * <p>
 * Both answers are the client's and neither is arithmetic a caller should have to do: text past the
 * field's cap is dropped rather than refused, and text too wide to fit scrolls so its end stays in
 * view. The second is the one that matters for what is drawn - a field shows the end of what was
 * typed into it, so a name is recognised by its tail.
 */
@DisplayName("A text field cuts what it cannot hold and scrolls what it cannot fit")
class MenuFieldTextTest {

    /** the anvil's own well, which is the only one any shipped screen declares */
    private static final Decoration.TextWell WELL = Decoration.FIELD.textWell().orElseThrow();

    /** longer than the field's own cap, so the cut is observable */
    private static final String OVER_LENGTH = "x".repeat(WELL.maxLength() + 12);

    /** the name the parity subjects carry, which is exactly the cap and far wider than the field */
    private static final String AT_LENGTH = "The Quick Brown Fox Jumps Over The Lazy Dog 123456";

    private static LineSegment plain(String text) {
        return LineSegment.builder().withSegments(new ColorSegment(text)).build();
    }

    private static int widthOf(String text) {
        return TextKit.measureLineMcPixels(plain(text));
    }

    @Test
    @DisplayName("text past the cap is dropped rather than refused")
    void textPastTheCapIsDropped() {
        assertThat(MenuRenderer.typedInto(OVER_LENGTH, WELL.maxLength()).length(),
            is(equalTo(WELL.maxLength())));
        assertThat("a name within the cap arrives whole",
            MenuRenderer.typedInto("Excalibur", WELL.maxLength()),
            is(equalTo("Excalibur")));
        assertThat("and the parity subjects' own name sits exactly on it",
            AT_LENGTH.length(), is(equalTo(WELL.maxLength())));
    }

    @Test
    @DisplayName("a name that fits is shown whole")
    void aNameThatFitsIsShownWhole() {
        assertThat(MenuRenderer.visibleTail("Excalibur", WELL.innerWidth()), is(equalTo("Excalibur")));
        assertThat(MenuRenderer.visibleTail("", WELL.innerWidth()), is(equalTo("")));
    }

    @Test
    @DisplayName("a name too wide is shown from its end, at the widest tail that fits")
    void aNameTooWideIsShownFromItsEnd() {
        assertThat("the fixture is wider than the field", widthOf(AT_LENGTH),
            is(greaterThan(WELL.innerWidth())));

        String tail = MenuRenderer.visibleTail(AT_LENGTH, WELL.innerWidth());
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
        for (MenuScreen screen : MenuScreen.measured()) {
            boolean hasField = screen.layout(true).marks().stream()
                .anyMatch(mark -> mark.kind().textWell().isPresent());

            assertThat(screen + " declares a field only if it is the anvil",
                hasField, is(equalTo(screen.equals(MenuScreen.anvil()))));
        }
    }

    @Test
    @DisplayName("the field's text sits inside its own well")
    void theFieldsTextSitsInsideItsOwnWell() {
        MenuLayout layout = MenuScreen.anvil().layout(true);
        MenuLayout.Mark field = layout.marks().stream()
            .filter(mark -> mark.kind().textWell().isPresent())
            .findFirst()
            .orElseThrow();

        // The insets clear the two-pixel bevel on each side, and the text runs no wider than what is
        // left between them - a field that let its text reach the well's edge would paint over it.
        assertThat("the text opens clear of the bevel", WELL.inset().x(), is(greaterThan(2)));
        assertThat("the run fits between the bevels",
            WELL.inset().x() + WELL.innerWidth(),
            is(lessThanOrEqualTo(field.kind().extent().width() - 2)));
    }

}
