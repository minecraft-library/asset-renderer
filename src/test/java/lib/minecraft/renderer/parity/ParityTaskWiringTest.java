package lib.minecraft.renderer.parity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pins the build-file wiring the parity refusals rest on, none of which any run of the suite reaches.
 *
 * <p>Every statement read here is one a Gradle build has no unit to fail in, and every one of them
 * restores a defect end to end when it goes: an ordering edge gone and a promotion certifies itself
 * against the store it has just been written into; a value-blind flag reader and
 * {@code -Pbootstrap=false} turns bootstrap on; the task-name matcher gone and {@code parityCapt}
 * leaves every self-capturing producer UP-TO-DATE over the root the erase has just emptied, so the
 * step after them fails on a file nothing rewrote; the override forwarding gone and the one answer
 * to the dirty-tree refusal is unreachable from a command line; the run-count forwarding gone and
 * an operator's claim below the determinism floor is replaced by the floor that refusal exists to
 * enforce. Each left both suites green.
 *
 * <p>So the statements are read out of the build file and asserted. That is what is available -
 * exercising them needs a Gradle build, and the alternative is the state these landed in, where the
 * only thing that ever saw them was a hand-typed {@code --dry-run}. The build file is declared as
 * an input to this suite, so an edit to it re-runs these rather than leaving them UP-TO-DATE.
 *
 * <p>Each function is asserted <b>whole</b>, whitespace collapsed. A predicate that still reads the
 * start parameter and then answers differently is the exact shape of every defect above, and a
 * containment test on the part that did not change passes on all of them. Where a statement sits
 * inside a body too large to assert whole, what is read is the enclosing declaration rather than
 * the file: the same statement in the wrong body is a forwarding that reaches nothing.
 */
@DisplayName("the build file wires the parity refusals it says it wires")
final class ParityTaskWiringTest {

    /** The boolean-option reader, whose presence-only form turned {@code -Pname=false} into ON. */
    private static final String READS_A_BOOLEAN_OPTION =
        "fun parityFlag(name: String): Boolean = "
            + "gradle.startParameter.projectProperties.containsKey(name) "
            + "&& parityProperty(name) != \"false\"";

    /** The requested-task predicate, which has to decide a token the way Gradle itself will. */
    private static final String RESOLVES_THROUGH_GRADLES_MATCHER =
        "fun parityTaskRequested(name: String): Boolean = "
            + "gradle.startParameter.taskNames.any { typed -> "
            + "val token = typed.substringAfterLast(':') "
            + "typed == name || typed.endsWith(\":$name\") || "
            + "(token.isNotEmpty() && "
            + "org.gradle.util.internal.NameMatcher().find(token, listOf(name)) != null) }";

    /** The refusal hook, which asks the resolved graph rather than the tokens that were typed. */
    private static final String REFUSES_OFF_THE_RESOLVED_GRAPH =
        "fun Task.refuseWhenScheduled(refuse: () -> Unit) { "
            + "val scheduled = path "
            + "project.gradle.taskGraph.whenReady { if (hasTask(scheduled)) refuse() } }";

    /** How the forwarder is declared, which is as an extension and so not by its name alone. */
    private static final String DECLARES_THE_FORWARDER =
        "fun org.gradle.process.JavaForkOptions.forwardAssetProperties()";

    /** The forwarder, which is what puts the daemon's asset flags onto a fork. */
    private static final String FORWARDS_EVERY_ASSET_PROPERTY =
        "fun org.gradle.process.JavaForkOptions.forwardAssetProperties() = "
            + "System.getProperties().forEach { k, v -> "
            + "val key = k.toString() "
            + "if (key.startsWith(assetPropertyPrefix)) systemProperty(key, v.toString()) }";

    /** The recorder, which reads the daemon's asset flags and is what a capture stamps. */
    private static final String RECORDS_EVERY_ASSET_PROPERTY =
        "fun assetPropertiesInForce(): Map<String, String> = "
            + "sortedMapOf<String, String>().also { found -> "
            + "System.getProperties().forEach { key, value -> "
            + "val name = key.toString() "
            + "if (name.startsWith(assetPropertyPrefix)) found[name] = value.toString() } }";

    /** The one guarded spelling of the run-count forwarding, which every site has to be. */
    private static final String FORWARDS_THE_RUN_COUNT = "runs?.let { add(\"--runs\"); add(it) }";

    /** What an unguarded forwarding would still have to contain, so the two can be counted apart. */
    private static final String NAMES_THE_RUN_COUNT = "add(\"--runs\")";

    /** The capture step's registration, which is the body that argv reaches a capture through. */
    private static final String REGISTERS_A_CAPTURE_STEP =
        "fun TaskContainer.registerParityCapture(";

    /** The harness-run registration, whose mode argument is what a capture stamps. */
    private static final String REGISTERS_A_HARNESS_RUN = "fun TaskContainer.registerHarnessRun(";

    /** The once-per-invocation erase, which every producer and every capture step runs after. */
    private static final String ERASES_THE_WORKING_ROOT = "register<Exec>(parityCaptureBeginTask) {";

    /** Where one harness run is registered, as opposed to where the registration is declared. */
    private static final String CALLS_A_HARNESS_RUN = "registerHarnessRun(\"";

    /** What a run's own description says when it writes outside the reference tree. */
    private static final String REFRESHES_NO_REFERENCE = "Refreshes no reference.";

    /** One member of a {@code parityExpect} registration, forwarded only when it was given. */
    private static final Pattern FORWARDS_AN_EXPECT_MEMBER =
        Pattern.compile("(\\w+)\\?\\.let \\{ add\\(\"--(\\w+)\"\\); add\\(it\\) \\}");

    /** One harness-run call site, capturing the HarnessMode constant it names. */
    private static final Pattern HARNESS_RUN_CALL = Pattern.compile(
        "registerHarnessRun\\(\"[^\"]+\", (?:null|\"[^\"]+\"), \"([A-Z_]+)\"");

    /** One constant of the harness's own mode enum, which is the vocabulary those names come from. */
    private static final Pattern HARNESS_MODE_CONSTANT = Pattern.compile("\n    ([A-Z][A-Z_]*)[,;]");

    /** What joins the modes of an invocation that ran more than one render into the stamped value. */
    private static final String MODE_SEPARATOR = ",";

    /** Where the harness declares that enum. */
    private static final Path HARNESS_MODE =
        Path.of("harness/src/client/java/lib/minecraft/refharness/HarnessMode.java");

    /** One artifact row of the build's table: its id and its producer list. */
    private static final Pattern ARTIFACT_ROW =
        Pattern.compile("ParityArtifact\\(\"([^\"]+)\",\\s*listOf\\(([^)]*)\\)");

    /** One quoted string, for reading a producer list apart. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** Where the skill states its own entry points, and states how many of them there are. */
    private static final Path PARITY_SKILL = Path.of(".claude/skills/parity-gate/SKILL.md");

    /** The shipped invariant, capturing the count it writes as a word. */
    private static final Pattern SKILL_TASK_COUNT =
        Pattern.compile("The skill runs exactly the (\\w+) Gradle tasks in group `parity`");

    /** One task a command line names. */
    private static final Pattern SKILL_INVOCATION = Pattern.compile("\\./gradlew (\\w+)");

