package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Proves the {@code parity-gate} skill's two generated reference files are still what their JSON
 * renders to.
 *
 * <p>A stale reference is worse than a missing one: it reads as current and tells a reader which
 * gates can see their change, so it is exactly the file that must not be allowed to drift. The
 * mechanism has to be a test rather than a rule, and it runs in the fast suite with no pipeline and
 * no network.
 *
 * <p>Third of three generated-file gates, each covering a different destination:
 * {@link ParityIndexTest} relates the Java roster to {@code index.json}, {@link ParityViewsTest}
 * gates the store's own markdown views, and this one gates the skill's reference files.
 *
 * <p>One check is about the gate rather than the files: the references are read by filesystem path,
 * so Gradle learns of them from the build file's declaration alone, and a declaration naming a
 * different real directory leaves this suite UP-TO-DATE over the edit it exists to catch.
 *
 * <p>Passing {@code -Dasset.parity.regenerateViews=true} makes it <b>write</b> the references rather
 * than assert against them, through the same code path for {@code ParityViewsTest}'s reason: a
 * regeneration that went through a second renderer could produce a file this test then rejects. That
 * flag is the <b>only</b> arm that writes, and an absent reference fails rather than reappearing.
 */
@DisplayName("The generated parity-gate references regenerate from their JSON source")
final class ParityReferencesTest {

    /** Set on the command line to rewrite the references rather than assert against them. */
    private static final boolean REGENERATE = Boolean.getBoolean("asset.parity.regenerateViews");

    /**
     * The word a count of twenty or more opens with.
     *
     * <p>Twenty is where the line falls, and it is measured rather than chosen: the reach map's own
     * rendered prose counts <b>one artifact</b> either side of a change and the <b>two artifacts</b>
     * a rule is defined over, so a vocabulary reaching below twenty fires on sentences that were
     * never a population. Above it nothing in these files counts in words except a population, which
     * is what makes the whole band admissible.
     */
    private static final List<String> COUNT_TENS = List.of("twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety");

    /**
     * A population of artifacts written as a number, which is the one thing no generated file may
     * say.
     *
     * <p>In digits any count at all, and in words any count from twenty up, the hyphenated tail
     * included. The asymmetry is the band above: digits before the word are a population and read as
     * nothing else, where a word is one only once it is larger than anything these files legitimately
     * count.
     *
     * <p>A form and not a value. Nothing here is derived from the roster's own size, so no entry
     * goes unmatchable when that size moves - which is exactly how the four literals fitted to a
     * roster of sixty went inert, and how a spelling of that one size would go inert after them. A
     * count of twenty or more is caught whether it is the current one or a stale one.
     */
    private static final Pattern ARTIFACT_COUNT = Pattern.compile("(?i)\\b(?:\\d+|(?:"
        + String.join("|", COUNT_TENS) + ")(?:-[a-z]+)?)\\s+(?:artifacts?|registered)\\b");

    /**
     * One phrase per word the vocabulary holds, in the grammar the guard reads.
     *
     * <p>The vocabulary read against something that is not the vocabulary. Building the guard's
     * subject out of the same list the guard was built from asserts nothing about either: it holds
     * for any regex-inert string, so a misspelt entry leaves the word matching nothing real with the
     * self-test green. These are written out a second time as whole phrases, so that entry fails
     * here instead.
     *
     * <p>None of them is the roster's own size, which is what keeps them from being the four fitted
     * literals over again: the roster moving leaves every one of them still exercising the same
     * word.
     */
    private static final List<String> COUNT_SPECIMENS = List.of(
        "twenty artifacts", "thirty registered", "forty artifacts", "fifty registered",
        "sixty artifacts", "seventy registered", "eighty artifacts", "ninety-one registered");

    /**
     * Every file a renderer in this package writes, which is what the count guard below has to read.
     *
     * <p>Not one source: {@code artifacts.md} and the store's own {@code README.md} are rendered
     * from the index, and {@code blindness.md} from the reach map. The list is by output file
     * because that is what the guard's rule is about - a population typed into shipped text is the
     * same defect whichever JSON the file was rendered from.
     *
     * <p>Named rather than counted, because that guard's operand is two renderers joined - the
     * skill's references and the store's own views - and any check that only asks whether the join
     * is populated is satisfied by either side alone. Dropping one renderer then leaves the other's
     * files in the map, the guard reads half of what it exists for and nothing changes colour, which
     * is the same shape as the literal counts it replaced. A fourth generated file is a decision
     * somebody takes here.
     */
    private static final List<String> GENERATED_FILES =
        List.of("artifacts.md", "blindness.md", "README.md");

    /** One inline code span, whatever it holds. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /** What a Gradle task name looks like, which is what tells one from a flag or a class in prose. */
    private static final Pattern TASK_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    /** The heading over the artifact reference's table of tasks no artifact id covers. */
    private static final String NO_ID_TASK_HEADING = "## Tasks that carry no artifact id";

