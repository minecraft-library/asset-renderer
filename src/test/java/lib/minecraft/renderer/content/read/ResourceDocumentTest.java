package lib.minecraft.renderer.content.read;

import com.google.gson.JsonSyntaxException;
import dev.simplified.gson.exception.JsonException;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for {@link ResourceDocument} envelope validation: {@code format == 2} accept, missing/wrong format
 * reject, the multi-format overload accepting every named value and refusing the rest, a
 * {@code source_version} mismatch parsing rather than throwing, the typed DTO deserialisation
 * surface, and a payload that parses and does not bind refused as a {@link ContentException}. The
 * accepted format is retained on {@code format()}; the rest of the envelope is validated and not
 * retained, so the throw is what the validation is observable through.
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
        assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("{\"source_version\":\"" + SOURCE_VERSION + "\"}")));
    }

    @Test
    @DisplayName("rejects a resource declaring a non-2 format")
    void rejectsWrongFormat() {
        assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("{\"format\":1,\"source_version\":\"" + SOURCE_VERSION + "\"}")));
    }

    @Test
    @DisplayName("rejects non-JSON bytes")
    void rejectsMalformedJson() {
        assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("not json at all")));
    }

    @Test
    @DisplayName("the multi-format open accepts every named format, and format() answers the declared value")
    void acceptsEveryNamedFormat() {
        assertEquals(2, ResourceDocument.open(bytes("{\"format\":2}"), 2, 3).format());
        assertEquals(3, ResourceDocument.open(bytes("{\"format\":3}"), 2, 3).format());
        assertEquals(2, ResourceDocument.open(bytes("{\"format\":2}")).format());
    }

    @Test
    @DisplayName("the multi-format open rejects a format outside the named set, listing the set")
    void rejectsFormatOutsideTheNamedSet() {
        ContentException below = assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("{\"format\":1}"), 2, 3));
        assertTrue(below.getMessage().contains("expected '2 or 3'"),
            "the refusal lists the accepted set: " + below.getMessage());
        assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("{\"format\":4}"), 2, 3));
    }

    @Test
    @DisplayName("the strict path still rejects format 3")
    void strictPathStillRejectsFormatThree() {
        assertThrows(ContentException.class,
            () -> ResourceDocument.open(bytes("{\"format\":3}")));
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

    @Test
    @DisplayName("as() refuses a member of the wrong type with a ContentException")
    void asRefusesAMistypedMemberAsContentException() {
        ResourceDocument doc = ResourceDocument.open(bytes("{\"format\":2,\"count\":\"seven\",\"label\":\"glint\"}"));

        ContentException ex = assertThrows(ContentException.class, () -> doc.as(Payload.class));
        assertInstanceOf(JsonSyntaxException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("Payload"), "the refusal names the DTO: " + ex.getMessage());
    }

    @Test
    @DisplayName("as() refuses a malformed colour with a ContentException")
    void asRefusesAMalformedColourAsContentException() {
        ResourceDocument doc = ResourceDocument.open(bytes("{\"format\":2,\"colour\":\"zz\"}"));

        ContentException ex = assertThrows(ContentException.class, () -> doc.as(Tinted.class));
        assertInstanceOf(JsonException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("Tinted"), "the refusal names the DTO: " + ex.getMessage());
    }

    /** A minimal DTO proving whole-document deserialisation ignores the envelope members. */
    record Payload(int count, @NotNull String label) {}

    /** A DTO carrying a colour, bound through the gson-extras colour adapter the bundled tint tables use. */
    record Tinted(@NotNull Color colour) {}
}
