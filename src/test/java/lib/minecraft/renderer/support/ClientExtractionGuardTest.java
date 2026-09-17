package lib.minecraft.renderer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The one test that reports a missing client extraction, so a suite thinned by one reads as a
 * failure rather than as a silence.
 *
 * <p>Every class that reads the client assets installs {@link ClientAssetsExtension}, which abandons
 * the class where nothing has extracted the client - which is what keeps the fast suite off the
 * network, and equally what would let a machine with no extraction report green over coverage it
 * never ran. This asserts the condition those classes assume, so the suite says once and loudly what
 * is missing and which task writes it, instead of saying nothing many times.
 *
 * <p>It installs no extension itself. Installing one would make this abandon on exactly the
 * condition it exists to report.
 */
@DisplayName("The client extraction every asset-reading test assumes")
final class ClientExtractionGuardTest {

    @Test
    @DisplayName("the extracted client assets are on disk, or this names the task that writes them")
    void theExtractionIsPresent() {
        assertThat("no client extraction at '" + ClientAssetsExtension.vanillaRoot() + "'. Every test"
                + " reading the client assets assumes away without one, so the suite reports green over"
                + " coverage it did not run. Write one with './gradlew slowTest --tests"
                + " \"*ClientAcquisitionIntegrationTest\"', or by any task that boots the pipeline",
            ClientAssetsExtension.isExtracted(), is(true));
    }

}