    @Test
    @DisplayName("every generated reference regenerates byte-identically")
    void referencesAreRegenerable() {
        for (Map.Entry<String, String> rendered : ParityReferences.renderAll().entrySet()) {
            Path file = ParityReferences.HOME.resolve(rendered.getKey());

            if (REGENERATE) {
                write(file, rendered.getValue());
                continue;
            }

            // Absence is a failure, not a bootstrap. A reference the skill loads by path and this
            // suite quietly re-creates is one a rename can move with nothing going red, so the run
            // could report a STALE reference and never a MISSING one.
            assertThat("the skill holds no " + rendered.getKey() + "; it is a tracked file and this "
                    + "suite does not create one. Restore it, or regenerate with "
                    + ParityReferences.REGEN_COMMAND,
                Files.isRegularFile(file), is(true));
            assertThat(rendered.getKey() + " is stale; regenerate with "
                    + ParityReferences.REGEN_COMMAND,
                ParityStore.readNormalized(file), equalTo(rendered.getValue()));
        }
    }

    @Test
    @DisplayName("the artifact reference names every registered artifact")
    void everyArtifactAppearsInTheReference() {
        // The population check the byte comparison above cannot make: two files can agree with each
        // other and both be missing a row. This is what makes coining a 60th artifact reach the
        // skill rather than only the store.
        String artifacts = ParityReferences.render("artifacts.md");
        for (ParityArtifacts.Registration registration : ParityArtifacts.ALL)
            assertThat("the skill's artifact reference omits a registered artifact",
                artifacts, containsString("`" + registration.id() + "`"));
    }

    @Test
    @DisplayName("the blindness reference names every rule and every no-reach glob")
    void everyRuleAppearsInTheReference() {
        String blindness = ParityReferences.render("blindness.md");
        var map = ParityStore.read("roster.blindness-rules");
        for (var rule : map.getAsJsonArray("rules"))
            assertThat("the skill's blindness reference omits a rule", blindness,
                containsString("## " + rule.getAsJsonObject().get("id").getAsString() + " -"));
        for (var entry : map.getAsJsonArray("no_reach")) {
            var declaration = entry.getAsJsonObject();
            assertThat("the skill's blindness reference omits a no_reach glob", blindness,
                containsString("### `" + declaration.get("glob").getAsString() + "`"));
            // The glob alone was what this asserted, and a glob with no mechanism beside it is the
            // shape the entry is not allowed to have: the reference would list the path and leave a
            // reader with no way to tell a decision from an oversight.
            assertThat("the skill's blindness reference omits a no_reach reason", blindness,
                containsString(declaration.get("reason").getAsString()));
            assertThat("the skill's blindness reference omits a no_reach probe", blindness,
                containsString(declaration.get("probe").getAsString()));
        }
    }

    @Test
    @DisplayName("no generated file prints an artifact count as a literal")
    void noReferenceRestatesTheCount() {
        // The count lives in index.json and nowhere else: a literal in shipped text went stale three
        // times over in the design documents before a line of code was written.
        //
        // A pattern over the FORM of a count rather than any count in particular. Fitted strings
        // were what this was: the current size and its predecessor, spelled out, so one more
        // artifact made every one of them unmatchable and the guard went inert WITHOUT changing
        // colour - the exact failure mode it exists to catch, one level up. Nothing below is a
        // function of the roster's size, so nothing below moves when that size does. The store's own
        // README is rendered from the same index by the sibling renderer and carries the same risk,
        // so it is read here rather than left to a second copy of this case.
        Map<String, String> generated = new LinkedHashMap<>(ParityReferences.renderAll());
        for (String view : ParityViews.TRACKED) generated.put(view, ParityViews.render(view));

        List<String> restated = new ArrayList<>();
        for (Map.Entry<String, String> rendered : generated.entrySet()) {
            Matcher literal = ARTIFACT_COUNT.matcher(rendered.getValue());
            while (literal.find()) restated.add(rendered.getKey() + ": " + literal.group());
        }

        assertThat("the generated files this case reads, against the ones it is written for. Two "
            + "renderers are joined to build that list and a check on whether the join is populated "
            + "is answered by either of them: drop the references and the store's README is still "
            + "there, so the case stops reading the two files it was written for and stays green",
            List.copyOf(generated.keySet()), equalTo(GENERATED_FILES));
        assertThat("the guard's digit form, exercised on the roster's own size. It is the half that "
            + "needs no vocabulary and so catches a stale count as well as a current one, and a "
            + "pattern that stopped matching a count in digits would report all three files clean",
            ARTIFACT_COUNT.matcher(ParityArtifacts.ALL.size() + " artifacts").find(), is(true));
        assertThat("and its word form, on a phrase per word the vocabulary holds. Each is written "
                + "out here rather than assembled from that vocabulary, which is the whole of what "
                + "the check is: a subject built from the same list the pattern was built from "
                + "matches for any string at all and says nothing about either, so a misspelt entry "
                + "would leave the word half matching nothing real and this green",
            COUNT_SPECIMENS.stream().filter(specimen -> !ARTIFACT_COUNT.matcher(specimen).find())
                .toList(), is(empty()));
        assertThat("and the words above, against the phrases exercising them. A word no phrase "
                + "opens with is one the clause above never reads, and a vocabulary entry nothing "
                + "reads is the going-quietly-unmatchable this whole case is about",
            COUNT_TENS.stream().filter(word -> COUNT_SPECIMENS.stream()
                .noneMatch(specimen -> specimen.startsWith(word))).toList(), is(empty()));
        assertThat("generated files restating the artifact count as a literal. The count is a "
            + "property of index.json, and a number typed beside the word is one nothing re-derives "
            + "- it reads as current for exactly as long as the roster does not move, in digits or "
            + "written out", restated, is(empty()));
    }

