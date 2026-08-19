package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.support.BuildScripts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
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
 * table has no row for refuses the capture as Gradle resolves that task's dependencies while it
 * builds the graph, and every gate on that path with it.
 *
 * <p>The rest read the build file for a different reason: the git index and the walk below decide
 * which files this suite sees, and Gradle decides whether it runs at all from the same index and the
 * same roots. Only one of those two answers is observable from here, so the other is asserted against
 * the text - the skip lists, the roots the trigger globs need, and the input declarations that carry
 * them. A guard whose own precondition is undeclared is a guard that stops firing the moment the task
 * is called UP-TO-DATE, which is exactly the day something moved.
 *
 * <p>What fails when a rule goes stale in a way no test can see - the claim is wrong but its paths
 * still exist - is the gate itself: the artifact the rule called blind moves, and the compare reports
 * an unexpected mover on an artifact a rule called blind. That is a loud failure at the moment it
 * matters, and it names the rule to fix.
 *
 * <p>What a rule's {@code sees} and {@code trigger_paths} lists <b>resolve to</b> is answered here
 * too. The suite carries the same union-then-demote-then-suppress arithmetic the toolkit does and
 * runs it against the shipped map, so a reach edit answers to checks on this side and not only to the
 * generated reference: the worked example pins what one kit file comes back reaching and what is
 * demoted for it, the membership checks pin that the two files a member list is typed in reach the
 * artifacts that list defines, and every blindness claim another rule's {@code sees} overrules on the
 * claiming rule's own triggers is compared against a set written down above. Dropping an artifact
 * from a {@code sees} list, narrowing a trigger off one of those files, or making a blind claim a
 * second rule then overrules, each fails here and names the rule. What goes through is an edit the
 * map's own redundancy absorbs: dropping an artifact a second rule selects on the same path leaves
 * every resolution where it was, and the reference is the only thing that moves.
 *
 * <p>What no check here can answer is whether a {@code sees} list is <b>right</b>. That an artifact
 * really moves when one of those paths changes is a statement about a render, and only capturing it
 * either side of the change settles one - the resolution above is judged against a second
 * implementation of the same grammar rather than against the truth. The toolkit holds its own
 * assertions over the same map, and a reach edit is worth running against those as well, because they
 * go through the code the planner really uses where this side goes through a mirror of it. Every
 * parity task depends on the task that runs them, so the gate does; a bare {@code ./gradlew test}
 * does not, which is the split rather than a gap.
 */
@DisplayName("The blindness map refers only to real paths and registered artifacts")
final class BlindnessMapTest {

    /** The modes a rule may declare. */
    private static final Set<String> MODES = Set.of("select", "demote", "suppress");

    /**
     * The one {@code no_reach} glob that matches no tracked path, and why that is correct.
     *
     * <p>{@code notes/**} is gitignored by decision, so git tracks none of it however many files the
     * working copy happens to carry - which is why the orphan check below is sourced from the index
     * rather than from a walk, and why this exemption is a live branch rather than a dead one. Every
     * other glob names something the repository holds, and the check is what keeps a rotted or
     * over-broad entry from silently holding paths out of reach resolution, so the exemption is one
     * name rather than a softened assertion.
     */
    private static final Set<String> MAY_MATCH_NOTHING = Set.of("notes/**");

    /**
     * Blindness declarations another rule's {@code sees} overrules on the claiming rule's own
     * triggers, as {@code "<rule> -> <artifact>"}.
     *
     * <p>A {@code select} rule's {@code blind} list is a statement rather than a subtraction, so an
     * artifact a second fired rule selects stays in the bundle and the claim is printed as
     * "claimed blind, selected by ..." rather than dropped. That is deliberate for every pair here -
     * each speaks about a code region inside a wider tree, and demoting one would remove a gate the
     * rest of that tree genuinely reaches.
     *
     * <p>B24 is the one that reads the other way round: it is the wide claim and the exception is a
     * subset of its own triggers. The dump really is blind to an options bag, which is what the claim
     * was measured on; it is not blind to the vanilla vocabulary those bags name, because that lives
     * under {@code asset} and the dump serialises it. Narrowing B24 off those paths would drop the
     * four CRC pins and three manifests only it reaches, so the claim stays wide and the pair is
     * recorded.
     *
     * <p>Four pairs left this set by being <b>measured false</b> rather than by being reclassified.
     * Perturbing a file each claimant triggers on moved the artifact it called blind, so the claim was
     * not a statement about a narrower region at all - it was wrong, and the artifact is now in that
     * rule's {@code sees}. Being overruled by a sibling rule is what kept the bundle honest while the
     * declaration was false, which is why the plan prints such a pair instead of trusting it.
     *
     * <p>Every claimant here is a {@code select} rule by construction rather than by choice: the
     * resolution below applies the demote pass, so a rule whose {@code blind} list subtracts on its
     * own triggers can never be overruled on one of them. A {@code demote} or {@code suppress} claim
     * is overruled from a path that rule does not trigger on, which makes it a property of a change
     * set rather than of the map - the plan marks it the same way, and this set is not where it gets
     * recorded.
     *
     * <p>The set is written down so a <b>new</b> pair is a decision somebody makes rather than a line
     * that quietly stops printing: narrow the claiming rule's triggers, make it {@code demote}, or add
     * it here. Pairs rather than a count, so the failure names which rule to look at.
     */
    private static final Set<String> OVERRULED_CLAIMS = Set.of(
        "B10 -> sweep.block", "B10 -> sweep.item",
        "B11a -> sweep.block", "B11a -> sweep.item",
        "B18 -> manifest.dump.vanilla",
        "B2 -> manifest.dump.vanilla",
        "B21 -> sweep.item",
        "B22 -> manifest.dump.packs", "B22 -> manifest.dump.vanilla",
        "B23 -> manifest.player-raw", "B23 -> sweep.armor", "B23 -> sweep.block",
        "B23 -> sweep.entity", "B23 -> sweep.glint", "B23 -> sweep.item",
        "B24 -> manifest.dump.packs", "B24 -> manifest.dump.vanilla");

