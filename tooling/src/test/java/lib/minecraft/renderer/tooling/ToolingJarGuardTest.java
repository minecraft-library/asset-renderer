package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.client.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test that reports a missing client jar, so a suite thinned by five reads as a failure
 * rather than as a silence.
 *
 * <p>Each walk here opens the jar the cache already holds and abandons its class where nothing has
 * cached one, which is what keeps this suite off the network. That leaves the walks unable to say
 * they did not run, so this says it for them, once, naming what writes the jar.
 *
 * <p>It is the renderer's client-extraction guard for the jar rather than the extracted tree,
 * because a walk reads bytecode: the tree is the renderer's requirement and one file is this
 * build's.
 */
@DisplayName("The cached client jar every walk assumes")
class ToolingJarGuardTest {

    @Test
    @DisplayName("the cached client jar is on disk, or this names what writes it")
    void theCachedJarIsPresent() {
        Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
        assertTrue(Files.isRegularFile(jar), () -> "no cached client jar at '" + jar + "'. Every walk"
            + " here assumes away without one, so this suite reports green over five classes that did"
            + " not run. Cache one with './gradlew generateTables', or any parity capture");
    }

}
