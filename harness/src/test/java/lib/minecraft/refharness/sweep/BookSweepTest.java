package lib.minecraft.refharness.sweep;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the book roster's own refusals, the ones made before any page meets the client's codec.
 *
 * <p>The tracked roster is of the format read and names every key once, so no boot over it reaches
 * either refusal, and disabling one leaves every run drawing and every other suite green. The codec's
 * refusal needs the running registries and is a boot's to meet.
 */
@DisplayName("the book roster is read as the sweep reads it")
class BookSweepTest {

    /** Where the refusals say the roster was read from. */
    private static final Path FILE = Path.of("book-roster.json");

    @Test
    @DisplayName("a roster of the format read answers its subjects in order, a subject naming no page with none")
    void rosterAnswersItsSubjects() {
        JsonObject page = JsonParser.parseString("{\"text\": \"One line\"}").getAsJsonObject();
        List<BookSweep.RosterEntry> entries = BookSweep.entries(roster("1",
            "{\"key\": \"background\"}, {\"key\": \"one_line\", \"page\": {\"text\": \"One line\"}}"), FILE);

        assertEquals(List.of(new BookSweep.RosterEntry("background", Optional.empty()),
                new BookSweep.RosterEntry("one_line", Optional.of(page))), entries,
            "the background subject carries no page, and a page is handed on as the roster spells it");
    }

    @Test
    @DisplayName("a roster of another format, or naming none, is refused")
    void anotherFormatIsRefused() {
        assertThrows(IllegalStateException.class,
            () -> BookSweep.entries(roster("2", "{\"key\": \"background\"}"), FILE), "format 2");
        assertThrows(IllegalStateException.class,
            () -> BookSweep.entries(JsonParser.parseString("{\"subjects\": []}").getAsJsonObject(), FILE),
            "no format at all");
    }

    @Test
    @DisplayName("two subjects that would write one file are refused")
    void twoSubjectsOneFileAreRefused() {
        assertThrows(IllegalStateException.class,
            () -> BookSweep.entries(roster("1", "{\"key\": \"empty\"}, {\"key\": \"empty\", \"page\": \"\"}"), FILE));
    }

    @Test
    @DisplayName("a roster listing no subjects, or a subject carrying no key, is refused")
    void malformedSubjectsAreRefused() {
        assertThrows(IllegalStateException.class,
            () -> BookSweep.entries(JsonParser.parseString("{\"format\": 1}").getAsJsonObject(), FILE),
            "no subjects member");
        assertThrows(IllegalStateException.class,
            () -> BookSweep.entries(roster("1", "{\"page\": \"\"}"), FILE), "a subject with no key");
    }

    /**
     * Builds a roster of one format over the given subjects.
     *
     * @param format the format member, as the roster spells it
     * @param subjects the subjects array's members, as JSON text
     * @return the roster
     */
    private static JsonObject roster(String format, String subjects) {
        return JsonParser.parseString("{\"format\": " + format + ", \"subjects\": [" + subjects + "]}").getAsJsonObject();
    }
}
