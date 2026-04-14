package dev.sbs.renderer.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Smoke coverage for {@link MinecraftFont}. Forces every classpath-backed enum value to load
 * its underlying {@code .otf} file, catching any mismatch between the enum's declared
 * filenames and what the {@code generateFonts} Gradle task actually produces.
 */
@DisplayName("MinecraftFont resolves every .otf from the classpath")
class MinecraftFontTest {

    @Test
    @DisplayName("REGULAR loads Minecraft-Regular.otf")
    void regularResolves() {
        assertThat(MinecraftFont.REGULAR.getActual(), notNullValue());
        assertThat(MinecraftFont.REGULAR.getStyle(), equalTo(MinecraftFont.Style.REGULAR));
        assertThat(MinecraftFont.REGULAR.getSize(), greaterThan(0f));
    }

    @Test
    @DisplayName("BOLD loads Minecraft-Bold.otf")
    void boldResolves() {
        assertThat(MinecraftFont.BOLD.getActual(), notNullValue());
        assertThat(MinecraftFont.BOLD.getStyle(), equalTo(MinecraftFont.Style.BOLD));
    }

    @Test
    @DisplayName("ITALIC loads Minecraft-Italic.otf")
    void italicResolves() {
        assertThat(MinecraftFont.ITALIC.getActual(), notNullValue());
        assertThat(MinecraftFont.ITALIC.getStyle(), equalTo(MinecraftFont.Style.ITALIC));
    }

    @Test
    @DisplayName("BOLD_ITALIC loads Minecraft-BoldItalic.otf")
    void boldItalicResolves() {
        assertThat(MinecraftFont.BOLD_ITALIC.getActual(), notNullValue());
        assertThat(MinecraftFont.BOLD_ITALIC.getStyle(), equalTo(MinecraftFont.Style.BOLD_ITALIC));
    }

    @Test
    @DisplayName("GALACTIC loads Minecraft-Galactic.otf (Standard Galactic Alphabet)")
    void galacticResolves() {
        assertThat(MinecraftFont.GALACTIC.getActual(), notNullValue());
        assertThat(MinecraftFont.GALACTIC.getStyle(), equalTo(MinecraftFont.Style.GALACTIC));
    }

    @Test
    @DisplayName("ILLAGERALT loads Minecraft-Illageralt.otf")
    void illageraltResolves() {
        assertThat(MinecraftFont.ILLAGERALT.getActual(), notNullValue());
        assertThat(MinecraftFont.ILLAGERALT.getStyle(), equalTo(MinecraftFont.Style.ILLAGERALT));
    }

    @Test
    @DisplayName("of(style) returns the matching enum value for typographical styles")
    void ofStyleReturnsMatchingTypographicStyles() {
        assertThat(MinecraftFont.of(MinecraftFont.Style.REGULAR), equalTo(MinecraftFont.REGULAR));
        assertThat(MinecraftFont.of(MinecraftFont.Style.BOLD), equalTo(MinecraftFont.BOLD));
        assertThat(MinecraftFont.of(MinecraftFont.Style.ITALIC), equalTo(MinecraftFont.ITALIC));
        assertThat(MinecraftFont.of(MinecraftFont.Style.BOLD_ITALIC), equalTo(MinecraftFont.BOLD_ITALIC));
    }

    @Test
    @DisplayName("of(style) returns the matching enum value for alternate-script styles")
    void ofStyleReturnsMatchingScriptStyles() {
        assertThat(MinecraftFont.of(MinecraftFont.Style.GALACTIC), equalTo(MinecraftFont.GALACTIC));
        assertThat(MinecraftFont.of(MinecraftFont.Style.ILLAGERALT), equalTo(MinecraftFont.ILLAGERALT));
    }

    @Test
    @DisplayName("Style.of(int) round-trips awt style ints")
    void styleOfIntRoundTripsAwtIds() {
        assertThat(MinecraftFont.Style.of(0), equalTo(MinecraftFont.Style.REGULAR));
        assertThat(MinecraftFont.Style.of(1), equalTo(MinecraftFont.Style.BOLD));
        assertThat(MinecraftFont.Style.of(2), equalTo(MinecraftFont.Style.ITALIC));
        assertThat(MinecraftFont.Style.of(3), equalTo(MinecraftFont.Style.BOLD_ITALIC));
        assertThat(MinecraftFont.Style.of(4), equalTo(MinecraftFont.Style.GALACTIC));
        assertThat(MinecraftFont.Style.of(5), equalTo(MinecraftFont.Style.ILLAGERALT));
    }

    @Test
    @DisplayName("Style.of(unknown) falls back to REGULAR")
    void styleOfUnknownFallsBackToRegular() {
        assertThat(MinecraftFont.Style.of(99), equalTo(MinecraftFont.Style.REGULAR));
        assertThat(MinecraftFont.Style.of(-1), equalTo(MinecraftFont.Style.REGULAR));
    }

}
