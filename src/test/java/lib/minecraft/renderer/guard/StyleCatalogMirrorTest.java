package lib.minecraft.renderer.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the harness's animation contract equal to the SHIPPED style catalog.
 *
 * <p>The shipped {@code entity_models.json} styles member is what this renderer answers a frame
 * from, and the harness renders the same subjects in a separate build that can name no type of this
 * one. What goes wrong when they part is silent: both render happily, and the parity row reports the
 * difference as a defect in this renderer. So the shipped file is read as the reference side and the
 * harness sources are regex-scraped as the pinned side, the same shape as the operator roster's
 * mirror and for the same reason.
 *
 * <p>What is held: every grouped one-hot driver against the harness's state roster (group and field,
 * held at extent one); the member each group rests at and moves at against the rows the {@code idle}
 * and {@code stride} ids carry; every clock the catalog drives against the harness mixin that writes
 * it; the stride pair's shape, the rule a pace obeys and the ceiling above it; the file's figure
 * period against {@link StyleCatalog#FIGURE_PERIOD_TICKS}; and the network id the generator folds
 * against the one the harness renders every subject at. A clock's values and a pace reach the harness
 * through the schedule roster, so what is held for each is a consumer or a rule, never a number.
 */
class StyleCatalogMirrorTest {

    /** The shipped catalog every reader of this build answers styles from - the reference side. */
    private static final Path SHIPPED =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** The harness's roster declarations, which are its own build's copy of the same facts. */
    private static final Path HARNESS =
        Path.of("harness/src/client/java/lib/minecraft/refharness/IdleFigures.java");

    /** The harness's mixins, where every clock the shipped catalog drives is written. */
    private static final Path MIXINS =
        Path.of("harness/src/client/java/lib/minecraft/refharness/mixin");

    /** The generator's declared rests, among them the network id it folds every pose against. */
    private static final Path TOOLING =
        Path.of("tooling/src/main/java/lib/minecraft/renderer/tooling/animation/StyleRoster.java");

    /** The network id both sides render every subject at, spelled apart for the same reason. */
    private static final Pattern PINNED_ID =
        Pattern.compile("(?:PINNED_ENTITY_ID = |\"entityId\", )([0-9]+)f?");

    /** A one-hot member, which carries the selector it belongs to and the field it drives. */
    private static final Pattern STATE =
        Pattern.compile("^([A-Z][A-Z_]*)\\(Group\\.(\\w+), \"(\\w*)\"\\)[,;]$");

    /** One arm of a group's default - the group, and the member or the two a gait chooses between. */
    private static final Pattern SELECTED =
        Pattern.compile("^case (\\w+) -> (?:walking \\? )?(?:State\\.)?(\\w+)(?: : (?:State\\.)?(\\w+))?;$");

    /** One read of a clock the armed schedule carries, naming the clock. */
    private static final Pattern CLOCK_CALL = Pattern.compile("AnimationClock\\.clock\\(\"(\\w+)\"\\)");

    /** One clock written into a render-state field - the field written, then the clock read. */
    private static final Pattern CLOCK_WRITE =
        Pattern.compile("state\\.(\\w+) = AnimationClock\\.clock\\(\"(\\w+)\"\\);");

    /** One question whether the armed schedule carries a clock, naming the clock. */
    private static final Pattern DRIVES_CALL = Pattern.compile("AnimationClock\\.drives\\(\"(\\w+)\"\\)");

    /** The fields with homes of their own - the age the harness stamps, and the pair a pace carries. */
    private static final Set<String> STRIDE_TRIO = Set.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos");

    /** The waves whose value at a frame reads no window, so the roster carries one value a frame. */
    private static final Set<String> CARRIED_WAVES = Set.of("hold", "ramp", "stroke", "trapezoid");

    @Test
    @DisplayName("every clock the catalog drives is written by a harness mixin, and every write names one")
    void everyClockHasAHarnessConsumer() throws IOException {
        Set<String> shipped = new TreeSet<>();
        for (Row row : rows()) {
            for (JsonObject drive : row.drives()) {
                if (drive.has("group") || STRIDE_TRIO.contains(fieldOf(drive))) continue;
                String wave = drive.get("wave").getAsString();
                assertTrue(CARRIED_WAVES.contains(wave),
                    row.entity() + " row '" + row.id() + "' drives clock '" + fieldOf(drive) + "' on a " + wave
                        + " - the roster carries one value a frame for a wave that reads no window, and a "
                        + "sweep or a cycle reads one");
                shipped.add(fieldOf(drive));
            }
        }

        Set<String> written = new TreeSet<>();
        Set<String> asked = new TreeSet<>();
        int calls = 0;
        int writes = 0;
        for (String line : codeLinesOf(MIXINS)) {
            Matcher call = CLOCK_CALL.matcher(line);
            while (call.find()) {
                written.add(call.group(1));
                calls++;
            }
            Matcher write = CLOCK_WRITE.matcher(line);
            while (write.find())
                if (write.group(1).equals(write.group(2))) writes++;
            Matcher drives = DRIVES_CALL.matcher(line);
            while (drives.find()) asked.add(drives.group(1));
        }

        assertFalse(shipped.isEmpty(), "no clock read from " + SHIPPED + " - the catalog drives none");
        assertFalse(written.isEmpty(),
            "no AnimationClock.clock read matched under " + MIXINS + " - the pattern has drifted");
        assertEquals(calls, writes,
            "a clock read under " + MIXINS + " is not written as state.<field> = AnimationClock.clock(\"<field>\") "
                + "with the field it names - a value written into another clock's field");
        assertEquals(Set.of(), difference(shipped, written),
            "driven by the shipped catalog and written by no harness mixin: the reference would hold "
                + "vanilla's never-ticked value while this renderer drives it");
        assertEquals(Set.of(), difference(written, shipped),
            "written by a harness mixin and driven by no shipped row: the harness would refuse every frame "
                + "of that subject");
        assertEquals(Set.of(), difference(asked, written),
            "asked after with AnimationClock.drives and written by no harness mixin");
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
    @DisplayName("the stride pair is a pace where the row reads the walk clock, under its ceiling, and inert where it does not")
    void theStridePairIsAPace() throws IOException {
        List<Row> rows = rows();
        Set<String> withBabies = babyModels();
        Map<String, Map<String, List<Row>>> byEntity = new TreeMap<>();
        for (Row row : rows)
            byEntity.computeIfAbsent(row.entity(), key -> new LinkedHashMap<>())
                .computeIfAbsent(row.id(), key -> new ArrayList<>())
                .add(row);

        int paced = 0;
        int inert = 0;
        Set<String> ceiledSides = new TreeSet<>();
        for (Row row : rows) {
            String at = row.entity() + " row '" + row.id() + "'" + row.age().map(age -> " (" + age + ")").orElse("");
            for (JsonObject drive : row.drives()) {
                switch (fieldOf(drive)) {
                    case "walkAnimationSpeed" -> assertEquals("hold", drive.get("wave").getAsString(),
                        at + " does not hold the walk speed");
                    case "walkAnimationPos" -> assertEquals("ramp", drive.get("wave").getAsString(),
                        at + " does not ramp the walk clock");
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

            boolean reads = row.sources().contains("stride");
            boolean ceiled = "stride".equals(row.id()) && reads;
            if (!ceiled) {
                assertTrue(row.ceiling().isEmpty(),
                    at + " meets a ceiling its catalog records, which only a stride walking at its own pace meets");
            }

            Map<String, JsonObject> composed = composedDrives(row, byEntity.get(row.entity()));
            JsonObject speedDrive = composed.get("walkAnimationSpeed");
            JsonObject rateDrive = composed.get("walkAnimationPos");
            if (speedDrive == null || rateDrive == null) continue;
            float speed = extentOf(speedDrive);
            float rate = extentOf(rateDrive);

            if (!reads) {
                inert++;
                assertEquals(Float.floatToIntBits(1f), Float.floatToIntBits(speed),
                    at + " reads no walk clock and holds the walk speed at " + speed
                        + " - a pose that reads no walk clock carries the pair at one, untouched by a pace");
                assertEquals(Float.floatToIntBits(1f), Float.floatToIntBits(rate),
                    at + " reads no walk clock and ramps the walk clock at " + rate
                        + " - a pose that reads no walk clock carries the pair at one, untouched by a pace");
                continue;
            }

            paced++;
            assertTrue(speed > 0f && speed <= 1f,
                at + " walks at " + speed + " - a pace is above zero, and vanilla's render-state walk speed "
                    + "never exceeds one");
            boolean baby = row.age().filter("baby"::equals).isPresent();
            float expected = baby ? 3f * speed : speed;
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(rate),
                at + " ramps the walk clock at " + rate + " where its walk speed " + speed + " asks "
                    + expected + (baby
                        ? " - vanilla runs a baby's walk clock three times its pace"
                        : " - the swing and an adult's walk clock are one number"));
            if (withBabies.contains(row.entity())) {
                assertTrue(row.age().isPresent(),
                    at + " paces a model with a baby age and names no age - both ages would walk the baby's "
                        + "clock at the adult's rate");
            }

            if (ceiled) {
                ceiledSides.add(row.entity() + " " + row.age().orElse("adult"));
                JsonObject ceiling = row.ceiling().orElse(null);
                assertNotNull(ceiling, at + " walks at its own pace and its catalog records no ceiling at its age");
                assertTrue(ceiling.get("speed").getAsFloat() >= speed,
                    at + " ceiling speed " + ceiling.get("speed") + " sits under its pace " + speed);
                assertTrue(ceiling.get("rate").getAsFloat() >= rate,
                    at + " ceiling rate " + ceiling.get("rate") + " sits under its walk-clock rate " + rate);
            }
        }
        assertTrue(paced > 0, "no row of " + SHIPPED + " walks at a pace");
        assertTrue(inert > 0, "no row of " + SHIPPED + " carries the walk pair inert");
        Set<String> recorded = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entity : shipped().getAsJsonObject("models").entrySet()) {
            JsonObject ceilings = entity.getValue().getAsJsonObject().getAsJsonObject("ceilings");
            if (ceilings != null) ceilings.keySet().forEach(age -> recorded.add(entity.getKey() + " " + age));
        }
        assertEquals(ceiledSides, recorded,
            "a catalog records a ceiling at exactly the ages its stride walks at its own pace");

        for (Row row : rows) {
            Set<String> composed = composedDrives(row, byEntity.get(row.entity())).keySet();
            if ("stride".equals(row.id()))
                assertTrue(composed.containsAll(Set.of("walkAnimationSpeed", "walkAnimationPos")),
                    row.entity() + " stride row composes " + new TreeSet<>(composed)
                        + " and a stride is carried on the walk pair");
            if ("idle".equals(row.id()))
                assertTrue(composed.contains("ageInTicks"),
                    row.entity() + " idle row composes " + new TreeSet<>(composed)
                        + " and elapsed age is what separates an idle frame from its neighbour");
        }
    }

    @Test
    @DisplayName("the shipped figure period is the catalog's one window")
    void theFigurePeriodIsOneWindow() throws IOException {
        assertEquals(StyleCatalog.FIGURE_PERIOD_TICKS, shipped().get("period_ticks").getAsInt(),
            "the period the shipped file declares and the figure period a catalog it never mentions "
                + "frames at have parted");
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

    /**
     * One shipped style row, flattened to what the pins here read of it.
     *
     * @param entity the model key the row belongs to
     * @param id the row's id
     * @param base the id the row composes over, empty where it composes over none
     * @param age the age the row applies to, empty where it applies to both
     * @param sources the mechanisms the row's own inventory names, gates dropped
     * @param ceiling the pace ceiling the row's catalog records at the age a stride row walks at, empty
     *     for every other row and where the catalog records none there
     * @param drives the row's own drives, in file order
     */
    private record Row(String entity, String id, Optional<String> base, Optional<String> age, List<String> sources,
                       Optional<JsonObject> ceiling, List<JsonObject> drives) {}

    /** Every style row of the shipped file, in file order. */
    private static List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        JsonObject models = shipped().getAsJsonObject("models");
        for (Map.Entry<String, JsonElement> entity : models.entrySet()) {
            JsonElement styles = entity.getValue().getAsJsonObject().get("styles");
            if (styles == null) continue;
            JsonObject ceilings = entity.getValue().getAsJsonObject().getAsJsonObject("ceilings");
            for (JsonElement row : styles.getAsJsonArray()) {
                JsonObject held = row.getAsJsonObject();
                assertFalse(held.has("ceiling"), entity.getKey() + " row '" + held.get("id").getAsString()
                    + "' carries a ceiling of its own, which its catalog's ceilings hold");
                Optional<String> age = held.has("age") ? Optional.of(held.get("age").getAsString()) : Optional.empty();
                Optional<JsonObject> ceiling = ceilings == null || !"stride".equals(held.get("id").getAsString())
                    ? Optional.empty()
                    : Optional.ofNullable(ceilings.getAsJsonObject(age.orElse("adult")));
                List<JsonObject> drives = new ArrayList<>();
                JsonArray spelled = held.getAsJsonArray("drives");
                if (spelled != null)
                    for (JsonElement drive : spelled)
                        drives.add(drive.getAsJsonObject());
                List<String> sources = new ArrayList<>();
                JsonArray inventory = held.getAsJsonArray("sources");
                if (inventory != null) {
                    for (JsonElement source : inventory) {
                        sources.add(source.isJsonPrimitive()
                            ? source.getAsString()
                            : source.getAsJsonObject().get("source").getAsString());
                    }
                }
                out.add(new Row(entity.getKey(), held.get("id").getAsString(),
                    held.has("base") ? Optional.of(held.get("base").getAsString()) : Optional.empty(),
                    age, List.copyOf(sources), ceiling, drives));
            }
        }
        assertFalse(out.isEmpty(), "no style row read from " + SHIPPED);
        return out;
    }

    /** Every model key whose age axis offers a baby. */
    private static Set<String> babyModels() throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entity : shipped().getAsJsonObject("models").entrySet()) {
            JsonObject axes = entity.getValue().getAsJsonObject().getAsJsonObject("axes");
            if (axes == null || !axes.has("age")) continue;
            JsonObject options = axes.getAsJsonObject("age").getAsJsonObject("options");
            if (options != null && options.has("baby")) out.add(entity.getKey());
        }
        assertFalse(out.isEmpty(), "no model of " + SHIPPED + " offers a baby age");
        return out;
    }

    /**
     * The drives one row composes - its base chain's, each overwritten by the row's own on the same
     * field, as the loader composes them. A base resolves to the row of its id at the age of the row
     * naming it where one ships, otherwise to its ageless row, otherwise to its first row.
     */
    private static Map<String, JsonObject> composedDrives(Row row, Map<String, List<Row>> byId) {
        List<Row> chain = new ArrayList<>();
        Set<String> walked = new LinkedHashSet<>();
        for (Row held = row; held != null && walked.add(held.id()); held = baseOf(held, byId).orElse(null))
            chain.add(held);

        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (Row held : chain.reversed())
            for (JsonObject drive : held.drives()) out.put(fieldOf(drive), drive);
        return out;
    }

    /**
     * The row one row's base resolves to, as the loader resolves it - among the rows of the id the base
     * names, the one at the age of the row naming it where one ships, otherwise the ageless row,
     * otherwise the first.
     *
     * @param row the row naming the base
     * @param byId the rows of each id of the row's entity, in file order
     * @return the base row, empty where the row names no base or its id carries no row
     */
    private static Optional<Row> baseOf(Row row, Map<String, List<Row>> byId) {
        List<Row> over = row.base().map(byId::get).orElse(List.of());
        if (over.isEmpty()) return Optional.empty();
        return over.stream()
            .filter(candidate -> candidate.age().equals(row.age()))
            .findFirst()
            .or(() -> over.stream().filter(candidate -> candidate.age().isEmpty()).findFirst())
            .or(() -> Optional.of(over.getFirst()));
    }

    /** The members of the first set the second lacks, sorted. */
    private static Set<String> difference(Set<String> first, Set<String> second) {
        Set<String> out = new TreeSet<>(first);
        out.removeAll(second);
        return out;
    }

    private static JsonObject shipped() throws IOException {
        return JsonParser
            .parseString(Files.readString(SHIPPED, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static String fieldOf(JsonObject drive) {
        return drive.get("field").getAsString();
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

    /** Every constant of one shape in one source, indent dropped so shapes match line-anchored. */
    private static List<String> constantsOf(Path source, Pattern shape) throws IOException {
        return Files.readAllLines(source)
            .stream()
            .map(String::strip)
            .filter(line -> shape.matcher(line).matches())
            .collect(Collectors.toList());
    }

    /**
     * Every code line of the {@code .java} files under one directory, files walked sorted - a line
     * whose stripped text opens a comment or continues a javadoc is not code, so a doc may cite a call.
     */
    private static List<String> codeLinesOf(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory), directory + " is expected beside this build");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no source under " + directory);
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                String stripped = line.strip();
                if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) continue;
                out.add(stripped);
            }
        }
        return out;
    }

}
