package lib.minecraft.renderer.asset.pose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the idle roster this renderer answers from and the one the harness drives to one another.
 *
 * <p>The two are separate builds, so neither can name the other's type and each declares its own
 * copy. What goes wrong when they part is silent rather than loud: the harness drives a squid's
 * tentacles to one extent while this side answers another, both render happily, and the parity row
 * reports the difference as a defect in this renderer. Nothing else in either build would catch it,
 * because each side is internally consistent.
 *
 * <p>So the constants are compared as text, which is why both are written in one declaration per
 * figure with identical spelling. The same shape as the operator roster's mirror, and for the same
 * reason.
 */
class IdleFigureMirrorTest {

    /** This side's scalar roster. */
    private static final Path ASSET_FIGURES =
        Path.of("src/main/java/lib/minecraft/renderer/asset/pose/IdleFigure.java");

    /** This side's one-hot roster, which is a separate type because it is a separate shape. */
    private static final Path ASSET_STATES =
        Path.of("src/main/java/lib/minecraft/renderer/asset/pose/IdleState.java");

    /** The harness's, which is its own build, holds both rosters and shares no type with this one. */
    private static final Path HARNESS =
        Path.of("harness/src/client/java/lib/minecraft/refharness/IdleFigures.java");

    /** A scalar constant, which both sides spell the same way once its indent is dropped. */
    private static final Pattern CONTINUOUS =
        Pattern.compile("^([A-Z][A-Z_]*)\\(\"(\\w+)\", ([-\\d.f]+), ([-\\d.f]+), Shape\\.(\\w+)\\)[,;]$");

    /** A one-hot member, which carries the selector it belongs to and the field it drives. */
    private static final Pattern STATE =
        Pattern.compile("^([A-Z][A-Z_]*)\\(Group\\.(\\w+), \"(\\w*)\"\\)[,;]$");

    /** Which capture of a shape holds the render-state field, the two shapes spelling it differently. */
    private static final int FIELD_OF_CONTINUOUS = 2;

    private static final int FIELD_OF_STATE = 3;

    @Test
    @DisplayName("the harness drives every scalar this side answers, at the same excursion")
    void theScalarRostersAreOneRoster() throws IOException {
        List<String> asset = constantsOf(ASSET_FIGURES, CONTINUOUS);
        assertFalse(asset.isEmpty(),
            "no scalar matched in " + ASSET_FIGURES + " - the pattern has drifted");
        assertEquals(asset, constantsOf(HARNESS, CONTINUOUS),
            "the scalar roster this renderer answers from and the one the harness drives have parted; "
                + "a figure driven to one extent and answered at another renders as a defect in this "
                + "renderer and is not one");
    }

    @Test
    @DisplayName("the harness selects over the same one-hot this side answers")
    void theStateRostersAreOneRoster() throws IOException {
        List<String> asset = constantsOf(ASSET_STATES, STATE);
        assertFalse(asset.isEmpty(),
            "no state matched in " + ASSET_STATES + " - the pattern has drifted");
        assertEquals(asset, constantsOf(HARNESS, STATE),
            "the one-hot this renderer answers from and the one the harness selects over have "
                + "parted; a member driving one factor here and another there renders a subject in "
                + "two states at once");
    }

    @Test
    @DisplayName("every field either roster names is one the lookup answers for")
    void everyFieldIsAnswered() throws IOException {
        for (String line : constantsOf(ASSET_FIGURES, CONTINUOUS)) {
            String field = fieldOf(CONTINUOUS, FIELD_OF_CONTINUOUS, line);
            assertNotNull(IdleFigure.ofField(field),
                "the scalar roster declares '" + field + "' and its lookup does not answer for it");
        }
        for (String line : constantsOf(ASSET_STATES, STATE)) {
            String field = fieldOf(STATE, FIELD_OF_STATE, line);
            // A member that drives no field is still a member a caller may choose, and the empty
            // token is not a key - no render-state field is spelled that way.
            if (field.isEmpty()) continue;
            assertNotNull(IdleState.ofField(field),
                "the one-hot declares '" + field + "' and its lookup does not answer for it");
        }
    }

    @Test
    @DisplayName("the field a one-hot member names is named by no other member")
    void everyNamedFieldIsOneMembers() {
        Map<String, IdleState> byField = new LinkedHashMap<>();
        for (IdleState member : IdleState.values()) {
            if (member.field().isEmpty()) continue;
            IdleState held = byField.put(member.field(), member);
            assertNull(held,
                "'" + member.field() + "' is named by " + held + " and by " + member + ", so the "
                    + "lookup answers whichever was declared first and the other is unreachable");
        }
    }

    /** The render-state field one constant declaration names. */
    private static String fieldOf(Pattern shape, int capture, String line) {
        Matcher matched = shape.matcher(line);
        assertTrue(matched.matches(), line);
        return matched.group(capture);
    }

    /** Every constant of one shape in one source, indent dropped so the two sides compare. */
    private static List<String> constantsOf(Path source, Pattern shape) throws IOException {
        return Files.readAllLines(source)
            .stream()
            .map(String::strip)
            .filter(line -> shape.matcher(line).matches())
            .collect(Collectors.toList());
    }
}
