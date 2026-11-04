package lib.minecraft.renderer.content.read;

import lib.minecraft.renderer.exception.ContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link BundledResource}'s per-method missing policy: an absent resource is empty under
 * {@link BundledResource#read} and fatal under {@link BundledResource#require}.
 */
@DisplayName("BundledResource classpath read + per-method missing policy")
class BundledResourceTest {
    @Test
    @DisplayName("read returns empty for an absent resource")
    void gracefulEmptyForAbsent() {
        Optional<ResourceDocument> doc = BundledResource.read("does_not_exist.json");

        assertTrue(doc.isEmpty(), "an absent resource must yield Optional.empty() from read");
    }

    @Test
    @DisplayName("require throws for an absent resource")
    void requiredThrowsForAbsent() {
        assertThrows(ContentException.class,
            () -> BundledResource.require("does_not_exist.json"));
    }
}