    /**
     * Tracked files a rule claims that a {@code no_reach} glob would also excuse.
     *
     * <p>A rule wins wherever both match, so each of these is held up by its rule today and by the
     * excuse list the moment that rule stops matching it. That is the one way a path loses its reach
     * answer without becoming UNKNOWN: the rule narrows or goes, the glob absorbs the path, and the
     * coverage check stays green over a file no rule speaks for any more. Everything else the excuse
     * list holds up is uncontested, so deleting its rule really would show up as uncovered.
     *
     * <p>Each sits under a directory some rule claims wholesale while carrying an excuse that says
     * something narrower and truer about it: three READMEs matched by the markdown glob, and one
     * authoring script named by a glob of its own, under a resource root whose rule speaks for the
     * test suite. Written down rather than counted, so a fifth is a decision somebody makes - narrow
     * the glob, or accept that the path can be absorbed - rather than a widening that arrives with
     * the file.
     *
     * <p>Sorted, because the comparison is against a file list and a list is ordered.
     */
    private static final List<String> ABSORBABLE_BY_AN_EXCUSE = List.of(
        "parity/scripts/parity/README.md",
        "parity/scripts/parity/lab/README.md",
        "src/test/resources/lib/minecraft/renderer/parity/README.md",
        "src/test/resources/scripts/euler_reference_svg.py");

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
     * The inputs the {@code test} task declares, against the operand each is declared on.
     *
     * <p>Every one of them is read by a guard in this package by a route the classpath does not
     * carry, so Gradle learns about it from the declaration alone. Dropping one leaves the guard
     * green over the edit it exists to catch, because the task is never re-run: it is the same defect
     * as an unguarded rule, one level up.
     *
     * <p>The git index is the one no guard opens by name. It is what {@code git ls-files} answers
     * from, and that answer is the operand of every check here that judges the map against what the
     * repository holds - so tracking a file under a root nothing else declares is precisely the edit
     * those checks exist for, and precisely the edit that would otherwise leave them UP-TO-DATE.
     *
     * <p>Every one of them names a path, and the reader below takes a declared value as well, so
     * that a value declared here arrives as a mismatch rather than as a declaration nothing
     * relates. One thing a guard in this package reads is not a file and has no entry: the set of
     * task names this build registers, which reaches it as a flag rather than as an input. It needs
     * none, because the registry is a function of the build scripts and of the versions they pin,
     * and {@link #REGISTRY_SOURCES} holds every file that can move it to this same list.
     */
    private static final Map<String, String> DECLARED_INPUTS = Map.of(
        "parityStore", "parityProductionStore",
        "parityBuildFile", "\"build.gradle.kts\"",
        "parityTriggerRoots", "parityTriggerRoots",
        "paritySkillReferences", "paritySkillReferences",
        "paritySkillFile", "paritySkillFile",
        "parityTestSources", "\"src/test/java\"",
        "parityMainSources", "\"src/main/java\"",
        "parityGitIndex", "\".git/index\"",
        "parityClaudeMd", "\"CLAUDE.md\"");

    /**
     * The files the Gradle task registry is a function of.
     *
     * <p>Two guards in this package resolve a name against that registry, and it reaches them as a
     * flag a {@code doFirst} sets. Measured, three routes re-run those guards and each does it for
     * its own reason: a registration typed into a build script, off that file's own declaration; an
     * edit under {@code gradle/}, off the declaration of that root; and one arriving from an init
     * script, off Gradle's fingerprint of the forwarding action, no declared file being touched.
     * The first two are what the case below keeps in place. The third rests on the forwarding and
     * is not a declaration at all.
     *
     * <p>Directories where the pin is a directory: the wrapper's distribution pin sits under
     * {@code gradle/}, and the Gradle version it selects decides which built-in tasks exist. The
     * version catalogue beside it declares no plugin - it carries {@code [versions]} and
     * {@code [libraries]} only, the one third-party plugin being pinned inline in the build script -
     * so a catalogue edit moves no task and is covered here only by sharing the root.
     *
     * <p>Written down rather than derived from the declaration it is checked against, for the
     * reason the roots above are: derived, it would agree with whatever that declaration says.
     */
    private static final List<String> REGISTRY_SOURCES =
        List.of("build.gradle.kts", "gradle", "settings.gradle.kts");

    /**
     * The roots a triggered file may sit under that {@code parityTriggerRoots} does not name.
     *
     * <p>The build file and the test Java tree are each their own declared input, and everything
     * else under the two source trees reaches the task as compiled classes and processed resources.
     * Those routes are not observable from here, which is why they are written down rather than
     * derived - and why the collection exists at all for everything else.
     */
    private static final List<String> CLASSPATH_ROOTS =
        List.of("build.gradle.kts", "src/main", "src/test");

    /**
     * The files a shared-source artifact's membership is typed in.
     *
     * <p>Two artifacts take one directory as their source and are told apart by a list of members
     * rather than by a directory each. That list is a declaration and not a measurement, so an edit
     * to it moves the artifact's stored rows with no producer having run - which is the one thing the
     * rules over these two files are otherwise right to say they reach nothing.
     *
     * <p>Written down rather than derived <b>from the map</b>, because the point is that these two
     * paths are ordinary members of a wide glob whose rule denies reach: taking them from the same
     * rule that has to name them would make the check agree with whatever the map says. What the
     * check does compare them against is the membership itself - the tracked files that spell out
     * every member of every declared membership - which is the operand that keeps a path dropped
     * from here from quietly narrowing the check instead of failing it.
     *
     * <p>Sorted, because that comparison is against a file list and a list is ordered.
     */
    private static final List<String> MEMBERSHIP_DECLARATIONS =
        List.of("gradle/visual.gradle.kts", "parity/scripts/parity/manifest.py");

    /**
     * The directory a renderer is a direct child of.
     *
     * <p>The library root itself rather than the tree below it, because that is where the roster is
     * defined from: a renderer sits directly in it by construction, and a walk that descended would
     * take every {@code *Renderer.java} in the packages beneath - names the roster has no constant
     * for and is not claiming to have one for.
     */
    private static final String LIBRARY_ROOT = "src/main/java/lib/minecraft/renderer/";

    /** The suffix a renderer's file name ends in, which is what the roster is walked by. */
    private static final String RENDERER_SUFFIX = "Renderer.java";

    /** The annotation token a derived trigger path stands for, as it is written in source. */
    private static final String DECLARATION = "@Parity";

    /** The file a package's declaration is written in, which is what a package glob stands for. */
    private static final String PACKAGE_DECLARATION = "package-info.java";

    /**
     * The source roots a declaration can be written in, which are the only paths derivable.
     *
     * <p>The toolkit's own list in the same order, and the two parting company is silent in both
     * directions: a root only there leaves the checks below saying nothing about a tree the scan
     * reads, and a root only here fails them over declarations nothing can derive. Written down
     * rather than read out of the toolkit, because a check that took its operand from the thing it
     * checks would agree with whatever that says.
     */
    private static final List<String> SOURCE_ROOTS = List.of("src/main/java", "parity/src/main/java",
        "tooling/src/main/java", "tooling/src/test/java", "client/src/main/java",
        "harness/src/client/java");

    /** The lead-in a claiming package's own paragraph opens with. */
    private static final String PARITY_PARAGRAPH = "<p><b>Parity.</b>";

    /** A subject a declaration names, in either the single-constant or the braced form. */
    private static final Pattern DECLARED_SUBJECT =
        Pattern.compile("subject\\s*=\\s*\\{?([^)}]*)}?");

    /** One constant inside a subject list. */
    private static final Pattern SUBJECT_CONSTANT = Pattern.compile("Subject\\.(\\w+)");

    /**
     * The stored artifact whose name identifies each renderer that has one.
     *
     * <p>Seven of the eleven. The atlas renderer's output is registered as no artifact by decision -
     * it does not reproduce, so a digest over it would be a gate failing for its own reasons - and
     * the grid, layout and text renderers have none at all. Those four appear nowhere among the
     * store's ids, which is the fact the roster exists to carry: a subject is the only place in this
     * repository a reader learns that the text renderer is in the parity picture and is ungated.
     *
     * <p>Written down rather than derived from the id grammar, because {@code sweep.armor} and
     * {@code sweep.glint} are named for what is drawn rather than for who draws it, and a rule that
     * split ids on a dot would hand each of them a renderer that does not exist.
     */
    private static final Map<String, String> IDENTIFYING_ARTIFACT = Map.of(
        "BLOCK", "sweep.block",
        "ENTITY", "sweep.entity",
        "FLUID", "manifest.fluid",
        "ITEM", "sweep.item",
        "MENU", "sweep.menu",
        "PLAYER", "sweep.player",
        "PORTAL", "manifest.portal");

    /** The skill body, whose frontmatter is what decides when the gate is offered at all. */
    private static final Path PARITY_SKILL = Path.of(".claude/skills/parity-gate/SKILL.md");

    /** The repository's own rules, whose headings the map's {@code source} column cites by name. */
    private static final Path CLAUDE_MD = Path.of("CLAUDE.md");

    /** One heading of a markdown file, at any level. */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6} (.+)$");

    /** A citation naming a section of the repository's rules, as the {@code source} column spells one. */
    private static final Pattern CLAUDE_MD_CITATION = Pattern.compile("CLAUDE\\.md '([^']*)'");

    /** What a rule's {@code source} says instead of a citation when this map is where a fact lives. */
    private static final String HOME_CLAIM = "moved out of CLAUDE.md and this map is its home";

    /** A verb of custody, which is what turns naming a file into a statement of what it holds. */
    private static final Pattern CUSTODY =
        Pattern.compile("\\b(carries|carry|carrying|holds|hold|home|lives|records)\\b");

    /**
     * The skill's hand-authored statements of what {@code CLAUDE.md} holds, one normalized paragraph
     * each.
     *
     * <p>The rules whose {@code source} carries {@link #HOME_CLAIM} tell a reader that a fact left
     * that file and this map is where it is now. Neither the claim nor a sentence answering it is a
     * citation, so the citation check above reads neither, and the generated view is the map's own
     * column printed back - which leaves a sibling reference sending a reader to {@code CLAUDE.md}
     * for the same fact contradicting the map with nothing between them. That is what happened, in
     * this skill, to this claim.
     *
     * <p>The paragraph whole rather than a phrase, because the contradiction was one clause of a
     * list and a phrase short enough to name it is one a rewording steps around. Paragraphs rather
     * than lines, because these files wrap their prose and a rewrap is not a different statement.
     */
    private static final List<String> CLAUDE_MD_CUSTODY = List.of(
        ".claude/skills/parity-gate/references/diagnostics.md: `CLAUDE.md` in the repo root carries "
            + "the durable findings: the depth contract, the armour shell, the face vocabulary, the "
            + "iso pose. Which artifacts see a given change is not one of them - that answer is "
            + "`blindness.json`, which `parityPlan` resolves and `references/blindness.md` renders, "
            + "and where a rule's claim did come from a section of `CLAUDE.md` the rule cites it by "
            + "name. This file holds the *method* - how those were arrived at - and does not "
            + "restate them.");

    /** One inline code span, which is how the skill body spells each trigger path. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /**
     * What the skill says the precision of its announced trigger list is.
     *
     * <p>Whitespace collapsed, because the file wraps its prose and a rewrap is not a different
     * statement. Both of its halves are measured where the two lists are compared, and the sentence
     * claims exactly what those two measurements answer and no more: nothing the map gives reach is
     * unannounced, and the reverse inclusion fails on at least one path. A magnitude in place of
     * that second half - <b>plenty</b>, <b>most</b> - is a word no population here decides, and
     * would leave a list narrowed to a single coarse path shipping a sentence saying more than the
     * map supports. Neither population reads prose, so the converse sentence - an exact list, every
     * path under one of these seeing something - is one every count here holds under.
     *
     * <p>Compared against the whole shipped sentence and not looked for inside the file, which is
     * the shape the decision-table rows below are pinned in. Over the file, the constant shortened
     * to its own prefix goes on matching and the pin quietly weakens to whatever is left of it -
     * and the clause it would drop is the second half, the one the reverse-inclusion count is the
     * whole evidence for.
     */
    private static final String COARSENESS_CLAIM = "It is a coarse prefix list and not an exact "
        + "one: every path some rule gives a non-empty `sees` is under one of these, and the "
        + "converse does not hold - a path under one of these can reach nothing.";

    /** What that sentence opens with, which is how it is found without being matched by a prefix. */
    private static final String COARSENESS_OPENING = "It is a coarse prefix list";

    /** A gate as a table cell spells one: a Gradle task by itself, or the command line that runs it. */
    private static final Pattern NAMED_GATE =
        Pattern.compile("\\./gradlew [a-z]+|[a-z]+[A-Z][A-Za-z]*");

