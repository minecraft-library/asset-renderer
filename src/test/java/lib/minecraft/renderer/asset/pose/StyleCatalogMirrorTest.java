package lib.minecraft.renderer.asset.pose;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * Holds the harness's animation contract equal to the SHIPPED style catalog, field for field.
 *
 * <p>The shipped {@code entity_models.json} styles member is what this renderer answers a frame
 * from, and the harness drives the same figures from declarations of its own in a separate build
 * that can name no type of this one. What goes wrong when they part is silent: the harness drives a
 * squid's tentacles to one extent while the catalog answers another, both render happily, and the
 * parity row reports the difference as a defect in this renderer. So the shipped file is read as the
 * reference side and the harness sources are regex-scraped as the pinned side, the same shape as the
 * operator roster's mirror and for the same reason.
 *
 * <p>What is held equal: every swept or cycling driver against the harness's scalar roster
 * (rest, extent and shape, as float bits); every grouped one-hot driver against the harness's state
 * roster (group and field, held at extent one); the member each group rests at and moves at against
 * the rows the {@code idle} and {@code stride} ids carry; the stride pair against the harness's
 * declared amplitude; the file's period against the harness's frame schedule and
 * {@link StyleCatalog#STRIP_FRAMES}; and the network id the generator folds against the one the
 * harness renders every subject at.
 */
class StyleCatalogMirrorTest {

    /** The shipped catalog every reader of this build answers styles from - the reference side. */
    private static final Path SHIPPED =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** The harness's roster declarations, which are its own build's copy of the same facts. */
    private static final Path HARNESS =
        Path.of("harness/src/client/java/lib/minecraft/refharness/IdleFigures.java");

    /** Where the harness declares the amplitude it drives a walking reference at. */
    private static final Path HARNESS_STRIDE =
        Path.of("harness/src/client/java/lib/minecraft/refharness/AnimationClock.java");

    /** Where the harness declares the frame schedule the shipped period must divide across. */
    private static final Path HARNESS_SCHEDULE =
        Path.of("harness/src/client/java/lib/minecraft/refharness/sweep/EntityAnimationSweep.java");

    /** The generator's declared rests, among them the network id it folds every pose against. */
    private static final Path TOOLING =
        Path.of("tooling/src/main/java/lib/minecraft/renderer/tooling/animation/PoseFlow.java");

    /** The stride amplitude, read as a value rather than as text - the two spell the literal apart. */
    private static final Pattern AMPLITUDE =
        Pattern.compile("WALK_AMPLITUDE = ([0-9]*\\.?[0-9]+)f;");

    /** The network id both sides render every subject at, spelled apart for the same reason. */
    private static final Pattern PINNED_ID =
        Pattern.compile("(?:PINNED_ENTITY_ID = |\"entityId\", )([0-9]+)f?");

    /** A scalar constant of the harness roster - the figure, its excursion and its shape. */
    private static final Pattern CONTINUOUS =
        Pattern.compile("^([A-Z][A-Z_]*)\\(\"(\\w+)\", ([-\\d.f]+), ([-\\d.f]+), Shape\\.(\\w+)\\)[,;]$");

    /** A one-hot member, which carries the selector it belongs to and the field it drives. */
    private static final Pattern STATE =
        Pattern.compile("^([A-Z][A-Z_]*)\\(Group\\.(\\w+), \"(\\w*)\"\\)[,;]$");

    /** One arm of a group's default - the group, and the member or the two a gait chooses between. */
    private static final Pattern SELECTED =
        Pattern.compile("^case (\\w+) -> (?:walking \\? )?(?:State\\.)?(\\w+)(?: : (?:State\\.)?(\\w+))?;$");

    /** One declared schedule constant of the harness sweep, read as a value. */
    private static final Pattern FRAME_COUNT = Pattern.compile("FRAME_COUNT = ([0-9]+);");

    private static final Pattern TICKS_PER_FRAME = Pattern.compile("TICKS_PER_FRAME = ([0-9]+);");

    private static final Pattern START_TICK = Pattern.compile("START_TICK = ([0-9]+);");

    @Test
    @DisplayName("the swept and cycling drivers are the harness scalar roster, excursion for excursion")
    void theSweptDriversAreTheScalarRoster() throws IOException {
        Map<String, String> harness = new TreeMap<>();
        for (String line : constantsOf(HARNESS, CONTINUOUS)) {
            Matcher scalar = CONTINUOUS.matcher(line);
            assertTrue(scalar.matches(), line);
            String held = harness.put(scalar.group(2),
                excursion(Float.parseFloat(scalar.group(3)), Float.parseFloat(scalar.group(4)),
                    scalar.group(5)));
            assertNull(held, "'" + scalar.group(2) + "' is declared twice in " + HARNESS);
        }
        assertFalse(harness.isEmpty(),
            "no scalar matched in " + HARNESS + " - the pattern has drifted");

        Map<String, String> shipped = new TreeMap<>();
        for (Row row : rows()) {
            for (JsonObject drive : row.drives()) {
                String wave = drive.get("wave").getAsString();
                if (!"sweep".equals(wave) && !"cycle".equals(wave)) continue;
                assertEquals("idle", row.id(),
                    row.entity() + " sweeps '" + fieldOf(drive) + "' on row '" + row.id()
                        + "' - a figure's home is the idle row, and a stride inherits it");
                String spelled = excursion(restOf(drive), extentOf(drive),
                    wave.toUpperCase(Locale.ROOT));
                String held = shipped.put(fieldOf(drive), spelled);
                assertTrue(held == null || held.equals(spelled),
                    "'" + fieldOf(drive) + "' is swept two ways in the shipped catalog: "
                        + held + " and " + spelled);
            }
        }

        assertEquals(harness, shipped,
            "the scalar excursions the shipped catalog answers and the ones the harness drives have "
                + "parted; a figure driven to one extent and answered at another renders as a defect "
                + "in this renderer and is not one");
    }

    @Test
    @DisplayName("the grouped drivers are the harness one-hot roster, member for member")
    void theGroupedDriversAreTheOneHotRoster() throws IOException {
        Set<String> harness = new TreeSet<>();
        for (String line : constantsOf(HARNESS, STATE)) {
            Matcher member = STATE.matcher(line);
            assertTrue(member.matches(), line);
            if (member.group(3).isEmpty()) continue;
            harness.add(member.group(2).toLowerCase(Locale.ROOT) + ":" + member.group(3));
        }
        assertFalse(harness.isEmpty(),
            "no one-hot matched in " + HARNESS + " - the pattern has drifted");

        Set<String> shipped = new TreeSet<>();
        for (Row row : rows())
            for (JsonObject drive : row.drives()) {
                JsonElement group = drive.get("group");
                if (group == null) continue;
                assertEquals("hold", drive.get("wave").getAsString(),
                    row.entity() + " row '" + row.id() + "' drives grouped '" + fieldOf(drive)
                        + "' on a wave that is not a hold - a one-hot stands at its extent");
                assertEquals(Float.floatToIntBits(1f), Float.floatToIntBits(extentOf(drive)),
                    row.entity() + " row '" + row.id() + "' holds '" + fieldOf(drive)
                        + "' at " + extentOf(drive) + " - a selected member answers one");
                shipped.add(group.getAsString() + ":" + fieldOf(drive));
            }

        assertEquals(harness, shipped,
            "the grouped drivers the shipped catalog carries and the one-hot roster the harness "
                + "selects over have parted; a member driving one factor here and another there "
                + "renders a subject in two states at once");
    }

    @Test
    @DisplayName("the idle and stride rows select the member each group defaults to, at both gaits")
    void theGroupDefaultsSelectTheIdleAndStrideRows() throws IOException {
        Map<String, String> memberField = new LinkedHashMap<>();
        for (String line : constantsOf(HARNESS, STATE)) {
            Matcher member = STATE.matcher(line);
            assertTrue(member.matches(), line);
            memberField.put(member.group(1), member.group(3));
        }
        Map<String, String[]> defaults = new LinkedHashMap<>();
        for (String line : constantsOf(HARNESS, SELECTED)) {
            Matcher arm = SELECTED.matcher(line);
            assertTrue(arm.matches(), line);
            String resting = arm.group(3) == null ? arm.group(2) : arm.group(3);
            defaults.put(arm.group(1).toLowerCase(Locale.ROOT),
                new String[]{resting, arm.group(2)});
        }
        assertFalse(defaults.isEmpty(),
            "no group default matched in " + HARNESS + " - the pattern has drifted");

        Map<String, Map<String, Set<String>>> idleByEntity = new TreeMap<>();
        Map<String, Map<String, Set<String>>> strideByEntity = new TreeMap<>();
        Set<String> carried = new TreeSet<>();
        for (Row row : rows())
            for (JsonObject drive : row.drives()) {
                JsonElement group = drive.get("group");
                if (group == null) continue;
                carried.add(row.entity() + ":" + group.getAsString());
                Map<String, Map<String, Set<String>>> side = switch (row.id()) {
                    case "idle" -> idleByEntity;
                    case "stride" -> strideByEntity;
                    default -> null;
                };
                if (side != null)
                    side.computeIfAbsent(row.entity(), key -> new TreeMap<>())
                        .computeIfAbsent(group.getAsString(), key -> new TreeSet<>())
                        .add(fieldOf(drive));
            }
        assertFalse(carried.isEmpty(), "no grouped driver read from " + SHIPPED);

        for (String pair : carried) {
            // Split on the LAST separator - the entity id carries the namespace one.
            String entity = pair.substring(0, pair.lastIndexOf(':'));
            String group = pair.substring(pair.lastIndexOf(':') + 1);
            String[] arms = defaults.get(group);
            assertNotNull(arms,
                entity + " carries group '" + group + "', which no harness selector declares");
            String restingField = memberField.get(arms[0]);
            String walkingField = memberField.get(arms[1]);
            assertNotNull(restingField, arms[0] + " is selected and not a member in " + HARNESS);
            assertNotNull(walkingField, arms[1] + " is selected and not a member in " + HARNESS);

            Set<String> idle = idleByEntity
                .getOrDefault(entity, Map.of()).getOrDefault(group, Set.of());
            assertEquals(restingField.isEmpty() ? Set.of() : Set.of(restingField), idle,
                entity + " group '" + group + "': the member the idle row drives and the one the "
                    + "harness rests the group at have parted");

            Set<String> stride = strideByEntity
                .getOrDefault(entity, Map.of()).getOrDefault(group, Set.of());
            assertEquals(arms[0].equals(arms[1]) ? Set.of() : Set.of(walkingField), stride,
                entity + " group '" + group + "': the member the stride row replaces toward and the "
                    + "one the harness drives a walking subject to have parted - a walking render "
                    + "that came back with the resting arm swings the legs of a subject vanilla "
                    + "draws in a clip");
        }
    }

    @Test
    @DisplayName("the stride pair and elapsed age are driven at the amplitude the harness declares")
    void theStridePairIsDrivenAtTheHarnessAmplitude() throws IOException {
        float amplitude = amplitudeOf(HARNESS_STRIDE);
        List<Row> rows = rows();
        for (Row row : rows)
            for (JsonObject drive : row.drives()) {
                String at = row.entity() + " row '" + row.id() + "'";
                switch (fieldOf(drive)) {
                    case "walkAnimationSpeed" -> {
                        assertEquals("hold", drive.get("wave").getAsString(),
                            at + " does not hold the stride amplitude");
                        assertEquals(Float.floatToIntBits(amplitude),
                            Float.floatToIntBits(extentOf(drive)),
                            at + " walks at " + extentOf(drive) + " where the harness drives "
                                + amplitude + " - the amplitude is the phase as well as the speed, "
                                + "so the two sides stand at different points of different strides");
                    }
                    case "walkAnimationPos" -> {
                        assertEquals("ramp", drive.get("wave").getAsString(),
                            at + " does not ramp the stride phase");
                        assertEquals(Float.floatToIntBits(amplitude),
                            Float.floatToIntBits(extentOf(drive)),
                            at + " advances the phase by " + extentOf(drive)
                                + " a tick where the harness advances it by the amplitude");
                    }
                    case "ageInTicks" -> {
                        assertEquals("ramp", drive.get("wave").getAsString(),
                            at + " does not ramp elapsed age");
                        assertEquals(Float.floatToIntBits(1f), Float.floatToIntBits(extentOf(drive)),
                            at + " climbs age at " + extentOf(drive)
                                + " a tick where the harness stamps the tick itself");
                    }
                    default -> { }
                }
            }

        Map<String, Map<String, Row>> byEntity = new TreeMap<>();
        for (Row row : rows)
            byEntity.computeIfAbsent(row.entity(), key -> new LinkedHashMap<>())
                .putIfAbsent(row.id(), row);
        for (Row row : rows) {
            Set<String> composed = composedFields(row, byEntity.get(row.entity()));
            if ("stride".equals(row.id()))
                assertTrue(composed.containsAll(Set.of("walkAnimationSpeed", "walkAnimationPos")),
                    row.entity() + " stride row composes " + composed
                        + " and a stride is carried on the walk pair");
            if ("idle".equals(row.id()))
                assertTrue(composed.contains("ageInTicks"),
                    row.entity() + " idle row composes " + composed
                        + " and elapsed age is what separates an idle frame from its neighbour");
        }
    }

    @Test
    @DisplayName("the shipped period is the harness frame schedule, and the strip divides it")
    void theShippedPeriodIsTheHarnessSchedule() throws IOException {
        int frames = (int) valueOf(HARNESS_SCHEDULE, FRAME_COUNT);
        int ticksPerFrame = (int) valueOf(HARNESS_SCHEDULE, TICKS_PER_FRAME);
        int start = (int) valueOf(HARNESS_SCHEDULE, START_TICK);
        int period = shipped().get("period_ticks").getAsInt();

        assertEquals(frames * ticksPerFrame, period,
            "the ticks one shipped excursion spans and the span the harness strip covers have "
                + "parted, so a strip either stops short of the excursion or runs past its own start");
        assertEquals(StyleCatalog.STRIP_FRAMES, frames,
            "the frames one shipped strip samples and the frames the harness renders have parted");
        assertEquals(ticksPerFrame, period / StyleCatalog.STRIP_FRAMES,
            "the per-frame tick step the catalog derives and the harness's cadence have parted");
        assertEquals(0, start,
            "the harness strip starts at a tick other than zero, and tick zero is what every "
                + "driver rests at - a later seat poses frame 0 away from the authored rest");
    }

    @Test
    @DisplayName("the id the harness renders every subject at is the one the generator folded")
    void thePinnedEntityIdIsOneId() throws IOException {
        assertEquals(valueOf(TOOLING, PINNED_ID), valueOf(HARNESS, PINNED_ID),
            "the network id the generator folded a pose against and the one the harness renders at "
                + "have parted. It picks a FREQUENCY - a witch's nose turns by "
                + "sin(age * 0.01 * (id % 10)) - so two values are two animations, and the shipped "
                + "table carries one of them baked while the reference set shows the other");
    }

    // ------------------------------------------------------------------------------------

    /** One shipped style row, flattened to what the pins here read of it. */
    private record Row(String entity, String id, String base, List<JsonObject> drives) {}

    /** Every style row of the shipped file, in file order. */
    private static List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        JsonObject models = shipped().getAsJsonObject("models");
        for (Map.Entry<String, JsonElement> entity : models.entrySet()) {
            JsonElement styles = entity.getValue().getAsJsonObject().get("styles");
            if (styles == null) continue;
            for (JsonElement row : styles.getAsJsonArray()) {
                JsonObject held = row.getAsJsonObject();
                List<JsonObject> drives = new ArrayList<>();
                JsonArray spelled = held.getAsJsonArray("drives");
                if (spelled != null)
                    for (JsonElement drive : spelled)
                        drives.add(drive.getAsJsonObject());
                out.add(new Row(entity.getKey(), held.get("id").getAsString(),
                    held.has("base") ? held.get("base").getAsString() : null, drives));
            }
        }
        assertFalse(out.isEmpty(), "no style row read from " + SHIPPED);
        return out;
    }

    /** The fields one row composes - its own drives, flat against its base chain. */
    private static Set<String> composedFields(Row row, Map<String, Row> byId) {
        Set<String> out = new TreeSet<>();
        Set<String> walked = new LinkedHashSet<>();
        for (Row held = row; held != null && walked.add(held.id());
             held = held.base() == null ? null : byId.get(held.base()))
            for (JsonObject drive : held.drives()) out.add(fieldOf(drive));
        return out;
    }

    private static JsonObject shipped() throws IOException {
        return JsonParser
            .parseString(Files.readString(SHIPPED, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    /** One excursion spelled for comparison, float identity carried by the decimal spelling. */
    private static String excursion(float rest, float extent, String shape) {
        return rest + ".." + extent + "@" + shape;
    }

    private static String fieldOf(JsonObject drive) {
        return drive.get("field").getAsString();
    }

    /** A drive's rest, defaulted the way the loader defaults it. */
    private static float restOf(JsonObject drive) {
        JsonElement rest = drive.get("rest");
        return rest == null ? 0f : rest.getAsFloat();
    }

    /** A drive's extent, defaulted the way the loader defaults it. */
    private static float extentOf(JsonObject drive) {
        JsonElement extent = drive.get("extent");
        return extent == null ? 1f : extent.getAsFloat();
    }

    /** One declared value, read as a number - the builds spell their literals apart. */
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

    /** Every constant of one shape in one source, indent dropped so shapes match line-anchored. */
    private static List<String> constantsOf(Path source, Pattern shape) throws IOException {
        return Files.readAllLines(source)
            .stream()
            .map(String::strip)
            .filter(line -> shape.matcher(line).matches())
            .collect(Collectors.toList());
    }

}
