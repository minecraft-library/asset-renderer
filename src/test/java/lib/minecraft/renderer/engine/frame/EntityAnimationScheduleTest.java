package lib.minecraft.renderer.engine.frame;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract the two builds read the animated sweep's schedule through.
 *
 * <p>The asset side resolves every posed subject's schedule - its frames, its step, its walk and each
 * clock's value at every frame - and writes it to a roster beside the reference tree. The harness reads
 * that roster, renders each subject at its own schedule, and records what it rendered in a sidecar of
 * each posed sub-tree, which the visual sweep holds to its own resolution. So no schedule value lives in
 * either source: what the two share is the one constant that seats frame 0, the member names of the two
 * files, and where those files are.
 *
 * <p>Held here: no schedule constant, figure period or wave roster grows back beside the roster; frame
 * 0 is tick 0 on both sides; every reader and writer spells each member of the file it reads or writes;
 * both sides name one roster path and one sidecar name; the harness arms no baby scale of its own; only
 * the entity a frame is armed for takes the schedule's walk and clocks; a posed capture refuses an
 * entity no frame is armed for, and every frame is armed for the entity it draws; and the roster
 * resolves through the one type a render resolves through.
 *
 * <p>Read out of SOURCES as text. The harness is a separate Gradle build with no test source set and
 * nothing on this classpath, so its types cannot be referenced; and a mis-edit on either side shows
 * otherwise only as a refusal at a client boot or an exit of the visual sweep.
 */
@DisplayName("the animated sweep's schedule contract")
class EntityAnimationScheduleTest {

    /** The harness's client sources, in its own build, reachable from the renderer root as a path and no other way. */
    private static final @NotNull Path HARNESS = Path.of("harness/src/client/java/lib/minecraft/refharness");

    /** The harness's posed sweep, which enumerates each subject at its schedule and writes the sidecar. */
    private static final @NotNull Path HARNESS_SWEEP = HARNESS.resolve("sweep/EntityAnimationSweep.java");

    /** The harness's reader of the roster. */
    private static final @NotNull Path HARNESS_ROSTER = HARNESS.resolve("sweep/ScheduleRoster.java");

    /** The harness's one subject's schedule, as the roster carries it. */
    private static final @NotNull Path HARNESS_SCHEDULE = HARNESS.resolve("sweep/SubjectSchedule.java");

    /** The harness's frame renderer, which arms each frame of a schedule. */
    private static final @NotNull Path HARNESS_FRAMES = HARNESS.resolve("frame/AnimatedEntityFrameRenderer.java");

    /** The harness's entity capture, through which every posed frame is measured and drawn. */
    private static final @NotNull Path HARNESS_CAPTURE = HARNESS.resolve("frame/EntityFrameRenderer.java");

    /** The harness's clock, which answers the armed frame's tick, walk and clocks. */
    private static final @NotNull Path HARNESS_CLOCK = HARNESS.resolve("AnimationClock.java");

    /** The harness's one-hot selections and pinned network id. */
    private static final @NotNull Path HARNESS_FIGURES = HARNESS.resolve("IdleFigures.java");

    /** The harness's mixins, every clock write among them. */
    private static final @NotNull Path HARNESS_MIXINS = HARNESS.resolve("mixin");

    /** The harness's mixin that writes the tick onto every render state and the walk onto the subject's. */
    private static final @NotNull Path HARNESS_FREEZE = HARNESS_MIXINS.resolve("FreezeAnimationStateMixin.java");

    /** The asset side's visual sweep, which reads the sidecar and holds it to its own resolution. */
    private static final @NotNull Path ASSET_SWEEP = Path.of(
        "src/visual/java/lib/minecraft/renderer/sweep/EntityAnimationParitySweep.java");

    /** The asset side's roster writer. */
    private static final @NotNull Path ASSET_ROSTER = Path.of(
        "src/visual/java/lib/minecraft/renderer/sweep/EntityScheduleRoster.java");

