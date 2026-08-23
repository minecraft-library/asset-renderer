package lib.minecraft.renderer.visual;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample schedule the animated sweep is rendered on, held equal across the two repositories.
 *
 * <p>The harness renders frame {@code N} of each subject at tick {@code START_TICK + N *
 * TICKS_PER_FRAME} and the asset side poses frame {@code N} at the tick it computes the same way. So
 * the pairing of a frame to a tick is a fact both sides hold a copy of, and the copies were held
 * together by a javadoc sentence on each saying the other MUST match.
 *
 * <p><b>What a mis-edit costs is not a missing frame but a silent one.</b> Both sides still render
 * eight images and the diff still pairs them by index; what moves is which tick each side drew, so
 * the sweep reports the disagreement as pose error, spread across every subject that animates at
 * all. There is no shape to that failure - it looks exactly like the mechanism being wrong.
 *
 * <p>Read out of both SOURCES as text rather than off either side's field. The harness is a separate
 * Gradle build with no test source set and nothing on this classpath, so its constant cannot be
 * referenced; and reading the asset side's own field while parsing the harness's would make one of
 * them the authority, when what is owed is that the two agree.
 */
@DisplayName("the animated sweep's sample schedule")
class EntityAnimationScheduleTest {

    /** The harness's copy, in its own build, reachable from the renderer root as a path and no other way. */
    private static final @NotNull Path HARNESS = Path.of(
        "harness/src/client/java/lib/minecraft/refharness/sweep/EntityAnimationSweep.java");

    /** The asset side's copy, which drives the pose and the diff. */
    private static final @NotNull Path ASSET = Path.of(
        "src/test/java/lib/minecraft/renderer/visual/TestEntityAnimationParityVanilla.java");

    /** The three the schedule is built out of; a frame's tick is a function of exactly these. */
    private static final @NotNull List<String> SCHEDULE =
        List.of("FRAME_COUNT", "TICKS_PER_FRAME", "START_TICK");

    @Test
    @DisplayName("names the same tick for every frame on both sides")
    void bothRepositoriesAgree() {
        String harness = read(HARNESS);
        String asset = read(ASSET);

        for (String constant : SCHEDULE)
            assertEquals(declared(harness, constant, HARNESS), declared(asset, constant, ASSET),
                constant + " pairs a frame with a tick, and the two repositories hold one copy each");

        // The schedule itself rather than its three parts, because that is what a reference and a
        // render have to agree on and it is the thing a reader can check against a file on disk.
        int frames = declared(asset, "FRAME_COUNT", ASSET);
        int step = declared(asset, "TICKS_PER_FRAME", ASSET);
        int start = declared(asset, "START_TICK", ASSET);
        assertTrue(frames > 1, "a strip of one frame measures nothing about a pose that moves");
        assertEquals(List.of(0, 3, 6, 9, 12, 15, 18, 21),
            IntStream.range(0, frames).map(frame -> start + frame * step).boxed().toList(),
            "the ticks the reference tree on disk was rendered at");
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

    private static @NotNull String read(@NotNull Path path) {
        try {
            assertTrue(Files.isRegularFile(path), path + " is expected beside this build");
            return Files.readString(path);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

}
