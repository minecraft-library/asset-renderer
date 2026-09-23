package lib.minecraft.renderer.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * The package tier order, held against every import in every source root.
 *
 * <p>Every library package carries a tier, and a package may import only a package with a strictly lower
 * tier - so two packages at one tier may not name each other at all. Where one package of a tier has to
 * name another, the table gives the two an explicit sub-order as a decimal inside the tier, and the
 * sub-order is written in the table rather than left for a reader to infer.
 *
 * <p>An edge is an import the file's CODE uses. An import a javadoc link alone reads is not one: the house
 * rule imports a link target, and a link is documentation rather than a dependency. Nor is an import only
 * a {@code @Parity} annotation reads: the vocabulary is source-retained, so nothing survives compilation
 * to depend on. Everything else counts, in a test or a driver as much as in the library, because a test
 * filed in a package is held to that package's place in the order.
 *
 * <p>Three sets sit outside the table. The test harness - the shared fixtures, the extensions, the guards,
 * the parity store and the sweeps and drivers - may import anything and be imported by any test, and the
 * library imports none of it. The generators under {@code tooling} may name a library package up to the
 * content index and nothing above it, and are not ordered among themselves here. The {@code @Parity}
 * vocabulary is a build of its own that everything names.
 *
 * <p>{@link #KNOWN} is the ledger of edges that break the order today, each with what clears it. It is
 * held exactly: an edge that breaks the order and is not on it fails, and an entry the tree no longer
 * carries fails too, so the ledger only ever shrinks and never records a debt that has been paid.
 */
@DisplayName("Every import runs downhill through the package tiers")
class TierOrderTest {

    /** The library's root package, which every tier below is named relative to. */
    private static final String BASE = "lib.minecraft.renderer";

    /** The spelling of the root package itself in the table and the ledger. */
    private static final String ROOT = "~";

    /** Every source root the order binds, the library's and everything written against it. */
    private static final List<Path> SOURCE_ROOTS = List.of(
        Path.of("src/main/java"), Path.of("src/test/java"), Path.of("src/visual/java"), Path.of("src/jmh/java"),
        Path.of("tooling/src/main/java"), Path.of("tooling/src/test/java"));

    /** The highest tier a generator may name. */
    private static final double TOOLING_CEILING = 14;

    /** Each library package's tier, relative to {@link #BASE}; a decimal is a sub-order inside a tier. */
    private static final Map<String, Double> TIERS = Map.ofEntries(
        Map.entry("exception", 0.0), Map.entry("diagnostic", 0.0), Map.entry("math", 0.0),
        Map.entry("engine.layer", 1.0), Map.entry("engine.pose", 1.0), Map.entry("vanilla.id", 1.0),
        Map.entry("engine.geometry", 2.0), Map.entry("slot", 2.0),
        Map.entry("engine.draw", 3.0), Map.entry("engine.light", 3.1),
        Map.entry("engine.camera", 4.0), Map.entry("engine.texture", 4.0),
        Map.entry("engine.mesh", 5.0), Map.entry("engine.raster", 5.0),
        Map.entry("engine.frame", 6.0),
        Map.entry("vanilla", 7.0), Map.entry("vanilla.appearance.villager", 7.0),
        Map.entry("vanilla.equipment", 7.0), Map.entry("vanilla.gui", 7.0),
        Map.entry("vanilla.appearance", 7.1), Map.entry("vanilla.mesh", 7.1),
        Map.entry("asset.mesh", 8.0), Map.entry("asset.model", 8.0), Map.entry("asset.pack", 8.0),
        Map.entry("asset.rule.filter", 8.0),
        Map.entry("asset.pose", 8.1), Map.entry("asset.equipment", 8.1), Map.entry("asset.rule", 8.1),
        Map.entry("asset", 8.2), Map.entry("asset.item", 8.3),
        Map.entry("request", 9.0),
        Map.entry("port.answer", 10.0),
        Map.entry("port", 11.0),
        Map.entry("content.read", 12.0), Map.entry("content.client", 12.0),
        Map.entry("content.json", 13.0), Map.entry("content.table", 13.1), Map.entry("content.pack.cats", 13.1),
        Map.entry("content.rule", 13.1), Map.entry("content.pack", 13.2),
        Map.entry("content.index", 14.0),
        Map.entry("bake.texture", 15.0), Map.entry("bake.pose", 15.0), Map.entry("bake.mesh", 15.1),
        Map.entry("bake.armor", 15.2),
        Map.entry("screen.chrome", 15.0), Map.entry("screen", 15.1), Map.entry("atlas", 15.0),
        Map.entry(ROOT, 16.0),
        Map.entry("author", 17.0), Map.entry("author.mesh", 17.1), Map.entry("author.compile", 17.2),
        Map.entry("author.audit", 17.3), Map.entry("author.install", 17.4),
        // two parents that hold a declaration and no type, so they import nothing and name no tier
        Map.entry("engine", 1.0), Map.entry("content", 12.0));

    /** The test harness, outside the order: any test may import it and the library imports none of it. */
    private static final Set<String> HARNESS = Set.of(
        "support", "fixture", "guard", "showcase", "store", "store.diff", "store.view",
        "sweep", "driver", "dump", "example", "bench");

    /** The package whose members are the {@code @Parity} vocabulary, a build of its own. */
    private static final String PARITY = "parity";

    /** The edges that break the order today, each with what clears it. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
        // an asset record still answers a selection or a request itself
        Map.entry("asset -> bake.pose", "Entity.resolve narrows its style catalog; clears when the resolve moves onto the request bag"),
        Map.entry("asset -> request", "Entity.resolve(AppearanceOptions); clears when the resolve moves onto the request bag"),
        Map.entry("asset.equipment -> bake.armor", "Shell walks its worn boxes itself; clears when the walk is derived at bake time"),
        Map.entry("asset.equipment -> request", "Shell.walk(AppearanceOptions); clears with the walk"),
        Map.entry("asset.item -> request", "the dispatch tree resolves itself against an ItemModelContext"),
        Map.entry("asset.rule -> request", "a rule matches itself against an ItemContext"),
        Map.entry("asset.rule -> port.answer", "RuleSet answers with a CtmContext and a GlintPolicy"),
        Map.entry("asset.rule -> content.rule", "RuleSet names RuleScanner and CtmNeighbors"),
        // a parsed record names the Gson adapter it reads through
        Map.entry("asset.mesh -> content.json", "@JsonAdapter on the bone tree and texture size"),
        Map.entry("asset.model -> content.json", "@JsonAdapter on the model texture and transform"),
        Map.entry("asset.pack -> content.pack", "ResourcePack names the pack container it describes"),
        // the pack readers and the shipped tables still reach up or sideways
        Map.entry("content.read -> content.pack", "PackSubtree and BlockRendererOverrides hold the stack; clears when both take a container handle"),
        Map.entry("content.read -> content.table", "BundledResource reads the ResourceDocument envelope, and BlockRendererOverrides a table reader"),
        Map.entry("content.rule -> content.pack", "RuleScanner holds the stack and the container; clears with the container handle"),
        Map.entry("content.pack -> content.index", "BlockModelLoader assembles, and BlockTagLoader builds a BlockTag"),
        Map.entry("content.table -> content.index", "EntityModelLoader drives EntityIndexBuilder"),
        Map.entry("content.index -> atlas", "AssetContent sorts the block and item ids with AtlasOrder"),
        // the engine and the port reach above themselves
        Map.entry("port.answer -> content.pack", "ResolvedTexture names the pack container a texture came from"),
        // a request bag names a screen type
        Map.entry("request -> screen", "MenuOptions.theme and TextOptions.chrome hold screen types; clears when both hold a style token"),
        // vanilla facts that answer a selection or hold a record
        Map.entry("vanilla -> asset.pose", "UniversalStyles holds PoseStyle rows"),
        Map.entry("vanilla.equipment -> asset.mesh", "ArmorForm.covers walks a decoded shell"),
        Map.entry("vanilla.gui -> screen", "ScreenMetrics lays out a MenuLayout and a Window"),
        Map.entry("vanilla.mesh -> asset.mesh", "ElytraMesh builds its wing bones as a decoded mesh"),
        // tests filed below what they exercise
        Map.entry("bake.pose -> ~", "StyleResolutionTest renders through EntityRenderer"),
        Map.entry("bake.pose -> author", "StyleResolutionTest builds a style"),
        Map.entry("bake.pose -> author.install", "StyleResolutionTest installs through the registrar"),
        Map.entry("content.client -> content.pack", "the acquisition tests read the resolved pack stack"),
        Map.entry("content.client -> content.table", "ClientAcquisitionIntegrationTest reads a shipped table"),
        Map.entry("content.index -> bake.pose", "EntityStyleCatalogJoinTest resolves a style"),
        Map.entry("engine.camera -> asset.mesh", "VanillaEntityTransformGoldenTest poses a decoded mesh"),
        Map.entry("engine.camera -> bake.mesh", "VanillaEntityTransformGoldenTest builds through the entity kit"),
        Map.entry("engine.geometry -> content.json", "EulerRotationTest round-trips the Gson adapter"),
        Map.entry("engine.pose -> asset.pose", "PoseNodeTextTest and StyleDriverTest read the shipped pose rows"),
        Map.entry("request -> content.rule", "ItemContextTest parses a rule to match against"),
        Map.entry("request -> content.table", "EntityResolveTest loads the shipped entity table"),
        Map.entry("screen -> ~", "TooltipChromeTest renders through TextRenderer"),
        Map.entry("vanilla -> request", "SunAngleTest builds an ItemContext"),
        Map.entry("tooling.animation -> author", "PoseEmitterTest builds the style it emits"),
        Map.entry("tooling.animation -> author.install", "PoseEmitterTest installs the style it emits"),
        Map.entry("tooling.animation -> bake.pose", "PoseEmitterTest and StyleFlowEmitTest play a pose back"));

    /** A package declaration. */
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    /** An import, static or not. */
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)\\s*;[ \\t]*$");

    /** Comments, text blocks, string and character literals - text a type name can sit in without being used. */
    private static final Pattern NOISE = Pattern.compile(
        "/\\*.*?\\*/|//[^\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

    /** A {@code @Parity} annotation, whose class literals are read by nothing after compilation. */
    private static final Pattern PARITY_ANNOTATION = Pattern.compile("@Parity\\((?:[^()]|\\([^()]*\\))*\\)");

    /** One source file: where it is, its package and its text. */
    private record Source(Path path, String pkg, String text) {}

    @Test
    @DisplayName("every library package has a tier")
    void everyPackageHasATier() {
        Set<String> untiered = new TreeSet<>();
        for (Source source : sources()) {
            Optional<String> name = relative(source.pkg());
            if (name.isEmpty() || isTooling(name.get()) || HARNESS.contains(name.get()) || name.get().equals(PARITY))
                continue;
            if (!TIERS.containsKey(name.get())) untiered.add(name.get() + " (" + source.path() + ")");
        }

        assertThat("packages with no tier - a new package is given one deliberately rather than inheriting its "
            + "parent's, because nesting carries no direction", untiered, is(empty()));
    }

    @Test
    @DisplayName("every import runs downhill, and the ledger of those that do not is exact")
    void everyImportRunsDownhill() {
        Map<String, Set<String>> violations = new TreeMap<>();
        for (Source source : sources()) {
            Optional<String> from = relative(source.pkg());
            if (from.isEmpty() || HARNESS.contains(from.get()) || from.get().equals(PARITY)) continue;
            String code = PARITY_ANNOTATION.matcher(IMPORT.matcher(NOISE.matcher(source.text()).replaceAll(" "))
                .replaceAll(" ")).replaceAll(" ");
            Matcher imports = IMPORT.matcher(source.text());
            while (imports.find()) {
                String imported = imports.group(1);
                Optional<String> to = packageOf(imported).flatMap(TierOrderTest::relative);
                if (to.isEmpty() || to.get().equals(from.get())) continue;
                if (HARNESS.contains(to.get()) || to.get().equals(PARITY) || isTooling(to.get())) continue;
                String simple = imported.substring(imported.lastIndexOf('.') + 1);
                if (!Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(code).find()) continue;
                if (breaksTheOrder(from.get(), to.get()))
                    violations.computeIfAbsent(from.get() + " -> " + to.get(), key -> new TreeSet<>())
                        .add(source.path().toString().replace('\\', '/'));
            }
        }

        List<String> unrecorded = new ArrayList<>();
        violations.forEach((edge, files) -> {
            if (!KNOWN.containsKey(edge)) unrecorded.add(edge + " " + files);
        });
        List<String> paid = KNOWN.keySet().stream().filter(edge -> !violations.containsKey(edge)).sorted().toList();

        assertThat("imports that run uphill or sideways and are on no ledger. Fix the edge - move the member, or "
            + "file the type where the order puts it - rather than recording it", unrecorded, is(empty()));
        assertThat("ledger entries the tree no longer carries. The debt is paid, so the entry goes", paid, is(empty()));
    }

    /**
     * Answers whether one edge breaks the order.
     *
     * @param from the importing package, relative to the root
     * @param to the imported package, relative to the root
     * @return {@code true} when the import does not run strictly downhill
     */
    private static boolean breaksTheOrder(String from, String to) {
        if (isTooling(from)) return TIERS.get(to) > TOOLING_CEILING;
        return TIERS.get(to) >= TIERS.get(from);
    }

    /**
     * Answers whether a package is one of the generators'.
     *
     * @param name the package, relative to the root
     * @return {@code true} for {@code tooling} and every package below it
     */
    private static boolean isTooling(String name) {
        return name.equals("tooling") || name.startsWith("tooling.");
    }

    /**
     * Names a package relative to the library root, the way the table does.
     *
     * @param pkg the fully qualified package
     * @return the relative name, {@link #ROOT} for the root itself, or empty for a package outside it
     */
    private static Optional<String> relative(String pkg) {
        if (pkg.equals(BASE)) return Optional.of(ROOT);
        if (pkg.startsWith(BASE + ".")) return Optional.of(pkg.substring(BASE.length() + 1));
        return Optional.empty();
    }

    /**
     * Resolves the package an imported name belongs to, walking nested types and static members back to it.
     *
     * @param imported the imported name
     * @return the longest declared package that prefixes it, or empty for a name outside every source root
     */
    private static Optional<String> packageOf(String imported) {
        Set<String> declared = declaredPackages();
        String candidate = imported;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            if (declared.contains(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Every package a source root declares, plus the vocabulary's, read once. */
    private static Set<String> declaredPackages;

    /**
     * Returns every package a source root declares, plus the {@code @Parity} vocabulary's.
     *
     * @return the declared packages
     */
    private static synchronized Set<String> declaredPackages() {
        if (declaredPackages == null) {
            Set<String> declared = new TreeSet<>();
            sources().forEach(source -> declared.add(source.pkg()));
            declared.add(BASE + "." + PARITY);
            declaredPackages = declared;
        }
        return declaredPackages;
    }

    /** Every source file under every root, read once. */
    private static List<Source> sources;

    /**
     * Returns every Java source under every root the order binds.
     *
     * @return the sources, in walk order
     */
    private static synchronized List<Source> sources() {
        if (sources == null) {
            List<Source> read = new ArrayList<>();
            for (Path root : SOURCE_ROOTS) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        String text = Files.readString(file, StandardCharsets.UTF_8);
                        Matcher pkg = PACKAGE.matcher(text);
                        if (pkg.find()) read.add(new Source(file, pkg.group(1), text));
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            sources = List.copyOf(read);
        }
        return sources;
    }

}
