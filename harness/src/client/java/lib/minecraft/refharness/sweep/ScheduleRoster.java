package lib.minecraft.refharness.sweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lib.minecraft.refharness.Gait;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.SharedConstants;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The schedule every posed subject is rendered at, read from the roster the asset side writes beside
 * the reference tree.
 *
 * <p>The asset side resolves each entity's strip, walk and clocks at both gaits through the code its own
 * render runs, and writes them to {@code entity-schedule/roster.json}, a sibling of the output root.
 * This reads that one file, so the harness renders a subject at the schedule the asset side draws it at
 * and holds no schedule of its own. Every posed reference run on the asset side writes the roster
 * before it boots the client.
 *
 * <p><b>Absent, malformed or stale is a refusal, never a default.</b> The whole roster is read and
 * every entry validated when it is loaded, which is at boot, ahead of every sweep; a subject the roster
 * does not name is refused when the posed sweep enumerates it. Either way the run exits non-zero rather
 * than writing a reference at a schedule nobody resolved.
 *
 * <p>The roster names the client its schedules were resolved for, compared by major.minor: the asset
 * side reads a client jar whose version is the release line, while the harness runs a patch of it,
 * and the reference tree is named by the same two parts.
 */
@Parity(claim = "harness-animation-sweep", mode = Mode.DEMOTE, subject = Subject.ENTITY)
public final class ScheduleRoster {

    /** The format this reader reads, and the one the asset side writes. */
    private static final int FORMAT = 1;

    /** The fields a schedule carries members of their own for, so no clock may be named for one. */
    private static final Set<String> STRIDE_TRIO = Set.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos");

    /** Every entry, keyed by entity id, age key and gait key. */
    private final Map<Key, Entry> entries;

    private ScheduleRoster(Map<Key, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    /**
     * One entry's place in the roster.
     *
     * @param entity the entity id, as the registry spells it
     * @param age the age key, {@code adult} or {@code baby}
     * @param gait the gait key, {@code idle} or {@code walk}
     */
    private record Key(String entity, String age, String gait) {}

    /**
     * One entity, age and gait's schedules.
     *
     * @param schedule the schedule of the entity's default appearance at that age
     * @param coats the schedule of each coat that resolves another one, keyed by coat name
     * @param traits the schedule of each posed trait subject, keyed by its trait key, sorted
     */
    private record Entry(SubjectSchedule schedule, Map<String, SubjectSchedule> coats,
                         SortedMap<String, SubjectSchedule> traits) {}

    /**
     * Reads and validates the roster written beside an output root.
     *
     * @param outputRoot the reference tree root the run writes below
     * @return the roster
     * @throws IllegalStateException if the roster is absent or does not parse, is of another format, was
     *     resolved for another client, or carries an entry a render cannot be armed at
     */
    public static ScheduleRoster load(Path outputRoot) {
        Path file = outputRoot.resolveSibling("entity-schedule").resolve("roster.json");
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(String.format(
                "No entity schedule roster at '%s' - run ./gradlew entityScheduleRoster from asset-renderer, "
                    + "which every posed reference run depends on", file.toAbsolutePath().normalize()), ex);
        }
        JsonObject roster;
        try {
            roster = JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalStateException(String.format(
                "The entity schedule roster at '%s' does not parse", file.toAbsolutePath().normalize()), ex);
        }

        JsonElement format = roster.get("format");
        if (format == null || !format.isJsonPrimitive() || !String.valueOf(FORMAT).equals(format.getAsString())) {
            throw new IllegalStateException(String.format(
                "The entity schedule roster is format '%s', and this harness reads format 1", format));
        }

        String resolvedFor = member(roster, "source_version", "the roster").getAsString();
        String client = SharedConstants.getCurrentVersion().id();
        if (!majorMinor(resolvedFor).equals(majorMinor(client))) {
            throw new IllegalStateException(String.format(
                "The entity schedule roster was resolved for '%s', and this client is '%s'", resolvedFor, client));
        }

        Map<Key, Entry> entries = new HashMap<>();
        for (Map.Entry<String, JsonElement> entity : object(roster, "subjects", "the roster").entrySet()) {
            JsonObject ages = asObject(entity.getValue(), entity.getKey());
            for (String age : List.of("adult", "baby")) {
                if (!ages.has(age)) continue;
                JsonObject gaits = asObject(ages.get(age), entity.getKey() + " " + age);
                for (String gait : List.of("idle", "walk")) {
                    if (!gaits.has(gait)) continue;
                    Key key = new Key(entity.getKey(), age, gait);
                    entries.put(key, entryOf(key, asObject(gaits.get(gait), keyText(key))));
                }
            }
        }
        return new ScheduleRoster(entries);
    }

