package lib.minecraft.renderer.pipeline.load;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.BundledResources.MissingPolicy;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link BundledResources}: a present resource envelope-validates through
 * {@link ResourceDocument}, an absent resource is graceful-empty under
 * {@link MissingPolicy#GRACEFUL_EMPTY} and throws under {@link MissingPolicy#REQUIRED}.
 */
@DisplayName("BundledResources classpath read + declared missing policy")
class BundledResourcesTest {

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

    @Test
    @DisplayName("reads a bundled required resource and envelope-validates it")
    void readsRequiredResource() {
        Optional<ResourceDocument> doc = BundledResources.read("potion_colors.json", MissingPolicy.REQUIRED, diagnostics());

        assertTrue(doc.isPresent(), "the bundled potion_colors.json must be present");
        assertEquals(2, doc.get().format());
    }

    @Test
    @DisplayName("reads a bundled resource under the graceful-empty policy too")
    void readsPresentResourceGracefully() {
        Optional<ResourceDocument> doc = BundledResources.read("entity_models.json", MissingPolicy.GRACEFUL_EMPTY, diagnostics());

        assertTrue(doc.isPresent(), "entity_models.json is present, so graceful-empty still yields it");
    }

    @Test
    @DisplayName("graceful-empty policy returns empty for an absent resource")
    void gracefulEmptyForAbsent() {
        Optional<ResourceDocument> doc = BundledResources.read("does_not_exist.json", MissingPolicy.GRACEFUL_EMPTY, diagnostics());

        assertTrue(doc.isEmpty(), "an absent resource under GRACEFUL_EMPTY must yield Optional.empty()");
    }

    @Test
    @DisplayName("required policy throws for an absent resource")
    void requiredThrowsForAbsent() {
        assertThrows(PipelineException.class,
            () -> BundledResources.read("does_not_exist.json", MissingPolicy.REQUIRED, diagnostics()));
    }
}
