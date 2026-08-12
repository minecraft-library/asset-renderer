package lib.minecraft.renderer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The slow tag read off the test sources as a rule, rather than left to whoever remembers it.
 *
 * <p>The fast suite excludes {@code @Tag("slow")} and the slow one selects it, so an untagged class
 * that downloads the client jar or reads the offline extraction charges the fast suite a network
 * round trip or a cache miss, and on a machine without the cache it either skips silently or fails
 * for a reason that has nothing to do with the change under test.
 *
 * <p>Three markers decide it, each a code fact rather than a phrase: the {@code ClientAcquisition}
 * import, a {@code "cache/} path literal, and the shared client-assets extension. A source spelling
 * any of them opens a socket or reads the cache root. The Minecraft version literal was measured as a
 * fourth and refused - it is also the {@code pack_format} description and the {@code source_version}
 * of synthetic fixtures that read nothing, and a rule that cries wolf gets deleted rather than
 * obeyed. That measurement is pinned below rather than described.
 *
 * <p>What the markers are read against is the sources JUnit collects, meaning those declaring a test
 * method. A fixture or an extension declares none, so JUnit never collects it and a tag on it would
 * be inert - the tag belongs on the class the extension is installed on, which is what the extension
 * marker finds. The marker-carrying sources that fact applies to are named below with their reason,
 * and their premise is asserted, so one that grows a test method reports rather than goes quiet.
 *
 * <p>Two limits, stated because they bound what a green run here means. A test reaching the cache
 * only through a helper that holds the path carries no marker of its own, and the rule does not chase
 * a transitive read. A tag anywhere in the file satisfies the rule, class-level or on a single method,
 * because a text scan cannot say which methods need it.
 */
@DisplayName("Every test reaching the network or the cache carries the slow tag")
final class SlowTagRuleTest {

    /** The test source set, walked as text rather than reflected over, so a class that fails to load still reads */
    private static final Path TEST_SOURCES = Path.of("src/test/java");

    /** This file, excluded from every scan because it spells all three markers as the data it searches for */
    private static final String SELF = "SlowTagRuleTest.java";

    /** The file extension the walk keeps */
    private static final String JAVA = ".java";

    /** The tag the fast suite excludes and the slow suite selects */
    private static final String SLOW_TAG = "@Tag(\"slow\")";

    /** How a source outside the acquisition's own package names it */
    private static final String ACQUISITION_IMPORT = "import lib.minecraft.renderer.client.ClientAcquisition;";

    /** The acquisition's own package, whose members reach it with no import to spot it by */
    private static final String ACQUISITION_PACKAGE = "package lib.minecraft.renderer.pipeline;";

    /** How a call on the acquisition reads, which is the only spelling left inside its own package */
    private static final String ACQUISITION_CALL = "ClientAcquisition.";

    /** The opening of a path literal below the module's cache root */
    private static final String CACHE_LITERAL = "\"cache/";

    /** The extension that acquires the client assets once per JVM for the classes it is installed on */
    private static final String CLIENT_ASSETS_EXTENSION = "ClientAssetsExtension";

    /** The refused fourth signal, kept here so what it was measured to do stays re-runnable */
    private static final String VERSION_LITERAL = "\"26.1\"";

    /** What a declared test method is annotated with, in every form the suite uses */
    private static final List<String> TEST_ANNOTATIONS =
        List.of("@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory");

    /**
     * One signal that a source reaches the network or the filesystem cache.
     *
     * @param name what the marker is called in a failure message
     * @param firesOn whether the marker matches a source's text
     */
    private record Marker(String name, Predicate<String> firesOn) {}

    /** The three signals, each with no false positive over the sources JUnit collects */
    private static final List<Marker> MARKERS = List.of(
        new Marker("acquires the client jar", SlowTagRuleTest::acquiresTheClient),
        new Marker("holds a cache/ path literal", source -> source.contains(CACHE_LITERAL)),
        new Marker("installs the client-assets extension", source -> source.contains(CLIENT_ASSETS_EXTENSION)));

    /**
     * A marker-carrying source JUnit does not collect.
     *
     * @param path the repo-relative file or directory the entry covers
     * @param reason why the tag would be inert there
     */
    private record Uncollected(String path, String reason) {}

    /** The marker carriers the suites never run, named rather than globbed so each one states its reason */
    private static final List<Uncollected> NOT_COLLECTED = List.of(
        new Uncollected("src/test/java/lib/minecraft/renderer/visual",
            "sweep and render drivers with a main method, run by Gradle JavaExec outside both suites"),
        new Uncollected("src/test/java/lib/minecraft/renderer/example",
            "a main driver for the atlas build task, run by Gradle JavaExec"),
        new Uncollected("src/test/java/lib/minecraft/renderer/pipeline/dump/PipelineParityDump.java",
            "a main driver that writes the pipeline dump"),
        new Uncollected("src/test/java/lib/minecraft/renderer/parity/ParityPaths.java",
            "holds the reference root the tagged sweeps read through"),
        new Uncollected("src/test/java/lib/minecraft/renderer/parity/ParityStore.java",
            "holds the store root the tagged captures are written under"));

    @Test
    @DisplayName("a test class reaching the network or the cache carries the tag")
    void everyCacheReachingTestCarriesTheSlowTag() {
        List<String> untagged = new ArrayList<>();
        for (Path file : scannedSources()) {
            String source = read(file);
            if (!declaresATest(source) || source.contains(SLOW_TAG)) continue;
            List<String> fired = markersOn(source);
            if (!fired.isEmpty()) untagged.add(relative(file) + " - " + String.join(", ", fired));
        }

        assertThat("test classes reaching the network or the filesystem cache without " + SLOW_TAG,
            untagged, is(empty()));
    }

    @Test
    @DisplayName("every exemption still names a marker carrier the suites do not collect")
    void theExemptionsStillHold() {
        List<Path> sources = scannedSources();
        List<String> stale = new ArrayList<>();
        List<String> collected = new ArrayList<>();
        for (Uncollected entry : NOT_COLLECTED) {
            List<Path> covered = sources.stream().filter(file -> covers(entry, file)).toList();
            if (covered.isEmpty()) {
                stale.add(entry.path() + " - names nothing the tree holds");
                continue;
            }
            List<Path> carriers = covered.stream().filter(file -> !markersOn(read(file)).isEmpty()).toList();
            if (carriers.isEmpty()) stale.add(entry.path() + " - covers no marker carrier, so it exempts nothing");
            carriers.stream().filter(file -> declaresATest(read(file)))
                .forEach(file -> collected.add(relative(file) + " - " + entry.reason()));
        }

        assertThat("exemptions the tree has moved out from under", stale, is(empty()));
        assertThat("exempt marker carriers that have grown a test method, so the suites do collect them",
            collected, is(empty()));
    }

    @Test
    @DisplayName("no marker has gone dead - each one still matches the tree")
    void everyMarkerStillFires() {
        List<String> sources = scannedSources().stream().map(SlowTagRuleTest::read).toList();

        List<String> dead = MARKERS.stream()
            .filter(marker -> sources.stream().noneMatch(marker.firesOn()))
            .map(Marker::name)
            .toList();

        assertThat("markers matching nothing in the test source set, so they guard nothing", dead, is(empty()));
    }

    @Test
    @DisplayName("the version literal is not a marker, because it fires where nothing is read")
    void theVersionLiteralWouldCryWolf() {
        List<String> criedWolf = new ArrayList<>();
        for (Path file : scannedSources()) {
            String source = read(file);
            if (source.contains(VERSION_LITERAL) && markersOn(source).isEmpty()) criedWolf.add(relative(file));
        }

        assertThat("sources " + VERSION_LITERAL + " fires on that reach neither the network nor the cache, "
            + "which is why it is not a marker", criedWolf, is(not(empty())));
    }

    /**
     * Returns every marker firing on a source.
     *
     * @param source the file's text
     * @return the names of the markers that matched
     */
    private static List<String> markersOn(String source) {
        return MARKERS.stream().filter(marker -> marker.firesOn().test(source)).map(Marker::name).toList();
    }

    /**
     * Answers whether a source reaches the client acquisition, by the import outside its package and
     * by a call inside it, since a member of its own package names it with no import at all.
     *
     * @param source the file's text
     * @return {@code true} when the source calls the acquisition
     */
    private static boolean acquiresTheClient(String source) {
        if (source.contains(ACQUISITION_IMPORT)) return true;
        return source.contains(ACQUISITION_PACKAGE) && code(source).anyMatch(line -> line.contains(ACQUISITION_CALL));
    }

    /**
     * Answers whether JUnit collects a source, which is whether it declares a test method.
     *
     * @param source the file's text
     * @return {@code true} when some line opens with a test annotation
     */
    private static boolean declaresATest(String source) {
        return code(source).anyMatch(line -> TEST_ANNOTATIONS.stream().anyMatch(line::startsWith));
    }

    /**
     * Returns a source's lines with the comment ones dropped, so a name written in prose is not read
     * as a call.
     *
     * @param source the file's text
     * @return the trimmed lines that are not a comment
     */
    private static Stream<String> code(String source) {
        return source.lines().map(String::trim)
            .filter(line -> !line.startsWith("*") && !line.startsWith("/*") && !line.startsWith("//"));
    }

    /**
     * Answers whether an exemption covers a file, as the file itself or as a directory above it.
     *
     * @param entry the exemption
     * @param file the file being scanned
     * @return {@code true} when the entry covers the file
     */
    private static boolean covers(Uncollected entry, Path file) {
        String path = relative(file);
        return path.equals(entry.path()) || path.startsWith(entry.path() + "/");
    }

    /**
     * Returns every source the rule scans, which is every file below the test source set but this one.
     *
     * @return the sources, in walk order
     */
    private static List<Path> scannedSources() {
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            return files.filter(file -> file.getFileName().toString().endsWith(JAVA))
                .filter(file -> !file.getFileName().toString().equals(SELF))
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Spells a scanned path the way an exemption and a failure message do.
     *
     * @param file the walked path
     * @return the repo-relative path with forward slashes
     */
    private static String relative(Path file) {
        return file.toString().replace('\\', '/');
    }

    /**
     * Reads a source's text, by path because a test source is not on the classpath.
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