    /**
     * The identifiers of a second source of truth: a schedule constant, a harness copy of the figure
     * period, or a wave roster the harness evaluates.
     */
    private static final @NotNull List<String> SCHEDULE_CONSTANTS =
        List.of("FRAME_COUNT", "TICKS_PER_FRAME", "WALK_AMPLITUDE", "PERIOD_TICKS", "Continuous");

    /** Every member the roster's writer and reader spell - the contract both sides are held to. */
    private static final @NotNull List<String> ROSTER_MEMBERS = List.of(
        "format", "source_version", "subjects", "frames", "ticks_per_frame", "walk_speed", "walk_rate",
        "clocks", "coats", "traits", "adult", "baby", "idle", "walk");

    /** Every member the sidecar's writer and reader spell. */
    private static final @NotNull List<String> SIDECAR_MEMBERS = List.of(
        "format", "subjects", "entity", "age", "frames", "ticks_per_frame", "walk_speed", "walk_rate",
        "clocks", "adult", "baby");

    /** A baby scale armed in the harness - the baby flag, or a float literal of three. */
    private static final @NotNull Pattern BABY_SCALE = Pattern.compile("\\bisBaby\\b|\\b3(\\.0*)?[fF]\\b");

    /** The renderer's own import, which the roster writer never takes. */
    private static final @NotNull Pattern RENDERER_IMPORT =
        Pattern.compile("import\\s+lib\\.minecraft\\.renderer\\.EntityRenderer\\s*;");

    /** A read that takes the armed schedule into a render state - one of its clocks, or its walk. */
    private static final @NotNull Pattern SCHEDULE_READ =
        Pattern.compile("AnimationClock\\.(?:clock|walkAnimationPos|walkAnimationSpeed)\\(");

    /** The question whether the entity a render state is extracted from is the armed subject. */
    private static final @NotNull String SUBJECT_QUESTION = "AnimationClock.isSubject(entity)";

    /** The arming of one frame, naming the entity the frame draws. */
    private static final @NotNull String SUBJECT_ARMED = "AnimationClock.arm(entity, ";

    /** One call that arms a frame of a schedule, capturing the entity it arms the frame for. */
    private static final @NotNull Pattern FRAME_ARMED = Pattern.compile("\\barmAt\\(\\s*([\\w.()]+?)\\s*,");

    /** The capture's methods that extract an entity's render state to measure it or to draw it. */
    private static final @NotNull List<String> CAPTURE_EXTRACTS = List.of("measureBounds", "renderInternal");

    /** The capture's refusal, called ahead of each extract. */
    private static final @NotNull String CAPTURE_REFUSES = "refuseUnarmed(entity);";

    /** The question the refusal asks - a posed run, and an entity no frame is armed for. */
    private static final @NotNull String UNARMED = "PoseState.posed() && !AnimationClock.isSubject(entity)";

    /** A block comment, javadoc among them, or a line comment - prose that can name a call it does not make. */
    private static final @NotNull Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    @DisplayName("no schedule constant, figure period or wave roster survives beside the roster")
    void noScheduleConstantSurvives() {
        Set<Path> scanned = new TreeSet<>(List.of(
            HARNESS_SWEEP, HARNESS_FRAMES, HARNESS_CLOCK, HARNESS_FIGURES, HARNESS_FREEZE, ASSET_SWEEP));
        scanned.addAll(filesUnder(HARNESS_MIXINS));

        List<String> found = new ArrayList<>();
        for (Path file : scanned) {
            String text = read(file);
            for (String constant : SCHEDULE_CONSTANTS)
                if (Pattern.compile("\\b" + constant + "\\b").matcher(text).find()) found.add(file + " names " + constant);
        }
        assertEquals(List.of(), found,
            "a schedule the roster carries has a second source here - a frame count, a step or an amplitude "
                + "the two sides would each hold a copy of, or a figure period and a wave the harness would "
                + "evaluate where it writes what the roster names");
    }

    @Test
    @DisplayName("frame 0 is tick 0 on both sides")
    void theStripStartsAtTickZeroOnBothSides() {
        assertEquals(0, declared(read(HARNESS_SWEEP), "START_TICK", HARNESS_SWEEP),
            "the harness seats frame 0 at a tick other than zero, and tick zero is the still every "
                + "driver rests at");
        assertEquals(0, declared(read(ASSET_SWEEP), "START_TICK", ASSET_SWEEP),
            "the visual sweep seats frame 0 at a tick other than zero, and tick zero is the still every "
                + "driver rests at");
    }