    /**
     * Returns the schedule of a type's default appearance at one age and gait.
     *
     * @param type the entity type
     * @param baby whether the subject is aged down
     * @param gait the gait the subject is rendered at
     * @return the schedule
     * @throws IllegalStateException if the roster names no such entry
     * @throws IllegalArgumentException if the gait poses nothing
     */
    public SubjectSchedule scheduleOf(EntityType<?> type, boolean baby, Gait gait) {
        return this.scheduleOf(type, baby, gait, Optional.empty(), Optional.empty());
    }

    /**
     * Returns the schedule of one subject at one gait.
     *
     * <p>With a trait, the entry's trait schedule under that key, the coat not consulted - a trait
     * subject is the type's default appearance with its trait selected, which is the appearance the
     * asset side resolved it at. Otherwise the coat's own schedule where the entry carries one, and the
     * entry's own schedule for every coat that resolves the same one.
     *
     * @param type the entity type
     * @param baby whether the subject is aged down
     * @param gait the gait the subject is rendered at
     * @param coat the subject's coat name, empty for a type with none
     * @param trait the posed trait key, {@code axis=value}, empty for a plain subject
     * @return the schedule
     * @throws IllegalStateException if the roster names no such entry or no such trait
     * @throws IllegalArgumentException if the gait poses nothing
     */
    public SubjectSchedule scheduleOf(EntityType<?> type, boolean baby, Gait gait, Optional<String> coat,
                                      Optional<String> trait) {
        Entry entry = this.entryOf(type, baby, gait);
        if (trait.isPresent()) {
            SubjectSchedule traited = entry.traits().get(trait.get());
            if (traited == null) {
                throw new IllegalStateException(String.format(
                    "No entity schedule for '%s' trait '%s' at gait '%s' - the roster names no such posed trait subject",
                    EntityType.getKey(type), trait.get(), gaitKey(gait)));
            }
            return traited;
        }
        return coat.map(entry.coats()::get).orElse(entry.schedule());
    }

    /**
     * Returns the posed trait keys the roster names for one type at one age and gait.
     *
     * @param type the entity type
     * @param baby whether the subject is aged down
     * @param gait the gait the subject is rendered at
     * @return the trait keys in sorted order, empty where the entry names none
     * @throws IllegalStateException if the roster names no such entry
     * @throws IllegalArgumentException if the gait poses nothing
     */
    public List<String> traitsOf(EntityType<?> type, boolean baby, Gait gait) {
        return List.copyOf(this.entryOf(type, baby, gait).traits().keySet());
    }

    /** The entry of one type at one age and gait, refusing one the roster does not name. */
    private Entry entryOf(EntityType<?> type, boolean baby, Gait gait) {
        String entity = EntityType.getKey(type).toString();
        String age = baby ? "baby" : "adult";
        Entry entry = this.entries.get(new Key(entity, age, gaitKey(gait)));
        if (entry == null) {
            throw new IllegalStateException(String.format(
                "No entity schedule for '%s' '%s' at gait '%s' - the roster and the client's roster of subjects have parted",
                entity, age, gaitKey(gait)));
        }
        return entry;
    }

    /**
     * Reads one entry - its own schedule, then the coats and the trait subjects it carries, neither of
     * which may nest either member again.
     */
    private static Entry entryOf(Key key, JsonObject entry) {
        return new Entry(scheduleOf(key, entry), Map.copyOf(nestedOf(key, entry, "coats")),
            Collections.unmodifiableSortedMap(nestedOf(key, entry, "traits")));
    }

    /**
     * Reads the coat or trait entries one entry carries under a member, empty where it carries none.
     *
     * @param key the entry's place
     * @param entry the entry
     * @param member {@code coats} or {@code traits}
     * @return each nested entry's schedule, keyed and sorted by its name
     */
    private static SortedMap<String, SubjectSchedule> nestedOf(Key key, JsonObject entry, String member) {
        SortedMap<String, SubjectSchedule> out = new TreeMap<>();
        if (!entry.has(member)) return out;
        for (Map.Entry<String, JsonElement> named : object(entry, member, keyText(key)).entrySet()) {
            JsonObject nested = asObject(named.getValue(), keyText(key) + " " + named.getKey());
            for (String inner : List.of("coats", "traits")) {
                if (nested.has(inner)) {
                    throw new IllegalStateException(String.format(
                        "Entity schedule '%s' '%s' '%s' nests '%s' inside a '%s' entry",
                        key.entity(), key.age(), key.gait(), inner, member));
                }
            }
            out.put(named.getKey(), scheduleOf(key, nested));
        }
        return out;
    }

