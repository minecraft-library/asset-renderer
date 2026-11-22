package lib.minecraft.renderer.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Holds the working root to a directory of its own inside {@code cache/}, however it is spelled.
 *
 * <p>The root is a parameter here rather than {@link ParityStore#WORKING}, which is resolved once
 * from a system property. The rule under test is the one the build spells a second time, and
 * {@code ParityTaskWiringTest} holds the two spellings to each other, so what is refused here is
 * what a {@code -PparityRoot} is refused for.
 */
@DisplayName("The working root is a directory of its own inside cache/")
final class ParityStoreWorkingRootTest {

    @Test
    @DisplayName("the default and a redirected root are accepted as they are spelled")
    void aRootInsideCacheIsAccepted() {
        for (String root : List.of("cache/parity/current", "cache/parity/base", "cache/parity/a.b-c_d", "cache/x"))
            assertThat(root, ParityStore.resolveWorking(root), equalTo(Path.of(root)));
    }

    @Test
    @DisplayName("either separator spells the same root")
    void aBackslashSeparatesAsASlashDoes() {
        assertThat(ParityStore.resolveWorking("cache\\parity\\base"), equalTo(Path.of("cache\\parity\\base")));
        assertThrows(ParityStoreException.class, () -> ParityStore.resolveWorking("cache\\..\\x"));
    }

    @Test
    @DisplayName("a root outside cache/, cache/ itself and a root that climbs back out are refused")
    void aRootThatIsNotInsideCacheIsRefused() {
        for (String root : List.of("", "cache", "cache/", "cache/.", "cache/..", "cache/../..", "cache/a/../../x",
            "cache/parity/../base", "cache//parity", "cache/parity/", "../cache/parity", "build/parity", "/cache/parity",
            "C:/cache/parity", "cache/par ity", "cachex/parity"))
            assertThrows(ParityStoreException.class, () -> ParityStore.resolveWorking(root), root);
    }

}