    /**
     * The condition cell of the decision-table row that proceeds on an empty SEES.
     *
     * <p>The cell verbatim rather than a shape, because the whole value of the split is that each
     * half names a state the resolver reaches, and a reworded condition is how one stops doing so.
     */
    private static final String PROCEEDING_CONDITION =
        "| SEES empty, and one of the fired rules declares `sees: []` |";

    /**
     * That row whole, the behaviour cell included.
     *
     * <p>The behaviour as well as the condition, for the reason its twin below carries the same
     * pin: this is the half of the split that sends a reader somewhere rather than stopping them,
     * and what it sends them to is a task name typed in a table cell. The populations measured
     * against it read no prose, so a cell naming a gate no {@code sees: []} rule asks for would
     * hand every one of those files a task no rule on their paths asked for, with every count
     * below unmoved.
     */
    private static final String PROCEEDING_ON_AN_EMPTY_SEES = PROCEEDING_CONDITION
        + " Proceed on that rule's own terms, which its `reason` states: print the rule id and that "
        + "reason, do what it says, and report the answer. Several name another gate - "
        + "`paritySelfTest` for the toolkit, `./gradlew test` for the test tree and for a "
        + "hand-edited baseline, the resolved argv of the tasks a build edit rewires - and naming "
        + "one makes it the thing to run. A reason that names none has already said what an empty "
        + "`sees` means: no artifact this store holds answers, so say so and gate nothing. |";

    /** The condition cell of the decision-table row that refuses a path nothing speaks for. */
    private static final String UNCOVERED_CONDITION =
        "| A changed path matches no rule and no `no_reach` glob |";

    /**
     * That row whole, the behaviour cell included.
     *
     * <p>Its condition is the coverage measured below, both halves of it: a path reaches this row
     * only when no rule's trigger glob and no excuse claims it. Pinned for the reason the empty-SEES
     * pair is - the refusing one of those two states in its own shipped text that this row has
     * already refused the paths no excuse covers, so a condition narrowed back to the rules alone
     * leaves that sentence citing a mechanism the table no longer has, and every population here
     * reads no prose.
     */
    private static final String REFUSING_AN_UNCOVERED_PATH =
        UNCOVERED_CONDITION + " Refuse (R1). Add the rule or declare `no_reach`. |";

    /** The condition cell of the decision-table row that refuses on an empty SEES. */
    private static final String REFUSING_CONDITION =
        "| SEES empty, and no fired rule declares `sees: []` |";

    /**
     * That row whole, the behaviour cell included.
     *
     * <p>The behaviour as well as the condition, because this is the row that says which mechanism
     * left the answer empty, and the populations measured against it below are what make that the
     * mechanism a reader will meet. A cell that enumerates a second one orders a reader to report
     * which of two cases occurred when the map can only produce one, and a population check reads
     * no prose - so the text is pinned where the measurement is.
     */
    private static final String REFUSING_ON_AN_EMPTY_SEES = REFUSING_CONDITION
        + " Refuse (R2). No rule fired on any changed path at all, R1 above having already refused "
        + "the paths no `no_reach` entry excuses, so the plan listed every one of them under "
        + "`NO REACH` and the map answered ahead of the run rather than as a finding that no "
        + "artifact this store holds moves. Gate nothing, and report that list. If the change does "
        + "move something, the excuse absorbing its path is what is wrong - replace it with a rule, "
        + "and name it. |";

    @Test
    @DisplayName("every CLAUDE.md section a rule cites is a heading CLAUDE.md still carries")
    void everyClaudeMdCitationNamesALiveHeading() {
        Set<String> headings = new TreeSet<>();
        Matcher heading = MARKDOWN_HEADING.matcher(text(CLAUDE_MD));
        while (heading.find()) headings.add(heading.group(1).trim());

        List<String> cited = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (JsonObject rule : rules()) {
            Matcher citation = CLAUDE_MD_CITATION.matcher(rule.get("source").getAsString());
            while (citation.find()) {
                cited.add(rule.get("id").getAsString() + " -> " + citation.group(1));
                if (!headings.contains(citation.group(1))) dead.add(cited.getLast());
            }
        }

        assertThat("CLAUDE.md carries no heading at all, so every citation below would read as dead "
            + "and the failure would be in this walk rather than in the map", headings, is(not(empty())));
        assertThat("no rule cites a CLAUDE.md section, so this case has no operand; it needs a "
            + "different shape rather than deleting", cited, is(not(empty())));
        assertThat("rules citing a CLAUDE.md section that file no longer has a heading for. A "
            + "`source` is where the claim came from, and a reader following one to a heading that "
            + "was deleted or renamed cannot tell a moved rule from an abandoned one - eleven of "
            + "these went dead in a single rebuild with nothing mechanical able to notice, because "
            + "the only check over the column was that it is non-blank. A sentence this map is now "
            + "the sole home of says so in prose and cites nothing, which is what keeps this "
            + "operand meaning what it says", dead, is(empty()));
    }

    @Test
    @DisplayName("nothing else states a home for what a rule says moved out of CLAUDE.md")
    void theMapKeepsTheHomeItsSourceColumnClaims() {
        List<String> claiming = rules().stream()
            .filter(rule -> rule.get("source").getAsString().contains(HOME_CLAIM))
            .map(rule -> rule.get("id").getAsString())
            .toList();
        List<String> handAuthored = handAuthoredSkillFiles();
        List<String> custody = handAuthored.stream()
            .flatMap(path -> paragraphs(text(Path.of(path))).stream()
                .filter(paragraph -> paragraph.contains("CLAUDE.md"))
                .filter(paragraph -> CUSTODY.matcher(paragraph).find())
                .map(paragraph -> path + ": " + paragraph))
            .toList();
        String rulebook = text(CLAUDE_MD);
        List<String> named = ParityArtifacts.ALL.stream()
            .map(ParityArtifacts.Registration::id)
            .filter(rulebook::contains)
            .sorted()
            .toList();

        assertThat("no rule's source claims this map is a fact's home, so both clauses below are "
            + "true of nothing; this case needs a different shape rather than deleting", claiming,
            is(not(empty())));
        assertThat("the skill ships no hand-authored file, which would leave the comparison below "
            + "empty on the side it is derived from", handAuthored, is(not(empty())));
        assertThat("the skill's hand-authored prose saying what CLAUDE.md holds, against the "
            + "statements recorded here. " + claiming + " say a fact moved out of that file and "
            + "this map is where it is now, and a sibling reference of the same skill sending a "
            + "reader back there for it is a contradiction both halves of the skill are loaded "
            + "with - a claim naming no section and an answer that is not a citation, so the check "
            + "above sees neither. A new statement of custody is a decision: make it agree with the "
            + "map, or record it here", custody, equalTo(CLAUDE_MD_CUSTODY));
        assertThat("artifact ids CLAUDE.md names. A statement of what a gate sees has to name the "
            + "gate, and a gate is an artifact of this store - so an id in that file is the answer "
            + "those rows say moved out of it, growing a second home back where it was. The map is "
            + "where the statement goes; citing a CLAUDE.md section as where a rule's claim came "
            + "from is the other case, and the check above is what holds that citation live",
            named, is(empty()));
    }

    /**
     * The skill's files a rendering does not re-derive, which are the ones a human writes into.
     *
     * <p>The two generated references are left out because they are this map's own columns printed
     * back: holding a sentence there to the map would be holding the map to itself, and the byte
     * comparison over the render already does it.
     *
     * @return the paths, sorted
     */
    private static List<String> handAuthoredSkillFiles() {
        String home = PARITY_SKILL.getParent().toString().replace('\\', '/') + "/";
        List<String> generated = ParityReferences.GENERATED.stream()
            .map(name -> ParityReferences.HOME.resolve(name).toString().replace('\\', '/'))
            .toList();
        return trackedFiles().stream()
            .filter(path -> path.startsWith(home))
            .filter(path -> !generated.contains(path))
            .sorted()
            .toList();
    }

    /**
     * The blank-line separated paragraphs of one markdown file.
     *
     * @param markdown the file's text
     * @return the paragraphs, whitespace collapsed, in file order
     */
    private static List<String> paragraphs(String markdown) {
        return Stream.of(markdown.split("\\n\\s*\\n"))
            .map(paragraph -> paragraph.replaceAll("\\s+", " ").trim())
            .filter(paragraph -> !paragraph.isEmpty())
            .toList();
    }

    /**
     * The gates one decision-table row names.
     *
     * <p>A backticked span that is a Gradle task by itself or a whole {@code ./gradlew} command
     * line. The other spans a row carries name a map field or a JSON value, which is no gate.
     *
     * @param row the decision-table row
     * @return the gates, in the order the row names them
     */
    private static List<String> namedGates(String row) {
        List<String> out = new ArrayList<>();
        Matcher span = BACKTICKED.matcher(row);
        while (span.find())
            if (NAMED_GATE.matcher(span.group(1)).matches()) out.add(span.group(1));
        return out;
    }

    @Test
    @DisplayName("the skill's two trigger lists are one list, and it covers every path with reach")
    void theSkillsTriggerListAgreesWithTheMap() {
        List<String> frontmatter = skillFrontmatterTriggers();
        List<String> body = skillBodyTriggers();
        List<JsonObject> rules = rules();
        List<String> tracked = trackedFiles();
        List<String> seeing = tracked.stream().filter(path -> !seesFor(path, rules).isEmpty()).toList();
        List<Pattern> patterns = frontmatter.stream().map(BlindnessMapTest::compileGlob).toList();

        List<String> unannounced = seeing.stream()
            .filter(path -> patterns.stream().noneMatch(pattern -> pattern.matcher(path).matches()))
            .sorted()
            .toList();
        List<String> carrying = frontmatter.stream()
            .filter(glob -> seeing.stream().anyMatch(path -> compileGlob(glob).matcher(path).matches()))
            .toList();
        List<String> announcedReachingNothing = tracked.stream()
            .filter(path -> patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches()))
            .filter(path -> seesFor(path, rules).isEmpty())
            .toList();

