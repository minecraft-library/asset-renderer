package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.bake.pose.StyleResolution;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.sweep.EntityScheduleRoster;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The strip every shipped row derives, pinned so a change to one shows in review as the rows it
 * moved.
 *
 * <p>Every strip is the one {@link StyleResolution#of} answers - the resolution a render naming no
 * frame count draws - for each entity the shipped tables carry, at each age it offers, under:
 * <ul>
 *     <li><b>{@code idle} and {@code stride}</b>, which every catalog resolves, a style-less model to
 *     the universal rows, and every other id its catalog lists at that age;</li>
 *     <li><b>{@code <row>@<gate>}</b> for a row carrying a gated source, resolved under an appearance
 *     admitting the gate, so the gate's motion is pinned beside the row it narrows - the charged
 *     creeper's scroll;</li>
 *     <li><b>{@code <row>~<axis>=<option>}</b> for every option of a form axis whose resolved body pose
 *     is a different program from the default's, so a form posed by a model class of its own is pinned
 *     however its row is spelled;</li>
 *     <li><b>{@code <row>~<trait>=<value>}</b> for every posed trait subject
 *     {@link EntityScheduleRoster#POSED_TRAITS} names, at {@code idle} and {@code stride} of the adult,
 *     so each subject the posed references render apart is pinned at the strip they render it over -
 *     the list read rather than copied, so the golden, the roster and the harness name one set.</li>
 * </ul>
 * The golden holds each row on one line - frames, step, the restart's seam to two places and the
 * channel it is on - so a moved row is a one-line diff.
 *
 * <p>Beside the pin: every derived strip keeps the cap and the two steps whatever the golden says; no
 * shipped row carries two gates, and every gate, form axis and trait is one the test can spell, so a new
 * one arrives as a failure rather than as a row left unpinned; a row gated on a trait is pinned through
 * its entity's trait keys, so every entity carrying one is a posed trait subject; and a variant coat
 * measured beside its family is measured over the strip a render of that coat draws.
 *
 * <p>Nothing is drawn and no texture is read, so the class runs wherever the shipped tables are.
 */
@DisplayName("every shipped row derives the strip pinned for it")
class PoseStripsCorpusTest {

    /** The golden, read and written at its source path so a regeneration and a comparison read one file. */
    private static final @NotNull Path GOLDEN = Path.of("src/test/resources/lib/minecraft/renderer/bake/pose/pose-strips.json");

    /** What rewrites the golden rather than comparing against it. */
    private static final @NotNull String REGENERATE_COMMAND =
        "./gradlew :test --tests \"*PoseStripsCorpusTest\" -Dasset.pose.regenerateStrips=true --rerun";

    /** Whether this run writes the golden; unset, the derived strips are compared with it instead. */
    private static final boolean REGENERATE = Boolean.getBoolean("asset.pose.regenerateStrips");

    /** How an appearance admits each gate token a shipped source carries. */
    private static final @NotNull Map<String, UnaryOperator<AppearanceOptions.Builder>> GATES =
        Map.of("charged", builder -> builder.charged(true));

    /** How an appearance selects one option of each form axis, the option spelled as its key is. */
    private static final @NotNull Map<String, BiFunction<AppearanceOptions.Builder, String, AppearanceOptions.Builder>> AXES = Map.of(
        "variant", AppearanceOptions.Builder::variant,
        "size", (builder, option) -> builder.size(Size.valueOf(option.toUpperCase(Locale.ROOT))),
        "shape", (builder, option) -> builder.pattern(firstPattern(option)));

    /**
     * How an appearance takes each posed trait, the value spelled as a reference stem spells it - a
     * namespaced id with its {@code :} written {@code __}. A gate token named here is pinned through
     * the trait keys of its entity rather than as a gate key of its own, which would resolve the very
     * subject a trait key does.
     */
    private static final @NotNull Map<String, BiFunction<AppearanceOptions.Builder, String, AppearanceOptions.Builder>> TRAITS = Map.of(
        "carried", (builder, value) -> builder.carried(value.replace("__", ":")));

    /** The ages a key is pinned at, in the golden's order. */
    private static final @NotNull List<Age> AGES = List.of(Age.ADULT, Age.BABY);

    /** One pinned row as the golden writes it. */
    private static final @NotNull Pattern ROW = Pattern.compile("^ {6}\"([^\"]+)\": (\\{.*})(,?)$");

    /** One entity or age opening as the golden writes it, its depth the indent. */
    private static final @NotNull Pattern OPENING = Pattern.compile("^( {2}| {4})\"([^\"]+)\": \\{$");

    /** The shipped tables. */
    private static ConcurrentMap<String, Entity> entities;

    /** The context every strip is resolved through, over the shipped tables alone. */
    private static RendererContext context;

    /** Every derived strip - entity id, age, row key - each map sorted. */
    private static SortedMap<String, SortedMap<String, SortedMap<String, StripFit>>> derived;

    /** Every shipped row carrying more than one distinct gate token, one line each. */
    private static List<String> doubleGated;

    /** Every gate token, form axis or posed trait the corpus needs that the test cannot spell, one line each. */
    private static List<String> unspelled;

    /** Every entity whose shipped rows carry a source gated on a trait, sorted. */
    private static Set<String> traitGated;

    @BeforeAll
    static void derive() {
        entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        context = RendererContext.builder().entities(entities).build();
        EntityRenderer renderer = new EntityRenderer(context);
        derived = new TreeMap<>();
        doubleGated = new ArrayList<>();
        unspelled = new ArrayList<>();
        traitGated = new TreeSet<>();
        for (String id : new TreeMap<>(entities).keySet())
            derive(renderer, id);
    }

    @Test
    @DisplayName("every shipped row derives the strip the golden pins for it")
    void everyShippedRowDerivesItsPinnedStrip() throws IOException {
        String actual = golden(derived);
        if (REGENERATE) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            return;
        }

        assertTrue(Files.isRegularFile(GOLDEN), GOLDEN + " is absent - regenerate with " + REGENERATE_COMMAND);
        String pinned = Files.readString(GOLDEN, StandardCharsets.UTF_8);
        if (pinned.equals(actual)) return;

        SortedMap<String, String> was = rows(pinned);
        SortedMap<String, String> now = rows(actual);
        List<String> moved = new ArrayList<>();
        for (String key : union(was.keySet(), now.keySet())) {
            String before = was.get(key);
            String after = now.get(key);
            if (before == null) moved.add(key + ": derived " + after + ", pinned nowhere");
            else if (after == null) moved.add(key + ": pinned " + before + ", derived nowhere");
            else if (!before.equals(after)) moved.add(key + ": pinned " + before + " derived " + after);
        }
        throw new AssertionError("the derived strips are not the pinned ones"
            + (moved.isEmpty() ? " - every row agrees, so the file's layout differs" : ":\n  " + String.join("\n  ", moved))
            + "\nregenerate with " + REGENERATE_COMMAND);
    }

    @Test
    @DisplayName("every derived strip keeps the cap and one of the two steps")
    void everyDerivedStripKeepsTheCap() {
        List<String> broken = new ArrayList<>();
        each((key, strip) -> {
            boolean step = strip.ticksPerFrame() == StripFit.FINE_STEP || strip.ticksPerFrame() == StripFit.COARSE_STEP;
            boolean frames = strip.frames() >= 1 && strip.frames() <= StripFit.MAX_FRAMES;
            boolean still = !strip.isStill() || strip.ticksPerFrame() == StripFit.COARSE_STEP;
            if (!step || !frames || !still) broken.add(key + ": " + strip);
        });
        assertEquals(List.of(), broken, "a derived strip runs from one frame to " + StripFit.MAX_FRAMES
            + " at a step of " + StripFit.FINE_STEP + " or " + StripFit.COARSE_STEP + ", a still one at "
            + StripFit.COARSE_STEP);
    }

    @Test
    @DisplayName("every shipped gate is one the test can admit, and no shipped row carries two")
    void noShippedRowCarriesTwoGates() {
        assertEquals(List.of(), doubleGated, "a row gated on two tokens pins under neither alone - a second "
            + "gate or a combination needs a key of its own");
        assertEquals(List.of(), unspelled.stream().filter(line -> line.contains("@")).toList(),
            "a gate token the test cannot admit leaves its gated motion unpinned - spell it in GATES");
    }

    @Test
    @DisplayName("every variant coat is measured beside its family over the strip a render of it draws")
    void everyCoatMeasuresAtItsResolvedStrip() {
        List<String> parted = new ArrayList<>();
        int measured = 0;
        for (String id : new TreeMap<>(entities).keySet()) {
            Possible<Entity> found = context.findEntity(id);
            if (found.isEmpty()) continue;
            Map<String, Entity> coats = new TreeMap<>(found.get().axes().variant().options());
            if (coats.isEmpty()) continue;
            AppearanceOptions adult = AppearanceOptions.builder().age(Age.ADULT).build();
            for (String rowId : List.of(PoseStyle.IDLE, PoseStyle.STRIDE)) {
                StyleResolution family = StyleResolution.of(found, id, rowId, adult);
                Entity subject = family.subject().orElseThrow();
                PosePlayer.PosedFrames posed = PosePlayer.frames(subject, family.row(), subject.styles().periodTicks());
                for (Map.Entry<String, Entity> coat : coats.entrySet()) {
                    StripFit measuredOver = posed.memberStrip(coat.getValue());
                    StripFit drawn = StyleResolution.of(found, id, rowId,
                        AppearanceOptions.builder().age(Age.ADULT).variant(coat.getKey()).build()).strip();
                    measured++;
                    if (!measuredOver.equals(drawn)) {
                        parted.add(id + " " + rowId + " ~variant=" + coat.getKey() + ": measured over " + measuredOver
                            + ", drawn over " + drawn);
                    }
                }
            }
        }
        assertTrue(measured > 0, "the shipped tables carry variant coats to measure");
        assertEquals(List.of(), parted, "a coat measured over a strip other than the one its own render draws "
            + "sizes a canvas for frames that render never shows");
    }

    @Test
    @DisplayName("every form axis a shipped entity carries and every posed trait is one the test can select")
    void everyFormAndTraitKeyIsSpelled() {
        assertEquals(List.of(), unspelled.stream().filter(line -> line.contains("~")).toList(),
            "an axis or a trait the test cannot select leaves every key on it unpinned - spell it in AXES or TRAITS");
        assertTrue(EntityScheduleRoster.POSED_TRAITS.keySet().containsAll(traitGated),
            "a row gated on a trait is pinned through its entity's trait keys, so every entity carrying one "
                + "is a posed trait subject: " + traitGated + " against " + EntityScheduleRoster.POSED_TRAITS.keySet());
    }

    // ------------------------------------------------------------------------------------

    /**
     * Derives every key of one entity into {@link #derived}, recording each gate, axis and trait it
     * cannot spell and each row carrying two gates.
     */
    private static void derive(@NotNull EntityRenderer renderer, @NotNull String id) {
        Entity definition = entities.get(id);
        Possible<Entity> found = context.findEntity(id);
        SortedMap<String, SortedMap<String, StripFit>> ages = new TreeMap<>();
        for (Age age : AGES) {
            if (age == Age.BABY && definition.axes().baby().isEmpty()) continue;
            AppearanceOptions appearance = AppearanceOptions.builder().age(age).build();
            SortedMap<String, StripFit> rows = new TreeMap<>();
            List<String> rowIds = rowIds(renderer, found, id, appearance);
            for (String rowId : rowIds) {
                rows.put(rowId, StyleResolution.of(found, id, rowId, appearance).strip());
                if (found.isPresent()) gates(found, id, rowId, age, rows);
            }
            if (found.isPresent()) forms(found, id, rowIds, age, rows);
            // The posed references render a trait subject at the adult alone, so that is where it is pinned.
            if (age == Age.ADULT && EntityScheduleRoster.POSED_TRAITS.containsKey(id)) traits(found, id, rows);
            ages.put(spelled(age), rows);
        }
        derived.put(id, ages);
    }

    /**
     * The row ids pinned at one age - {@code idle} and {@code stride} always, then every other id the
     * render's catalog lists and holds a row of at that age.
     */
    private static @NotNull List<String> rowIds(@NotNull EntityRenderer renderer, @NotNull Possible<Entity> found,
                                                @NotNull String id, @NotNull AppearanceOptions appearance) {
        Set<String> out = new LinkedHashSet<>(List.of(PoseStyle.IDLE, PoseStyle.STRIDE));
        for (String rowId : renderer.styles(id).ids()) {
            if (rowId.equals(PoseStyle.BIND) || out.contains(rowId)) continue;
            if (found.get().styles().byId(rowId, appearance::applies).isPresent()) out.add(rowId);
        }
        return List.copyOf(out);
    }

    /**
     * Pins one row under each gate its shipped sources carry, recording a row carrying two and an
     * entity whose row is gated on a trait, which its trait keys pin instead.
     */
    private static void gates(@NotNull Possible<Entity> found, @NotNull String id, @NotNull String rowId,
                              @NotNull Age age, @NotNull SortedMap<String, StripFit> rows) {
        PoseStyle shipped = found.get().styles().resolve(rowId, AppearanceOptions.builder().age(age).build()::applies, id);
        Set<String> tokens = new LinkedHashSet<>();
        for (PoseStyle.StyleSource source : shipped.sources())
            source.gate().ifPresent(tokens::add);
        if (tokens.size() > 1) doubleGated.add(id + " " + spelled(age) + " " + rowId + " " + tokens);
        for (String token : tokens) {
            if (TRAITS.containsKey(token)) {
                traitGated.add(id);
                continue;
            }
            UnaryOperator<AppearanceOptions.Builder> admits = GATES.get(token);
            if (admits == null) {
                unspelled.add(id + " " + spelled(age) + " " + rowId + "@" + token);
                continue;
            }
            AppearanceOptions admitting = admits.apply(AppearanceOptions.builder().age(age)).build();
            rows.put(rowId + "@" + token, StyleResolution.of(found, id, rowId, admitting).strip());
        }
    }

    /**
     * Pins every row under each form option whose resolved body pose is another program than the
     * default's, recording an axis carrying options the test cannot select.
     */
    private static void forms(@NotNull Possible<Entity> found, @NotNull String id, @NotNull List<String> rowIds,
                              @NotNull Age age, @NotNull SortedMap<String, StripFit> rows) {
        Entity definition = found.get();
        EntityPose defaultPose = AppearanceOptions.builder().age(age).build().resolve(definition).pose();
        for (RecordComponent axis : formAxes()) {
            Map<String, ?> options = options(axis, definition);
            if (options.isEmpty()) continue;
            BiFunction<AppearanceOptions.Builder, String, AppearanceOptions.Builder> select = AXES.get(axis.getName());
            if (select == null) {
                unspelled.add(id + " " + spelled(age) + " ~" + axis.getName());
                continue;
            }
            for (String option : new TreeMap<>(options).keySet()) {
                AppearanceOptions selecting = select.apply(AppearanceOptions.builder().age(age), option).build();
                if (selecting.resolve(definition).pose() == defaultPose) continue;
                for (String rowId : rowIds) {
                    rows.put(rowId + "~" + axis.getName() + "=" + option,
                        StyleResolution.of(found, id, rowId, selecting).strip());
                }
            }
        }
    }

    /**
     * Pins {@code idle} and {@code stride} under each posed trait the schedule roster names for one
     * entity, recording a trait the test cannot select.
     */
    private static void traits(@NotNull Possible<Entity> found, @NotNull String id, @NotNull SortedMap<String, StripFit> rows) {
        for (String key : EntityScheduleRoster.POSED_TRAITS.get(id)) {
            int split = key.indexOf('=');
            BiFunction<AppearanceOptions.Builder, String, AppearanceOptions.Builder> take =
                split < 0 ? null : TRAITS.get(key.substring(0, split));
            if (take == null) {
                unspelled.add(id + " " + spelled(Age.ADULT) + " ~" + key);
                continue;
            }
            AppearanceOptions taking = take.apply(AppearanceOptions.builder().age(Age.ADULT), key.substring(split + 1)).build();
            for (String rowId : List.of(PoseStyle.IDLE, PoseStyle.STRIDE))
                rows.put(rowId + "~" + key, StyleResolution.of(found, id, rowId, taking).strip());
        }
    }

    /** Every axis of {@link Entity.Axes} whose options are forms of the entity, in declaration order. */
    private static @NotNull List<RecordComponent> formAxes() {
        return Arrays.stream(Entity.Axes.class.getRecordComponents())
            .filter(component -> component.getType() == Entity.Variation.class)
            .filter(component -> component.getGenericType() instanceof ParameterizedType type
                && type.getActualTypeArguments()[1] == Entity.class)
            .toList();
    }

    /** One form axis's options, keyed by the option as a form key spells it. */
    private static @NotNull Map<String, ?> options(@NotNull RecordComponent axis, @NotNull Entity definition) {
        Entity.Variation<?, ?> variation;
        try {
            variation = (Entity.Variation<?, ?>) axis.getAccessor().invoke(definition.axes());
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new AssertionError("the '" + axis.getName() + "' axis could not be read", ex);
        }
        Map<String, Object> out = new TreeMap<>();
        variation.options().forEach((option, form) -> out.put(spelled(option), form));
        return out;
    }

    /** The first tropical fish pattern drawn on one body shape, the shape axis's only route. */
    private static @NotNull TropicalFishPattern firstPattern(@NotNull String shape) {
        TropicalFishPattern.Shape wanted = TropicalFishPattern.Shape.valueOf(shape.toUpperCase(Locale.ROOT));
        return Arrays.stream(TropicalFishPattern.values())
            .filter(pattern -> pattern.shape() == wanted)
            .findFirst()
            .orElseThrow();
    }

    /** An option or an age as a key spells it - an enum constant in lower case, anything else as it reads. */
    private static @NotNull String spelled(@NotNull Object value) {
        return value instanceof Enum<?> constant ? constant.name().toLowerCase(Locale.ROOT) : value.toString();
    }

    /** Runs one check over every derived strip, keyed {@code entity age row}. */
    private static void each(@NotNull BiConsumer<String, StripFit> check) {
        derived.forEach((id, ages) -> ages.forEach((age, rows) ->
            rows.forEach((row, strip) -> check.accept(id + " " + age + " " + row, strip))));
    }

    /** The golden's text for a set of strips - sorted keys, two-space indent, one row a line. */
    private static @NotNull String golden(@NotNull SortedMap<String, SortedMap<String, SortedMap<String, StripFit>>> strips) {
        StringBuilder out = new StringBuilder("{\n");
        int entity = 0;
        for (Map.Entry<String, SortedMap<String, SortedMap<String, StripFit>>> byEntity : strips.entrySet()) {
            out.append("  ").append(quoted(byEntity.getKey())).append(": {\n");
            int age = 0;
            for (Map.Entry<String, SortedMap<String, StripFit>> byAge : byEntity.getValue().entrySet()) {
                out.append("    ").append(quoted(byAge.getKey())).append(": {\n");
                int row = 0;
                for (Map.Entry<String, StripFit> byRow : byAge.getValue().entrySet()) {
                    out.append("      ").append(quoted(byRow.getKey())).append(": ").append(row(byRow.getValue()))
                        .append(++row < byAge.getValue().size() ? ",\n" : "\n");
                }
                out.append("    }").append(++age < byEntity.getValue().size() ? ",\n" : "\n");
            }
            out.append("  }").append(++entity < strips.size() ? ",\n" : "\n");
        }
        return out.append("}\n").toString();
    }

    /** One strip as its golden row - frames, step, the seam to two places and the channel it is on. */
    private static @NotNull String row(@NotNull StripFit strip) {
        BigDecimal seam = new BigDecimal(String.format(Locale.ROOT, "%.2f", strip.seam()));
        return "{\"frames\": " + strip.frames() + ", \"ticks_per_frame\": " + strip.ticksPerFrame()
            + ", \"seam\": " + seam.toPlainString()
            + strip.worstChannel().map(channel -> ", \"worst_channel\": " + quoted(channel)).orElse("") + "}";
    }

    /** A JSON string literal. */
    private static @NotNull String quoted(@NotNull String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Every row of a golden text, keyed {@code entity age row}. */
    private static @NotNull SortedMap<String, String> rows(@NotNull String text) {
        SortedMap<String, String> out = new TreeMap<>();
        String entity = "";
        String age = "";
        for (String line : text.split("\n")) {
            Matcher opening = OPENING.matcher(line);
            Matcher row = ROW.matcher(line);
            if (opening.matches()) {
                if (opening.group(1).length() == 2) entity = opening.group(2);
                else age = opening.group(2);
            } else if (row.matches()) {
                out.put(entity + " " + age + " " + row.group(1), row.group(2));
            }
        }
        return out;
    }

    /** Two key sets as one, sorted. */
    private static @NotNull Set<String> union(@NotNull Set<String> first, @NotNull Set<String> second) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }

}
