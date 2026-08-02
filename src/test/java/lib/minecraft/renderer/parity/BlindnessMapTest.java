package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Checks that the blindness map still refers to real things.
 *
 * <p>The map cannot verify its own <b>claims</b>: "BlockRenderer never calls buildBox" is a
 * statement about the code that only its probe re-establishes. What can be checked mechanically is
 * referential integrity, and that is enough to catch the failure that actually happens - a rename or
 * a package move silently orphaning a rule, so a gate quietly stops being consulted about a path
 * nobody realises moved.
 *
 * <p>Two of the checks are about the split between the two answers a plan carries: {@code sees}
 * states what a change moves, whatever home the artifact lives in, and the capture set is the part of
 * it the store holds a file for. Both are assertions about the map's own {@code sees} lists, so they
 * belong to the suite that owns the map; the build file is their second operand and is read here as
 * text. Those two registries disagreeing is the failure - a rule reaching an artifact the capture
 * table has no row for refuses the capture at configuration time, and every gate on that path with
 * it.
 *
 * <p>The rest read the build file for a different reason: the walk below decides which files this
 * suite sees, and Gradle decides whether it runs at all from the same list of directories and the
 * same roots. Only one of those two answers is observable from here, so the other is asserted against
 * the text - the skip lists, the roots the trigger globs need, and the input declarations that carry
 * them. A guard whose own precondition is undeclared is a guard that stops firing the moment the task
 * is called UP-TO-DATE, which is exactly the day something moved.
 *
 * <p>What fails when a rule goes stale in a way no test can see - the claim is wrong but its paths
 * still exist - is the gate itself: the artifact the rule called blind moves, and the compare reports
 * an unexpected mover on an artifact a rule called blind. That is a loud failure at the moment it
 * matters, and it names the rule to fix.
 */
@DisplayName("The blindness map refers only to real paths and registered artifacts")
final class BlindnessMapTest {

    /** The main-source tree whose every file some rule has to speak about. */
    private static final Path MAIN_SOURCE = Path.of("src/main/java/lib/minecraft/renderer");

    /** The modes a rule may declare. */
    private static final Set<String> MODES = Set.of("select", "demote", "suppress");

    /**
     * The directories the repository walk skips, at any depth.
     *
     * <p>Gitignored, so no trigger glob is written against one and walking them would add six figures
     * of cache files for nothing. The build file excludes the same names from the file collection it
     * declares as this suite's inputs, and the two parting company is silent in both directions: a
     * name only here leaves Gradle watching files the walk never reads, and a name only there leaves
     * an edit that changes this suite's answer with the task UP-TO-DATE.
     */
    private static final Set<String> WALK_SKIPS = Set.of(".git", ".gradle", ".idea", "build",
        "cache", "texturepacks", ".jmh", "__pycache__");

    /**
     * The filesystem inputs the {@code test} task declares, against the operand each is declared on.
     *
     * <p>Every one of them is read by a guard in this package off the filesystem rather than the
     * classpath, so Gradle learns about it from the declaration alone. Dropping one leaves the guard
     * green over the edit it exists to catch, because the task is never re-run: it is the same defect
     * as an unguarded rule, one level up.
     */
    private static final Map<String, String> DECLARED_INPUTS = Map.of(
        "parityStore", "parityProductionStore",
        "parityBuildFile", "\"build.gradle.kts\"",
        "parityTriggerRoots", "parityTriggerRoots",
        "paritySkillReferences", "paritySkillReferences");

    /**
     * The roots a triggered file may sit under that {@code parityTriggerRoots} does not name.
     *
     * <p>The build file is its own declared input, and the two source trees reach the task as
     * compiled classes and processed resources. Those routes are not observable from here, which is
     * why they are written down rather than derived - and why the collection exists at all for
     * everything else.
     */
    private static final List<String> CLASSPATH_ROOTS =
        List.of("build.gradle.kts", "src/main", "src/test");

