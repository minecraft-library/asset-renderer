package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
     * B2 and B4 speak about a code region inside a wider tree, and demoting them would remove a dump
     * gate the rest of that tree genuinely reaches.
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
        "B18 -> manifest.dump.packs", "B18 -> manifest.dump.vanilla",
        "B2 -> manifest.dump.packs", "B2 -> manifest.dump.vanilla",
        "B21 -> sweep.item",
        "B22 -> manifest.dump.packs", "B22 -> manifest.dump.vanilla",
        "B23 -> manifest.player-raw", "B23 -> sweep.armor", "B23 -> sweep.block",
        "B23 -> sweep.entity", "B23 -> sweep.glint", "B23 -> sweep.item",
        "B4 -> manifest.dump.vanilla",
        "B8 -> sweep.player");

    /**
     * Tracked files a rule claims that a {@code no_reach} glob would also excuse.
     *
     * <p>A rule wins wherever both match, so each of these is held up by its rule today and by the
     * excuse list the moment that rule stops matching it. That is the one way a path loses its reach
     * answer without becoming UNKNOWN: the rule narrows or goes, the glob absorbs the path, and the
     * coverage check stays green over a file no rule speaks for any more. Everything else the excuse
     * list holds up is uncontested, so deleting its rule really would show up as uncovered.
     *
     * <p>All three are READMEs under a directory a rule claims, matched by the markdown glob. Written
     * down rather than counted, so a fourth is a decision somebody makes - narrow the glob, or accept
     * that the path can be absorbed - rather than a widening that arrives with the file.
     *
     * <p>Sorted, because the comparison is against a file list and a list is ordered.
     */
    private static final List<String> ABSORBABLE_BY_AN_EXCUSE = List.of(
        "scripts/parity/README.md",
        "scripts/parity/lab/README.md",
        "src/test/resources/lib/minecraft/renderer/parity/README.md");

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
     *
     * <p>The git index is the one no guard opens by name. It is what {@code git ls-files} answers
     * from, and that answer is the operand of every check here that judges the map against what the
     * repository holds - so tracking a file under a root nothing else declares is precisely the edit
     * those checks exist for, and precisely the edit that would otherwise leave them UP-TO-DATE.
     */
    private static final Map<String, String> DECLARED_INPUTS = Map.of(
        "parityStore", "parityProductionStore",
        "parityBuildFile", "\"build.gradle.kts\"",
        "parityTriggerRoots", "parityTriggerRoots",
        "paritySkillReferences", "paritySkillReferences",
        "paritySkillFile", "paritySkillFile",
        "parityTestSources", "\"src/test/java\"",
        "parityGitIndex", "\".git/index\"");

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
        List.of("build.gradle.kts", "scripts/parity/manifest.py");

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
        assertThat("git tracks nothing these trigger globs match - a rename orphaned the rule, so the "
            + "gate it speaks for silently stopped being consulted", orphaned, is(empty()));
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
        return text(Path.of("build.gradle.kts"));
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
        String toolkit = text(Path.of("scripts/parity/manifest.py"));
        int start = toolkit.indexOf("SUBTREES = {");
        if (start < 0) throw new AssertionError("scripts/parity/manifest.py declares no SUBTREES");
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