    /**
     * Reads one schedule's four members and its clocks, refusing a value no render can be armed at.
     *
     * <p>A walk speed above one is one vanilla's render state never holds, its speed being clamped at
     * one; a clock array of another length than the strip, or a clock named for a field the schedule
     * carries a member of its own for, is a roster this reader and its writer do not agree on.
     */
    private static SubjectSchedule scheduleOf(Key key, JsonObject entry) {
        String where = keyText(key);
        int frames = member(entry, "frames", where).getAsInt();
        int ticksPerFrame = member(entry, "ticks_per_frame", where).getAsInt();
        float walkSpeed = Float.parseFloat(member(entry, "walk_speed", where).getAsString());
        float walkRate = Float.parseFloat(member(entry, "walk_rate", where).getAsString());
        if (frames < 1 || ticksPerFrame < 1 || !(walkSpeed >= 0f && walkSpeed <= 1f) || !(walkRate >= 0f) || Float.isInfinite(walkRate)) {
            throw new IllegalStateException(String.format(
                "Entity schedule '%s' '%s' '%s' is out of range: frames '%s', ticks_per_frame '%s', walk_speed '%s', walk_rate '%s'",
                key.entity(), key.age(), key.gait(), frames, ticksPerFrame, walkSpeed, walkRate));
        }

        Map<String, List<Float>> clocks = new HashMap<>();
        if (!entry.has("clocks")) return new SubjectSchedule(frames, ticksPerFrame, walkSpeed, walkRate, clocks);
        for (Map.Entry<String, JsonElement> clock : object(entry, "clocks", where).entrySet()) {
            List<JsonElement> spelled = clock.getValue().getAsJsonArray().asList();
            if (spelled.size() != frames) {
                throw new IllegalStateException(String.format(
                    "Entity schedule '%s' '%s' '%s' carries '%s' values for clock '%s' across '%s' frames",
                    key.entity(), key.age(), key.gait(), spelled.size(), clock.getKey(), frames));
            }
            List<Float> values = new ArrayList<>(frames);
            for (int frame = 0; frame < frames; frame++) {
                float value = Float.parseFloat(spelled.get(frame).getAsString());
                if (!Float.isFinite(value) || STRIDE_TRIO.contains(clock.getKey())) {
                    throw new IllegalStateException(String.format(
                        "Entity schedule '%s' '%s' '%s' clock '%s' holds '%s' at frame '%s'",
                        key.entity(), key.age(), key.gait(), clock.getKey(), value, frame));
                }
                values.add(value);
            }
            clocks.put(clock.getKey(), values);
        }
        return new SubjectSchedule(frames, ticksPerFrame, walkSpeed, walkRate, clocks);
    }

    /** The roster's key for a gait, which is the name of the sub-tree that gait writes. */
    private static String gaitKey(Gait gait) {
        return switch (gait) {
            case IDLE -> "idle";
            case WALK -> "walk";
            case BIND -> throw new IllegalArgumentException(String.format(
                "Gait '%s' poses nothing, so the entity schedule roster carries no schedule for it", gait));
        };
    }

    /** A version id cut to its first two dot-separated parts. */
    private static String majorMinor(String version) {
        String[] parts = version.split("\\.");
        return parts.length < 2 ? version : parts[0] + "." + parts[1];
    }

    /** One entry's place, spelled for a message. */
    private static String keyText(Key key) {
        return key.entity() + " " + key.age() + " " + key.gait();
    }

    /** One member of an object, refusing an object that lacks it. */
    private static JsonElement member(JsonObject object, String member, String where) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull())
            throw new IllegalStateException(String.format("Entity schedule '%s' carries no member '%s'", where, member));
        return value;
    }

    /** One member of an object that is itself an object. */
    private static JsonObject object(JsonObject object, String member, String where) {
        return asObject(member(object, member, where), where + " " + member);
    }

    /** One element read as an object, refusing one that is not. */
    private static JsonObject asObject(JsonElement element, String where) {
        if (element == null || !element.isJsonObject())
            throw new IllegalStateException(String.format("Entity schedule '%s' is not an object", where));
        return element.getAsJsonObject();
    }
}