    @Test
    @DisplayName("every trigger glob matches a file that exists at HEAD")
    void noRuleIsOrphaned() {
        List<String> repoFiles = repoFiles();
        List<String> orphaned = new ArrayList<>();
        for (JsonObject rule : rules()) {
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                if (repoFiles.stream().noneMatch(path -> pattern.matcher(path).matches()))
                    orphaned.add(rule.get("id").getAsString() + " -> " + glob.getAsString());
            }
        }
        assertThat("trigger globs matching nothing at HEAD - a rename orphaned the rule, so the gate "
            + "it speaks for silently stopped being consulted", orphaned, is(empty()));
    }

    @Test
    @DisplayName("every sees and blind value is a registered artifact id")
    void everyArtifactReferenceResolves() {
        List<String> unknown = new ArrayList<>();
        for (JsonObject rule : rules())
            for (String field : List.of("sees", "blind"))
                for (JsonElement id : rule.getAsJsonArray(field))
                    if (!ParityArtifacts.BY_ID.containsKey(id.getAsString()))
                        unknown.add(rule.get("id").getAsString() + "." + field + " -> " + id.getAsString());

        assertThat("artifact ids no roster registers; a typo or a retired artifact leaves a rule "
            + "pointing at nothing", unknown, is(empty()));
    }

    @Test
    @DisplayName("every artifact a plan can name has a capture row")
    void everyPlannableArtifactCanBeCaptured() {
        Set<String> store = Set.copyOf(ParityArtifacts.withHome(ParityArtifacts.Home.STORE));
        Set<String> rows = captureRows();
        List<String> plannable = reach().stream().filter(store::contains).sorted().toList();
        List<String> unrunnable = plannable.stream().filter(id -> !rows.contains(id)).sorted().toList();

        assertThat("the build file's artifact table did not parse, which would leave this check "
            + "vacuous", rows, is(not(empty())));
        assertThat("no reached artifact is store-homed, so this check has nothing to be true of - "
            + "an empty operand satisfies it whatever the capture table holds", plannable,
            is(not(empty())));
        assertThat("store-homed artifacts a rule's sees names that the build file has no capture row "
            + "for. The roster admits store artifacts no producer captures - the index the promotion "
            + "writes, the map itself - so a rule reaching one of those refuses the capture at "
            + "configuration time exactly as a pointer would", unrunnable, is(empty()));
    }

    @Test
    @DisplayName("the build file draws its capture set from the plan key, never from the reach answer")
    void theBuildFileReadsThePlanKey() {
        Set<String> rows = captureRows();
        List<String> unrunnable = reach().stream().filter(id -> !rows.contains(id)).sorted().toList();
        List<String> keys = planKeysRead();
        // Printed for the same reason the coverage count is: these are what the two keys differ by,
        // and a reader of this suite should see which artifacts that is.
        System.out.printf("reach names %d artifact(s) the capture table has no row for: %s%n",
            unrunnable.size(), unrunnable);

        assertThat("the build file's artifact table did not parse, which would leave this check "
            + "vacuous", rows, is(not(empty())));
        assertThat("reach names nothing the capture table lacks a row for, so either key would work "
            + "and this check has no operand; it needs a different shape rather than deleting",
            unrunnable, is(not(empty())));
        assertThat("the plan-document keys parityPlannedArtifacts reads. `sees` states what a change "
            + "moves whatever home the artifact lives in and names " + unrunnable + ", which this "
            + "table has no row for, so reading it refuses parityCapture at configuration time for "
            + "every change whose reach includes one", keys, equalTo(List.of("plan")));
    }

    @Test
    @DisplayName("the walk and the build file skip the same directories")
    void theSkipListsAgree() {
        List<String> declared = buildFileWalkSkips();

        assertThat("build.gradle.kts declares parityWalkSkips as an empty list, which would leave "
            + "this check vacuous", declared, is(not(empty())));
        assertThat("the directory names build.gradle.kts excludes from the file collection it "
            + "declares as this suite's inputs, against the ones the walk skips. Only the walk's "
            + "answer is observable from Java, so a name dropped from one side is a constant nothing "
            + "reads until the day Gradle decides this suite is UP-TO-DATE over an edit it is not",
            declared, equalTo(WALK_SKIPS.stream().sorted().toList()));
    }

    @Test
    @DisplayName("the test task declares every filesystem input its guards read")
    void theTestTaskDeclaresWhatItsGuardsRead() {
        Map<String, String> declared = testTaskInputs();

        assertThat("build.gradle.kts declares no inputs on the Test task, which would leave this "
            + "check vacuous", declared.keySet(), is(not(empty())));
        assertThat("what the Test task declares as an input, against what a guard in this package "
            + "reads off the filesystem. A declaration on any other task type does not answer: "
            + "UP-TO-DATE is decided per task, so a guard whose operand nothing declares here goes "
            + "green over the edit it exists to catch and never runs again",
            declared, equalTo(DECLARED_INPUTS));
    }

    @Test
    @DisplayName("the declared roots and the triggered files name each other exactly")
    void theDeclaredRootsCoverEveryTriggeredFile() {
        List<String> repoFiles = repoFiles();
        List<String> declared = buildFileTriggerRoots();
        List<String> reachable = new ArrayList<>(declared);
        reachable.addAll(CLASSPATH_ROOTS);
        List<String> uncovered = new ArrayList<>();
        Set<String> carrying = new LinkedHashSet<>();
        // Judged at the ROOT, which is the granularity the declaration is written at. A file tree
        // drops the gitignore family under a declared root and the opt-out is global, so a handful of
        // matched files are undeclared with their root declared; the build file records that, and no
        // trigger glob's only match is one of them.
        for (JsonObject rule : rules()) {
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                String witness = null;
                for (String path : repoFiles) {
                    if (!pattern.matcher(path).matches()) continue;
                    List<String> roots = reachable.stream().filter(root -> under(root, path)).toList();
                    // One witness per glob rather than every match: a dropped root orphans thousands
                    // of files at once and a reader needs the glob, not the enumeration.
                    if (roots.isEmpty() && witness == null) witness = path;
                    roots.stream().filter(declared::contains).forEach(carrying::add);
                }
                if (witness != null) uncovered.add(rule.get("id").getAsString() + " -> "
                    + glob.getAsString() + " (" + witness + ")");
            }
        }

        assertThat("build.gradle.kts declares parityTriggerRoots as an empty collection, which would "
            + "leave this check vacuous", declared, is(not(empty())));
        assertThat("files a trigger glob matches that no declared input reaches. The walk above "
            + "resolves those globs against the tree, so a root only the walk knows about leaves "
            + "this suite answering a question Gradle thinks it has already answered - and a rename "
            + "under that root orphans a rule with the task UP-TO-DATE", uncovered, is(empty()));
        assertThat("declared roots against the ones a trigger glob actually matches under. A root "
            + "nothing triggers on is a file collection Gradle watches for no reader, and it is what "
            + "the check above would go quietly vacuous behind",
            carrying.stream().sorted().toList(), equalTo(declared));
    }

    @Test
    @DisplayName("sees and blind are disjoint within a rule")
    void noRuleContradictsItself() {
        List<String> contradictions = new ArrayList<>();
        for (JsonObject rule : rules()) {
            Set<String> sees = strings(rule.getAsJsonArray("sees"));
            for (String id : strings(rule.getAsJsonArray("blind")))
                if (sees.contains(id))
                    contradictions.add(rule.get("id").getAsString() + " -> " + id);
        }
        assertThat("a rule that calls one artifact both seeing and blind", contradictions, is(empty()));
    }

    @Test
    @DisplayName("every rule declares a known mode and carries a non-empty reason and probe")
    void everyRuleIsSubstantive() {
        List<String> defects = new ArrayList<>();
        for (JsonObject rule : rules()) {
            String id = rule.get("id").getAsString();
            if (!MODES.contains(rule.get("mode").getAsString()))
                defects.add(id + " mode=" + rule.get("mode").getAsString());
            // An emptiness check rather than a quality one, and it is the same anti-vacuity guard the
            // golden-float pins carry: a rule with no reason cannot be re-checked and rots into
            // folklore, and one with no probe makes "the map went stale" an accusation rather than a
            // fixable statement.
            for (String field : List.of("claim", "reason", "probe", "source"))
                if (rule.get(field).getAsString().isBlank()) defects.add(id + " has a blank " + field);
        }
        assertThat("rules that state no mechanism or offer no falsification", defects, is(empty()));
    }

    @Test
    @DisplayName("the rules cover every main-source file, and the coverage is not one wide glob")
    void theMapCoversTheMainSource() {
        List<String> sources;
        try (Stream<Path> walk = Files.walk(MAIN_SOURCE)) {
            sources = walk.filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        JsonObject map = ParityStore.read("roster.blindness-rules");
        List<Pattern> noReach = new ArrayList<>();
        if (map.has("no_reach"))
            for (JsonElement glob : map.getAsJsonArray("no_reach")) noReach.add(compileGlob(glob.getAsString()));

        Set<String> covered = new LinkedHashSet<>();
        TreeMap<String, Integer> contribution = new TreeMap<>();
        for (JsonObject rule : rules()) {
            String id = rule.get("id").getAsString();
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                for (String source : sources)
                    if (pattern.matcher(source).matches()) {
                        covered.add(source);
                        contribution.merge(id, 1, Integer::sum);
                    }
            }
        }
        for (String source : sources)
            if (noReach.stream().anyMatch(pattern -> pattern.matcher(source).matches())) covered.add(source);

        List<String> uncovered = sources.stream().filter(source -> !covered.contains(source)).toList();
        // Printed rather than merely asserted: a single rule covering everything would satisfy the
        // count while telling a reader nothing, so the per-rule contribution is visible in the output.
        System.out.printf("blindness coverage: %d/%d files, %d rules contributed %s%n",
            covered.size(), sources.size(), contribution.size(), contribution);

        assertThat("main-source files no rule and no no_reach glob covers - a new package with no rule "
            + "would otherwise become UNKNOWN at gate time, which refuses every plan that touches it",
            uncovered, is(empty()));
        assertThat("coverage should come from many rules rather than one catch-all",
            contribution.size() > 1, is(true));
    }

    @Test
    @DisplayName("the worked example resolves the way the corpus records it")
    void theBoxBuilderExampleResolves() {
        // B10's own gates, which is what the map has to keep answering for that path.
        assertThat(seesFor("src/main/java/lib/minecraft/renderer/engine/kit/BlockGeometryKit.java")
                .containsAll(List.of("sweep.entity", "sweep.armor", "pin.player-crc", "manifest.player-sheets")),
            is(true));
        // And the dump is demoted for it, because B19 fires on engine/** and the dump never renders.
        assertThat(seesFor("src/main/java/lib/minecraft/renderer/engine/kit/BlockGeometryKit.java")
                .stream().noneMatch(id -> id.startsWith("manifest.dump.")),
            is(true));
    }

    /**
     * Resolves one changed path against the map, applying the same union and demotion order the
     * toolkit does.
     *
     * @param path the changed path
     * @return the artifacts that can see it
     */
    private static Set<String> seesFor(String path) {
        List<JsonObject> fired = rules().stream()
            .filter(rule -> {
                for (JsonElement glob : rule.getAsJsonArray("trigger_paths"))
                    if (compileGlob(glob.getAsString()).matcher(path).matches()) return true;
                return false;
            })
            .toList();

        Set<String> sees = new LinkedHashSet<>();
        fired.forEach(rule -> sees.addAll(strings(rule.getAsJsonArray("sees"))));
        fired.stream().filter(rule -> rule.get("mode").getAsString().equals("demote"))
            .forEach(rule -> sees.removeAll(strings(rule.getAsJsonArray("blind"))));
        return sees;
    }

    /**
     * Every artifact id some rule's {@code sees} names, which is the universe a plan is drawn from.
     *
     * @return the ids, in the order the rules name them
     */
    private static Set<String> reach() {
        Set<String> out = new LinkedHashSet<>();
        for (JsonObject rule : rules()) out.addAll(strings(rule.getAsJsonArray("sees")));
        return out;
    }

    /**
     * The artifact ids the build file's capture table has a row for.
     *
     * <p>Read out of the build file because that table is the only place a capture row is spelled,
     * and the two registries admitting different sets is the whole failure this checks for: a
     * {@code sees} list validated here against the roster, and the same ids validated there against
     * the table, went green in the fast suite and refused the gate at configuration time.
     *
     * @return the ids, in table order
     */
    private static Set<String> captureRows() {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("ParityArtifact\\(\"([^\"]+)\"").matcher(buildFile());
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    /**
     * Which keys of the plan document the build file's reader takes its capture set from.
     *
     * <p>Every subscript in that function's body rather than the one literal, so renaming its local
     * cannot make the check pass by matching nothing.
     *
     * @return the keys, in the order the reader takes them
     */
    private static List<String> planKeysRead() {
        String build = buildFile();
        int start = build.indexOf("fun parityPlannedArtifacts(");
        if (start < 0) throw new AssertionError("build.gradle.kts declares no parityPlannedArtifacts");
        int end = build.indexOf("\n}", start);
        if (end < 0) throw new AssertionError("parityPlannedArtifacts has no line-initial closing brace");
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\[\"([^\"]+)\"]").matcher(build.substring(start, end));
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    /**
     * The directory names the build file excludes from the file collection it declares as this
     * suite's inputs.
     *
     * @return the names, sorted
     */
    private static List<String> buildFileWalkSkips() {
        String build = buildFile();
        int start = build.indexOf("val parityWalkSkips");
        if (start < 0) throw new AssertionError("build.gradle.kts declares no parityWalkSkips");
        int open = build.indexOf("listOf(", start);
        int close = build.indexOf(")", open);
        if (open < 0 || close < 0) throw new AssertionError("parityWalkSkips is not a listOf(...)");
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(build.substring(open, close));
        while (matcher.find()) out.add(matcher.group(1));
        return out.stream().sorted().toList();
    }

    /**
     * The filesystem inputs the build file declares on the {@code Test} task.
     *
     * <p>Scoped to that one configuration block, because UP-TO-DATE is decided per task and the same
     * declaration on {@code JavaExec} keeps nothing fresh. The operand is kept alongside the property
     * name so a declaration renamed onto the wrong path still reads as a difference.
     *
     * @return the property name to declared operand, in declaration order
     */
    private static Map<String, String> testTaskInputs() {
        String build = buildFile();
        int start = build.indexOf("tasks.withType<Test>()");
        if (start < 0) throw new AssertionError("build.gradle.kts configures no Test task");
        int end = build.indexOf("\n}", start);
        if (end < 0) throw new AssertionError("the Test block has no line-initial closing brace");
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = Pattern
            .compile("inputs\\.(?:file|files|dir)\\(([^)]*)\\)\\.withPropertyName\\(\"([^\"]+)\"\\)")
            .matcher(build.substring(start, end));
        while (matcher.find()) out.put(matcher.group(2), matcher.group(1));
        return out;
    }

    /**
     * The roots the build file collects into this suite's declared trigger inputs.
     *
     * <p>Every string literal of that declaration, whether it names a file or the argument of a
     * {@code fileTree}, because both reach the task the same way and the distinction is Gradle's
     * rather than this check's.
     *
     * @return the roots, sorted
     */
    private static List<String> buildFileTriggerRoots() {
        String build = buildFile();
        int start = build.indexOf("val parityTriggerRoots");
        if (start < 0) throw new AssertionError("build.gradle.kts declares no parityTriggerRoots");
        int open = build.indexOf("files(", start);
        int close = build.indexOf("\n)", open);
        if (open < 0 || close < 0) throw new AssertionError("parityTriggerRoots is not a files(...)");
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(build.substring(open, close));
        while (matcher.find()) out.add(matcher.group(1));
        return out.stream().sorted().toList();
    }

    /**
     * Whether a repo-relative path is the given root or sits below it.
     *
     * <p>Segment-wise, so {@code src/jmh} does not answer for {@code src/jmhExtra}.
     *
     * @param root the declared root
     * @param path the repo-relative path
     * @return whether the root reaches the path
     */
    private static boolean under(String root, String path) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    /** The build file's text. */
    private static String buildFile() {
        try {
            return Files.readString(Path.of("build.gradle.kts"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The map's rules. */
    private static List<JsonObject> rules() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement rule : ParityStore.read("roster.blindness-rules").getAsJsonArray("rules"))
            out.add(rule.getAsJsonObject());
        return out;
    }

    /**
     * Every file in the working tree, as repo-relative POSIX paths.
     *
     * <p>The whole repository rather than one source root, because a rule may legitimately trigger on
     * the build file, the toolkit or the harness - and a check that could not see those would report
     * every such rule as orphaned. What it does not descend into is {@link #WALK_SKIPS}.
     */
    private static List<String> repoFiles() {
        try (Stream<Path> walk = Files.walk(Path.of(""))) {
            return walk
                .filter(path -> path.getNameCount() == 0
                    || Stream.iterate(path, Path::getParent)
                        .limit(path.getNameCount())
                        .noneMatch(part -> WALK_SKIPS.contains(part.getFileName().toString())))
                .filter(Files::isRegularFile)
                .map(path -> path.toString().replace('\\', '/'))
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Compiles one repo-relative glob, in the grammar the toolkit's own resolver uses.
     *
     * <p>{@code **} spans path segments; {@code *} and {@code ?} do not. The two implementations are
     * deliberately the same grammar written twice rather than one shared through a file, because a
     * map whose two readers disagree about what a glob matches is worse than no map - this test would
     * pass on a rule the planner never fires.
     *
     * @param glob the glob
     * @return its pattern
     */
    private static Pattern compileGlob(String glob) {
        StringBuilder out = new StringBuilder("^");
        int index = 0;
        while (index < glob.length()) {
            if (glob.startsWith("**/", index)) {
                out.append("(?:.*/)?");
                index += 3;
            } else if (glob.startsWith("**", index)) {
                out.append(".*");
                index += 2;
            } else if (glob.charAt(index) == '*') {
                out.append("[^/]*");
                index++;
            } else if (glob.charAt(index) == '?') {
                out.append("[^/]");
                index++;
            } else {
                out.append(Pattern.quote(String.valueOf(glob.charAt(index))));
                index++;
            }
        }
        return Pattern.compile(out.append("$").toString());
    }

    /** A JSON string array as a set. */
    private static Set<String> strings(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement element : array) out.add(element.getAsString());
        return out;
    }

}
