package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link BundledResource}: a present resource envelope-validates through
 * {@link ResourceDocument} through either entry point, and an absent resource is empty under
 * {@link BundledResource#read} and fatal under {@link BundledResource#require}.
 */
@DisplayName("BundledResource classpath read + per-method missing policy")
class BundledResourceTest {
    @Test
    @DisplayName("reads a bundled required resource and envelope-validates it")
    void readsRequiredResource() {
        // open() throws on any format other than 2 and require() throws on an absent resource, so a
        // returned document IS the envelope assertion.
        assertNotNull(BundledResource.require("potion_colors.json"),
            "the bundled potion_colors.json must be present and envelope-valid");
    }

    @Test
    @DisplayName("reads a bundled resource through the absence-tolerating entry point too")
    void readsPresentResourceGracefully() {
        Optional<ResourceDocument> doc = BundledResource.read("entity_models.json");

        assertTrue(doc.isPresent(), "entity_models.json is present, so the tolerant read still yields it");
    }

    @Test
    @DisplayName("read returns empty for an absent resource")
    void gracefulEmptyForAbsent() {
        Optional<ResourceDocument> doc = BundledResource.read("does_not_exist.json");

        assertTrue(doc.isEmpty(), "an absent resource must yield Optional.empty() from read");
    }

    @Test
    @DisplayName("require throws for an absent resource")
    void requiredThrowsForAbsent() {
        assertThrows(PipelineException.class,
            () -> BundledResource.require("does_not_exist.json"));
    }
}
