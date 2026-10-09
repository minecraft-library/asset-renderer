package lib.minecraft.renderer.guard;

import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The one test that reports a missing client jar, so a suite thinned by one reads as a failure rather
 * than as a silence.
 *
 * <p>Two sets of tests read the jar the cache holds and abandon their class where nothing has cached
 * one, which is what keeps them off the network. Every renderer class that reads the client assets
 * installs {@link ClientAssetsExtension}, which reads the vanilla pack out of the jar; and the tooling
 * build's gate walks read its bytecode, each abandoning on the same file. That leaves both sets unable
 * to say they did not run, so this says it for them, once and loudly, naming what writes the jar.
 *
 * <p>It installs no extension itself. Installing one would make this abandon on exactly the
 * condition it exists to report.
 */
@DisplayName("The cached client jar every asset-reading test and every tooling walk assumes")
final class ClientJarGuardTest {

    @Test
    @DisplayName("the cached client jar is on disk, or this names what writes it")
    void theCachedJarIsPresent() {
        assertThat("no cached client jar at '" + ClientAssetsExtension.jar() + "'. Every test reading"
                + " the client assets and every tooling walk assumes away without one, so the suites report"
                + " green over coverage they did not run. Cache one with './gradlew slowTest --tests"
                + " \"*ClientAcquisitionIntegrationTest\"', './gradlew generateTables', or any task that"
                + " boots the pipeline",
            ClientAssetsExtension.isCached(), is(true));
    }

}
