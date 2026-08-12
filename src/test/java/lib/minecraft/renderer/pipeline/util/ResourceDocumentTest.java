package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins for {@link ResourceDocument} envelope validation: {@code format == 2} accept, missing/wrong format
 * reject, a {@code source_version} mismatch parsing rather than throwing, and the typed DTO
 * deserialisation surface. The envelope members are validated and not retained, so the throw is what
 * the validation is observable through.
 */
@DisplayName("ResourceDocument envelope validation + DTO surface")
class ResourceDocumentTest {

    /** The version stamp the fixtures declare, re-stated here so an MC bump fails loudly */
    private static final @NotNull String SOURCE_VERSION = "26.1";
    private static byte @NotNull [] bytes(@NotNull String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("accepts format 2 with a // header and a matching source_version")
    void acceptsFormatTwo() {
        ResourceDocument.open(
            bytes("{\"//\":\"provenance\",\"format\":2,\"source_version\":\"" + SOURCE_VERSION + "\",\"effects\":[]}"));

        // TODO: restore pipeline diagnostics
        // assertEquals(0, diag.count(Diagnostics.Severity.WARN), "a matching source_version must not warn");
    }

    @Test
    @DisplayName("rejects a resource with no format member")
    void rejectsMissingFormat() {
        assertThrows(PipelineException.class,
            () -> ResourceDocument.open(bytes("{\"source_version\":\"" + SOURCE_VERSION + "\"}")));
    }

    @Test
    @DisplayName("rejects a resource declaring a non-2 format")
    void rejectsWrongFormat() {
        assertThrows(PipelineException.class,
            () -> ResourceDocument.open(bytes("{\"format\":1,\"source_version\":\"" + SOURCE_VERSION + "\"}")));
    }

    @Test
    @DisplayName("rejects non-JSON bytes")
    void rejectsMalformedJson() {
        assertThrows(PipelineException.class,
            () -> ResourceDocument.open(bytes("not json at all")));
    }

    @Test
    @DisplayName("parses (not throws) on a source_version mismatch")
    void parsesOnVersionMismatch() {
        ResourceDocument.open(bytes("{\"format\":2,\"source_version\":\"99.9\"}"));

        // TODO: restore pipeline diagnostics
        // assertEquals(1, diag.count(Diagnostics.Severity.WARN), "a mismatched source_version must warn once");
    }

    @Test
    @DisplayName("parses on an absent source_version")
    void parsesOnAbsentVersion() {
        ResourceDocument.open(bytes("{\"format\":2}"));

        // TODO: restore pipeline diagnostics
        // assertEquals(1, diag.count(Diagnostics.Severity.WARN), "an absent source_version must warn once");
    }

    @Test
    @DisplayName("as() deserialises the payload into a typed DTO, ignoring envelope members")
    void asDeserialisesDto() {
        ResourceDocument doc = ResourceDocument.open(
            bytes("{\"format\":2,\"source_version\":\"" + SOURCE_VERSION + "\",\"count\":7,\"label\":\"glint\"}"));

        Payload payload = doc.as(Payload.class);
        assertEquals(7, payload.count());
        assertEquals("glint", payload.label());
    }

    /** A minimal DTO proving whole-document deserialisation ignores the envelope members. */
    record Payload(int count, @NotNull String label) {}
}
