package lib.minecraft.renderer.content.rule;

import lib.minecraft.renderer.asset.rule.ColorProperties;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link ColorPropertiesParser#parse}: a value that is blank or does not parse as hex
 * leaves its key unset while the file's other keys stand, a good value is read opaque, and a body
 * that cannot be loaded at all answers no file rather than an empty one.
 */
@DisplayName("ColorPropertiesParser.parse")
class ColorPropertiesParserTest {

    private static final @NotNull ResourceId ID = new ResourceId("minecraft", "optifine/color.properties");

    private static final @NotNull PackId PACK = new PackId("userpack");

    @Test
    @DisplayName("a blank, whitespace-only, bare or unparseable value is unset, and a good key beside it stays")
    void blankAndUnparseableValuesAreUnset() {
        for (String line : List.of("redstone.0=", "redstone.0=   ", "redstone.0", "redstone.0=zzz")) {
            ColorProperties parsed = parse(line + "\nredstone.1=334cff");

            assertThat(line, parsed.get("redstone.0"), is(Optional.empty()));
            assertThat(line, parsed.get("redstone.1"), is(Optional.of(0xFF334CFF)));
        }
    }

    @Test
    @DisplayName("a bare six-digit value is read with its alpha forced opaque")
    void bareSixDigitValueIsOpaque() {
        assertThat(parse("redstone.0=000000").get("redstone.0"), is(Optional.of(0xFF000000)));
        assertThat(parse("lilypad=5EA334").get("lilypad"), is(Optional.of(0xFF5EA334)));
    }

    @Test
    @DisplayName("a body holding no usable key is a file with no override, not a missing file")
    void bodyWithNoUsableKeyIsPresentAndEmpty() {
        for (String body : List.of("", "# only a comment", "redstone.0="))
            assertThat(body, ColorPropertiesParser.parse(body, ID, PACK).map(ColorProperties::isEmpty), is(Optional.of(true)));
    }

    @Test
    @DisplayName("a body with a malformed unicode escape answers no file")
    void malformedEscapeAnswersNoFile() {
        assertThat(ColorPropertiesParser.parse("redstone.0=0x222222\nbroken=\\uZZZZ", ID, PACK), is(Optional.empty()));
    }

    /**
     * Parses a body that loads.
     *
     * @param body the file body
     * @return the parsed properties
     */
    private static @NotNull ColorProperties parse(@NotNull String body) {
        return ColorPropertiesParser.parse(body, ID, PACK).orElseThrow();
    }

}
