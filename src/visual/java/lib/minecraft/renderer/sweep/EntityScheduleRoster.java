package lib.minecraft.renderer.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.bake.pose.StyleResolution;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.StripMemo;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The roster of schedules the posed reference sweeps render at - for every entity, age and gait, the
 * frames, frame step, walk speed, walk-clock rate and each clock's value per frame that a render naming
 * no frame count takes.
 * <p>
 * Each entry is the {@link StyleResolution} a render of that subject resolves, so the schedule the
 * harness renders a subject at is the one this renderer draws it at, from one piece of code. An entry
 * carries a {@code coats} member where a coat of the entity resolves another schedule, and the adult
 * entries of each {@link #POSED_TRAITS} entity carry a {@code traits} member, one entry per posed trait
 * subject.
 * <p>
 * The harness reads this one file and refuses a subject it does not name, and each posed sub-tree
 * records the schedules it rendered in its own {@code schedule.json}, which
 * {@link EntityAnimationParitySweep} holds to {@link Schedule#of} of its own resolution. The roster is
 * written beside the reference tree, and every posed reference run writes it first.
 * <p>
 * Usage: {@code EntityScheduleRoster}, no arguments.
 */
@UtilityClass
public final class EntityScheduleRoster {

    /** The roster's directory, a sibling of the reference tree. */
    static final @NotNull Path HOME = ParityPaths.REFERENCES.resolveSibling("entity-schedule");

    /** The roster file itself. */
    static final @NotNull Path ROSTER = HOME.resolve("roster.json");

    /**
     * The posed trait subjects, entity id to trait keys in sorted order - each key a stem's qualifier
     * after {@code ~}. The harness renders exactly the trait subjects the roster names.
     */
    public static final @NotNull SortedMap<String, List<String>> POSED_TRAITS = Collections.unmodifiableSortedMap(new TreeMap<>(Map.of(
        "minecraft:enderman", List.of("carried=minecraft__grass_block"),
        "minecraft:iron_golem", List.of("carried=minecraft__poppy"))));

    /** The age keys an entry is written under, each with the age its appearance selects, sorted. */
    private static final @NotNull SortedMap<String, Age> AGES = Collections.unmodifiableSortedMap(new TreeMap<>(Map.of(
        "adult", Age.ADULT,
        "baby", Age.BABY)));

    /** The fields a schedule carries members of their own for, so no clock is written for one. */
    private static final @NotNull Set<String> STRIDE_TRIO = Set.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos");

    /** The format this writer writes and the harness reads. */
    private static final int FORMAT = 1;

    private static final @NotNull Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * The two posed gaits, each with the row a render of it resolves.
     *
     * @param key the gait's key in the roster, which is also its reference sub-tree's name
     * @param row the style id the render resolves at that gait
     */
    private record Gait(@NotNull String key, @NotNull String row) {

        /** Standing, the {@code idle} sub-tree. */
        private static final @NotNull Gait IDLE = new Gait("idle", PoseStyle.IDLE);

        /** Walking, the {@code walk} sub-tree. */
        private static final @NotNull Gait WALK = new Gait("walk", PoseStyle.STRIDE);

        /** Both gaits, in the roster's key order. */
        private static final @NotNull List<Gait> BOTH = List.of(IDLE, WALK);

    }

    /**
     * One subject's schedule at one gait - what the roster carries for it, and what the visual sweep
     * holds a sidecar entry to.
     *
     * @param frames the frames the strip draws
     * @param ticksPerFrame the ticks between two frames
     * @param walkSpeed the held walk speed, zero where the row drives none
     * @param walkRate the walk clock's advance a tick, zero where the row drives none
     * @param clocks each clock field's value at every frame, in frame order, keyed and walked sorted
     */
    record Schedule(int frames, int ticksPerFrame, float walkSpeed, float walkRate,
                    @NotNull Map<String, List<Float>> clocks) {

        /**
         * Constructs a new {@code Schedule}, copying the clocks deep so equality compares every value.
         */
        Schedule {
            TreeMap<String, List<Float>> copied = new TreeMap<>();
            clocks.forEach((field, values) -> copied.put(field, List.copyOf(values)));
            clocks = Collections.unmodifiableSortedMap(copied);
        }

        /**
         * Reads the schedule a resolution draws at: its strip, the in-force row's walk pair, and every
         * ungrouped clock the row drives evaluated at each frame's tick.
         *
         * @param resolved the resolution a render of the subject takes
         * @return its schedule
         * @throws IllegalStateException if the row drives the walk speed on a wave other than a hold, the
         *     walk clock on a wave other than a ramp, or a clock on a sweep or a cycle, none of which the
         *     harness can arm from what a schedule carries
         */
        static @NotNull Schedule of(@NotNull StyleResolution resolved) {
            StripFit strip = resolved.strip();
            PoseStyle row = resolved.row();
            StyleDriver speed = row.drivers().get("walkAnimationSpeed");
            if (speed != null && speed.wave() != StyleDriver.Wave.Plain.HOLD) {
                throw new IllegalStateException(String.format(
                    "Row '%s' drives the walk speed on wave '%s', and a schedule carries a held speed alone", row.id(), speed.wave()));
            }
            StyleDriver rate = row.drivers().get("walkAnimationPos");
            if (rate != null && rate.wave() != StyleDriver.Wave.Plain.RAMP) {
                throw new IllegalStateException(String.format(
                    "Row '%s' drives the walk clock on wave '%s', and a schedule carries a ramped clock alone", row.id(), rate.wave()));
            }

            Map<String, List<Float>> clocks = new TreeMap<>();
            for (StyleDriver driver : row.drivers().values()) {
                if (driver.group().isPresent() || STRIDE_TRIO.contains(driver.field())) continue;
                if (driver.wave() == StyleDriver.Wave.Plain.SWEEP || driver.wave() == StyleDriver.Wave.Plain.CYCLE) {
                    throw new IllegalStateException(String.format(
                        "Row '%s' drives clock '%s' on wave '%s', which reads a window a schedule does not carry",
                        row.id(), driver.field(), driver.wave()));
                }
                List<Float> values = new ArrayList<>(strip.frames());
                for (int frame = 0; frame < strip.frames(); frame++)
                    values.add(driver.at(EntityAnimationParitySweep.START_TICK + frame * strip.ticksPerFrame(), StyleCatalog.FIGURE_PERIOD_TICKS));
                clocks.put(driver.field(), values);
            }
            return new Schedule(strip.frames(), strip.ticksPerFrame(),
                speed == null ? 0f : speed.extent(), rate == null ? 0f : rate.extent(), clocks);
        }

    }

    /**
     * Writes the roster.
     *
     * @param args unused
     * @throws IOException if the roster cannot be written
     * @throws IllegalStateException if a subject resolves no schedule, an idle subject walks, or a posed
     *     trait names an entity the index lacks or does not parse to a subject of its entity
     */
    public static void main(String @NotNull [] args) throws IOException {
        ClientOptions options = ClientOptions.defaults();
        RendererContext context = RendererContext.load(ClientAcquisition.acquire(options));
        StripMemo strips = context.strips();
        // Every row, the types vanilla draws nothing for included: the context answers those empty,
        // and they are written like any other key.
        ConcurrentMap<String, Entity> entities = EntityModelLoader.loadAll();
        if (entities.isEmpty())
            throw new IllegalStateException("The entity index is empty - run entityModels first");
        AppearanceCodec codec = AppearanceCodec.of(entities);
        for (String id : POSED_TRAITS.keySet()) {
            if (!entities.containsKey(id))
                throw new IllegalStateException(String.format("Posed trait entity '%s' is not in the entity index", id));
        }

        Tally tally = new Tally();
        JsonObject subjects = new JsonObject();
        for (String id : new TreeMap<>(entities).keySet()) {
            Possible<Entity> found = context.findEntity(id);
            JsonObject ages = new JsonObject();
            for (Map.Entry<String, Age> age : AGES.entrySet()) {
                if (age.getValue() == Age.BABY && entities.get(id).axes().baby().isEmpty()) continue;
                JsonObject gaits = new JsonObject();
                for (Gait gait : Gait.BOTH) {
                    AppearanceOptions appearance = AppearanceOptions.builder().age(age.getValue()).build();
                    Schedule schedule = resolve(strips, found, id, age.getKey(), gait, "default", appearance);
                    JsonObject entry = entryOf(schedule);
                    tally.entry(schedule);

                    JsonObject coats = new JsonObject();
                    if (found.isPresent()) {
                        for (String coat : new TreeMap<>(found.get().axes().variant().options()).keySet()) {
                            Schedule coated = resolve(strips, found, id, age.getKey(), gait, "coat=" + coat,
                                AppearanceOptions.builder().age(age.getValue()).variant(coat).build());
                            if (coated.equals(schedule)) continue;
                            coats.add(coat, entryOf(coated));
                            tally.coat(coated);
                        }
                    }
                    if (!coats.isEmpty()) entry.add("coats", coats);

                    if (age.getValue() == Age.ADULT && POSED_TRAITS.containsKey(id)) {
                        JsonObject traits = new JsonObject();
                        for (String key : POSED_TRAITS.get(id)) {
                            Schedule trait = resolve(strips, found, id, age.getKey(), gait, key,
                                traitKey(codec, id, key).appearance());
                            traits.add(key, entryOf(trait));
                            tally.trait(trait);
                        }
                        entry.add("traits", traits);
                    }
                    gaits.add(gait.key(), entry);
                }
                ages.add(age.getKey(), gaits);
            }
            subjects.add(id, ages);
        }

        JsonObject roster = new JsonObject();
        roster.addProperty("format", FORMAT);
        roster.addProperty("source_version", options.getVersion());
        roster.add("subjects", subjects);
        Files.createDirectories(HOME);
        Files.writeString(ROSTER, GSON.toJson(roster) + "\n", StandardCharsets.UTF_8);

        System.out.printf("Entity schedule roster: %d entries, %d..%d frames a strip, %d coat overrides, "
                + "%d entries carrying clocks, %d posed trait entries -> %s%n",
            tally.entries, tally.fewest, tally.most, tally.coats, tally.clocked, tally.traits,
            ROSTER.toAbsolutePath().normalize());
    }

    /**
     * Resolves one subject's schedule at one gait, refusing an idle one that walks.
     *
     * @param strips the memo of the context the entity id is answered by
     * @param found what the index answers for the entity id
     * @param id the entity id
     * @param age the age key the entry is written under
     * @param gait the gait
     * @param form what the appearance selects beyond the age - {@code default}, a coat or a trait key
     * @param appearance the appearance a render of the subject takes
     * @return the subject's schedule
     * @throws IllegalStateException if the subject resolves no schedule, or walks at the idle gait
     */
    private static @NotNull Schedule resolve(@NotNull StripMemo strips, @NotNull Possible<Entity> found,
                                             @NotNull String id, @NotNull String age, @NotNull Gait gait,
                                             @NotNull String form, @NotNull AppearanceOptions appearance) {
        Schedule schedule;
        try {
            schedule = Schedule.of(StyleResolution.of(strips, found, id, gait.row(), appearance));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(String.format(
                "Entity '%s' age '%s' gait '%s' as '%s' resolves no schedule", id, age, gait.key(), form), ex);
        }
        // The harness arms no walk on an idle frame, so an idle row that walked could never be drawn alike.
        if (gait == Gait.IDLE && (schedule.walkSpeed() != 0f || schedule.walkRate() != 0f)) {
            throw new IllegalStateException(String.format(
                "Entity '%s' age '%s' as '%s' walks at speed '%s' and walk-clock rate '%s' standing idle, and an idle frame arms no walk",
                id, age, form, schedule.walkSpeed(), schedule.walkRate()));
        }
        return schedule;
    }

    /**
     * Parses one posed trait subject's stem, the way the visual sweep parses the stem it finds.
     *
     * @param codec the codec over the loaded index
     * @param id the entity id
     * @param key the trait key
     * @return the parsed subject
     * @throws IllegalStateException if the stem does not parse, or parses to another entity
     */
    private static @NotNull AppearanceKey traitKey(@NotNull AppearanceCodec codec, @NotNull String id, @NotNull String key) {
        String stem = id.replace(":", "__") + "~" + key;
        if (codec.parse(stem + ".png") instanceof AppearanceKey.Result.Parsed parsed && parsed.key().entityId().equals(id))
            return parsed.key();
        throw new IllegalStateException(String.format("Posed trait '%s' of '%s' does not parse to a subject of that entity", key, id));
    }

    /**
     * One schedule as its roster entry - the four members in order, then its clocks where it carries
     * any, fields sorted and values in frame order.
     *
     * @param schedule the schedule
     * @return its entry
     */
    private static @NotNull JsonObject entryOf(@NotNull Schedule schedule) {
        JsonObject entry = new JsonObject();
        entry.addProperty("frames", schedule.frames());
        entry.addProperty("ticks_per_frame", schedule.ticksPerFrame());
        entry.addProperty("walk_speed", schedule.walkSpeed());
        entry.addProperty("walk_rate", schedule.walkRate());
        if (!schedule.clocks().isEmpty()) {
            JsonObject clocks = new JsonObject();
            schedule.clocks().forEach((field, values) -> {
                JsonArray array = new JsonArray();
                values.forEach(array::add);
                clocks.add(field, array);
            });
            entry.add("clocks", clocks);
        }
        return entry;
    }

    /** What the roster wrote, counted for its one line. */
    private static final class Tally {

        /** The entity, age and gait entries written. */
        private int entries;

        /** The coat overrides written. */
        private int coats;

        /** The posed trait entries written. */
        private int traits;

        /** The entries, overrides and trait entries carrying clocks. */
        private int clocked;

        /** The shortest strip written. */
        private int fewest = Integer.MAX_VALUE;

        /** The longest strip written. */
        private int most;

        private void entry(@NotNull Schedule schedule) {
            this.entries++;
            this.count(schedule);
        }

        private void coat(@NotNull Schedule schedule) {
            this.coats++;
            this.count(schedule);
        }

        private void trait(@NotNull Schedule schedule) {
            this.traits++;
            this.count(schedule);
        }

        private void count(@NotNull Schedule schedule) {
            if (!schedule.clocks().isEmpty()) this.clocked++;
            this.fewest = Math.min(this.fewest, schedule.frames());
            this.most = Math.max(this.most, schedule.frames());
        }

    }

}