    @Test
    @DisplayName("both sides spell every member of the roster and of the sidecar")
    void bothSidesSpellOneSchema() {
        List<String> missing = new ArrayList<>();
        for (Path file : List.of(ASSET_ROSTER, HARNESS_ROSTER))
            missing.addAll(unspelled(file, ROSTER_MEMBERS));
        for (Path file : List.of(HARNESS_SWEEP, ASSET_SWEEP))
            missing.addAll(unspelled(file, SIDECAR_MEMBERS));
        assertEquals(List.of(), missing,
            "a member one side writes and the other does not read under that name - the harness refuses "
                + "the roster at boot, or the visual sweep fails every row, and only a client run shows it");
    }

    @Test
    @DisplayName("both sides name one roster file and one sidecar")
    void bothSidesReadOneFile() {
        List<String> missing = new ArrayList<>();
        for (Path file : List.of(ASSET_ROSTER, HARNESS_ROSTER)) {
            String text = read(file);
            for (String spelled : List.of("resolveSibling(\"entity-schedule\")", "\"roster.json\""))
                if (!text.contains(spelled)) missing.add(file + " does not spell " + spelled);
        }
        for (Path file : List.of(HARNESS_SWEEP, ASSET_SWEEP))
            if (!read(file).contains("\"schedule.json\"")) missing.add(file + " does not spell \"schedule.json\"");
        assertEquals(List.of(), missing,
            "the two sides name different files, so the harness reads a roster nobody wrote or the visual "
                + "sweep reads a sidecar nobody recorded");
    }

    @Test
    @DisplayName("the harness arms no baby scale of its own")
    void theHarnessArmsNoBabyScale() {
        List<String> found = new ArrayList<>();
        for (Path file : List.of(HARNESS_CLOCK, HARNESS_FREEZE, HARNESS_FRAMES, HARNESS_SWEEP, HARNESS_SCHEDULE, HARNESS_ROSTER)) {
            Matcher scale = BABY_SCALE.matcher(read(file));
            while (scale.find()) found.add(file + " spells '" + scale.group() + "'");
        }
        assertEquals(List.of(), found,
            "a baby's walk clock runs three times its own pace once, as the rate the roster carries for the "
                + "baby row - a scale armed here as well walks it at nine");
    }

    @Test
    @DisplayName("only the entity a frame is armed for takes the schedule's walk and clocks")
    void onlyTheArmedSubjectTakesTheSchedule() {
        List<String> found = new ArrayList<>();
        int readers = 0;
        for (Path file : filesUnder(HARNESS_MIXINS)) {
            String code = codeOf(read(file));
            Matcher reads = SCHEDULE_READ.matcher(code);
            if (!reads.find()) continue;
            readers++;
            int asked = code.indexOf(SUBJECT_QUESTION);
            if (asked < 0 || asked > reads.start())
                found.add(file + " reads '" + reads.group() + "' without first asking " + SUBJECT_QUESTION);
        }
        if (!codeOf(read(HARNESS_FRAMES)).contains(SUBJECT_ARMED))
            found.add(HARNESS_FRAMES + " arms a frame without spelling " + SUBJECT_ARMED);

        assertTrue(readers > 0,
            "no mixin under " + HARNESS_MIXINS + " reads a clock or the walk - the pattern has drifted");
        assertEquals(List.of(), found,
            "a schedule read reaches every entity the client extracts while a frame is armed - a world mob "
                + "drawn behind the capture before the first frame is armed walks into the clock's refusal, "
                + "and one drawn under another subject's schedule reads a clock that schedule does not carry");
    }

