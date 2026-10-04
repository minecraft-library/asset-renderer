package lib.minecraft.renderer.guard;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the harness's triangle dump to the layer loop its bounds walk measures with.
 *
 * <p>The {@code [PX] TRI} trace armed by {@code -PentityPixelDump} exists to tell chain drift from
 * rasterizer coverage, and it can do so only while it walks each layer where the bounds walk places
 * it and leaves out the layers the bounds walk leaves out - the wings moved back an eighth of a block,
 * an unworn equipment layer skipped. Two loops each carrying those decisions part the moment one of
 * them changes, and nothing else notices: the dump is off on every reference run, so neither a
 * reference nor a parity row moves when it goes wrong. So both walks are held to the one loop, and
 * the loop to the decisions. The same holds for posing: on a posed sweep the render poses each model
 * at the frame's tick, so a walk that decides otherwise lists corners the frame never draws, and both
 * walks are held to the one switch that decides it.
 *
 * <p>Read out of the harness SOURCE as text. The harness is a separate Gradle build with no test
 * source set and nothing on this classpath, so its methods cannot be called from here. Comments and
 * string literals are blanked before anything is matched, so neither can supply a call the code
 * does not make.
 */
@DisplayName("the harness triangle dump and bounds walk")
class HarnessLayerLoopTest {

    /** The walker, in the harness's own build, reachable from the renderer root as a path alone. */
    private static final @NotNull Path WALKER = Path.of(
        "harness/src/client/java/lib/minecraft/refharness/frame/EntityBoundsWalker.java");

    /** The one loop both walks run the layers through. */
    private static final @NotNull String SHARED_LOOP = "forEachDrawnLayerModel";

    /** The two walks that visit a living renderer's model layers. */
    private static final @NotNull List<String> WALKS = List.of("walkLayerExtents", "dumpTrianglesIfRequested");

    /** What a walk that runs a layer loop of its own reaches for. */
    private static final @NotNull List<String> OWN_LOOP =
        List.of("layersOf(", "isLayerActiveForState(", "findLayerModels(");

    /** What the shared loop decides for both: which layers draw, which sit unworn, and where the wings sit. */
    private static final @NotNull List<String> DECISIONS =
        List.of("layersOf(", "isLayerActiveForState(", "reflectLayerType(", "WINGS_BACK_SHIFT", "findLayerModels(");

    /** The one switch that says whether a walk poses a model before visiting it. */
    private static final @NotNull String POSING = "posesBeforeWalking(";

    @Test
    @DisplayName("walk the layers through one loop, which alone decides whether a layer is walked and where")
    void bothWalksShareOneLayerLoop() {
        String code = code(read(WALKER));
        for (String walk : WALKS) {
            String body = body(code, walk);
            assertTrue(body.contains(SHARED_LOOP + "("), walk + " walks the layers through " + SHARED_LOOP);
            for (String call : OWN_LOOP)
                assertFalse(body.contains(call), walk + " runs no layer loop of its own, yet calls " + call);
        }
        String loop = body(code, SHARED_LOOP);
        for (String decision : DECISIONS)
            assertTrue(loop.contains(decision), SHARED_LOOP + " makes the decision " + decision + " stands for");
    }

    @Test
    @DisplayName("pose a model before visiting it exactly where the bounds walk does")
    void bothWalksPoseByOneSwitch() {
        String code = code(read(WALKER));
        for (String walk : WALKS)
            assertTrue(body(code, walk).contains(POSING), walk + " decides whether to pose by " + POSING);
    }

    /**
     * Finds the body of one {@code void} method the walker declares.
     *
     * @param code the walker's text, comments and literals blanked
     * @param method the method's name
     * @return the text between the method's opening brace and the brace that closes it
     */
    private static @NotNull String body(@NotNull String code, @NotNull String method) {
        Matcher declared = Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(").matcher(code);
        assertTrue(declared.find(), WALKER + " declares " + method);
        int open = code.indexOf('{', declared.end());
        int depth = 0;
        for (int at = open; at < code.length(); at++) {
            char c = code.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(open + 1, at);
        }
        throw new AssertionError(method + " never closes in " + WALKER);
    }

    /**
     * Drops every comment and empties every string and character literal, so what is left is only what
     * the code does.
     *
     * @param source the file's text
     * @return the text with comments and literal contents gone
     */
    private static @NotNull String code(@NotNull String source) {
        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                at = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                at++;
                while (at < source.length() && source.charAt(at) != c)
                    at += source.charAt(at) == '\\' ? 2 : 1;
                at++;
                out.append(c).append(c);
            } else {
                out.append(c);
                at++;
            }
        }
        return out.toString();
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
