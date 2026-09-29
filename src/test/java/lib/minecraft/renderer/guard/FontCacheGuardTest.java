package lib.minecraft.renderer.guard;

import lib.minecraft.renderer.support.MinecraftFontsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The one test that reports missing Minecraft fonts, so a suite thinned by their absence reads as a
 * failure rather than as a silence.
 *
 * <p>Every class that renders text installs {@link MinecraftFontsExtension}, which abandons the class
 * where no cache holds the fonts - which is what keeps the fast suite off the network, and equally what
 * would let a machine with no fonts report green over coverage it never ran. This asserts the condition
 * those classes assume, so the suite says once and loudly what is missing and which test writes it.
 *
 * <p>It installs no extension itself. Installing one would make this abandon on exactly the condition
 * it exists to report.
 */
@DisplayName("The Minecraft fonts every text-rendering test assumes")
final class FontCacheGuardTest {

    @Test
    @DisplayName("the Minecraft fonts are cached, or this names the test that writes them")
    void theFontsArePresent() {
        assertThat("no cache holds every Minecraft font. Every test rendering text assumes away without"
                + " them, so the suite reports green over coverage it did not run. Write them with"
                + " './gradlew slowTest --tests \"*FontGenerationIntegrationTest\"'",
            MinecraftFontsExtension.isPresent(), is(true));
    }

}
