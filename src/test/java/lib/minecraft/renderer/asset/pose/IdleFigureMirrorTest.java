package lib.minecraft.renderer.asset.pose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the three copies of the idle roster to one another - this renderer's, the harness's, and the
 * generator's.
 *
 * <p>All three are separate builds, so none can name another's type and each declares its own copy.
 * What goes wrong when they part is silent rather than loud, and it is a different silence per pair.
 * The harness drives a squid's tentacles to one extent while this side answers another, both render
 * happily, and the parity row reports the difference as a defect in this renderer. The generator
 * FOLDS a field no roster names, so a body that branches on one ships the arm a never-ticked subject
 * takes and a caller's selection reaches nothing - the shape that cost a rabbit's head 55.73 of
 * delta with its canvas 18 px wide. Nothing else in any of the three builds would catch either,
 * because each side is internally consistent.
 *
 * <p>So the constants are compared as text, which is why the two rosters are written in one
 * declaration per figure with identical spelling. The same shape as the operator roster's mirror,
 * and for the same reason.
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

    /** The generator's, a third build again, whose copy decides what the shipped table keeps symbolic. */
    private static final Path TOOLING =
        Path.of("tooling/src/main/java/lib/minecraft/renderer/tooling/animation/PoseFlow.java");

    /** Where this renderer declares the amplitude its own gait preset walks at. */
    private static final Path ASSET_STRIDE =
        Path.of("src/main/java/lib/minecraft/renderer/engine/kit/PoseKit.java");

    /** Where the harness declares the amplitude it drives a walking reference at. */
    private static final Path HARNESS_STRIDE =
        Path.of("harness/src/client/java/lib/minecraft/refharness/AnimationClock.java");

    /** Where the harness declares the network id it renders every subject at. */
    private static final Path HARNESS_STRIDE_ID =
        Path.of("harness/src/client/java/lib/minecraft/refharness/IdleFigures.java");

    /** The stride amplitude, read as a value rather than as text - the two spell the literal apart. */
    private static final Pattern AMPLITUDE =
        Pattern.compile("WALK_AMPLITUDE = ([0-9]*\\.?[0-9]+)f;");

    /** The network id both sides render every subject at, spelled apart for the same reason. */
    private static final Pattern PINNED_ID =
        Pattern.compile("(?:PINNED_ENTITY_ID = |\"entityId\", )([0-9]+)f?");

    /** The generator's set, whose members are string literals across however many lines it wraps to. */
    private static final Pattern DRIVEN =
        Pattern.compile("Set<String> DRIVEN = Set\\.of\\(([^;]*)\\);", Pattern.DOTALL);

    /** One quoted member of it. */
    private static final Pattern QUOTED = Pattern.compile("\"(\\w+)\"");

    /**
     * The three figures the frame answers off the tick itself rather than off either roster.
     *
     * <p>Named here because they are the part of the driven set that does not grow: elapsed age, and
     * the stride pair a gait carries. {@code PoseKit.frameAt} answers them directly and no roster
     * declares them, so the equality below is asserted over what is left.
     */
    private static final List<String> FRAME_FIGURES =
        List.of("ageInTicks", "walkAnimationPos", "walkAnimationSpeed");

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
            // A member that drives no field is still a member a caller may choose, and it is the
            // lookup's job to leave the token it carries unreachable - asserted below.
            if (field.isEmpty()) continue;
            assertNotNull(IdleState.ofField(field),
                "the one-hot declares '" + field + "' and its lookup does not answer for it");
        }
    }

    @Test
    @DisplayName("the token a resting member carries reaches no member at all")
    void theRestingTokenIsNotAKey() {
        assertNull(IdleState.ofField(""),
            "four members carry the empty token, so a lookup that scans the whole roster answers "
                + "whichever was declared first - a member no caller selected, standing in for a "
                + "render-state field nothing spells");
    }

    @Test
    @DisplayName("the generator keeps symbolic exactly the fields the two rosters answer")
    void theGeneratorDrivesTheSameRoster() throws IOException {
        Set<String> declared = new TreeSet<>();
        for (String line : constantsOf(ASSET_FIGURES, CONTINUOUS))
            declared.add(fieldOf(CONTINUOUS, FIELD_OF_CONTINUOUS, line));
        for (String line : constantsOf(ASSET_STATES, STATE)) {
            String field = fieldOf(STATE, FIELD_OF_STATE, line);
            if (!field.isEmpty()) declared.add(field);
        }

        Set<String> driven = new TreeSet<>(drivenOf(TOOLING));
        assertTrue(driven.removeAll(FRAME_FIGURES),
            "the generator's driven set names none of " + FRAME_FIGURES + " - it has drifted");

        assertEquals(declared, driven,
            "the fields the two rosters answer and the fields the generator keeps symbolic have "
                + "parted. A field a roster names and the generator folds ships the arm a "
                + "never-ticked subject takes, whatever a caller selects - which cost the rabbit's "
                + "head 55.73 of delta. A field the generator keeps symbolic and no roster names "
                + "reads zero at every tick, which is the subject nothing has ticked");
    }

    @Test
    @DisplayName("the stride the harness drives is the amplitude this renderer poses at")
    void theStrideAmplitudeIsOneAmplitude() throws IOException {
        assertEquals(amplitudeOf(ASSET_STRIDE), amplitudeOf(HARNESS_STRIDE),
            "the amplitude this renderer's gait preset walks at and the one the harness drives a "
                + "walking reference at have parted. It is the phase as well as the speed - vanilla "
                + "accumulates the phase BY the amplitude once a tick - so a value that moved on one "
                + "side only puts the two sides at different points of different strides and reports "
                + "the whole corpus as a defect in this renderer");
    }

    @Test
    @DisplayName("the id the harness renders every subject at is the one the generator folded")
    void thePinnedEntityIdIsOneId() throws IOException {
        assertEquals(valueOf(TOOLING, PINNED_ID), valueOf(HARNESS_STRIDE_ID, PINNED_ID),
            "the network id the generator folded a pose against and the one the harness renders at "
                + "have parted. It picks a FREQUENCY - a witch's nose turns by "
                + "sin(age * 0.01 * (id % 10)) - so two values are two animations, and the shipped "
                + "table carries one of them baked while the reference set shows the other");
    }

    /** One declared value, read as a number - the two builds spell the literal apart. */
    private static float valueOf(Path source, Pattern shape) throws IOException {
        Matcher declared = shape.matcher(Files.readString(source));
        assertTrue(declared.find(), "no value matched in " + source + " - the pattern has drifted");
        return Float.parseFloat(declared.group(1));
    }

    /** The stride amplitude one source declares, as a value. */
    private static float amplitudeOf(Path source) throws IOException {
        Matcher declared = AMPLITUDE.matcher(Files.readString(source));
        assertTrue(declared.find(),
            "no stride amplitude matched in " + source + " - the pattern has drifted");
        return Float.parseFloat(declared.group(1));
    }

    /** Every member of the generator's driven set, read as text - it is another build's private. */
    private static Set<String> drivenOf(Path source) throws IOException {
        Matcher declaration = DRIVEN.matcher(Files.readString(source));
        assertTrue(declaration.find(),
            "no driven set matched in " + source + " - the pattern has drifted");
        Set<String> members = new TreeSet<>();
        Matcher member = QUOTED.matcher(declaration.group(1));
        while (member.find()) members.add(member.group(1));
        assertFalse(members.isEmpty(), "the generator's driven set read as empty");
        return members;
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