    /** One fenced block of the skill, which is where a command an operator is handed to run lives. */
    private static final Pattern SKILL_FENCED_BLOCK =
        Pattern.compile("(?s)\n```[a-z]*\n(.*?)\n```\n");

    /** The words that invariant can spell its count with, each at the index it names. */
    private static final List<String> COUNT_WORDS = List.of(
        "no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

    /** The condition cell of the decision-table row that refuses a gate on stale references. */
    private static final String STALE_REFERENCES_CONDITION = "| References stale or partial |";

    /**
     * That row whole, the behaviour cell included.
     *
     * <p>The behaviour as well as the condition, because the whole content of this row is that the
     * command is <b>printed</b>: it hands a reader the most expensive single command in this
     * repository, and reworded to an imperative it licenses running the oracle refresh as a side
     * effect of gating the change measured against it. The tolerance below is read out of this text
     * and a set of task names cannot tell a print from a run, so what makes the name admissible is
     * pinned here rather than argued in a sentence nothing reads back.
     */
    private static final String REFUSING_ON_STALE_REFERENCES = STALE_REFERENCES_CONDITION
        + " Refuse (R6). Print `./gradlew renderVanillaAllReferences` and stop. Refreshing the "
        + "oracle is the operator's call, taken before the work, never a side effect of gating the "
        + "change measured against it. |";

    /**
     * The decision-table rows that hand a reader a command line rather than run one, by condition.
     *
     * <p>Two, and each earns it a different way. The refusal above prints the whole-tree re-render
     * and stops, refreshing the oracle being the operator's own act and taken before the work. The
     * row that proceeds on a rule declaring an empty {@code sees} names the gate that rule asks for
     * instead, and for the test tree and for a hand-edited baseline that gate is the fast suite.
     *
     * <p>Conditions rather than task names, because the tolerance is then read out of the rows
     * rather than typed beside them: what may carry a command outside a fenced block is one of
     * these rows, not a name one of them happens to mention, and each is held to its own text - the
     * first by the constant above, the second where the files it speaks for are counted. A row
     * reworded to run what it prints fails that pin instead of going on vouching for the name it
     * hands out.
     */
    private static final List<String> ROWS_HANDING_A_COMMAND_OVER = List.of(
        STALE_REFERENCES_CONDITION,
        "| SEES empty, and one of the fired rules declares `sees: []` |");

    /** The edge that puts this build's two cheap gates on a default verification run. */
    private static final String CHECK_SCHEDULES_THE_CHEAP_GATES =
        "named(\"check\") { dependsOn(\"paritySelfTest\", \"harnessClasses\") }";

    /** That edge's operand list, for reading back which tasks it actually names. */
    private static final Pattern CHECK_SCHEDULES =
        Pattern.compile("named\\(\"check\"\\) \\{ dependsOn\\(([^)]*)\\) \\}");

    /** The argv the harness gate hands its own wrapper, captured whole. */
    private static final Pattern HARNESS_COMMAND_LINE = Pattern.compile(
        "(?s)register<Exec>\\(\"harnessClasses\"\\) \\{.*?commandLine = buildList \\{(.*?)\n {8}}");

    /** One operand of it, read as written: a quoted literal with its quotes, an expression bare. */
    private static final Pattern ADDED_OPERAND = Pattern.compile("add\\((.+?)\\)");

    /** The one value the task registry reaches this suite as: read after configuration, forwarded. */
    private static final String FORWARDS_THE_REGISTRY =
        "val parityTaskNames = provider { tasks.names.joinToString(\",\") } "
            + "doFirst { systemProperty(\"asset.parity.task.names\", parityTaskNames.get()) }";

    /** The flag the registry is forwarded on, which is what a guard reads it back through. */
    private static final String NAMES_THE_TASK_REGISTRY = "asset.parity.task.names";

    /** What a task declares to join the group the skill's entry points live in. */
    private static final String DECLARES_THE_PARITY_GROUP = "group = \"parity\"";

    /**
     * A line comment, whose text is removed before the group declarations are attributed.
     *
     * <p>Not preceded by a colon, which is the one other place these two characters occur here: a
     * repository URL. Removing a comment is what keeps prose about a declaration from reading as
     * one, and keeps a brace inside prose out of the walk that attributes it.
     */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(?<!:)//.*$");

    @Test
    @DisplayName("a boolean option is off when it is given the value false")
    void aBooleanOptionIsValueAware() {
        assertThat("both the skill and the runbook spell these `-Pname=true`, which is the only "
                + "spelling that makes `-Pname=false` look meaningful - and a presence test reads "
                + "that as ON, so the documented way to turn one off turns it on",
            collapsed(function("parityFlag")), equalTo(READS_A_BOOLEAN_OPTION));
    }

    @Test
    @DisplayName("a requested task is resolved through Gradle's own name matcher")
    void aRequestedTaskIsMatchedTheWayGradleWill() {
        assertThat("the start parameter holds what was TYPED and Gradle expands an abbreviation "
                + "later, so a literal comparison answers no for `parityCapt` while the build goes "
                + "on to run parityCapture: it erased the working root, ran no producer, wrote a "
                + "valid index over nothing and reported success. A hand-rolled matcher is the one "
                + "replacement that must not pass here - disagreeing with Gradle about what a token "
                + "names is the defect itself",
            collapsed(function("parityTaskRequested")), equalTo(RESOLVES_THROUGH_GRADLES_MATCHER));
    }

    @Test
    @DisplayName("a refusal is raised off the resolved task graph, never off the typed tokens")
    void aRefusalAsksTheGraphWhichTasksWillRun() {
        String build = buildFile();

        assertThat("the typed tokens carry a task's OPTIONS beside its name, and which of them is "
                + "an option's value depends on that option's arity - `--task parityExpect` is a "
                + "value where `--rerun parityExpect` is the next task. Only the graph knows, "
                + "because only it has matched each token against the task that declares it",
            collapsed(toBlankLine("fun Task.refuseWhenScheduled(")),
            equalTo(REFUSES_OFF_THE_RESOLVED_GRAPH));
        assertThat("every refusal goes through it: read off the tokens instead, a task named after "
                + "a boolean task option is one the predicate misses, and the refusal it was "
                + "guarding is skipped on an invocation that goes on to run that very task",
            occurrences(collapsed(build), "refuseWhenScheduled {"), is(equalTo(3)));
        assertThat("and the promotion's own condition, which the count above cannot reach - a "
                + "count of SITES says nothing about what either one tests. The default is read "
                + "here too, because an absent -Preason is only empty while it falls back to the "
                + "empty string. Inverted, `parityPromote -Preason=x` fails the build and a bare "
                + "`parityPromote` configures clean, refused later by the toolkit on the far side "
                + "of the compare this refusal exists to precede",
            collapsed(taskBlock("parityPromote")),
            containsString("val reason = parityProperty(\"reason\") ?: \"\" "
                + "refuseWhenScheduled { if (reason.isEmpty()) throw GradleException("));
        assertThat("and the capture's refusal of the configuration cache, whose operand is read here "
                + "with it: a local spelled `configurationCache` says nothing about what it was "
                + "bound to, and the count above cannot tell three refusals apart from one written "
                + "three times. A reused configuration replays one stored set of -Dasset.* into both "
                + "the producers' forks and the capture's own record of what it measured under, "
                + "since both are read while the build is configured, so the flags typed on that run "
                + "reach neither and the record agrees with the fork - and what a dropped or "
                + "inverted condition leaves behind is a well-formed, promotable capture with "
                + "nothing in it to notice",
            collapsed(taskBlock("parityCapture")),
            containsString("val configurationCache = project.serviceOf<BuildFeatures>()"
                + ".configurationCache refuseWhenScheduled { "
                + "if (configurationCache.requested.getOrElse(false)) throw GradleException("));
        assertThat("and the token predicate keeps its one wiring caller and no second: its "
                + "declaration plus the up-to-date rule, which is the only thing an over-answer "
                + "leaves inert. A second call site is the predicate back on work that costs "
                + "something on an invocation it answered true for and Gradle then did not run",
            occurrences(build, "parityTaskRequested("), is(equalTo(2)));
        assertThat("the up-to-date rule, which over-answering only ever makes stricter",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("if (spec.selfCaptured && parityTaskRequested(\"parityCapture\")) "
                + "outputs.upToDateWhen { false }"));
        assertThat("and the producer edges behind a Callable, which Gradle resolves while it builds "
                + "the graph and only for a task the graph holds. Resolved where this task is "
                + "CONFIGURED instead, the refusal inside it fires on every invocation that merely "
                + "realizes the task to read its description - so `tasks --group parity`, the way "
                + "the group's own tasks are counted, failed on any checkout carrying no plan",
            collapsed(taskBlock("parityCapture")),
            containsString("dependsOn(Callable { "
                + "resolveParityArtifacts(parityProperty(\"artifacts\")) "
                + ".flatMap { spec -> spec.producers + parityCaptureTaskName(spec.artifact) } })"));
        assertThat("and the plan is read in that one place, so no second site can resolve it "
                + "eagerly under a token test", occurrences(build, "resolveParityArtifacts("),
            is(equalTo(2)));
    }

    @Test
    @DisplayName("a registration parityExpect cannot complete is refused rather than turned into a clear")
    void anUnderSpecifiedRegistrationIsNeverForwardedAsAClear() {
        String argv = collapsed(taskBlock("parityExpect"));

        assertThat("--empty is sent when it was asked for and never as a fallback. Sent as one, an "
                + "invocation the refusal above missed ERASES the registration a previous one left "
                + "and reports success, which is the one outcome worse than refusing",
            argv, containsString("if (empty) add(\"--empty\")"));
        for (String member : List.of("artifact", "key", "to", "reason"))
            assertThat("each member is forwarded exactly as given, so an incomplete set reaches the "
                    + "toolkit's own refusal rather than being rewritten into a different order",
                argv, containsString(member + "?.let { add(\"--" + member + "\"); add(it) }"));
    }

    @Test
    @DisplayName("both orders parityExpect cannot carry out are refused, and over every member")
    void bothRegistrationRefusalsAreRaisedOverEveryMember() {
        String block = collapsed(taskBlock("parityExpect"));

        assertThat("a clear and a registration in one invocation. Whichever is taken, the other is "
                + "discarded without a word - and the one discarded by the argv below is the "
                + "registration, so the command that named a row and a value reports a cleared "
                + "manifest and exits 0",
            block, containsString("if (empty && given.isNotEmpty()) throw GradleException("));
        assertThat("and an incomplete registration, which is the refusal this task exists for: a "
                + "-Pto is what makes a registration an assertion rather than a licence for the row "
                + "to take any value at all. The `!empty` half is load-bearing in the other "
                + "direction - dropped, the first line of the documented flow, a bare "
                + "-PexpectEmpty, is refused for naming none of the four",
            block, containsString("if (!empty && !registers) throw GradleException("));
        assertThat("what was given is counted over the member list rather than over four reads, so "
                + "a member added to one is a member the refusal already knows about",
            block, containsString("val given = members.filter { (_, value) -> value != null }"
                + ".map { (name, _) -> name }"));
        assertThat("and completeness is that list's own size, never a number beside it",
            block, containsString("val registers = given.size == members.size"));

        List<String> members = new ArrayList<>();
        Matcher declares = QUOTED.matcher(declared(block, "val members = listOf\\(([^)]*)\\)"));
        while (declares.find()) members.add(declares.group(1));
        List<String> forwarded = new ArrayList<>();
        Matcher sends = FORWARDS_AN_EXPECT_MEMBER.matcher(block);
        while (sends.find()) {
            assertThat("a member reaches the toolkit under its own name", sends.group(2),
                equalTo(sends.group(1)));
            forwarded.add(sends.group(2));
        }

        assertThat("the members the refusal counts are the members the argv sends. Dropped from the "
                + "list, a member is one the build hands the toolkit without ever having required "
                + "it: the registration configures three-quarters complete and the refusal that "
                + "catches it is the toolkit's, after the self-test the task depends on has run",
            members, equalTo(forwarded));
    }

    @Test
    @DisplayName("the number of entry points the skill claims is the number the group holds")
    void theSkillsTaskCountIsTheGroupsOwnSize() {
        Set<String> registered = parityGroupTasks();
        String skill = read(PARITY_SKILL);

        Matcher stated = SKILL_TASK_COUNT.matcher(skill);
        assertThat("SKILL.md states no count of the tasks it runs, so what is pinned below is not "
            + "the sentence that shipped", stated.find(), is(true));
        assertThat("the count is written as a word and this is the vocabulary; one outside it has "
                + "no number to compare and would pass by naming nothing",
            COUNT_WORDS, hasItem(stated.group(1)));
        assertThat("the number that sentence states, against the size of the group it states it "
                + "about. Nothing else relates the two: a task is asserted into the group where "
                + "its own body is sliced, which says how each is registered and never how many "
                + "are, so a sixth entry point leaves the shipped sentence false with every suite "
                + "green", registered.size(), is(equalTo(COUNT_WORDS.indexOf(stated.group(1)))));

        // The fenced blocks and not the whole file, which is the difference between a command the
        // skill RUNS and one it is told to print. The refusal on stale references names the
        // whole-tree re-render and stops: refreshing the oracle is the operator's own act, taken
        // before the work, and the most expensive single command in this repository. Handing it to a
        // reader inside a runnable block is what that row exists to prevent, so a fenced block is
        // exactly the operand.
        Set<String> invoked = new TreeSet<>();
        Matcher block = SKILL_FENCED_BLOCK.matcher(skill);
        while (block.find()) {
            Matcher runs = SKILL_INVOCATION.matcher(block.group(1));
            while (runs.find()) invoked.add(runs.group(1));
        }
        assertThat("and the tasks it hands an operator to run are those same tasks. `and no "
                + "others` is the rest of that sentence, and a count on its own is satisfied by a "
                + "skill naming a different five - or by a registration the skill never mentions "
                + "arriving as one it does",
            invoked, equalTo(registered));

        Set<String> mentioned = new TreeSet<>();
        Matcher anywhere = SKILL_INVOCATION.matcher(skill);
        while (anywhere.find()) mentioned.add(anywhere.group(1));
        Set<String> unregistered = new TreeSet<>(mentioned);
        unregistered.removeAll(registeredTaskNames());

        assertThat("the refusal on stale references, whole. It is where the whole-tree re-render is "
                + "handed over, so what the tolerance below rests on is a row that PRINTS a command "
                + "and stops: reworded to run it, the name it hands out is the same name, and a set "
                + "of names goes on vouching for a licence where it meant to vouch for a refusal",
            tableRow(skill, STALE_REFERENCES_CONDITION), equalTo(REFUSING_ON_STALE_REFERENCES));

        // Whole lines rather than the names on them. Narrowing the clause above to fenced blocks
        // bought the difference between a command the skill runs and one it hands to a reader, and a
        // name is exactly what those two have in common: the whole-tree re-render is already a name
        // the refusal hands over, so a sentence anywhere else telling a reader to run it adds no
        // name and moves no set of them. What it adds is a LINE, and the lines that may carry a
        // command outside a block are the rows that hand one over - each pinned whole, here and
        // where the files the other speaks for are counted.
        List<String> handingOver = ROWS_HANDING_A_COMMAND_OVER.stream()
            .map(condition -> tableRow(skill, condition))
            .sorted()
            .toList();
        List<String> handedToTheReader = commandLinesOutsideAFencedBlock(skill);

        assertThat("the build forwarded no task names, so the clause below has no operand and holds "
            + "over an empty registry whatever the skill says", registeredTaskNames(),
            is(not(empty())));
        assertThat("and every task named on a command line anywhere in the file is one this build "
            + "registers, fenced or not. Outside a block the skill quotes a command for a reader to "
            + "run themselves, which is a name that has to resolve even though the skill will not "
            + "run it", unregistered, is(empty()));
        assertThat("a condition below reads a row the table no longer carries, so the comparison "
            + "after it would be answered by an absence on both sides", handingOver,
            not(hasItem("")));
        assertThat("every line outside a fenced block that hands a command over, against the rows "
                + "that are allowed to. The most expensive single command in this repository is "
                + "handed over by a refusal, and a sentence in prose telling a reader to run it is "
                + "the licence that refusal exists to withhold - which a set of task names cannot "
                + "see, the name being one the refusal itself already carries. So what is admitted "
                + "outside a block is a row, never a name a row happens to mention. That task is "
                + "collected however it is spelled, prefix or none, because the spelling without "
                + "the prefix is the one this file used for it and adds no `./gradlew` line",
            handedToTheReader, equalTo(handingOver));
    }

    /**
     * Every line outside a fenced block that hands the reader a command.
     *
     * <p>Lines rather than the names on them, because a name is all a licence and a refusal have in
     * common: the row refusing a gate on stale references prints the whole-tree re-render, so that
     * name is one the file already carries and a second sentence naming it moves no set of names.
     *
     * <p>A {@code ./gradlew} command line is one way to hand one over and not the only one. The
     * task the refusal withholds is the most expensive single command in this repository, and a
     * sentence telling a reader to run it by its bare name is the same licence spelled without the
     * prefix - which is the spelling this file used for it before the refusal was written. So that
     * name is collected wherever it is written, and the prefix is what admits every other task.
     *
     * @param skill the skill body
     * @return the lines, sorted
     */
    private static List<String> commandLinesOutsideAFencedBlock(String skill) {
        String withheld = theTaskTheRefusalWithholds();
        List<String> out = new ArrayList<>();
        boolean fenced = false;
        for (String line : skill.lines().toList()) {
            if (line.startsWith("```")) fenced = !fenced;
            else if (!fenced && (SKILL_INVOCATION.matcher(line).find() || line.contains(withheld)))
                out.add(line);
        }
        return out.stream().sorted().toList();
    }

    /**
     * The task the stale-references refusal prints rather than runs.
     *
     * <p>Read off the pinned row rather than typed again, so the name the tolerance is written for
     * and the name the refusal hands over cannot become two names. That row is held to its shipped
     * text where the refusal itself is checked, which is what makes reading it here safe.
     *
     * @return the task name
     */
    private static String theTaskTheRefusalWithholds() {
        Matcher named = SKILL_INVOCATION.matcher(REFUSING_ON_STALE_REFERENCES);
        if (!named.find())
            throw new AssertionError("the stale-references refusal names no task to withhold");
        return named.group(1);
    }

    /**
     * The decision-table row whose condition cell is the given text.
     *
     * <p>The row rather than the whole file, so a failure prints the line that moved instead of the
     * skill, and so a reworded condition comes back as an absent row rather than as a match on
     * whatever else the table happens to say.
     *
     * @param skill the skill body
     * @param condition the condition cell, pipes included
     * @return the whole row, or the empty string when the table carries no row with that condition
     */
    private static String tableRow(String skill, String condition) {
        return skill.lines().filter(line -> line.startsWith(condition)).findFirst().orElse("");
    }

    @Test
    @DisplayName("a verification run schedules both cheap gates the fast suite does not reach")
    void checkSchedulesTheGatesNothingElseSchedules() {
        String build = collapsed(buildFile());

        assertThat("the edge WHOLE, both operands included. Without it `check` reached `test` and "
                + "nothing else: the toolkit's own suite ran only when a parity task pulled it in - "
                + "the very run the toolkit is being trusted to compute - and the harness, a "
                + "separate Gradle build, was compiled by nothing in this one, so an edit to it that "
                + "does not compile waited for a client boot. Each is one line and each was written "
                + "as one, so what a deletion leaves behind is a suite that stays green over the "
                + "hole it opened",
            build, containsString(CHECK_SCHEDULES_THE_CHEAP_GATES));

        Set<String> scheduled = new TreeSet<>();
        Matcher operands = QUOTED.matcher(declared(build, CHECK_SCHEDULES.pattern()));
        while (operands.find()) scheduled.add(operands.group(1));

        assertThat("the edge names no task at all, which the containment above stops seeing the "
            + "moment its own literal is what moved", scheduled, is(not(empty())));
        assertThat("and every name on it is one this build registers, read off the edge rather than "
                + "typed beside it. A gate renamed at its registration and not here fails when the "
                + "task graph is resolved, which is on the verification run this edge exists to put "
                + "it on - so the failure arrives exactly where the guard was supposed to have "
                + "spoken first",
            registeredTaskNames().containsAll(scheduled), is(true));
    }

    @Test
    @DisplayName("the harness gate asks the wrapper for the source set the harness keeps its code in")
    void theHarnessGateAsksForTheSourceSetThatHoldsTheSources() {
        List<String> operands = new ArrayList<>();
        Matcher added = ADDED_OPERAND.matcher(declared(buildFile(), HARNESS_COMMAND_LINE.pattern()));
        while (added.find()) operands.add(added.group(1));

        assertThat("the wrapper's whole argv, in order and as written, because `clientClasses` is "
                + "the operand that compiles anything at all: Loom's splitEnvironmentSourceSets() "
                + "puts every harness java file in the CLIENT source set, so `classes` alone "
                + "reports :compileJava NO-SOURCE and this gate reports success in seconds over a "
                + "harness that does not compile - the exact state it was added to end, and one "
                + "nothing else in either build notices before a client boot. The rest are read "
                + "with it because each is a decision a reader would otherwise re-derive: `cmd /c` "
                + "is what makes a .bat wrapper executable, `gradlew` - bare, the resolved path to "
                + "the harness's own wrapper rather than a literal - is the thing those two run, "
                + "and `--no-daemon` is what stops a second toolchain's daemon outliving the run",
            operands, is(equalTo(List.of("\"cmd\"", "\"/c\"", "gradlew", "\"classes\"",
                "\"clientClasses\"", "\"--no-daemon\""))));
    }

    @Test
    @DisplayName("the task registry reaches the suite as one value, read after configuration")
    void theTaskRegistryReachesTheSuiteAsOneValue() {
        String build = buildFile();

        assertThat("one value, read once and forwarded. Two guards resolve a name against this "
                + "registry and both read it out of the flag the doFirst sets, so the two "
                + "statements are read as one because they have to be the same value: a doFirst "
                + "forwarding an expression of its own is a fork whose halves part company exactly "
                + "when something moved. A provider rather than a direct read, so that one read "
                + "lands after configuration, when nothing further can register a task. What "
                + "re-runs the two guards when the registry moves is measured elsewhere and is "
                + "route-dependent: the declared input covering the file a registration arrived in, "
                + "which BlindnessMapTest holds against the files one can arrive in, or - for a "
                + "registration arriving from an init script, which edits no declared file - "
                + "Gradle's own fingerprint of this action",
            collapsed(build), containsString(FORWARDS_THE_REGISTRY));
        assertThat("and written in one place, so the two readers cannot be reading two registries",
            occurrences(build, NAMES_THE_TASK_REGISTRY), is(equalTo(1)));
    }

    /**
     * Every task name this build registers, as the build itself answers.
     *
     * @return the names
     */
    private static Set<String> registeredTaskNames() {
        String forwarded = System.getProperty("asset.parity.task.names", "");
        if (forwarded.isBlank()) return Set.of();
        return Set.of(forwarded.split(","));
    }

    @Test
    @DisplayName("a compare in the same invocation is ordered after the capture it reads")
    void theCompareIsOrderedAfterTheCapture() {
        assertThat("nothing but command-line order kept `parityCapture parityCompare` from "
                + "comparing a root the capture had not written yet",
            taskBlock("parityCompare"), containsString("mustRunAfter(\"parityCapture\")"));
    }

    @Test
    @DisplayName("a promotion in the same invocation is ordered after the compare it applies")
    void thePromotionIsOrderedAfterTheCompare() {
        assertThat("`parityPromote parityCompare` promoted the root into the store and then diffed "
                + "it against the store it had just been written into - necessarily zero movers, "
                + "and a verdict saying this exact tree was gated",
            taskBlock("parityPromote"), containsString("mustRunAfter(\"parityCompare\")"));
    }

    @Test
    @DisplayName("the dirty-tree override is read as a value and forwarded to the toolkit")
    void theDirtyTreeOverrideReachesTheToolkit() {
        String promote = taskBlock("parityPromote");
        assertThat("read through the value-aware reader like every other option here",
            promote, containsString("val allowDirty = parityFlag(\"allowDirty\")"));
        assertThat("a refusal whose only override is unreachable from a command line is a refusal "
                + "nobody can answer, and the runbook documents this one as the answer",
            promote, containsString("if (allowDirty) add(\"--allow-dirty\")"));
    }

    @Test
    @DisplayName("the run count reaches the capture of each artifact, and the index over them")
    void theRunCountReachesBothSidesOfACapture() {
        String step = collapsed(declaration(REGISTERS_A_CAPTURE_STEP));

        assertThat("read out of the body that builds a capture step's argv, so the statement is "
            + "pinned where it reaches an artifact rather than anywhere in the file",
            step, containsString("add(\"capture-normalize\")"));
        assertThat("the forwarding that carries the number into an ARTIFACT's provenance, which is "
                + "the value the promotion compares against the determinism floor. Dropped, every "
                + "capture is stamped with the floor instead - so a claim BELOW it, the one thing "
                + "the floor refusal exists to catch, is silently replaced by a passing one",
            step, containsString(FORWARDS_THE_RUN_COUNT));
        assertThat("and the one that records the same claim once for the capture as a whole, in "
                + "the index rather than under each row",
            taskBlock("parityCapture"), containsString(FORWARDS_THE_RUN_COUNT));
    }

    @Test
    @DisplayName("no forwarding of the run count is unguarded")
    void theRunCountIsForwardedOnlyWhenItWasMeasured() {
        String build = collapsed(buildFile());
        int named = occurrences(build, NAMES_THE_RUN_COUNT);

        assertThat("the build file forwards --runs nowhere at all, which would leave the "
            + "comparison below vacuous", named, is(greaterThan(0)));
        assertThat("absent, `--runs` is the artifact's declared floor and the toolkit stamps it - "
                + "which is a property of the artifact rather than of an invocation. A site "
                + "forwarding one unconditionally puts a build-side number under every capture and "
                + "makes that default unreachable, so the guarded spelling is the only one",
            occurrences(build, FORWARDS_THE_RUN_COUNT), is(equalTo(named)));
    }

    @Test
    @DisplayName("a compare is ordered after the expected-diff whose registration it reads")
    void theCompareIsOrderedAfterTheExpectedDiff() {
        assertThat("the expected-diff is the other input to the verdict, and registering a mover "
                + "after the compare that was meant to accept it leaves that compare RED for a "
                + "reason the operator has already answered",
            taskBlock("parityCompare"), containsString("mustRunAfter(\"parityExpect\")"));
    }

    @Test
    @DisplayName("the whole capture chain is ordered after the expected-diff, through the one erase")
    void theCaptureChainIsOrderedAfterTheExpectedDiff() {
        assertThat("taken on the erase rather than on parityCapture itself, because the erase is "
                + "what every producer and every step is already ordered after - an edge on the "
                + "entry point alone leaves the producers and the steps free to run first, and then "
                + "the documented order holds for the task and not for the work it stands for",
            collapsed(toBlankLine(ERASES_THE_WORKING_ROOT)),
            containsString("mustRunAfter(\"parityExpect\")"));
        assertThat("which is what makes that one edge reach the whole chain: the capture step takes "
                + "it, and so does every producer",
            occurrences(collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
                "mustRunAfter(parityCaptureBeginTask)"), is(equalTo(2)));
    }

    @Test
    @DisplayName("the flags a capture records are the daemon's own, selected the way a fork's are")
    void theCaptureRecordsTheSameFlagNamespaceTheForkCarries() {
        String build = buildFile();

        assertThat("every custom flag of this build lives under this namespace, so a capture "
                + "collecting a different one records a set no fork ever carried - and the two "
                + "differing is invisible by inspection, because both still read plausible",
            declared(build, "val assetPropertyPrefix: String = \"([^\"]+)\""), equalTo("asset."));
        assertThat("the forwarder, WHOLE. It walks the daemon's own System.getProperties() and keeps "
                + "the pairs whose name carries the one prefix above, which is the half of the "
                + "relation that decides what a fork carries. Read whole because the shape this has "
                + "to fail on is the test still being there and answering the other way: a count of "
                + "the sites reading the prefix is 2 with the sense of either one inverted, and so "
                + "is a containment test on the walk they sit in",
            collapsed(toBlankLine(DECLARES_THE_FORWARDER)), equalTo(FORWARDS_EVERY_ASSET_PROPERTY));
        assertThat("and the recorder, WHOLE, which is the other half: the same walk over the same "
                + "table, keeping the pairs the same name test keeps, so what a capture writes down "
                + "is the set the forwarder puts on a fork. The two selecting differently is "
                + "invisible by inspection because both still read plausible, and it makes a record "
                + "an inventory of some other set of flags. Its map is SORTED, and that too is read here "
                + "rather than beside this: it buys a caller an argv that is a function of the flags "
                + "rather than of a hash table's iteration order, and nothing more, because the "
                + "record folds the pairs into an object canonical JSON sorts and no stored byte can "
                + "tell two orders apart",
            collapsed(function("assetPropertiesInForce")), equalTo(RECORDS_EVERY_ASSET_PROPERTY));
        assertThat("and no site filters on a prefix of its own beside them",
            occurrences(collapsed(build), "startsWith(\"asset"), is(equalTo(0)));
        assertThat("the two parity roots are put on a fork AFTER the forwarder and outside it, and "
                + "that is where a fork's flags and a record's part company. systemProperty sets a "
                + "property in the FORKED JVM; assetPropertiesInForce reads the DAEMON's table. So a "
                + "root is recorded when a command line put it in the daemon and not because the "
                + "build put it on a fork - measured both ways on this tree: with no -D the daemon "
                + "held no asset.* and a capture would record none, while a Test fork was configured "
                + "with both roots; under -Dasset.parity.root the daemon held that one and a capture "
                + "records it, while asset.parity.references, which neither run set on the command "
                + "line, stayed on the fork and out of the record both times. Pinned so that stays "
                + "the arrangement - reordered, the forwarder overwrites the resolved roots with "
                + "whatever the daemon held, and dropped, a fork stops carrying them at all",
            occurrences(collapsed(build),
                "forwardAssetProperties() systemProperty(\"asset.parity.root\", parityWorkingRoot) "
                    + "systemProperty(\"asset.parity.references\", parityReferenceRoot)"),
            is(equalTo(2)));
        assertThat("collected once, so the fork's list and the capture's cannot be two walks",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("val assetFlags = assetPropertiesInForce()"));
        assertThat("one --flag per resolved property. A fork inherits them from a long-lived daemon "
                + "rather than from the command line, so two captures typed identically can "
                + "disagree and nothing else ever writes that difference down",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("assetFlags.forEach { (name, value) -> add(\"--flag\"); add(\"$name=$value\") }"));
    }

    @Test
    @DisplayName("the rows that read the reference tree are the rows whose producers read it")
    void theReferenceRuleSelectsTheRowsItSaysItDoes() {
        String build = buildFile();
        String prefix =
            declared(build, "val referenceDerived: Boolean get\\(\\) = artifact.startsWith\\(\"([^\"]+)\"\\)");
        assertThat("the wider rule is the narrow one plus the row that hashes the tree; read here "
                + "rather than restated, because a second spelling of it is a second answer",
            collapsed(build),
            containsString("val readsReferenceTree: Boolean get() = referenceDerived "
                + "|| artifact == parityReferencesArtifact"));

        Set<String> diffAgainstIt = idsWhoseProducerSatisfies(name -> name.endsWith("ParityVanilla"));
        Set<String> hashIt = idsWhoseProducerSatisfies(harnessRunNames()::contains);
        Set<String> selected = new TreeSet<>();
        Set<String> readsTheTree = new TreeSet<>();
        for (Map.Entry<String, List<String>> row : artifactTable(build).entrySet()) {
            if (row.getKey().startsWith(prefix)) selected.add(row.getKey());
            if (row.getKey().startsWith(prefix) || row.getKey().equals(referencesArtifact(build)))
                readsTheTree.add(row.getKey());
        }

        assertThat("no artifact's producer is a sweep, which would leave this vacuous",
            diffAgainstIt, is(not(empty())));
        assertThat("the rule decides which rows carry the digest of the ground truth they were "
                + "measured against, so it has to select the rows that ARE measured against it - "
                + "resolved from the producer column of the Java-side roster rather than from the "
                + "prefix, which is the thing under test", selected, equalTo(diffAgainstIt));
        assertThat("no artifact's producer is a harness run, the same", hashIt, is(not(empty())));
        assertThat("and the wider rule adds exactly the row a harness run writes",
            readsTheTree, equalTo(union(diffAgainstIt, hashIt)));
    }

    @Test
    @DisplayName("a capture stamps the mode of the run that happened, not of the row's own producer")
    void theStampedModeComesFromTheRunThatExecuted() {
        String build = buildFile();

        assertThat("declared once, written once and read once. The mode a row's declared producer "
                + "would select is that producer's name spelled a second way - a constant on the "
                + "one row that has one and absent on every other - so the recorded value has to "
                + "come from a run that executed, and a second writer would be a way to record one "
                + "that did not", occurrences(build, "parityHarnessModesRun"), is(equalTo(3)));
        assertThat("written where a render finishes, so a failed one records nothing",
            collapsed(toBlankLine(REGISTERS_A_HARNESS_RUN)),
            containsString("doLast { parityHarnessModesRun += mode }"));
        assertThat("and read where the capture's argv is built, at execution, on the rows that read "
                + "the tree and no others: which harness run is in the graph is not known while it "
                + "is being configured, and a row whose value is no function of the tree carries no "
                + "statement about what last wrote one - unguarded, a shipped-table digest taken in "
                + "an invocation that also rendered is stamped with the render's mode",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("if (spec.readsReferenceTree) doFirst { "
                + "val ran = parityHarnessModesRun.joinToString(\",\") "
                + "if (ran.isNotEmpty()) args(\"--mode\", ran) }"));
        assertThat("never off the artifact table's own producer column, which is where the value "
                + "restates the producer instead of describing the run",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)), not(containsString("harnessRunModes[")));
    }

    @Test
    @DisplayName("a run that refreshes no reference stamps no mode and orders nothing")
    void aRunThatRefreshesNothingIsNoRenderOfTheGroundTruth() {
        String registration = collapsed(toBlankLine(REGISTERS_A_HARNESS_RUN));

        assertThat("guarded on what the run REFRESHES. Both probes render into their own directory "
                + "beside the reference tree and rewrite nothing in it, so an invocation running one "
                + "has re-measured no ground truth - and a capture stamping its mode says the "
                + "opposite about numbers taken off references nobody touched, which is worse than "
                + "the absent field the honest answer leaves",
            registration,
            containsString("if (refreshes.isNotEmpty()) doLast { parityHarnessModesRun += mode }"));
        assertThat("and the ordering edges read the same set through the same predicate, so what a "
                + "row that reads the tree is ordered against is what can write the tree",
            registration,
            containsString("}.also { if (refreshes.isNotEmpty()) harnessRunModes[name] = mode }"));

        Map<String, Boolean> refreshesNothing = new TreeMap<>();
        Map<String, Boolean> saysItRefreshesNothing = new TreeMap<>();
        for (String call : harnessRunCalls()) {
            String name = declared(call, "registerHarnessRun\\(\"([^\"]+)\"");
            refreshesNothing.put(name, collapsed(call).contains("emptyList()"));
            saysItRefreshesNothing.put(name, call.contains(REFRESHES_NO_REFERENCE));
        }

        assertThat("no registered run declares an empty refresh list, which would leave the guard "
            + "above inert and the relation below vacuous", refreshesNothing.containsValue(true), is(true));
        assertThat("every registered run declares one, which would leave it equally vacuous the "
            + "other way", refreshesNothing.containsValue(false), is(true));
        assertThat("the two spellings of what a run refreshes answer together: the list the wiring "
                + "reads, and the sentence the task description gives an operator. A run whose list "
                + "says it rewrites a sub-tree while its description says it refreshes none is one "
                + "of the two claims being wrong, and the wiring reads the one nobody looks at",
            saysItRefreshesNothing, equalTo(refreshesNothing));
    }

    @Test
    @DisplayName("a row measured against the reference tree is ordered after any render of it")
    void whatReadsTheReferenceTreeRunsAfterARenderOfIt() {
        assertThat("a render landing between a sweep and the capture of it has that capture stamp "
                + "a mode and a manifest digest the measured value never saw, which reads as "
                + "evidence about the number and is a statement about task order",
            collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("if (spec.readsReferenceTree) mustRunAfter(harnessRunModes.keys)"));
        assertThat("and the producers take the same edge, which is the half that decides what the "
                + "number was measured against",
            collapsed(buildFile()),
            containsString("parityArtifacts.filter { it.readsReferenceTree } "
                + ".flatMap { it.producers } .distinct() .filter { it !in harnessRunModes } "
                + ".forEach { producer -> named(producer) { mustRunAfter(harnessRunModes.keys) } }"));
    }

    @Test
    @DisplayName("what a sweep was measured against is named as the tree, reachable on every capture")
    void theGroundTruthIsNamedAsTheTreeAndNotAsACapturedFile() {
        String step = collapsed(declaration(REGISTERS_A_CAPTURE_STEP));

        assertThat("named as the DIRECTORY the producer read. Named as the captured manifest "
                + "instead, the field landed only where that row was also captured - and an "
                + "ordinary renderer change's plan selects the sweeps and not the row that hashes "
                + "the tree, so the one field tying a number to its reference set was absent on "
                + "every sweep the sanctioned flow produces",
            step, containsString("if (spec.referenceDerived) { add(\"--reference-tree\"); "
                + "add(parityReferenceRoot) }"));
        assertThat("the same root the sweeps and the harness are pointed at, so the digest names "
                + "the tree that was read rather than a second derivation of where it should be",
            occurrences(buildFile(), "systemProperty(\"asset.parity.references\", parityReferenceRoot)"),
            is(greaterThan(0)));
        assertThat("forwarded once, under the rule that says which rows are measured against the "
                + "tree - an unguarded one puts the digest on rows nothing measured",
            occurrences(step, "add(\"--reference-tree\")"), is(equalTo(1)));
        assertThat("and never as a path into the working root, which is what made it conditional "
                + "on a capture that the routine flow does not take",
            step, not(containsString("manifests/references.json")));
    }

    @Test
    @DisplayName("every name a stamped mode can carry is one the harness itself declares")
    void everyHarnessModeIsOneTheHarnessDeclares() {
        Set<String> declared = new TreeSet<>();
        Matcher constants = HARNESS_MODE_CONSTANT.matcher(read(HARNESS_MODE));
        while (constants.find()) declared.add(constants.group(1));

        Set<String> named = new TreeSet<>();
        Matcher calls = HARNESS_RUN_CALL.matcher(buildFile());
        while (calls.find()) named.add(calls.group(1));

        assertThat("no harness run names a mode, which would leave this check vacuous",
            named, is(not(empty())));
        assertThat("the harness declares no modes, the same", declared, is(not(empty())));
        assertThat("a stamped mode is read out of a promoted baseline's provenance forever, so "
                + "every name it can carry has to be one the harness answers to rather than free "
                + "text nothing relates to a run",
            declared.containsAll(named), is(true));
        // The stamped value is a SET, because one invocation can run two renders - a player refresh
        // and an armour refresh land as `ARMOR,PLAYERS`. So the value is the names above joined,
        // and it only decomposes back into them while none of them carries the joiner.
        assertThat("the value is joined on this separator, so a reader splits on it to recover the "
                + "runs", collapsed(declaration(REGISTERS_A_CAPTURE_STEP)),
            containsString("parityHarnessModesRun.joinToString(\"" + MODE_SEPARATOR + "\")"));
        assertThat("and the set it joins is SORTED, so the joined value is a function of the modes "
                + "rather than of which render finished first. Unlike the flag map collected beside "
                + "it, whose pairs canonical JSON sorts back into place, this string is stamped "
                + "verbatim into a promoted baseline's provenance: unsorted, two invocations running "
                + "the same two renders in opposite orders name one reference tree two ways, and "
                + "the difference is in the stored bytes forever",
            declared(buildFile(),
                "val parityHarnessModesRun: MutableSet<String> = (\\w+)\\(\\)"),
            equalTo("sortedSetOf"));
        assertThat("a declared mode carrying the separator would make a two-run value ambiguous "
                + "with a one-run value, and the composite would stop being a set of names",
            declared.stream().filter(mode -> mode.contains(MODE_SEPARATOR)).toList(), is(empty()));
    }

    /**
     * Returns the one group of a pattern that has to match the build file exactly once.
     *
     * @param build the build file's text
     * @param pattern the pattern, carrying one capturing group
     * @return the captured text
     */
    private static String declared(String build, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(build);
        assertThat("build.gradle.kts carries nothing matching " + pattern, found.find(), is(true));
        return found.group(1);
    }

    /**
     * Returns the artifact table's rows, each id mapped to the producers it declares.
     *
     * @param build the build file's text
     * @return the producers by artifact id, in declaration order
     */
    private static Map<String, List<String>> artifactTable(String build) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher found = ARTIFACT_ROW.matcher(build);
        while (found.find())
            rows.put(found.group(1),
                QUOTED.matcher(found.group(2)).results().map(hit -> hit.group(1)).toList());
        assertThat("build.gradle.kts declares no artifact rows", rows.keySet(), is(not(empty())));
        return rows;
    }

    /** The artifact id the build gives the row that hashes the whole reference tree. */
    private static String referencesArtifact(String build) {
        return declared(build, "val parityReferencesArtifact: String = \"([^\"]+)\"");
    }

    /**
     * Returns every harness-run call site, each from its opening to the blank line after it.
     *
     * <p>Whole call sites rather than one captured argument, because what is read off them is two
     * spellings of one fact - the sub-trees the run declares it rewrites, and the sentence its
     * description gives an operator - and a pattern reaching only one of them cannot relate them.
     *
     * @return the call sites, verbatim
     */
    private static List<String> harnessRunCalls() {
        String build = buildFile();
        List<String> calls = new ArrayList<>();
        for (int at = build.indexOf(CALLS_A_HARNESS_RUN); at >= 0;
             at = build.indexOf(CALLS_A_HARNESS_RUN, at + 1)) {
            int end = build.indexOf("\n\n", at);
            assertThat("a harness run's call site runs to the end of the build file", end, is(not(-1)));
            calls.add(build.substring(at, end));
        }
        assertThat("build.gradle.kts calls no harness registration", calls, is(not(empty())));
        return calls;
    }

    /** Every harness-run task name the build registers. */
    private static Set<String> harnessRunNames() {
        Set<String> names = new TreeSet<>();
        Matcher calls = Pattern.compile("registerHarnessRun\\(\"([^\"]+)\"").matcher(buildFile());
        while (calls.find()) names.add(calls.group(1));
        assertThat("build.gradle.kts registers no harness run", names, is(not(empty())));
        return names;
    }

    /**
     * Returns the artifacts the Java-side roster homes in the store whose producer matches.
     *
     * <p>The roster is a deliberate second copy of the taxonomy, so resolving a build-file rule
     * against it relates two independently maintained statements rather than restating one.
     *
     * @param producer what at least one of the producing tasks' names has to satisfy
     * @return the matching artifact ids
     */
    private static Set<String> idsWhoseProducerSatisfies(Predicate<String> producer) {
        return ParityArtifacts.ALL.stream()
            .filter(registration -> registration.producers().stream().anyMatch(producer))
            .map(ParityArtifacts.Registration::id)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Returns every task the build registers into the {@code parity} group.
     *
     * <p>Enumerated from the <b>group declarations</b> and never from the registrations, because the
     * declaration is what decides membership while a registration has several spellings that all
     * reach the same container: {@code register<T>("name")} indented inside the container block,
     * {@code tasks.register<T>("name")} at column zero, {@code create}, {@code named} over a task
     * registered elsewhere. Sliced at one of those, the enumeration answers with a subset of the
     * group and the count it feeds is then true of the subset and false of the build - which is the
     * failure the count exists to catch, restored inside the thing that catches it.
     *
     * <p>Each declaration is attributed to a name by walking backwards from it over the same text,
     * counting a closing brace as one level deeper and an opening brace as one shallower, to the
     * brace that opens the block the declaration sits in; the task's name is the last quoted word
     * before that brace on its own line. Line comments go first, so a comment mentioning the
     * declaration is not attributed to whatever encloses it. A declaration inside no block, or one
     * whose enclosing block opens with no quoted name, fails here rather than being dropped: a
     * member the walk cannot name is a member of the group all the same.
     *
     * <p>What is left bound to a spelling is the declaration itself, read as the literal assignment
     * every task in the group is written with. A group named through a constant, or set by a call
     * rather than an assignment, is a member this does not see - and unlike a registration spelling,
     * that is not a form the build file uses anywhere.
     *
     * @return the task names
     */
    private static Set<String> parityGroupTasks() {
        String build = LINE_COMMENT.matcher(buildFile()).replaceAll("");
        Set<String> named = new TreeSet<>();
        for (int at = build.indexOf(DECLARES_THE_PARITY_GROUP); at >= 0;
             at = build.indexOf(DECLARES_THE_PARITY_GROUP, at + 1)) {
            int opens = enclosingBrace(build, at);
            assertThat("a parity-group declaration inside no block at all, which no registration can "
                + "own and which leaves the size of the group unanswerable", opens, is(not(-1)));
            String header = build.substring(build.lastIndexOf('\n', opens) + 1, opens);
            String name = null;
            Matcher quoted = QUOTED.matcher(header);
            while (quoted.find()) name = quoted.group(1);
            assertThat("a parity-group declaration whose enclosing block opens with no quoted name, "
                + "so the task joining the group cannot be named and counting the group would drop "
                + "it: " + header.trim(), name, is(notNullValue()));
            named.add(name);
        }
        assertThat("build.gradle.kts registers nothing into the parity group", named, is(not(empty())));
        return named;
    }

    /**
     * Returns where the block a position sits inside was opened.
     *
     * @param text the build file, its line comments already removed
     * @param from the position to walk back from
     * @return the offset of the opening brace, or -1 when the position is inside no block
     */
    private static int enclosingBrace(String text, int from) {
        int depth = 0;
        for (int at = from - 1; at >= 0; at--) {
            char ch = text.charAt(at);
            if (ch == '}') depth++;
            else if (ch == '{' && depth-- == 0) return at;
        }
        return -1;
    }

    /** The union of two id sets, sorted, so a failure prints both sides comparably. */
    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> both = new TreeSet<>(left);
        both.addAll(right);
        return both;
    }

    /**
     * Returns the body of a top-level build-file function, declaration line included.
     *
     * <p>Bounded by the blank line after it rather than by a brace, because both of these are
     * expression-bodied and the second's expression carries braces of its own.
     *
     * @param name the function name
     * @return its declaration and body, verbatim
     */
    private static String function(String name) {
        String build = buildFile();
        int start = build.indexOf("fun " + name + "(");
        assertThat("build.gradle.kts declares no " + name, start, is(not(-1)));
        int end = build.indexOf("\n\n", start);
        assertThat(name + " runs to the end of the build file", end, is(not(-1)));
        return build.substring(start, end);
    }

    /**
     * Returns a declaration from its opening text to the blank line after it.
     *
     * <p>The complement of {@link #declaration}: a declaration whose closing brace carries a
     * trailing call cannot be bounded by that brace, because that call is the part being read.
     *
     * @param opening the declaration's opening text, verbatim
     * @return the declaration and its body
     */
    private static String toBlankLine(String opening) {
        String build = buildFile();
        int start = build.indexOf(opening);
        assertThat("build.gradle.kts declares no " + opening, start, is(not(-1)));
        int end = build.indexOf("\n\n", start);
        assertThat(opening + " runs to the end of the build file", end, is(not(-1)));
        return build.substring(start, end);
    }

    /**
     * Returns a block-bodied top-level declaration, from its opening line to its closing brace.
     *
     * <p>Bounded by a brace in the first column, which is where a top-level declaration in this
     * file closes and where no declaration nested in one does.
     *
     * @param opening the declaration's opening text, verbatim
     * @return the declaration and its body
     */
    private static String declaration(String opening) {
        String build = buildFile();
        int start = build.indexOf(opening);
        assertThat("build.gradle.kts declares no " + opening, start, is(not(-1)));
        int end = build.indexOf("\n}", start);
        assertThat(opening + " has no line-initial closing brace", end, is(not(-1)));
        String body = build.substring(start, end);
        assertThat(opening + "'s slice ran into the declaration after it",
            body, not(containsString("\nfun ")));
        return body;
    }

    /**
     * Returns a registered task's body, from its opening brace to the next registration.
     *
     * <p>Sanity-checked here rather than beside a caller, so that every slice taken is checked
     * whatever asks for one: a slice running past its own registration would satisfy a containment
     * test out of a neighbour's body, and one stopping short of the body would satisfy none of them
     * and read as the wiring being gone.
     *
     * @param name the task name
     * @return the body, verbatim
     */
    private static String taskBlock(String name) {
        String build = buildFile();
        Matcher opens = Pattern.compile("register<[^>]+>\\(\"" + name + "\"\\) \\{").matcher(build);
        assertThat("build.gradle.kts registers no " + name, opens.find(), is(true));
        Matcher next = Pattern.compile("\n {4}register<").matcher(build);
        String block =
            build.substring(opens.end(), next.find(opens.end()) ? next.start() : build.length());
        assertThat(name + "'s slice ran into the registration after it",
            block, not(containsString("    register<")));
        assertThat(name + "'s slice stopped before its own body", block, containsString("description ="));
        assertThat(name + " must be registered in the parity group it is counted in",
            block, containsString("group = \"parity\""));
        return block;
    }

    /** Every run of whitespace as one space, so an assertion is about the statement and not its wrap. */
    private static String collapsed(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    /**
     * Counts non-overlapping occurrences of a literal.
     *
     * @param haystack the text to search
     * @param needle the literal to count
     * @return how many times it occurs
     */
    private static int occurrences(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length()))
            found++;
        return found;
    }

    private static String buildFile() {
        return read(Path.of("build.gradle.kts"));
    }

    /**
     * Reads a tracked file's text, by path because none of these is on the classpath.
     *
     * @param file the repo-relative path
     * @return its text
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
