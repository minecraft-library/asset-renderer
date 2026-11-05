package lib.minecraft.refharness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the refusal every run makes before its first sweep - of a client that does not draw as vanilla's
 * own - clause by clause.
 *
 * <p>The run directory every boot shares holds vanilla's selection and options, so no boot reaches a
 * refusal: deleting the check, or inverting one of its clauses, leaves every run drawing and every
 * other suite green. These cases are what holds each clause.
 */
@DisplayName("a run refuses a client that does not draw as vanilla's own")
class RefHarnessRendererTest {

    /** Ids a harness client selects over the shared run directory: vanilla's own pack and the mods' packs. */
    private static final List<String> VANILLA = List.of("vanilla", "fabric-api", "fabricloader");

    /** The class under test's source, read from the harness build's own directory. */
    private static final Path SOURCE = Path.of("src/client/java/lib/minecraft/refharness/RefHarnessRenderer.java");

    @Test
    @DisplayName("the run's start makes the check before it builds the first sweep")
    void startChecksBeforeTheFirstSweep() throws IOException {
        // Read as text, because the call sits in a method only a running client reaches: deleting it,
        // or moving it below the sweeps' construction, compiles clean and leaves every case below green.
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int start = source.indexOf("static void start(Minecraft client) {");
        int check = source.indexOf("requireVanillaClient(client);", start);
        int sweeps = source.indexOf("HarnessMode.sweeps(modes)", start);

        assertTrue(start >= 0, "the run's start is where it was looked for");
        assertTrue(check > start && sweeps > check,
            "start calls the check, and calls it before the first sweep exists, so a refused run writes no file");
    }

    @Test
    @DisplayName("a client selecting only built-in packs that redraw nothing is accepted")
    void vanillaSelectionIsAccepted() {
        assertDoesNotThrow(() -> RefHarnessRenderer.requireVanillaPacks(VANILLA, List.of()));
    }

    @Test
    @DisplayName("either built-in pack that redraws vanilla's resources is refused")
    void redrawingBuiltInPacksAreRefused() {
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaPacks(List.of("vanilla", "programmer_art"), List.of()),
            "programmer art replaces the block, item, entity and GUI textures the sweeps draw");
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaPacks(List.of("vanilla", "high_contrast"), List.of()),
            "high contrast replaces vanilla's GUI sprites");
    }

    @Test
    @DisplayName("a folder pack the run did not ask for is refused")
    void unrequestedFolderPackIsRefused() {
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaPacks(List.of("vanilla", "file/pack.zip"), List.of()),
            "a run naming no pack draws through none");
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaPacks(
                List.of("vanilla", "file/asked.zip", "file/left.zip"), List.of("file/asked.zip")),
            "a pack the run asked for licenses no other folder pack beside it");
    }

    @Test
    @DisplayName("a folder pack the run asked for is accepted")
    void requestedFolderPackIsAccepted() {
        assertDoesNotThrow(() -> RefHarnessRenderer.requireVanillaPacks(
            List.of("vanilla", "file/asked.zip"), List.of("file/asked.zip")));
    }

    @Test
    @DisplayName("vanilla's default text options are accepted")
    void defaultOptionsAreAccepted() {
        assertDoesNotThrow(() -> RefHarnessRenderer.requireVanillaOptions(false, false, false, "en_us"));
    }

    @Test
    @DisplayName("each text option off its default is refused on its own")
    void eachOptionIsRefused() {
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaOptions(true, false, false, "en_us"), "high contrast");
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaOptions(false, true, false, "en_us"), "the forced Unicode font");
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaOptions(false, false, true, "en_us"), "the Japanese glyph variants");
        assertThrows(IllegalStateException.class,
            () -> RefHarnessRenderer.requireVanillaOptions(false, false, false, "de_de"), "another language");
    }
}
