package lib.minecraft.renderer.tensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The incubator module flag, on every task type that resolves {@code jdk.incubator.vector}.
 *
 * <p>{@link SimdOps} imports the package, so a consumer without the flag does not fall back to the
 * scalar path - it fails. A JVM launch fails at class load, and the doclet fails with the package
 * reported as not visible. Both are loud, and neither is loud in a place any test reaches: four of
 * the five consumers are configured for tasks this suite is not one of, and the fifth is this suite
 * itself.
 *
 * <p>So the one that can be exercised is exercised, and the other four are read out of the build
 * file. The repo's own build bullet is read beside it, because it is the one shipped sentence that
 * names the consumers and it can go stale on its own: a task type wired and left out of it is a
 * knob nobody knows resolves the module. Both files are declared as inputs to the suite, so an edit
 * to either re-runs these rather than leaving them up to date.
 */
@DisplayName("every task type that resolves the Vector API is given the module flag")
final class VectorModuleWiringTest {

    /** The module the tensor fast path is written against. */
    private static final String MODULE = "jdk.incubator.vector";

    /** Where the repo states which task types are given the flag, in prose and nowhere else. */
    private static final Path REPO_RULES = Path.of("CLAUDE.md");

    /**
     * The build bullet's consumer list, captured as one run so a narrowed one is read, not missed.
     *
     * <p>A per-name containment test would answer the same on a sentence naming four of the five,
     * which is the state this exists to catch.
     */
    private static final Pattern STATED_CONSUMERS =
        Pattern.compile("wired into ([^.]+?) in `build\\.gradle\\.kts`");

    /** The one statement that binds the flag to a name, which the four below then pass around. */
    private static final String FLAG_DEFINITION =
        "val addVectorModuleArg = \"--add-modules=" + MODULE + "\"";

    /**
     * Each consumer, against the statement that gives it the flag, whitespace collapsed.
     *
     * <p>Read as whole statements rather than as the flag alone: the string appears in the build
     * file wherever it is defined, and a containment test on it passes on a build that defines it
     * and applies it to nothing.
     *
     * <p>Keyed by the task type as the build bullet spells it, because the bullet's list is checked
     * against these keys - so a consumer added here without a word in that file fails rather than
     * shipping undocumented.
     */
    private static final Map<String, String> CONSUMERS = new LinkedHashMap<>();

    static {
        CONSUMERS.put("JavaCompile", "tasks.withType<JavaCompile>().configureEach { "
            + "options.compilerArgs.add(addVectorModuleArg) }");
        CONSUMERS.put("Javadoc", "tasks.withType<Javadoc>().configureEach { "
            + "(options as StandardJavadocDocletOptions).addStringOption(\"-add-modules\", \"" + MODULE + "\") }");
        CONSUMERS.put("Test", "tasks.withType<Test>().configureEach { jvmArgs(addVectorModuleArg)");
        CONSUMERS.put("JavaExec", "tasks.withType<JavaExec>().configureEach { jvmArgs(addVectorModuleArg)");
        // JMH's forks are configured by the plugin's own extension and take a list of raw arguments,
        // so this is the one consumer that cannot be handed the constant.
        CONSUMERS.put("JMH", "jvmArgs.set(listOf(\"-Xmx2g\", \"--add-modules=" + MODULE + "\"))");
    }

    @Test
    @DisplayName("this JVM resolved the module, which is the Test consumer answering for itself")
    void theSuitesOwnForkCarriesTheModule() {
        assertThat("the incubator module is not in this JVM's boot layer, so the fast path's own "
                + "imports are unresolvable here. An incubator module is never resolved by default; "
                + "it is there because the Test tasks are given the flag, and this is the one "
                + "consumer that can say so from inside a test rather than off the build file",
            ModuleLayer.boot().findModule(MODULE).isPresent(), is(true));
    }

    @Test
    @DisplayName("the build file gives the flag to each of the task types that reads it")
    void everyConsumerIsWiredInTheBuildFile() {
        String build = collapsed(read(Path.of("build.gradle.kts")));

        List<String> unwired = new ArrayList<>();
        CONSUMERS.forEach((consumer, statement) -> {
            if (!build.contains(statement)) unwired.add(consumer + ": " + statement);
        });

        assertThat("the statement that binds the flag to the name four of the five are handed",
            build, containsString(FLAG_DEFINITION));
        assertThat("consumers the build file does not hand the module flag. Each fails loudly on "
            + "its own and silently here: a JVM launch cannot load the fast path's class and the "
            + "doclet reports the package as not visible, and no run of this suite is any of those "
            + "tasks", unwired, is(empty()));
        assertThat("the consumer table is empty, which would make the case above hold for a build "
            + "file that wires nothing", CONSUMERS.keySet(), is(not(empty())));
    }

    @Test
    @DisplayName("the shipped build bullet names those consumers and no others")
    void theShippedListIsTheWiredList() {
        Matcher stated = STATED_CONSUMERS.matcher(collapsed(read(REPO_RULES)));

        assertThat("the sentence this reads - \"wired into <list> in build.gradle.kts\" - is not in "
            + "the file at all, so a rewrite has taken the only shipped statement of the wiring "
            + "with it and there is nothing left to compare", stated.find(), is(true));
        List<String> documented = Arrays.stream(stated.group(1).split(",| and "))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .sorted()
            .toList();

        assertThat("the task types that bullet names, against the ones the build file hands the "
            + "flag to. The wiring itself cannot vanish quietly - the case above is what says so - "
            + "and the sentence beside it can, being the one place a reader is told which task "
            + "types resolve the module. Sorted, because which order the prose reads best in is the "
            + "prose's business", documented, equalTo(CONSUMERS.keySet().stream().sorted().toList()));
    }

    /**
     * Reads a tracked file's text, by path because the build file is not on the classpath.
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

    /** Every run of whitespace as one space, so an assertion is about the statement and not its wrap. */
    private static String collapsed(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

}
