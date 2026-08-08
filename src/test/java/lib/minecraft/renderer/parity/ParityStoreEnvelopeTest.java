package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The reader's refusals, fired against a fixture store.
 *
 * <p>The envelope guards - the file being a JSON object at all, its {@code artifact} declaration
 * against the id it was asked for, and its {@code format} against the one this reader understands -
 * are each about a file that is present and wrong. The production store holds no such file, so a
 * suite reading it reaches none of them, and {@link ParityStore#readFrom} taking its root as an
 * argument is what makes a directory built to hold one a legal operand. Each case about a wrong file
 * writes exactly that file under a temporary root; the case that reads the supported format writes a
 * well-formed one, so the refusal beside it is of something rather than of every file; and the two
 * refusals that answer before any file is opened - an id whose kind prefix names no directory, and
 * an id the store's index does not register - take no directory at all.
 *
 * <p>They are the guards that decide whether a wrong file is read as a right one. A store file copied
 * from its neighbour keeps its neighbour's {@code artifact} declaration; a file from a newer schema
 * carries members this reader would silently drop; and a truncated write is not an object at all. In
 * each case the failure mode is the same shape - a read that succeeds and answers about something
 * else - which is the one a comparison cannot detect afterwards.
 */
@DisplayName("The parity reader refuses every envelope it says it refuses")
final class ParityStoreEnvelopeTest {

    /** The id every fixture below is written and read as. */
    private static final String ARTIFACT = "pin.kit-corners";

    @Test
    @DisplayName("an id naming no kind the store holds has no path")
    void anUnmappableIdHasNoPath() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.pathOf("sweep"));

        assertThat("the kind prefix alone is not an artifact - the rule needs a name after the dot, "
            + "and a bare kind would otherwise resolve to a directory with an empty stem",
            thrown.getMessage(), containsString("No store path for artifact id 'sweep'"));
    }

    @Test
    @DisplayName("a store file that is not a JSON object is refused rather than half-read")
    void aNonJsonFileIsRefused(@TempDir Path root) {
        write(root, "this is not JSON at all");

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.readFrom(root, ARTIFACT));

        assertThat("a truncated or hand-mangled write, named as the file it is",
            thrown.getMessage(), containsString("is not a JSON object"));
        assertThat("and the path, because the id alone does not say which of the two roots it was "
            + "read from", thrown.getMessage(), containsString(ARTIFACT));
    }

    @Test
    @DisplayName("a file declaring another artifact's id is refused")
    void aFileClaimingItsNeighboursIdIsRefused(@TempDir Path root) {
        write(root, "{\"artifact\": \"pin.player-crc\", \"format\": 1}");

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.readFrom(root, ARTIFACT));

        assertThat("copying a store file over its neighbour is the one edit that leaves a "
                + "well-formed artifact answering for a value it never held, so the declaration "
                + "inside the file is checked against the id it was asked for",
            thrown.getMessage(),
            containsString("declares itself 'pin.player-crc' but was read as '" + ARTIFACT + "'"));
    }

    @Test
    @DisplayName("a file declaring no artifact at all is refused as a mismatch")
    void anUndeclaredFileIsRefused(@TempDir Path root) {
        write(root, "{\"format\": 1}");

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.readFrom(root, ARTIFACT));

        assertThat("an absent declaration reads as the empty one rather than as a pass, because the "
            + "check is what the file says it is and a file saying nothing says nothing",
            thrown.getMessage(), containsString("declares itself '' but was read as '" + ARTIFACT + "'"));
    }

    @Test
    @DisplayName("a file from a newer schema is refused rather than partly understood")
    void aNewerFormatIsRefused(@TempDir Path root) {
        write(root, "{\"artifact\": \"" + ARTIFACT + "\", \"format\": " + (ParityStore.SUPPORTED_FORMAT + 1) + "}");

        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.readFrom(root, ARTIFACT));

        assertThat("both numbers, because which reader is behind which file is the whole question",
            thrown.getMessage(),
            containsString("declares format " + (ParityStore.SUPPORTED_FORMAT + 1)
                + " and this reader understands " + ParityStore.SUPPORTED_FORMAT));
    }

    @Test
    @DisplayName("the supported format itself reads clean, so the refusal above is of something")
    void theSupportedFormatIsAccepted(@TempDir Path root) {
        write(root, "{\"artifact\": \"" + ARTIFACT + "\", \"format\": " + ParityStore.SUPPORTED_FORMAT + "}");

        assertThat("the boundary is above the supported version and not at it, which the refusal "
                + "case on its own cannot say",
            ParityStore.readFrom(root, ARTIFACT).get("artifact").getAsString(), equalTo(ARTIFACT));
    }

    @Test
    @DisplayName("asking whether an unregistered artifact is baselined refuses rather than answering no")
    void anUnregisteredArtifactHasNoBaselineAnswer() {
        ParityStoreException thrown = assertThrows(ParityStoreException.class,
            () -> ParityStore.isBaselined("pin.not-a-real-artifact"));

        assertThat("false would read as \"not promoted yet\", which is a different statement from "
            + "\"nothing has ever decided this should exist\" - and the second is what an id the "
            + "index does not carry means",
            thrown.getMessage(), containsString("is not registered in the store index"));
    }

    /**
     * Writes one fixture file at the artifact's own store path under a temporary root.
     *
     * @param root the fixture store root
     * @param text the file's exact text
     */
    private static void write(Path root, String text) {
        Path file = root.resolve(ParityStore.pathOf(ARTIFACT));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
