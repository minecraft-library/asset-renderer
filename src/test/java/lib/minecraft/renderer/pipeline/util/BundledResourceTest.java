package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.BundledResource.MissingPolicy;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link BundledResource}: a present resource envelope-validates through
 * {@link ResourceDocument}, an absent resource is graceful-empty under
 * {@link MissingPolicy#GRACEFUL_EMPTY} and throws under {@link MissingPolicy#REQUIRED}.
 */
@DisplayName("BundledResource classpath read + declared missing policy")
class BundledResourceTest {

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    @Test
    @DisplayName("reads a bundled required resource and envelope-validates it")
    void readsRequiredResource() {
        Optional<ResourceDocument> doc = BundledResource.read("potion_colors.json", MissingPolicy.REQUIRED, diagnostics());

        assertTrue(doc.isPresent(), "the bundled potion_colors.json must be present");
        assertEquals(2, doc.get().envelope().format());
    }

    @Test
    @DisplayName("reads a bundled resource under the graceful-empty policy too")
    void readsPresentResourceGracefully() {
        Optional<ResourceDocument> doc = BundledResource.read("entity_models.json", MissingPolicy.GRACEFUL_EMPTY, diagnostics());

        assertTrue(doc.isPresent(), "entity_models.json is present, so graceful-empty still yields it");
    }

    @Test
    @DisplayName("graceful-empty policy returns empty for an absent resource")
    void gracefulEmptyForAbsent() {
        Optional<ResourceDocument> doc = BundledResource.read("does_not_exist.json", MissingPolicy.GRACEFUL_EMPTY, diagnostics());

        assertTrue(doc.isEmpty(), "an absent resource under GRACEFUL_EMPTY must yield Optional.empty()");
    }

    @Test
    @DisplayName("required policy throws for an absent resource")
    void requiredThrowsForAbsent() {
        assertThrows(PipelineException.class,
            () -> BundledResource.read("does_not_exist.json", MissingPolicy.REQUIRED, diagnostics()));
    }
}