        assertThat("the frontmatter announces no trigger path, which would leave every clause below "
            + "with nothing to be true of", frontmatter, is(not(empty())));
        assertThat("no tracked file resolves to a non-empty sees, so the map answers nothing and the "
            + "coverage clause holds over an empty set", seeing, is(not(empty())));
        assertThat("the trigger paths the frontmatter announces, against the ones the body lists. "
            + "The frontmatter is what makes auto-invocation fire and the body is what a reader "
            + "checks, so the two disagreeing means one half of one file is wrong and neither half "
            + "says which", body, equalTo(frontmatter));
        assertThat("tracked files some rule gives a non-empty sees that no announced trigger path "
            + "matches. That is a change the map can gate and the skill is never offered for, which "
            + "is the expensive direction: the hook still answers and asks, so it costs a proactive "
            + "invocation rather than the gate - and it is exactly what a rule added without its "
            + "path being announced looks like", unannounced, is(empty()));
        assertThat("announced trigger paths against the ones that match a file with reach. The list "
            + "is deliberately coarser than the map - plenty of files under these prefixes reach "
            + "nothing, and narrowing the change is the answer to that - but a path announced that "
            + "reaches nothing at all offers the gate for a commit no artifact can see",
            carrying, equalTo(frontmatter));
        assertThat("tracked files an announced trigger path matches that no rule gives any reach. "
            + "This is the half of the claim below the clause above cannot make: coverage says every "
            + "path with reach is announced, and only this says the reverse inclusion fails. Empty, "
            + "the two lists would coincide, the announcement would be exact and the sentence would "
            + "be the wrong one. That is the whole of what the sentence claims, deliberately - a "
            + "magnitude reading on it would be a word this population does not decide, true of a "
            + "list narrowed to one coarse path exactly as of this one",
            announcedReachingNothing, is(not(empty())));
        assertThat("what the skill says the precision of that list is, against the sentence it "
            + "ships, whole. The two clauses above are what make it true, and neither of them reads "
            + "prose: the sentence replaced by its converse - an exact list, every path under one "
            + "of these seeing something - leaves both of them where they are and ships a claim "
            + "this map refutes on every file the clause above just counted. The whole sentence "
            + "rather than a fragment sought inside the file, so a claim cut back to its opening "
            + "clause fails here instead of going on matching what is left of it",
            sentence(text(PARITY_SKILL).replaceAll("\\s+", " "), COARSENESS_OPENING),
            equalTo(COARSENESS_CLAIM));
    }

    /**
     * The trigger paths the skill's frontmatter announces, which is what auto-invocation fires on.
     *
     * <p>Read out of the prose sentence rather than a structured field, because the frontmatter
     * {@code description} is one string by the format's own grammar and the list is a clause of it.
     *
     * @return the globs, in the order the sentence names them
     */
    private static List<String> skillFrontmatterTriggers() {
        return Stream.of(slice(text(PARITY_SKILL), "the working tree touches ", ". Resolves")
                .split(",| or "))
            .map(String::trim)
            .filter(glob -> !glob.isEmpty())
            .toList();
    }

    /**
     * The trigger paths the skill's body lists, which is what a reader checks against.
     *
     * @return the globs, in the order the list names them
     */
    private static List<String> skillBodyTriggers() {
        String list = slice(text(PARITY_SKILL), "**The tree touches a trigger path** - ",
            " - the same list the frontmatter carries");
        List<String> out = new ArrayList<>();
        Matcher glob = BACKTICKED.matcher(list);
        while (glob.find()) out.add(glob.group(1));
        return out;
    }

    /**
     * The one sentence of a collapsed text that opens with the given words.
     *
     * <p>The unit a prose claim is pinned as, the way a table row is the unit a cell is pinned as:
     * returned whole, so the comparison at the call site can be an equality and a constant cut back
     * to a prefix of the shipped sentence fails rather than still being found inside it. An absent
     * opening comes back as the empty string, which fails that equality too.
     *
     * @param collapsed the text, whitespace already collapsed
     * @param opening the words the sentence starts with
     * @return the sentence including its full stop, or the empty string when the text has no such
     *     sentence
     */
    private static String sentence(String collapsed, String opening) {
        int start = collapsed.indexOf(opening);
        if (start < 0) return "";
        int end = collapsed.indexOf(". ", start);
        return end < 0 ? collapsed.substring(start) : collapsed.substring(start, end + 1);
    }

    /**
     * The text between two markers, exclusive of both.
     *
     * @param source the file's text
     * @param opening what the slice starts after
     * @param terminator what closes it
     * @return the text between them
     */
    private static String slice(String source, String opening, String terminator) {
        int start = source.indexOf(opening);
        if (start < 0) throw new AssertionError("no '" + opening + "' in " + PARITY_SKILL);
        int body = start + opening.length();
        int end = source.indexOf(terminator, body);
        if (end < 0) throw new AssertionError("'" + opening + "' is not closed by '" + terminator + "'");
        return source.substring(body, end);
    }

    @Test
    @DisplayName("both of the decision table's empty-SEES rows are states this map reaches")
    void bothEmptySeesRowsAreReachable() {
        String skill = text(PARITY_SKILL);
        List<JsonObject> rules = rules();
        List<String> reachingNothing = trackedFiles().stream()
            .filter(path -> seesFor(path, rules).isEmpty())
            .toList();
        List<String> proceeds = reachingNothing.stream()
            .filter(path -> firedOn(path, rules).stream()
                .anyMatch(rule -> rule.getAsJsonArray("sees").isEmpty()))
            .toList();
        List<String> refuses = reachingNothing.stream().filter(path -> !proceeds.contains(path)).toList();
        List<String> selectionRemoved = refuses.stream()
            .filter(path -> !firedOn(path, rules).isEmpty())
            .toList();
        List<String> gates = namedGates(tableRow(skill, PROCEEDING_CONDITION));
        List<String> unasked = gates.stream()
            .filter(gate -> rules.stream()
                .filter(rule -> rule.getAsJsonArray("sees").isEmpty())
                .noneMatch(rule -> rule.get("reason").getAsString().contains(gate)))
            .toList();

        assertThat("the decision table's proceeding row, against the row the populations below are "
            + "measured for. Its condition is what decides which files it speaks for and its "
            + "behaviour is what it hands them instead of a gate, so a reword of either is an "
            + "answer this case has to re-measure rather than one it can keep vouching for",
            tableRow(skill, PROCEEDING_CONDITION), equalTo(PROCEEDING_ON_AN_EMPTY_SEES));
        assertThat("the proceeding row names no gate, so the clause below holds over an empty set "
            + "and the row could name any task at all", gates, is(not(empty())));
        assertThat("gates the proceeding row names, against the ones a `sees: []` rule's reason "
            + "asks for. The row's whole instruction is to run what the rule that fired names, so a "
            + "task no such rule names is one this map never asks for on any path - and the files "
            + "counted below are exactly the ones sent to it", unasked, is(empty()));
        assertThat("the decision table's refusing row, against the row the populations below are "
            + "measured for. Its condition is what decides which files it speaks for and its "
            + "behaviour is what it tells a reader happened to them, so a reword of either is an "
            + "answer this case has to re-measure rather than one it can keep vouching for",
            tableRow(skill, REFUSING_CONDITION), equalTo(REFUSING_ON_AN_EMPTY_SEES));
        assertThat("tracked files that resolve to an empty SEES, which is what both rows above are "
            + "conditions on", reachingNothing, is(not(empty())));
        assertThat("tracked files the proceeding row speaks for - an empty SEES with a fired rule "
            + "declaring `sees: []`, which is the rule naming the gate to run instead", proceeds,
            is(not(empty())));
        assertThat("tracked files the refusing row speaks for - an empty SEES with no fired rule "
            + "declaring `sees: []`. A refusal is only worth a row when the resolver can produce the "
            + "state it names, and this one is reached by the paths an excuse absorbs rather than by "
            + "anything a rule selects", refuses, is(not(empty())));
        assertThat("tracked files that reach the refusing row with a rule fired on them, which is an "
            + "empty SEES a demotion or a suppression left behind after another rule had selected "
            + "something. The row names one mechanism - no rule firing at all - because that is the "
            + "only one the shipped map produces on any path: its one suppression declares an empty "
            + "sees, and a demotion removes another rule's blind list rather than its own selection. "
            + "A file here is a second mechanism the row does not describe, so the row is what to "
            + "rewrite - a cell naming a case nothing reaches is what this clause exists to keep out "
            + "of it", selectionRemoved, is(empty()));
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

    /**
     * Returns the rules that fire on one path.
     *
     * @param path the changed path
     * @param rules the rules to match against
     * @return the rules whose trigger globs match it, in map order
     */
    private static List<JsonObject> firedOn(String path, List<JsonObject> rules) {
        return rules.stream()
            .filter(rule -> {
                for (JsonElement glob : rule.getAsJsonArray("trigger_paths"))
                    if (compileGlob(glob.getAsString()).matcher(path).matches()) return true;
                return false;
            })
            .toList();
    }

    @Test
    @DisplayName("every trigger glob matches a tracked file")
    void noRuleIsOrphaned() {
        List<String> tracked = trackedFiles();
        List<String> orphaned = new ArrayList<>();
        for (JsonObject rule : rules()) {
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                if (tracked.stream().noneMatch(path -> pattern.matcher(path).matches()))
                    orphaned.add(rule.get("id").getAsString() + " -> " + glob.getAsString());
            }
        }
        assertThat("git tracks nothing these trigger globs match, which is now two failures rather "
            + "than one. Over an authored path it is what it always was: a rename orphaned the rule "
            + "and the gate it speaks for silently stopped being consulted. Over a generated path it "
            + "is a move nobody regenerated, so the map states a reach no declaration makes - which "
            + "the scan above names more precisely, and this one catches whether or not the file it "
            + "points at still exists", orphaned, is(empty()));
    }

    @Test
    @DisplayName("no authored path under a scanned source root is an exact file")
    void noAuthoredPathIsAnExactSourceFile() {
        List<String> exact = new ArrayList<>();
        for (JsonObject rule : rules())
            for (JsonElement path : rule.getAsJsonArray("authored_paths")) {
                String authored = path.getAsString();
                if (underASourceRoot(authored) && !authored.contains("*") && !authored.contains("?"))
                    exact.add(rule.get("id").getAsString() + " -> " + authored);
            }

        assertThat("authored paths under a source root that name one file exactly. That is the "
            + "one path shape a move leaves stale, and it is the shape this whole mechanism exists "
            + "to retire: a file under this root can carry its own declaration, so an exact path "
            + "authored beside it is a second handle on the same claim that nothing keeps honest. "
            + "A glob here is different and stays allowed - it says something no single file can",
            exact, is(empty()));
    }

    @Test
    @DisplayName("every claiming package says in prose what it claims")
    void everyClaimingPackageStatesItInProse() {
        List<String> silent = trackedFiles().stream()
            .filter(path -> underASourceRoot(path) && path.endsWith(PACKAGE_DECLARATION))
            .filter(path -> text(Path.of(path)).contains(DECLARATION))
            .filter(path -> !text(Path.of(path)).contains(PARITY_PARAGRAPH))
            .sorted()
            .toList();
        List<String> claiming = trackedFiles().stream()
            .filter(path -> underASourceRoot(path) && path.endsWith(PACKAGE_DECLARATION))
            .filter(path -> text(Path.of(path)).contains(DECLARATION))
            .toList();

        assertThat("no package in any source root declares a claim, which would leave the check "
            + "below true of nothing", claiming, is(not(empty())));
        assertThat("packages that declare a claim and whose javadoc says nothing about it. A package "
            + "declaration is carried by every file below it, so its whole meaning would otherwise "
            + "sit in a file in another source tree and a reader standing in the package would have "
            + "no way to know it was in one. Presence of the paragraph only and never agreement with "
            + "the row: prose cannot be held to a measurement, and a check that pretended otherwise "
            + "would make the guard a liar rather than the paragraph", silent, is(empty()));
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
            + "writes, the map itself - so a rule reaching one of those refuses the capture as its "
            + "dependencies are resolved exactly as a pointer would", unrunnable, is(empty()));
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
            + "table has no row for, so reading it refuses parityCapture as Gradle resolves that "
            + "task's dependencies, for every change whose reach includes one",
            keys, equalTo(List.of("plan")));
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
    @DisplayName("the test task declares every input its guards read")
    void theTestTaskDeclaresWhatItsGuardsRead() {
        Map<String, String> declared = testTaskInputs();

        assertThat("build.gradle.kts declares no inputs on the Test task, which would leave this "
            + "check vacuous", declared.keySet(), is(not(empty())));
        assertThat("what the Test task declares as an input, against what a guard in this package "
            + "reads by a route the classpath does not carry. A declaration on any other task type "
            + "does not answer: UP-TO-DATE is decided per task, so a guard whose operand nothing "
            + "declares here goes green over the edit it exists to catch and never runs again. A "
            + "path and a value are both declarations and both are read here, because a check "
            + "scoped to one form excuses the other from ever being missed",
            declared, equalTo(DECLARED_INPUTS));
    }

    @Test
    @DisplayName("every file the task registry is a function of is a declared input")
    void theRegistrySourcesAreDeclaredInputs() {
        Map<String, String> declared = testTaskInputs();
        List<String> reachable = new ArrayList<>(declared.values().stream()
            .filter(operand -> operand.startsWith("\"") && operand.endsWith("\""))
            .map(operand -> operand.substring(1, operand.length() - 1))
            .toList());
        // The trigger collection is one declaration over many roots, so what it reaches is the roots
        // it names - read from the collection itself, because a check reading only the declaration's
        // own spelling would be satisfied by the word `parityTriggerRoots` and nothing under it.
        if (declared.containsValue("parityTriggerRoots")) reachable.addAll(buildFileTriggerRoots());
        List<String> undeclared = REGISTRY_SOURCES.stream()
            .filter(source -> reachable.stream().noneMatch(root -> under(root, source)))
            .toList();

        assertThat("the Test block declares no input naming a path, which would leave this check "
            + "vacuous", reachable, is(not(empty())));
        assertThat("files a task registration can arrive in that no declared input of the test task "
            + "reaches. A registration typed into one of these re-runs the suite off that file's own "
            + "declaration - measured, by deleting each of these in turn and moving the registry "
            + "behind it. Dropped, a renamed task ships with a guard resolving a name against the "
            + "registry answering from the list the previous run forwarded, unless the same move "
            + "also moves the forwarding action Gradle fingerprints",
            undeclared, is(empty()));
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
        // The same two mandatory fields, over the list every awkward path goes into. A bare glob
        // records that somebody decided a path reaches nothing and never why, which is the one
        // declaration in this file that costs nothing to write and cannot be re-checked.
        for (JsonObject entry : noReach()) {
            String glob = entry.has("glob") ? entry.get("glob").getAsString() : "";
            if (glob.isBlank()) defects.add("a no_reach entry has a blank glob");
            for (String field : List.of("reason", "probe"))
                if (!entry.has(field) || entry.get(field).getAsString().isBlank())
                    defects.add("no_reach " + glob + " has a blank " + field);
        }

        assertThat("the map declares no no_reach entries, which would leave half of this check "
            + "vacuous", noReach(), is(not(empty())));
        assertThat("rules that state no mechanism or offer no falsification", defects, is(empty()));
    }

    @Test
    @DisplayName("every no_reach glob matches a tracked file, and the exempt one matches none")
    void noNoReachGlobIsOrphaned() {
        List<String> tracked = trackedFiles();
        List<String> orphaned = new ArrayList<>();
        List<String> exemptButMatching = new ArrayList<>();
        for (JsonObject entry : noReach()) {
            String glob = entry.get("glob").getAsString();
            Pattern pattern = compileGlob(glob);
            boolean matches = tracked.stream().anyMatch(path -> pattern.matcher(path).matches());
            boolean exempt = MAY_MATCH_NOTHING.contains(glob);
            if (exempt && matches) exemptButMatching.add(glob);
            if (!exempt && !matches) orphaned.add(glob);
        }

        assertThat("the exempt globs are still in the map, so the exemption above is not itself "
            + "stale", noReach().stream().map(entry -> entry.get("glob").getAsString()).toList(),
            hasItems(MAY_MATCH_NOTHING.toArray(new String[0])));
        // Both directions, because the exemption is the branch that would otherwise never be taken:
        // a glob excused for matching nothing, that has since started matching tracked files, is an
        // exemption nobody would notice had gone wrong.
        assertThat("globs excused from the check that git now tracks something for. The excuse is "
            + "that the repository holds none of what they name, so one that does hold something is "
            + "an ordinary entry and the exemption is hiding it", exemptButMatching, is(empty()));
        assertThat("no_reach globs git tracks nothing for. This list is the one that holds paths OUT "
            + "of reach resolution, so a rotted or over-broad entry keeps something uncovered without "
            + "any rule saying so - the same orphaning a trigger glob is checked for, on the half "
            + "that was never checked. Tracked rather than present on disk, because an untracked file "
            + "is on nobody else's checkout", orphaned, is(empty()));
    }

    @Test
    @DisplayName("every store-homed artifact is named by some rule, as seen or as blind")
    void everyStoredArtifactIsSpokenFor() {
        // The store's own two root files are the exemption, and it is a property rather than a list:
        // no producer writes one, no capture holds one and no promotion baselines one, so a rule
        // naming either would be a rule about the map's own scaffolding.
        List<String> candidates = ParityArtifacts.withHome(ParityArtifacts.Home.STORE).stream()
            .filter(id -> !ParityStore.isRootFile(id)).sorted().toList();
        Set<String> named = namedArtifacts(rules());
        List<String> silent = candidates.stream().filter(id -> !named.contains(id)).toList();

        // Synthetic, because the shipped map reaches every store artifact through some rule's sees
        // and leaves the other half of the disjunction unexercised: an artifact declared blind and
        // never selected is exactly what the second field is for, and without a case for it this
        // check holds with that field dropped.
        assertThat("an artifact a rule names only as blind is spoken for. That is the whole point of "
            + "a blind entry - the real gate gets written down - so it answers this check exactly as "
            + "a sees entry does", namedArtifacts(List.of(
                syntheticRule("S", "select", "a/**", List.of(), List.of("sweep.block")))),
            equalTo(Set.of("sweep.block")));
        assertThat("the roster homes no artifact in the store, which would leave this check with "
            + "nothing to be true of", candidates, is(not(empty())));
        assertThat("store artifacts no rule names in either direction. A baselined artifact no plan "
            + "can select and no rule calls blind is gated by whatever its reader happens to be, "
            + "which is an inference rather than a statement - the point of a blind entry is that "
            + "the real gate gets written down", silent, is(empty()));
    }

    @Test
    @DisplayName("a blindness claim another rule overrules is one the map has already recorded")
    void everyOverruledClaimIsRecorded() {
        List<String> tracked = trackedFiles();
        Set<String> overruled = new TreeSet<>();
        for (JsonObject rule : rules()) {
            String id = rule.get("id").getAsString();
            Set<String> blind = strings(rule.getAsJsonArray("blind"));
            if (blind.isEmpty()) continue;
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                for (String path : tracked) {
                    if (!pattern.matcher(path).matches()) continue;
                    Set<String> sees = seesFor(path);
                    blind.stream().filter(sees::contains)
                        .forEach(artifact -> overruled.add(id + " -> " + artifact));
                }
            }
        }
        // Printed for the same reason the coverage count is: the reader of a failure wants the whole
        // set, not only the row that moved.
        System.out.printf("overruled blindness claims: %s%n", overruled);

        assertThat("blindness claims another rule's sees overrules on the claiming rule's own "
            + "triggers. Each one is printed by the plan as \"claimed blind, selected by <rule>\" "
            + "rather than dropped, so the bundle is unchanged and the answer is honest - but a NEW "
            + "one is a decision: narrow the claiming rule, make it demote, or record it here",
            overruled, equalTo(new TreeSet<>(OVERRULED_CLAIMS)));
    }

    @Test
    @DisplayName("the rules cover every tracked file, and the coverage is not one wide glob")
    void theMapCoversEveryTrackedFile() {
        List<String> tracked = trackedFiles();
        List<Pattern> noReach = noReach().stream()
            .map(entry -> compileGlob(entry.get("glob").getAsString()))
            .toList();

        Set<String> ruled = new LinkedHashSet<>();
        TreeMap<String, Integer> contribution = new TreeMap<>();
        for (JsonObject rule : rules()) {
            String id = rule.get("id").getAsString();
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                Pattern pattern = compileGlob(glob.getAsString());
                for (String path : tracked)
                    if (pattern.matcher(path).matches()) {
                        ruled.add(path);
                        contribution.merge(id, 1, Integer::sum);
                    }
            }
        }
        // Only what no rule already claims, because a rule wins over the excuse list wherever both
        // match - so a glob here that overlaps a rule is answering for nothing, and counting it as
        // coverage would hide the rule going away underneath it.
        List<String> excused = tracked.stream()
            .filter(path -> !ruled.contains(path))
            .filter(path -> noReach.stream().anyMatch(pattern -> pattern.matcher(path).matches()))
            .toList();
        Set<String> covered = new LinkedHashSet<>(ruled);
        covered.addAll(excused);
        List<String> uncovered = tracked.stream().filter(path -> !covered.contains(path)).toList();
        List<String> bothWays = ruled.stream()
            .filter(path -> noReach.stream().anyMatch(pattern -> pattern.matcher(path).matches()))
            .sorted()
            .toList();
        // Printed as well as asserted: a single rule covering everything would satisfy the count while
        // telling a reader nothing, so the per-rule contribution is visible in the output, and so is
        // how much of the tree is held up by an excuse rather than by a claim. The overlap is printed
        // beside them because it is the difference between the two readings of "excused".
        System.out.printf("blindness coverage: %d/%d tracked files, %d excused by no_reach, "
                + "%d also claimed by a rule, %d rules contributed %s%n",
            covered.size(), tracked.size(), excused.size(), bothWays.size(), contribution.size(),
            contribution);

        assertThat("git tracks nothing, which would leave this check with no operand at all", tracked,
            is(not(empty())));
        assertThat("the decision table's row for a path nothing speaks for, against the row this "
            + "coverage is measured for. Its condition is the two lists below unioned - a rule's "
            + "triggers and the excuse list - so a condition naming only the rules refuses a state "
            + "this measurement says nothing about, and the row that refuses an empty SEES cites "
            + "this one for exactly the mechanism the dropped half is",
            tableRow(text(PARITY_SKILL), UNCOVERED_CONDITION), equalTo(REFUSING_AN_UNCOVERED_PATH));
        assertThat("tracked files no rule and no no_reach glob covers - each one is UNKNOWN at gate "
            + "time, which refuses every plan that touches it. The whole index rather than one source "
            + "tree, because the map speaks for the harness, the toolkit, the build wiring and both "
            + "resource roots as well, and a walk of one of them leaves the rest of the map free to "
            + "orphan a path with this check still green", uncovered, is(empty()));
        assertThat("tracked files a rule claims that a no_reach glob would also excuse, against the "
            + "ones recorded above. Each is held up by its rule today and by the excuse list the "
            + "moment that rule stops matching it, so it is the one path shape that can lose its "
            + "reach answer without ever becoming UNKNOWN and without reddening the clause above. A "
            + "new one is a decision: narrow the glob, or record it here", bothWays,
            equalTo(ABSORBABLE_BY_AN_EXCUSE));
        assertThat("the two halves of that coverage added up, against the set they cover. A rule "
            + "wins wherever both match and the overlap above is not empty, so crediting the excuse "
            + "list for a path a rule already claims counts it twice here - and the clause below "
            + "would then hold on coverage some rule is really providing",
            ruled.size() + excused.size(), equalTo(covered.size()));
        assertThat("nothing is covered by no_reach alone, so that half contributes no coverage here "
            + "and this check would hold with the excuse list deleted", excused, is(not(empty())));
        assertThat("coverage should come from many rules rather than one catch-all",
            contribution.size() > 1, is(true));
    }

    @Test
    @DisplayName("the files a membership is declared in reach the artifacts that membership defines")
    void theMembershipDeclarationsReachWhatTheyDefine() {
        List<String> declared = declaredMemberships();
        List<String> spelling = filesSpellingEveryMembership();
        List<String> unreached = new ArrayList<>();
        for (String path : MEMBERSHIP_DECLARATIONS) {
            Set<String> sees = seesFor(path);
            declared.stream().filter(id -> !sees.contains(id))
                .forEach(id -> unreached.add(path + " -> " + id));
        }

        assertThat("the toolkit gives no artifact an explicit member list, which would leave this "
            + "check with nothing to be true of", declared, is(not(empty())));
        assertThat("no tracked file spells out every member of every declared membership, which "
            + "would leave the comparison below vacuous on the side it is derived from", spelling,
            is(not(empty())));
        assertThat("the tracked files that spell out every member of every declared membership, "
            + "against the ones the loop above resolves. A path this list has lost stops being "
            + "checked while the check stays green, and an empty list satisfies the loop whatever "
            + "the map answers - so the operand is compared against the membership rather than "
            + "merely asserted non-empty. A third file deciding a membership fails here rather than "
            + "going unnoticed", spelling, equalTo(MEMBERSHIP_DECLARATIONS));
        assertThat("artifacts whose membership is declared in a file the map answers nothing for. "
            + "A member list decides which sub-trees the manifest hashes, so editing one adds or "
            + "drops stored rows without a producer having run - the one reach the wide rules over "
            + "the toolkit and the build wiring cannot deny, and the one they have to carve out. "
            + "Derived from the toolkit's own member map, so a third artifact getting a list is a "
            + "failure here rather than a silence", unreached, is(empty()));
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

    @Test
    @DisplayName("the resolver above applies select, demote and suppress the way the toolkit does")
    void theResolverAppliesEveryMode() {
        // Synthetic, because the shipped map exercises two of the three modes: its one suppression
        // declares neither a sees nor a blind, so resolving any real path leaves that pass with
        // nothing to remove and the two checks that read this resolver would pass with it deleted.
        List<JsonObject> map = List.of(
            syntheticRule("S", "select", "a/**", List.of("sweep.block", "sweep.item"), List.of()),
            syntheticRule("D", "demote", "a/**", List.of("sweep.entity"), List.of("sweep.item")),
            syntheticRule("P", "suppress", "a/x.java", List.of("sweep.entity"), List.of("sweep.block")),
            syntheticRule("N", "select", "b/**", List.of("sweep.armor"), List.of()));

        assertThat("a path only one rule triggers on takes that rule's sees and nothing else - which "
            + "is what says the two passes below remove rather than never contribute",
            seesFor("b/y.java", map), equalTo(Set.of("sweep.armor")));
        assertThat("a demotion removes what another rule selected for the same path and leaves its "
            + "own sees standing, which is the only way a rule speaks about artifacts it does not "
            + "itself select", seesFor("a/y.java", map),
            equalTo(Set.of("sweep.block", "sweep.entity")));
        assertThat("a suppression outranks both by removing its own sees as well as its blind, so an "
            + "artifact it names is inadmissible whatever selected it", seesFor("a/x.java", map),
            is(empty()));
    }

    @Test
    @DisplayName("every derived trigger path still names a file that declares its claim")
    void everyDerivedPathStillCarriesItsDeclaration() {
        List<String> gone = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        for (JsonObject rule : rules()) {
            String key = rule.has("claim_key") ? rule.get("claim_key").getAsString() : "";
            if (key.isEmpty()) continue;
            Set<String> authored = strings(rule.getAsJsonArray("authored_paths"));
            for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
                String trigger = glob.getAsString();
                if (authored.contains(trigger)) continue;
                Path carrier = Path.of(carrierOf(trigger));
                if (!Files.isRegularFile(carrier)) {
                    gone.add(rule.get("id").getAsString() + " -> " + carrier);
                    continue;
                }
                // A token scan and never a parse, because a second reader of this grammar is a
                // second thing to drift: the toolkit owns the parse and this says only that the
                // file a generated path stands for still says something about this claim.
                String source = text(carrier);
                if (!source.contains(DECLARATION) || !(source.contains('"' + key + '"')
                    || source.contains("as = ")))
                    silent.add(rule.get("id").getAsString() + " -> " + carrier);
            }
        }

        assertThat("generated trigger paths naming a file that is no longer there. A commit moved "
            + "or renamed a file carrying a declaration and did not regenerate, so the planner "
            + "fires this rule on a path nothing occupies and the change that really moved is "
            + "planned by whatever else happens to claim its new home", gone, is(empty()));
        assertThat("generated trigger paths naming a file that declares nothing about this claim. "
            + "Either the declaration was deleted and the map still carries what it derived, or it "
            + "was retargeted at another claim - and both leave a rule reaching a file whose own "
            + "source no longer says so. A token scan, so a file joining by 'as' answers on the "
            + "join rather than on the slug; what re-derives the exact set is the generator, and "
            + "the toolkit's suite is what holds this file's answer to it", silent, is(empty()));
    }

    @Test
    @DisplayName("every rule reaches at least one path")
    void everyRuleHasAHome() {
        List<String> homeless = rules().stream()
            .filter(rule -> rule.getAsJsonArray("trigger_paths").isEmpty())
            .map(rule -> rule.get("id").getAsString()
                + (rule.has("claim_key") ? " (" + rule.get("claim_key").getAsString() + ")" : ""))
            .toList();

        assertThat("rules whose trigger list is empty. A rule's last carrier was deleted or its "
            + "claim_key was typed wrong, so the generator had nothing to put in the list and the "
            + "rule now fires on no path at all - which is not a rule that says nothing, it is a "
            + "gate that silently stopped being consulted. Nothing else here can see it: every "
            + "other check over this list is quantified over the paths it holds and is vacuously "
            + "true of none", homeless, is(empty()));
    }

    @Test
    @DisplayName("a claim naming one renderer reaches that renderer's picture and no other's")
    void everyRendererArtifactIsNamedByItsSubject() {
        List<String> contradicted = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (JsonObject rule : rules()) {
            String key = rule.has("claim_key") ? rule.get("claim_key").getAsString() : "";
            Set<String> named = subjectsOf(rule);
            if (key.isEmpty() || named.size() != 1) continue;
            String subject = named.iterator().next();
            Set<String> sees = strings(rule.getAsJsonArray("sees"));
            checked.add(key + " -> " + subject);
            IDENTIFYING_ARTIFACT.forEach((renderer, artifact) -> {
                if (renderer.equals(subject)) {
                    if (!sees.contains(artifact)) absent.add(key + " names " + subject
                        + " and does not reach " + artifact);
                } else if (sees.contains(artifact)) {
                    contradicted.add(key + " names " + subject + " and reaches " + artifact);
                }
            });
        }

        assertThat("no claim names exactly one renderer, which would leave both clauses below true "
            + "of nothing", checked, is(not(empty())));
        assertThat("claims naming one renderer that reach a different renderer's own picture. "
            + "Naming a single subject asserts that the files the claim reaches belong to that "
            + "renderer and to no other, and this is the half of that assertion a guard can hold it "
            + "to", contradicted, is(empty()));
        assertThat("claims naming a renderer whose own picture they do not reach. The converse is "
            + "deliberately NOT asserted: a claim reaching exactly one renderer's artifact is a fact "
            + "about which sweeps happen to move rather than about ownership - three claims over "
            + "shared pipeline code reach one sweep each and belong to no renderer at all - so "
            + "requiring a subject there would put a false name on each of them", absent,
            is(empty()));
    }

    @Test
    @DisplayName("the renderer roster and the parity subjects name each other exactly")
    void theSubjectRosterIsTheRendererRoster() {
        List<String> renderers = trackedFiles().stream()
            .filter(path -> path.startsWith(LIBRARY_ROOT))
            .map(path -> path.substring(LIBRARY_ROOT.length()))
            .filter(name -> !name.contains("/") && name.endsWith(RENDERER_SUFFIX))
            .map(name -> name.substring(0, name.length() - RENDERER_SUFFIX.length()))
            // The interface strips to nothing, which is how it excludes itself: it is the one file
            // matching the walk that names no renderer, and dropping it by name would go on dropping
            // whatever took that name.
            .filter(stem -> !stem.isEmpty())
            .map(stem -> stem.toUpperCase(Locale.ROOT))
            .sorted()
            .toList();
        List<String> subjects = Stream.of(Subject.values()).map(Enum::name).sorted().toList();

        assertThat("renderers directly under the library root, which is the walk the roster is "
            + "defined from. An empty list is a walk that has stopped finding anything - a moved "
            + "source root, or a suffix nothing ends in any more - and the comparison below would "
            + "then hold only for an empty enum", renderers, is(not(empty())));
        assertThat("the renderers this library ships, against the constants a claim names one by. "
            + "The roster is a closed vocabulary a claim asserts a file belongs to, so a renderer "
            + "with no constant cannot be named at all and a constant with no renderer names "
            + "something that is not there - and either way the claim that a file belongs to one "
            + "renderer and no other stops being a closure anything can be held to. Compared "
            + "upper-cased and stripped of the suffix, so a renderer whose name needs a word "
            + "boundary the constant has to spell fails here for somebody to decide rather than "
            + "resolving itself", subjects, equalTo(renderers));
    }

    /**
     * The renderers a rule's own carriers name it as being about.
     *
     * <p>Read off the declarations rather than out of the map, because the subject lives only in
     * source - and by token scan rather than by a parse, for the reason the derived-path scan is one:
     * the toolkit owns the grammar and a second reader of it here would be a second thing to drift.
     * A joining declaration writes no subject, so the union over a claim's carriers is what its
     * anchor wrote.
     *
     * @param rule the rule
     * @return the constants its carriers name, empty where none does
     */
    private static Set<String> subjectsOf(JsonObject rule) {
        Set<String> out = new TreeSet<>();
        Set<String> authored = strings(rule.getAsJsonArray("authored_paths"));
        for (JsonElement glob : rule.getAsJsonArray("trigger_paths")) {
            // The derived half alone. An authored entry is a glob in the map's own grammar and names
            // no file to read; only a path some declaration produced has a carrier to look in.
            if (authored.contains(glob.getAsString())) continue;
            Path carrier = Path.of(carrierOf(glob.getAsString()));
            if (!Files.isRegularFile(carrier)) continue;
            Matcher declared = DECLARED_SUBJECT.matcher(text(carrier));
            while (declared.find()) {
                Matcher constant = SUBJECT_CONSTANT.matcher(declared.group(1));
                while (constant.find()) out.add(constant.group(1));
            }
        }
        return out;
    }

    /**
     * The file a derived trigger path stands for.
     *
     * <p>A package's declaration is written in its {@code package-info.java} and the trigger it
     * derives is that directory's glob, so the two spellings of a package's reach - its own
     * compilation units and its whole tree - both answer with the one file that carries the line. A
     * type's trigger is the file itself.
     *
     * @param trigger the generated trigger path
     * @return the repo-relative path of the file that declares it
     */
    private static String carrierOf(String trigger) {
        if (trigger.endsWith("/**"))
            return trigger.substring(0, trigger.length() - 2) + PACKAGE_DECLARATION;
        if (trigger.endsWith("/*"))
            return trigger.substring(0, trigger.length() - 1) + PACKAGE_DECLARATION;
        return trigger;
    }

    /**
     * Every artifact a rule list names in either direction.
     *
     * <p>Both fields, because a rule speaks about an artifact by declaring it blind exactly as much
     * as by selecting it - the two answer different questions about the same artifact and either is a
     * statement somebody wrote down. The rules are an argument so the blind half can be exercised on
     * an input the shipped map does not offer.
     *
     * @param rules the rules to read
     * @return the artifact ids, in the order the rules name them
     */
    private static Set<String> namedArtifacts(List<JsonObject> rules) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonObject rule : rules)
            for (String field : List.of("sees", "blind"))
                out.addAll(strings(rule.getAsJsonArray(field)));
        return out;
    }

    /**
     * One rule, spelled the way the map spells it.
     *
     * @param id the rule id
     * @param mode the mode
     * @param trigger the one glob it triggers on
     * @param sees the artifacts it selects
     * @param blind the artifacts it declares blind
     * @return the rule
     */
    private static JsonObject syntheticRule(String id, String mode, String trigger,
                                            List<String> sees, List<String> blind) {
        JsonObject rule = new JsonObject();
        rule.addProperty("id", id);
        rule.addProperty("mode", mode);
        rule.add("trigger_paths", array(List.of(trigger)));
        rule.add("sees", array(sees));
        rule.add("blind", array(blind));
        return rule;
    }

    /** A list of strings as a JSON array. */
    private static JsonArray array(List<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }

    /**
     * Resolves one changed path against the shipped map.
     *
     * @param path the changed path
     * @return the artifacts that can see it
     */
    private static Set<String> seesFor(String path) {
        return seesFor(path, rules());
    }

    /**
     * Resolves one changed path against a rule list, applying the same union and post-union order the
     * toolkit does.
     *
     * <p>One path rather than a set, which is the granularity the toolkit resolves at: reach is the
     * union over the changed files, so a demotion answers for the files it triggers on and cancels
     * nothing another file in the same commit reaches.
     *
     * <p>The rules are an argument so the arithmetic can be exercised on inputs the shipped map does
     * not hold - its one suppression declares nothing, and a branch no input reaches is not a mirror
     * of anything.
     *
     * @param path the changed path
     * @param rules the rules to resolve against
     * @return the artifacts that can see it
     */
    private static Set<String> seesFor(String path, List<JsonObject> rules) {
        List<JsonObject> fired = rules.stream()
            .filter(rule -> {
                for (JsonElement glob : rule.getAsJsonArray("trigger_paths"))
                    if (compileGlob(glob.getAsString()).matcher(path).matches()) return true;
                return false;
            })
            .toList();

        Set<String> sees = new LinkedHashSet<>();
        fired.forEach(rule -> sees.addAll(strings(rule.getAsJsonArray("sees"))));
        // The two post-union passes, in the toolkit's order: a demotion removes what a different rule
        // selected, and a suppression outranks both by removing its own sees as well.
        fired.stream().filter(rule -> rule.get("mode").getAsString().equals("demote"))
            .forEach(rule -> sees.removeAll(strings(rule.getAsJsonArray("blind"))));
        fired.stream().filter(rule -> rule.get("mode").getAsString().equals("suppress"))
            .forEach(rule -> {
                sees.removeAll(strings(rule.getAsJsonArray("sees")));
                sees.removeAll(strings(rule.getAsJsonArray("blind")));
            });
        return sees;
    }

    /**
     * The map's declarations that a path reaches nothing.
     *
     * <p>Objects rather than bare globs, each carrying the same {@code reason} and {@code probe} every
     * rule does, because this is where a path goes when no rule wants it and an unexplained entry
     * there is indistinguishable from an oversight.
     *
     * @return the entries, in file order
     */
    private static List<JsonObject> noReach() {
        List<JsonObject> out = new ArrayList<>();
        JsonObject map = ParityStore.read("roster.blindness-rules");
        if (map.has("no_reach"))
            for (JsonElement entry : map.getAsJsonArray("no_reach")) out.add(entry.getAsJsonObject());
        return out;
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
     * the table, goes green in the fast suite and refuses the gate as Gradle resolves the capture's
     * dependencies.
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
     * The inputs the build file declares on the {@code Test} task.
     *
     * <p>Scoped to that one configuration block, because UP-TO-DATE is decided per task and the same
     * declaration on {@code JavaExec} keeps nothing fresh. The operand is kept alongside the property
     * name so a declaration renamed onto the wrong path still reads as a difference.
     *
     * <p>Both spellings, because a precondition is not always a file: a value declared with
     * {@code inputs.property} is tracked exactly as a path is, and reading only the path form leaves
     * a whole class of declaration outside every check here by construction rather than by
     * decision - which is how the one this suite's siblings rest on came to be droppable with the
     * suite green.
     *
     * @return the property name to declared operand, in declaration order
     */
    private static Map<String, String> testTaskInputs() {
        String build = buildFile();
        Map<String, String> out = new LinkedHashMap<>();
        Pattern declaration = Pattern
            .compile("inputs\\.(?:file|files|dir)\\(([^)]*)\\)\\.withPropertyName\\(\"([^\"]+)\"\\)"
                + "|inputs\\.property\\(\"([^\"]+)\", ([^)]*)\\)");
        // Every block, not the first: the flags a fork needs and the inputs a guard reads are two
        // concerns and sit in two scripts, and `configureEach` is additive, so what the task declares
        // is their union. Reading one block would answer with whichever script the join put first.
        int found = 0;
        for (int start = build.indexOf("tasks.withType<Test>()"); start >= 0;
             start = build.indexOf("tasks.withType<Test>()", start + 1)) {
            found++;
            int end = build.indexOf("\n}", start);
            if (end < 0) throw new AssertionError("a Test block has no line-initial closing brace");
            Matcher matcher = declaration.matcher(build.substring(start, end));
            while (matcher.find())
                if (matcher.group(2) != null) out.put(matcher.group(2), matcher.group(1));
                else out.put(matcher.group(3), matcher.group(4));
        }
        if (found == 0) throw new AssertionError("the build scripts configure no Test task");
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

    /**
     * Whether a repo-relative path sits under any root the scan reads declarations from.
     *
     * @param path the repo-relative path
     * @return whether some source root reaches it
     */
    private static boolean underASourceRoot(String path) {
        return SOURCE_ROOTS.stream().anyMatch(root -> under(root, path));
    }

    /** The build file's text. */
    private static String buildFile() {
        return BuildScripts.all();
    }

    /**
     * The artifacts the toolkit gives an explicit member list.
     *
     * <p>Read out of the toolkit rather than listed here, because the map's job is to answer for
     * whatever the toolkit currently declares: a third artifact given a member list is a third
     * artifact whose stored rows an edit to that map moves.
     *
     * @return the artifact ids, in declaration order
     */
    private static List<String> declaredMemberships() {
        return List.copyOf(declaredMembers().keySet());
    }

    /**
     * The artifacts the toolkit gives an explicit member list, each against the members it names.
     *
     * <p>The members and not only the ids, because they are what a file deciding a membership has to
     * spell: the ids appear anywhere an artifact is registered or baselined, and the member list is
     * what makes a file one of the two places the membership is actually written.
     *
     * @return the artifact id to its member sub-directories, in declaration order
     */
    private static Map<String, List<String>> declaredMembers() {
        String toolkit = text(Path.of("parity/scripts/parity/manifest.py"));
        int start = toolkit.indexOf("SUBTREES = {");
        if (start < 0) throw new AssertionError("parity/scripts/parity/manifest.py declares no SUBTREES");
        int end = toolkit.indexOf("\n}", start);
        if (end < 0) throw new AssertionError("SUBTREES has no line-initial closing brace");
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher entry = Pattern.compile("\"([^\"]+)\":\\s*\\(([^)]*)\\)")
            .matcher(toolkit.substring(start, end));
        while (entry.find()) {
            List<String> members = new ArrayList<>();
            Matcher member = Pattern.compile("\"([^\"]+)\"").matcher(entry.group(2));
            while (member.find()) members.add(member.group(1));
            out.put(entry.group(1), members);
        }
        return out;
    }

    /**
     * The tracked files that name every member of every declared membership.
     *
     * <p>Every membership rather than any one of them, which is what tells a file that <b>decides</b>
     * a membership from one that merely holds an artifact's rows: a stored manifest and the toolkit's
     * own fixtures spell one artifact's members and never the other's, where the two files the
     * membership is typed in carry both lists in full.
     *
     * @return the paths, sorted
     */
    private static List<String> filesSpellingEveryMembership() {
        Collection<List<String>> memberships = declaredMembers().values();
        return trackedFiles().stream()
            .filter(path -> Files.isRegularFile(Path.of(path)))
            .filter(path -> {
                String content = bytesAsText(Path.of(path));
                return memberships.stream()
                    .allMatch(members -> members.stream().allMatch(content::contains));
            })
            .sorted()
            .toList();
    }

    /**
     * Reads a tracked file's bytes as text, decoding leniently.
     *
     * <p>Its one caller sweeps the whole index, which holds a handful of binaries; a strict decode
     * would throw on those and a scan of the repository would become a scan of the text half of it.
     *
     * @param file the repo-relative path
     * @return the file's bytes as UTF-8, malformed sequences replaced
     */
    private static String bytesAsText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Reads a tracked file's text, by path because it is not on the classpath.
     *
     * @param file the repo-relative path
     * @return the file's text
     */
    private static String text(Path file) {
        try {
            return Files.readString(file);
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
     * Every file git tracks, as repo-relative POSIX paths.
     *
     * <p>The index rather than a walk, because a glob's job is to name something the repository
     * holds: a glob whose only witness is an untracked scratch file is an orphan on every other
     * checkout, and a gitignored tree makes an exemption for matching nothing pass without ever being
     * taken. The whole repository rather than one source root, because a rule may legitimately
     * trigger on the build file, the toolkit or the harness.
     *
     * <p>Sourced the same way for the trigger globs, for {@code no_reach} and for the coverage check
     * that unions the two, so every half of the map is judged against one file list.
     *
     * @return the tracked paths
     */
    private static List<String> trackedFiles() {
        ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "-z");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // A failure is an AssertionError rather than an empty list: every check below reads an
            // empty list as "this glob matches nothing", so a missing git would report the whole map
            // as orphaned or, with the polarity the other way, as fine.
            if (process.waitFor() != 0)
                throw new AssertionError("git ls-files failed, so the tracked file list is unknown "
                    + "and no orphan check below can be answered: " + out);
            return Stream.of(out.split("\0")).filter(path -> !path.isBlank()).toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while listing tracked files", ex);
        }
    }

    /**
     * Every file in the working tree, as repo-relative POSIX paths.
     *
     * <p>A walk rather than the index, because its one reader relates this suite's operands to a
     * Gradle file collection and a file collection is a walk - an untracked file under a declared
     * root reaches the task exactly as a tracked one does, and a root declared for nothing but
     * untracked files is still a root Gradle watches. What it does not descend into is
     * {@link #WALK_SKIPS}, which is the exclusion that declaration carries.
     *
     * @return the paths
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