    @Test
    @DisplayName("every task the artifact reference names as carrying no id is a registered task")
    void theTasksWithNoArtifactIdAreRealTasks() {
        Set<String> registered = registeredTaskNames();
        List<String> named = new ArrayList<>();
        for (String row : taskColumn(ParityReferences.render("artifacts.md"))) {
            Matcher token = BACKTICKED.matcher(row);
            while (token.find())
                if (TASK_NAME.matcher(token.group(1)).matches()) named.add(token.group(1));
        }
        List<String> unregistered = named.stream().filter(name -> !registered.contains(name)).toList();

        assertThat("the build forwarded no task names, so this case has no operand and passes on an "
            + "empty registry whatever the table says", registered, is(not(empty())));
        assertThat("the table's task column names nothing, which would satisfy the check below "
            + "vacuously", named, is(not(empty())));
        assertThat("names in the task column of \"tasks that carry no artifact id\" that this build "
            + "registers no task for. The table's whole purpose is that a reader does not conclude a "
            + "task was forgotten, so a row naming a task that was renamed away answers a question "
            + "about nothing - and the strings are literals in the renderer, so regenerating the "
            + "file reproduces them and the byte comparison above pins them green",
            unregistered, is(empty()));
    }

    /**
     * The first cell of every body row of the artifact reference's no-id task table.
     *
     * <p>That column and not the whole table: the second column is prose, and prose legitimately
     * names Java methods and other skills in backticks, which are not tasks and never were.
     *
     * @param artifacts the rendered {@code artifacts.md}
     * @return the cells, in table order
     */
    private static List<String> taskColumn(String artifacts) {
        List<String> out = new ArrayList<>();
        boolean inTable = false;
        for (String line : artifacts.split("\n", -1)) {
            if (line.startsWith("## ")) inTable = line.equals(NO_ID_TASK_HEADING);
            if (!inTable || !line.startsWith("| ")) continue;
            String cell = line.substring(1, line.indexOf('|', 1)).trim();
            if (cell.equals("task") || cell.startsWith("---")) continue;
            out.add(cell);
        }
        return out;
    }

    /**
     * Every task name this build registers, as the build itself answers.
     *
     * <p>Forwarded by the build rather than parsed out of it, because two of the rows this gates are
     * tasks a plugin provides and the build file spells neither: a check sourced from the
     * registrations in that file would answer with a subset and pass by not looking at them.
     *
     * @return the names
     */
    private static Set<String> registeredTaskNames() {
        String forwarded = System.getProperty("asset.parity.task.names", "");
        if (forwarded.isBlank()) return Set.of();
        return Set.of(forwarded.split(","));
    }

    @Test
    @DisplayName("the build file's references path is this one, and holds the files it claims")
    void theBuildFileNamesTheSameReferenceHome() {
        String declared = buildFileValue("paritySkillReferences");

        assertThat("the path build.gradle.kts declares as this suite's input, against the one this "
                + "suite reads. Gradle refuses a declared directory that does not exist, so what "
                + "survives silently is a path naming a real directory that is not this one - and "
                + "then editing a reference leaves the task UP-TO-DATE and the staleness unread",
            declared, equalTo(ParityReferences.HOME.toString().replace('\\', '/')));
        for (String generated : ParityReferences.GENERATED)
            assertThat("the declared path holds no such file, so the declaration watches a "
                    + "directory this suite does not read: " + declared + "/" + generated,
                Files.isRegularFile(Path.of(declared).resolve(generated)), is(true));
    }

    /**
     * The string a top-level {@code val} of the build file is assigned.
     *
     * @param name the declaration's name
     * @return its literal value
     */
    private static String buildFileValue(String name) {
        String build;
        try {
            build = Files.readString(Path.of("build.gradle.kts"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Matcher matcher = Pattern.compile("val " + Pattern.quote(name) + "[^=\\n]*= \"([^\"]+)\"")
            .matcher(build);
        if (!matcher.find()) throw new AssertionError("build.gradle.kts declares no " + name);
        return matcher.group(1);
    }

    /**
     * Writes a reference, LF and UTF-8, creating the directory when absent.
     *
     * @param file the reference file
     * @param text its rendered text
     */
    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
