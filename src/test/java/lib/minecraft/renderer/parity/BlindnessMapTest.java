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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Checks that the blindness map still refers to real things.
 *
 * <p>The map cannot verify its own <b>claims</b>: "BlockRenderer never calls buildBox" is a
 * statement about the code that only its probe re-establishes. What can be checked mechanically is
 * referential integrity, and that is enough to catch the failure that actually happens - a rename or
 * a package move silently orphaning a rule, so a gate quietly stops being consulted about a path
 * nobody realises moved.
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
     * every such rule as orphaned. The skipped directories are the gitignored ones a glob is never
     * written against; walking them would add six figures of cache files for nothing.
     */
    private static List<String> repoFiles() {
        Set<String> skip = Set.of(".git", ".gradle", ".idea", "build", "cache", "texturepacks", ".jmh");
        try (Stream<Path> walk = Files.walk(Path.of(""))) {
            return walk
                .filter(path -> path.getNameCount() == 0
                    || Stream.iterate(path, Path::getParent)
                        .limit(path.getNameCount())
                        .noneMatch(part -> skip.contains(part.getFileName().toString())))
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