    @Test
    @DisplayName("a posed capture refuses an entity no frame is armed for, and every frame is armed for the entity it draws")
    void aPosedCaptureTakesOnlyTheArmedSubject() {
        List<String> found = new ArrayList<>();
        String capture = codeOf(read(HARNESS_CAPTURE));
        if (!capture.contains(UNARMED))
            found.add(HARNESS_CAPTURE + " never asks " + UNARMED);
        for (String method : CAPTURE_EXTRACTS) {
            Matcher declared = Pattern.compile("\\b" + method + "\\([^)]*\\)[^{;]*\\{").matcher(capture);
            if (!declared.find()) {
                found.add(HARNESS_CAPTURE + " declares no " + method);
                continue;
            }
            int extracted = capture.indexOf("createRenderState(", declared.end());
            int refused = capture.indexOf(CAPTURE_REFUSES, declared.end());
            if (refused < 0 || extracted < 0 || refused > extracted)
                found.add(HARNESS_CAPTURE + " " + method + " extracts a render state without first calling " + CAPTURE_REFUSES);
        }

        int arms = 0;
        Matcher armed = FRAME_ARMED.matcher(codeOf(read(HARNESS_FRAMES)));
        while (armed.find()) {
            arms++;
            if (!armed.group(1).equals("entity"))
                found.add(HARNESS_FRAMES + " arms a frame for '" + armed.group(1) + "' rather than the entity it draws");
        }

        assertTrue(arms > 0, "no frame armed under " + HARNESS_FRAMES + " matched - the pattern has drifted");
        assertEquals(List.of(), found,
            "a posed frame armed for one instance and captured off another, or captured with none armed, "
                + "draws its subject as vanilla draws a stray - the walk and the clocks never reach it, nothing "
                + "refuses, and the strip records vanilla's values as ground truth");
    }

    @Test
    @DisplayName("the roster resolves through the one type a render resolves through")
    void theRosterResolvesThroughOneType() {
        String roster = read(ASSET_ROSTER);
        assertTrue(roster.contains("StyleResolution.of("),
            ASSET_ROSTER + " resolves no entry through StyleResolution.of, the resolution every render takes");
        assertFalse(RENDERER_IMPORT.matcher(roster).find(),
            ASSET_ROSTER + " imports EntityRenderer - an entry resolved through a renderer reaches past the "
                + "one type the reference tree is planned from");
        assertFalse(roster.contains("new EntityRenderer("),
            ASSET_ROSTER + " builds an EntityRenderer - an entry resolved through a renderer reaches past the "
                + "one type the reference tree is planned from");
    }

    /**
     * One {@code static final int} as the source declares it.
     *
     * @param source the file's text
     * @param constant the field name
     * @param where the file, named in the failure
     * @return the declared value
     */
    private static int declared(
        @NotNull String source, @NotNull String constant, @NotNull Path where) {

        Matcher found = Pattern.compile(
            "static\\s+final\\s+int\\s+" + Pattern.quote(constant) + "\\s*=\\s*(-?\\d+)\\s*;").matcher(source);
        assertTrue(found.find(), where + " declares " + constant + " as a plain int constant");
        return Integer.parseInt(found.group(1));
    }

    /**
     * The members one file does not spell as a quoted string, one line each.
     *
     * @param file the file to read
     * @param members the members it must spell
     * @return a line per member missing, empty where it spells all of them
     */
    private static @NotNull List<String> unspelled(@NotNull Path file, @NotNull List<String> members) {
        String text = read(file);
        List<String> missing = new ArrayList<>();
        for (String member : members)
            if (!text.contains("\"" + member + "\"")) missing.add(file + " does not spell \"" + member + "\"");
        return missing;
    }

    /** Every regular file under one directory, sorted, refusing a directory that holds none. */
    private static @NotNull List<Path> filesUnder(@NotNull Path directory) {
        assertTrue(Files.isDirectory(directory), directory + " is expected beside this build");
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            assertFalse(files.isEmpty(), directory + " holds no file");
            return files;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** One source with every comment taken out, so a call is matched only where the code makes it. */
    private static @NotNull String codeOf(@NotNull String source) {
        return COMMENT.matcher(source).replaceAll("");
    }

    private static @NotNull String read(@NotNull Path path) {
        try {
            assertTrue(Files.isRegularFile(path), path + " is expected beside this build");
            return Files.readString(path);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

}
